import Flutter
import Foundation
import UIKit

private final class IOSComparisonMatchCancellation: @unchecked Sendable {
  private let lock = NSLock()
  private var stopped = false
  func cancel() { lock.lock(); stopped = true; lock.unlock() }
  var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
}

/// Shared Flutter flow, isolated from the privacy inference/export pipelines.
@MainActor
final class IOSComparisonNativeApi: DanceComparisonApi {
  private let registry: FlutterTextureRegistry
  private var player: IOSComparisonPlayer?
  private let picker = IOSComparisonPicker()
  private let presenter: () -> UIViewController?
  // Keep a background export alive if Flutter recreates its engine in-process.
  private static var sharedExports: IOSComparisonExportCoordinator?
  private let exports: IOSComparisonExportCoordinator
  private var matchCancellation: IOSComparisonMatchCancellation?
  private var matchTask: Task<MusicAlignmentDto, Error>?
  private var matchRevision: UInt64 = 0

  init(registry: FlutterTextureRegistry, presenter: @escaping () -> UIViewController?) {
    self.presenter = presenter
    self.registry = registry
    if let existing = Self.sharedExports { exports = existing }
    else {
      let coordinator = IOSComparisonExportCoordinator(presenter: presenter)
      Self.sharedExports = coordinator
      exports = coordinator
    }
  }

  func pickVideo(role: String) async throws -> ComparisonSourceDto? {
    guard role == "teacher" || role == "learner" else { throw invalid("视频角色无效") }
    guard let view = presenter() else { throw invalid("请回到应用后选择视频") }
    return try await picker.choose(from: view)
  }
  func loadProjects() async throws -> [String] {
    let projects = try IOSComparisonProjectStore.load()
    // Run maintenance after reading projects but before opening any player.
    try? IOSComparisonProjectStore.pruneStaleImports()
    return projects
  }
  func saveProject(projectJson: String) async throws {
    try IOSComparisonProjectStore.save(projectJson)
  }

  func open(teacherUri: String, learnerUri: String, offsetUs: Int64, positionUs: Int64,
    speed: Double, width: Int64, height: Int64, sideBySide: Bool,
    practice: ComparisonPracticeDto, timeScale: Double) async throws -> ComparisonPlaybackDto {
    try canvas(width, height)
    guard !activeExport else { throw invalid("对比视频正在导出，请先查看导出进度") }
    if player == nil { player = IOSComparisonPlayer(registry: registry) }
    let player = try requirePlayer()
    let state = try await player.open(referenceURL: IOSComparisonFiles.resolve(teacherUri),
      targetURL: IOSComparisonFiles.resolve(learnerUri), offsetUs: offsetUs, positionUs: positionUs,
      speed: speed, width: Int(width), height: Int(height), sideBySide: sideBySide,
      practice: practice, timeScale: timeScale)
    // Reclaim only after the prior player has switched away from its source.
    Task.detached(priority: .utility) {
      try? IOSComparisonProjectStore.pruneStaleImports()
    }
    return state
  }
  func control(command: ComparisonCommand, valueUs: Int64, speed: Double) async throws -> ComparisonPlaybackDto {
    try await requirePlayer().control(command: command, valueUs: valueUs, speed: speed)
  }
  func setAlignment(offsetUs: Int64, timeScale: Double) async throws -> ComparisonPlaybackDto {
    try await requirePlayer().setAlignment(offsetUs: offsetUs, timeScale: timeScale)
  }
  func resize(width: Int64, height: Int64, sideBySide: Bool) async throws -> ComparisonPlaybackDto {
    try canvas(width, height)
    return try await requirePlayer().resize(width: Int(width), height: Int(height), sideBySide: sideBySide)
  }
  func setPractice(practice: ComparisonPracticeDto) async throws -> ComparisonPlaybackDto {
    try await requirePlayer().setPractice(practice)
  }
  func stepFrame(forward: Bool) async throws -> ComparisonPlaybackDto { try await requirePlayer().stepFrame(forward: forward) }
  func getState() async throws -> ComparisonPlaybackDto {
    player?.state() ?? ComparisonPlaybackDto(textureId: -1, phase: "closed", positionUs: 0,
      durationUs: 0, teacherStartUs: 0, offsetUs: 0, speed: 1)
  }

  func alignMusic(teacherUri: String, learnerUri: String) async throws -> MusicAlignmentDto {
    matchRevision &+= 1
    let revision = matchRevision
    await stopMatching()
    guard revision == matchRevision else { return cancelledMatch() }
    let reference = try IOSComparisonFiles.resolve(teacherUri)
    let target = try IOSComparisonFiles.resolve(learnerUri)
    let cancellation = IOSComparisonMatchCancellation()
    let task = Task.detached(priority: .userInitiated) {
      try IOSComparisonMusicAligner().align(referenceURL: reference, targetURL: target,
        isCancelled: { cancellation.isCancelled || Task.isCancelled })
    }
    matchCancellation = cancellation; matchTask = task
    defer {
      if matchCancellation === cancellation { matchCancellation = nil; matchTask = nil }
    }
    do {
      let result = try await task.value
      return revision == matchRevision ? result : cancelledMatch()
    }
    catch {
      if revision != matchRevision || cancellation.isCancelled || error is CancellationError {
        return cancelledMatch()
      }
      return MusicAlignmentDto(status: "error", message: "音乐匹配未完成，可以重试或手动校准。", candidates: [])
    }
  }
  func cancelAlignment() async throws { matchRevision &+= 1; await stopMatching() }
  private func cancelledMatch() -> MusicAlignmentDto {
    MusicAlignmentDto(status: "cancelled", message: "匹配已取消", candidates: [])
  }
  private func stopMatching() async {
    let old = matchTask
    matchCancellation?.cancel(); old?.cancel()
    matchTask = nil; matchCancellation = nil
    // Decoding checks cancellation between samples. Release it before reopening
    // other AVFoundation decoders, without blocking the main actor.
    _ = try? await old?.value
  }

  func startExport(projectJson: String, startUs: Int64, endUs: Int64,
    width: Int64, height: Int64, gap: Int64, sideBySide: Bool,
    replacePreviousUnsaved: Bool) async throws -> ComparisonExportDto {
    try canvas(width, height)
    guard gap > 0 && gap <= 64 else { throw invalid("画面间距无效") }
    closePlayer()
    return try await withCheckedThrowingContinuation { continuation in
      exports.start(projectJson: projectJson, startUs: startUs, endUs: endUs,
        width: Int(width), height: Int(height), gap: Int(gap), sideBySide: sideBySide,
        replacePreviousUnsaved: replacePreviousUnsaved) {
        continuation.resume(with: $0)
      }
    }
  }
  func getExport() async throws -> ComparisonExportDto? { exports.latest() }
  func cancelExport(jobId: String) async throws -> ComparisonExportDto { try exports.cancel(jobId: jobId) }
  func retryExport(jobId: String) async throws -> ComparisonExportDto {
    closePlayer()
    return try await withCheckedThrowingContinuation { continuation in
      exports.retry(jobId: jobId) { continuation.resume(with: $0) }
    }
  }
  func saveExport(jobId: String) async throws -> ComparisonExportDto {
    try await withCheckedThrowingContinuation { continuation in
      exports.save(jobId: jobId) { continuation.resume(with: $0) }
    }
  }
  func openExport(jobId: String) async throws { try exports.open(jobId: jobId) }
  func close() async throws {
    matchRevision &+= 1
    picker.cancel(); closePlayer(); await stopMatching()
    Task.detached(priority: .utility) {
      try? IOSComparisonProjectStore.pruneStaleImports()
    }
  }

  func detach() {
    matchRevision &+= 1
    picker.cancel(); closePlayer()
    matchCancellation?.cancel(); matchTask?.cancel()
    matchCancellation = nil; matchTask = nil
  }

  private func closePlayer() { player?.close(); player = nil }
  private func requirePlayer() throws -> IOSComparisonPlayer {
    guard let player else { throw invalid("请先打开两段视频") }
    return player
  }

  private var activeExport: Bool {
    guard let state = exports.latest()?.state else { return false }
    return ["queued", "encoding", "publishing", "cancelling"].contains(state)
  }
  private func canvas(_ width: Int64, _ height: Int64) throws {
    guard width >= 16 && height >= 16 && width <= 8192 && height <= 8192 else {
      throw invalid("请等待比较画面就绪")
    }
  }
  private func invalid(_ message: String) -> Error {
    NSError(domain: "WoahComparison", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}
