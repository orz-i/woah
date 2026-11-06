import Foundation

/// Durable metadata only; media and source files remain owned by the pipeline.
/// A process death must not turn a started privacy export into JOB_NOT_FOUND.
final class IOSExportJobJournal {
  private struct Record: Codable {
    let jobId: String
    var state: String
    var currentFrame: Int64
    var totalFrames: Int64
    var fps: Double
    var progress: Double
    var outputUri: String?
    var errorCode: String?
    var finalPath: String?
    var tempPath: String?

    init(_ status: JobStatusDto) {
      jobId = status.jobId
      state = status.state
      currentFrame = status.currentFrame
      totalFrames = status.totalFrames
      fps = status.fps
      progress = status.progress
      outputUri = status.outputUri
      errorCode = status.errorCode
    }

    func status() -> JobStatusDto {
      let safeCode = errorCode.flatMap { code in
        ["EXPORT_INTERRUPTED", "EXPORT_FAILED", "INVALID_VIDEO", "INVALID_ARGUMENT",
         "ENCODER_UNAVAILABLE", "AUDIO_WRITER_UNAVAILABLE", "METAL_PIPELINE_FAILED"].contains(code)
        ? code : "EXPORT_FAILED"
      }
      return JobStatusDto(
        jobId: jobId, state: state, currentFrame: currentFrame,
        totalFrames: totalFrames, fps: fps, progress: progress,
        outputUri: outputUri, currentPreviewPath: nil, errorCode: safeCode,
        errorMessage: safeCode.map { "stage=export; code=\($0)" }
      )
    }
  }

  private let fileManager: FileManager
  private let root: URL
  private let home: URL
  private let lock = NSLock()
  private var initializationError: Error?
  private let maxRecords = 80

  init(fileManager: FileManager = .default, root: URL? = nil) {
    self.fileManager = fileManager
    let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    self.root = (root ?? support.appendingPathComponent("WoahPrivacyExportJobs", isDirectory: true))
      .standardizedFileURL
    self.home = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
      .standardizedFileURL.resolvingSymlinksInPath()
    do {
      try fileManager.createDirectory(at: self.root, withIntermediateDirectories: true)
      var excluded = self.root
      var values = URLResourceValues()
      values.isExcludedFromBackup = true
      try excluded.setResourceValues(values)
      // Previous process cannot clear an unfinished input after termination.
      // This is local and idempotent; it never deletes source or final media.
      try recoverAndPrune()
    } catch {
      initializationError = error
    }
  }

  private func failure() -> PigeonError {
    PigeonError(code: "EXPORT_FAILED", message: "Privacy export status journal is unavailable.", details: nil)
  }

  private static func valid(_ jobId: String) -> Bool {
    jobId.range(of: "^job_[0-9]{1,20}_[A-Fa-f0-9]{8}$", options: .regularExpression) != nil
  }

  private func file(_ jobId: String) -> URL {
    root.appendingPathComponent(jobId + ".json", isDirectory: false)
  }

  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }

  private func read(_ jobId: String) throws -> Record? {
    guard Self.valid(jobId) else { return nil }
    let path = file(jobId)
    guard fileManager.fileExists(atPath: path.path) else { return nil }
    guard (try fileManager.attributesOfItem(atPath: path.path)[.type] as? FileAttributeType) == .typeRegular,
          path.resolvingSymlinksInPath().path == path.path else { throw failure() }
    let record = try JSONDecoder().decode(Record.self, from: Data(contentsOf: path))
    guard record.jobId == jobId else { throw failure() }
    return record
  }

  private func write(_ record: Record) throws {
    guard Self.valid(record.jobId) else { throw failure() }
    let encoded = try JSONEncoder().encode(record)
    let path = file(record.jobId)
    try encoded.write(to: path, options: .atomic)
    try? fileManager.setAttributes([.posixPermissions: NSNumber(value: 0o600)],
                                   ofItemAtPath: path.path)
  }

  /// Failure to journal the starting state must prevent a job from being
  /// launched; otherwise a process death could falsely report JOB_NOT_FOUND.
  func begin(_ status: JobStatusDto) throws {
    try withLock {
      if initializationError != nil { throw failure() }
      guard Self.valid(status.jobId), try read(status.jobId) == nil else { throw failure() }
      try write(Record(status))
    }
  }

  func rememberPaths(jobId: String, final: URL, temp: URL) throws {
    try withLock {
      guard var record = try read(jobId) else { throw failure() }
      // The exact temporary path must be beneath our own private app home.
      // Never authorize arbitrary document/source deletion from a journal.
      guard safeSandboxPath(temp) else { throw failure() }
      record.finalPath = final.path
      record.tempPath = temp.path
      try write(record)
    }
  }

  func update(_ status: JobStatusDto) throws {
    try withLock {
      guard var record = try read(status.jobId) else { throw failure() }
      // Preserve the last useful frame without writing JSON on every frame.
      let terminal = ["completed", "cancelled", "failed", "interrupted"].contains(status.state)
      guard terminal || record.state != status.state ||
            status.currentFrame - record.currentFrame >= 8 else { return }
      record.state = status.state
      record.currentFrame = status.currentFrame
      record.totalFrames = status.totalFrames
      record.fps = status.fps
      record.progress = status.progress
      record.outputUri = status.outputUri
      record.errorCode = status.errorCode
      try write(record)
    }
  }

  func status(_ jobId: String) -> JobStatusDto? {
    withLock { (try? read(jobId))?.status() }
  }

  /// Only after the cancellation token prohibits publication. Preserve the
  /// active journal until the worker acknowledges resource release.
  func cleanCancelledTemporary(_ jobId: String) {
    withLock {
      guard let record = try? read(jobId) else { return }
      cleanRegisteredTemp(record)
    }
  }

  func cleanTerminal(_ jobId: String) {
    withLock {
      guard let record = try? read(jobId),
            ["completed", "cancelled", "failed", "interrupted"].contains(record.state) else { return }
      cleanRegisteredTemp(record)
    }
  }

  private func safeSandboxPath(_ url: URL) -> Bool {
    let normalized = url.standardizedFileURL
    let resolved = normalized.resolvingSymlinksInPath()
    return normalized.path.hasPrefix(home.path + "/") &&
      normalized.path == resolved.path &&
      normalized.path != home.path
  }

  /// Remove only this job's exact registered temp movie and AVFoundation's
  /// temporary sandbox sidecars, never other files or the committed final MP4.
  private func cleanRegisteredTemp(_ record: Record) {
    guard let stored = record.tempPath else { return }
    let temp = URL(fileURLWithPath: stored).standardizedFileURL
    guard safeSandboxPath(temp),
          temp.lastPathComponent.range(
            of: "^\\.[A-Za-z0-9_-]+\\.[A-Fa-f0-9-]{36}\\.partial\\.mp4$",
            options: .regularExpression
          ) != nil else { return }
    let parent = temp.deletingLastPathComponent()
    guard safeSandboxPath(parent) else { return }
    let prefix = temp.lastPathComponent + ".sb-"
    guard let paths = try? fileManager.contentsOfDirectory(
      at: parent, includingPropertiesForKeys: nil) else { return }
    for path in paths {
      let name = path.lastPathComponent
      let isSidecar = name.hasPrefix(prefix) &&
        name.dropFirst(prefix.count).range(
          of: "^[A-Za-z0-9-]{1,80}$", options: .regularExpression) != nil
      guard name == temp.lastPathComponent || isSidecar else { continue }
      guard safeSandboxPath(path),
            (try? fileManager.attributesOfItem(atPath: path.path)[.type] as? FileAttributeType) == .typeRegular
      else { continue }
      try? fileManager.removeItem(at: path)
    }
  }

  private func recoverAndPrune() throws {
    let paths = try fileManager.contentsOfDirectory(
      at: root, includingPropertiesForKeys: [.contentModificationDateKey], options: [])
      .filter { $0.pathExtension == "json" && Self.valid($0.deletingPathExtension().lastPathComponent) }
    let sorted = paths.sorted { a, b in
      let dateA = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
        ?? Date.distantPast
      let dateB = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
        ?? Date.distantPast
      return dateA > dateB
    }
    for (index, path) in sorted.enumerated() {
      // Only prune OLD metadata. Original media is never owned by this store.
      if index >= maxRecords {
        if safeSandboxPath(path) { try? fileManager.removeItem(at: path) }
        continue
      }
      guard var record = try? read(path.deletingPathExtension().lastPathComponent) else { continue }
      if ["preparing", "exporting", "muxing"].contains(record.state) {
        record.state = "interrupted"
        record.errorCode = "EXPORT_INTERRUPTED"
        record.outputUri = nil
        cleanRegisteredTemp(record)
        try write(record)
      } else if ["cancelled", "failed"].contains(record.state) {
        cleanRegisteredTemp(record)
      }
    }
  }
}
