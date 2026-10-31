import Foundation

/// Process-wide ownership of reproducible files only; never owns source media or final exports.
final class IOSRegenerableCacheRegistry: @unchecked Sendable {
  static let shared = IOSRegenerableCacheRegistry()
  final class Owner: Hashable {
    fileprivate var closed = false
    static func == (lhs: Owner, rhs: Owner) -> Bool { lhs === rhs }
    func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(self)) }
  }
  private struct Entry {
    let owner: Owner?
    var leases: Int
    var released = false
  }
  final class Lease: @unchecked Sendable {
    let id: String
    private let lock = NSLock()
    private var end: (() -> Void)?
    fileprivate init(id: String, end: @escaping () -> Void) { self.id = id; self.end = end }
    func close() {
      lock.lock(); let action = end; end = nil; lock.unlock()
      action?()
    }
  }
  private let root: URL
  private let clock: () -> TimeInterval
  private let ttl: TimeInterval
  private let budget: Int64
  private let queue = DispatchQueue(label: "art.gaoge.woah.cache-cleanup", qos: .utility)
  private let lock = NSLock()
  private var entries: [String: Entry] = [:]
  private var cleanupListeners: [Owner: (String) -> Void] = [:]
  var trackedEntryCount: Int { withLock { entries.count } }
  func setOwnerCleanup(_ owner: Owner, cleanup: @escaping (String) -> Void) {
    withLock { cleanupListeners[owner] = cleanup }
  }
  private var lastMaintenance: TimeInterval?
  private let fm = FileManager.default
  private static let uuidPattern = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"
  private static let analysisPattern = "analysis_[0-9]+_(?:[a-fA-F0-9]{8}|\(uuidPattern))"
  private static let trimPattern = "trim_\(uuidPattern)"

  init(root: URL? = nil, clock: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
       ttl: TimeInterval = 24 * 60 * 60, budget: Int64 = 256 * 1024 * 1024) {
    self.root = (root ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? FileManager.default.temporaryDirectory).standardizedFileURL.resolvingSymlinksInPath()
    self.clock = clock; self.ttl = ttl; self.budget = budget
  }
  func beginAnalysis(owner: Owner, cacheId: String) throws -> Lease {
    guard Self.matches(cacheId, "[A-Za-z0-9_-]+") else { throw failure("INVALID_CACHE_ID") }
    return try begin(owner: owner, id: cacheId)
  }
  func beginTrim(owner: Owner) throws -> Lease {
    try begin(owner: owner, id: "trim_\(UUID().uuidString)")
  }
  private func begin(owner: Owner, id: String) throws -> Lease {
    let lease = try withLock { () throws -> Lease in
      guard !owner.closed else { throw failure("CACHE_OWNER_CLOSED") }
      guard entries[id] == nil else { throw failure("CACHE_ID_COLLISION") }
      entries[id] = Entry(owner: owner, leases: 1)
      return Lease(id: id) { self.endLease(id) }
    }
    maintainIfNeeded()
    return lease
  }
  func acquire(cacheId: String) throws -> Lease {
    try withLock {
      guard var entry = entries[cacheId], !entry.released else { throw failure("CACHE_NOT_RETAINED") }
      entry.leases += 1; entries[cacheId] = entry
      return Lease(id: cacheId) { self.endLease(cacheId) }
    }
  }
  func requestRelease(cacheId: String, owner: Owner) {
    let accepted = withLock { () -> Bool in
      guard var entry = entries[cacheId], entry.owner == owner else { return false }
      entry.released = true; entries[cacheId] = entry; return true
    }
    if accepted { scheduleCleanup(cacheId) }
  }
  func detachOwner(_ owner: Owner) {
    let ids = withLock { () -> [String] in
      owner.closed = true
      if !entries.values.contains(where: { $0.owner === owner }) { cleanupListeners.removeValue(forKey: owner) }
      let ids = entries.filter { $0.value.owner == owner }.map(\.key)
      for id in ids { entries[id]?.released = true }
      return ids
    }
    ids.forEach(scheduleCleanup)
  }
  private func endLease(_ id: String) {
    withLock {
      guard var entry = entries[id], entry.leases > 0 else { return }
      entry.leases -= 1; entries[id] = entry
    }
    scheduleCleanup(id)
  }
  private func scheduleCleanup(_ id: String) {
    queue.async {
      let ready = self.withLock { self.entries[id].map { $0.released && $0.leases == 0 } ?? false }
      if ready {
        let listeners = self.withLock { Array(self.cleanupListeners.values) }
        listeners.forEach { $0(id) }
        if self.deleteGroup(id) { self.finishDeletion(id) }
      }
    }
  }
  func maintainIfNeeded() {
    let now = clock()
    let due = withLock { () -> Bool in
      if let last = lastMaintenance, now >= last, now - last < 60 { return false }
      lastMaintenance = now; return true
    }
    if due { pruneInactive() }
  }
  func pruneInactive() { queue.async { self.pruneNow() } }
  /// Deterministic synthetic smoke tests only; production never blocks on cleanup.
  func drainForTesting() { queue.sync {} }

  private func pruneNow() {
    let retries = withLock { entries.filter { $0.value.released && $0.value.leases == 0 }.map(\.key) }
    for id in retries {
      let listeners = withLock { Array(cleanupListeners.values) }
      listeners.forEach { $0(id) }
      if deleteGroup(id) { finishDeletion(id) }
    }
    let now = clock()
    var candidates: [(String, TimeInterval, Int64)] = []
    for id in discoverGroups() {
      if withLock({ entries[id] != nil }) { continue }
      guard let files = groupFiles(id), !files.isEmpty else { continue }
      var modified: TimeInterval = 0; var bytes: Int64 = 0; var valid = true
      for file in files {
        guard let attrs = try? fm.attributesOfItem(atPath: file.path),
              let date = attrs[.modificationDate] as? Date,
              let size = attrs[.size] as? NSNumber else { valid = false; break }
        modified = max(modified, date.timeIntervalSince1970); bytes += size.int64Value
      }
      if valid && modified > 0 && modified <= now { candidates.append((id, modified, bytes)) }
    }
    candidates.sort { $0.1 < $1.1 }
    var total = candidates.reduce(Int64(0)) { $0 + $1.2 }
    for (id, modified, bytes) in candidates {
      if now - modified < ttl && total <= budget { continue }
      let claimed = withLock { () -> Bool in
        guard entries[id] == nil else { return false }
        entries[id] = Entry(owner: nil, leases: 0, released: true); return true
      }
      if claimed && deleteGroup(id) { total -= bytes; finishDeletion(id) }
    }
  }
  private func finishDeletion(_ id: String) {
    withLock {
      guard let entry = entries[id], entry.released, entry.leases == 0 else { return }
      entries.removeValue(forKey: id)
      if let owner = entry.owner, owner.closed,
         !entries.values.contains(where: { $0.owner === owner }) { cleanupListeners.removeValue(forKey: owner) }
    }
  }
  private func discoverGroups() -> Set<String> {
    var ids: Set<String> = []
    let analysis = root.appendingPathComponent("analysis")
    if safe(analysis) {
      for file in children(analysis) where Self.matches(file.lastPathComponent, Self.analysisPattern) {
        ids.insert(file.lastPathComponent)
      }
    }
    for file in children(root) {
      if let id = Self.capture(file.lastPathComponent, "preview_(\(Self.analysisPattern))_[0-9]+_[0-9]+\\.jpg") {
        ids.insert(id)
      }
    }
    let trim = root.appendingPathComponent("trim_thumbnails")
    if safe(trim) {
      for file in children(trim) {
        if let id = Self.capture(file.lastPathComponent, "(\(Self.trimPattern))_[0-9]+\\.jpg") { ids.insert(id) }
      }
    }
    return ids
  }
  private func groupFiles(_ id: String) -> [URL]? {
    var files: [URL] = []
    if Self.matches(id, Self.analysisPattern) {
      let parent = root.appendingPathComponent("analysis")
      let directory = parent.appendingPathComponent(id)
      guard safe(parent), safe(directory) else { return nil }
      if fm.fileExists(atPath: directory.path) {
        guard let children = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return nil }
        guard children.allSatisfy({ safeRegular($0) && Self.matches($0.lastPathComponent,
          "(?:source_uri\\.txt|analysis\\.json|person_[0-9]+\\.jpg)") }) else { return nil }
        files += children
      }
      let pattern = "preview_\(NSRegularExpression.escapedPattern(for: id))_[0-9]+_[0-9]+\\.jpg"
      let previews = children(root).filter { Self.matches($0.lastPathComponent, pattern) }
      guard previews.allSatisfy(safeRegular) else { return nil }; files += previews
    } else if Self.matches(id, Self.trimPattern) {
      let directory = root.appendingPathComponent("trim_thumbnails")
      guard safe(directory) else { return nil }
      files = children(directory).filter { Self.matches($0.lastPathComponent,
        "\(NSRegularExpression.escapedPattern(for: id))_[0-9]+\\.jpg") }
      guard files.allSatisfy(safeRegular) else { return nil }
    } else { return nil }
    return files
  }
  private func deleteGroup(_ id: String) -> Bool {
    guard let files = groupFiles(id) else { return false }
    var deleted = true
    for file in files {
      guard safeRegular(file) else { deleted = false; continue }
      do { try fm.removeItem(at: file) } catch { deleted = false }
    }
    if Self.matches(id, Self.analysisPattern) {
      let directory = root.appendingPathComponent("analysis").appendingPathComponent(id)
      if safe(directory), let remaining = try? fm.contentsOfDirectory(atPath: directory.path), remaining.isEmpty {
        do { try fm.removeItem(at: directory) } catch { deleted = false }
      }
    }
    return deleted
  }
  private func safe(_ url: URL) -> Bool {
    let plain = url.standardizedFileURL
    if let attrs = try? fm.attributesOfItem(atPath: plain.path), attrs[.type] as? FileAttributeType == .typeSymbolicLink { return false }
    return plain.path.hasPrefix(root.path + "/") && plain.resolvingSymlinksInPath().path == plain.path
  }
  private func safeRegular(_ url: URL) -> Bool {
    safe(url) && (try? fm.attributesOfItem(atPath: url.path)[.type] as? FileAttributeType) == .typeRegular
  }
  private func children(_ url: URL) -> [URL] {
    (try? fm.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)) ?? []
  }
  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock(); defer { lock.unlock() }; return try body()
  }
  private func failure(_ code: String) -> PigeonError { PigeonError(code: code, message: "Temporary cache is unavailable.", details: nil) }
  private static func matches(_ value: String, _ pattern: String) -> Bool {
    value.range(of: "^(?:\(pattern))$", options: .regularExpression) != nil
  }
  private static func capture(_ value: String, _ pattern: String) -> String? {
    guard let regex = try? NSRegularExpression(pattern: "^(?:\(pattern))$"),
          let match = regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
          let range = Range(match.range(at: 1), in: value) else { return nil }
    return String(value[range])
  }
}
