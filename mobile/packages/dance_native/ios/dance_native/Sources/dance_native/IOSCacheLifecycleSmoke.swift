import Foundation

/// Synthetic-only fixtures. This test never points a registry at the app's real cache.
enum IOSCacheLifecycleSmoke {
  static func run() throws -> [String: Any] {
    let fm = FileManager.default
    let fixture = fm.temporaryDirectory.appendingPathComponent("woah-cache-fixture-\(UUID().uuidString)")
    try fm.createDirectory(at: fixture, withIntermediateDirectories: true)
    defer { try? fm.removeItem(at: fixture) }
    func root(_ name: String) throws -> URL {
      let url = fixture.appendingPathComponent(name)
      try fm.createDirectory(at: url, withIntermediateDirectories: true)
      return url
    }
    func write(_ url: URL, _ text: String = "synthetic", time: TimeInterval = 90_000) throws {
      try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
      try Data(text.utf8).write(to: url)
      try fm.setAttributes([.modificationDate: Date(timeIntervalSince1970: time)], ofItemAtPath: url.path)
    }
    func exists(_ url: URL) -> Bool { fm.fileExists(atPath: url.path) }
    func require(_ condition: @autoclosure () throws -> Bool, _ code: String) throws {
      if try condition() == false { throw NSError(domain: "WoahCacheFixture", code: 1, userInfo: [NSLocalizedDescriptionKey: code]) }
    }
    func file(_ root: URL, _ id: String) -> URL { root.appendingPathComponent("analysis/\(id)/analysis.json") }
    let hundredRoot = try root("hundred")
    let hundred = IOSRegenerableCacheRegistry(root: hundredRoot, clock: { 100_000 }, ttl: 1000, budget: 1024)
    let sentinels = ["exports", "export_live_preview", "comparison", "profile", "models", "diagnostics", "stickers"].map {
      hundredRoot.appendingPathComponent("\($0)/keep.bin")
    }
    for sentinel in sentinels { try write(sentinel, "KEEP") }
    for index in 0..<100 {
      let owner = IOSRegenerableCacheRegistry.Owner()
      let id = "analysis_1_\(String(format: "%08x", index))"
      let creation = try hundred.beginAnalysis(owner: owner, cacheId: id)
      try write(file(hundredRoot, id))
      creation.close()
      hundred.requestRelease(cacheId: id, owner: owner)
      hundred.requestRelease(cacheId: id, owner: owner)
      hundred.drainForTesting()
      try require(!exists(file(hundredRoot, id)), "HUNDRED_CYCLE_RELEASE")
    }
    try require(hundred.trackedEntryCount == 0, "RELEASED_LEDGER_BOUNDED")
    for sentinel in sentinels { try require(try String(contentsOf: sentinel, encoding: .utf8) == "KEEP", "EXCLUDED_SENTINEL") }

    let leaseRoot = try root("leases")
    let leases = IOSRegenerableCacheRegistry(root: leaseRoot, clock: { 100_000 }, ttl: 1000, budget: 0)
    let owner = IOSRegenerableCacheRegistry.Owner(); let other = IOSRegenerableCacheRegistry.Owner(); let id = "analysis_2_00000000"
    let creation = try leases.beginAnalysis(owner: owner, cacheId: id)
    var memoryClears = 0
    leases.setOwnerCleanup(owner) { _ in memoryClears += 1 }
    try write(file(leaseRoot, id)); creation.close(); leases.drainForTesting()
    let preview = try leases.acquire(cacheId: id); let export = try leases.acquire(cacheId: id)
    leases.requestRelease(cacheId: id, owner: other)
    leases.drainForTesting(); try require(exists(file(leaseRoot, id)), "WRONG_OWNER")
    leases.requestRelease(cacheId: id, owner: owner)
    leases.drainForTesting(); try require(exists(file(leaseRoot, id)) && memoryClears == 0, "EARLY_RELEASE")
    do { _ = try leases.acquire(cacheId: id); throw NSError(domain: "REVIVED_RELEASED", code: 1) }
    catch let error as NSError where error.domain == "REVIVED_RELEASED" { throw error }
    catch { }
    preview.close(); preview.close(); leases.drainForTesting()
    try require(exists(file(leaseRoot, id)), "CANCEL_STATUS_IS_NOT_COMPLETION")
    export.close(); export.close(); leases.drainForTesting()
    try require(!exists(file(leaseRoot, id)) && memoryClears > 0, "FINAL_DRAIN")

    let trimOwner = IOSRegenerableCacheRegistry.Owner(); let trim = try leases.beginTrim(owner: trimOwner)
    let trimFile = leaseRoot.appendingPathComponent("trim_thumbnails/\(trim.id)_0.jpg")
    try write(trimFile); trim.close(); leases.pruneInactive(); leases.drainForTesting()
    try require(exists(trimFile), "RETURNED_TRIM_RETAINED")
    leases.detachOwner(trimOwner); leases.drainForTesting()
    try require(!exists(trimFile), "DETACHED_TRIM_RELEASED")
    let pendingOwner = IOSRegenerableCacheRegistry.Owner(); let pendingId = "analysis_3_00000000"
    let pending = try leases.beginAnalysis(owner: pendingOwner, cacheId: pendingId)
    try write(file(leaseRoot, pendingId)); leases.detachOwner(pendingOwner); leases.drainForTesting()
    try require(exists(file(leaseRoot, pendingId)), "DETACH_MUST_DRAIN_ANALYSIS")
    pending.close(); leases.drainForTesting(); try require(!exists(file(leaseRoot, pendingId)), "LATE_ANALYSIS_RELEASE")

    let orphanRoot = try root("orphans")
    let orphan = IOSRegenerableCacheRegistry(root: orphanRoot, clock: { 100_000 }, ttl: 1000, budget: 1024)
    let old = file(orphanRoot, "analysis_1_00000000")
    let recent = file(orphanRoot, "analysis_2_00000000")
    let future = file(orphanRoot, "analysis_3_00000000")
    try write(old, time: 98_999); try write(recent, time: 99_001); try write(future, time: 100_001)
    orphan.pruneInactive(); orphan.drainForTesting()
    try require(!exists(old) && exists(recent) && exists(future), "TTL_AND_CLOCK_ROLLBACK")
    let restarted = IOSRegenerableCacheRegistry(root: orphanRoot, clock: { 100_000 }, ttl: 1000, budget: 0)
    restarted.pruneInactive(); restarted.drainForTesting()
    try require(!exists(recent) && exists(future), "INACTIVE_SOFT_BUDGET")

    let safeRoot = try root("safety")
    let external = fixture.appendingPathComponent("external/source.mp4")
    try write(external, "KEEP")
    let safeId = "analysis_4_00000000"; let safeFile = file(safeRoot, safeId)
    try write(safeFile); try write(safeFile.deletingLastPathComponent().appendingPathComponent("source_uri.txt"), external.path)
    let badId = "analysis_5_00000000"; let badFile = file(safeRoot, badId)
    try write(badFile)
    try fm.createSymbolicLink(at: badFile.deletingLastPathComponent().appendingPathComponent("person_0.jpg"), withDestinationURL: external)
    let unknown = file(safeRoot, "analysis_6_00000000"); try write(unknown)
    try write(unknown.deletingLastPathComponent().appendingPathComponent("user-note.txt"), "KEEP")
    let prefix = safeRoot.appendingPathComponent("preview_\(safeId)_user.jpg"); try write(prefix, "KEEP")
    let previewFile = safeRoot.appendingPathComponent("preview_\(safeId)_0_1.jpg"); try write(previewFile)
    let safety = IOSRegenerableCacheRegistry(root: safeRoot, clock: { 100_000 }, ttl: 1000, budget: 0)
    safety.pruneInactive(); safety.drainForTesting()
    try require(!exists(safeFile) && !exists(previewFile), "KNOWN_GROUP_RELEASE")
    try require(exists(badFile) && exists(unknown) && exists(prefix), "LINK_UNKNOWN_PREFIX_QUARANTINE")
    try require(try String(contentsOf: external, encoding: .utf8) == "KEEP", "SOURCE_NEVER_DELETED")
    return ["cycles": 100, "syntheticOnly": true, "status": "PASS"]
  }
}
