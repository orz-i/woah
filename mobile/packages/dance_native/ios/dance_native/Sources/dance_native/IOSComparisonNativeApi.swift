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
    do {
      guard role == "teacher" || role == "learner" else { throw invalid("视频角色无效") }
          guard let view = presenter() else { throw invalid("请回到应用后选择视频") }
          return try await picker.choose(from: view)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func loadProjects() async throws -> [String] {
    do {
      let projects = try IOSComparisonProjectStore.load()
          // Run maintenance after reading projects but before opening any player.
          try? IOSComparisonProjectStore.pruneStaleImports()
          return projects
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func saveProject(projectJson: String) async throws {
    do {
      try IOSComparisonProjectStore.save(projectJson)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }

  func open(teacherUri: String, learnerUri: String, offsetUs: Int64, positionUs: Int64,
    speed: Double, width: Int64, height: Int64, sideBySide: Bool,
    practice: ComparisonPracticeDto, timeScale: Double) async throws -> ComparisonPlaybackDto {
    do {
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
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func control(command: ComparisonCommand, valueUs: Int64, speed: Double) async throws -> ComparisonPlaybackDto {
    do {
      return try await requirePlayer().control(command: command, valueUs: valueUs, speed: speed)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func setAlignment(offsetUs: Int64, timeScale: Double) async throws -> ComparisonPlaybackDto {
    do {
      return try await requirePlayer().setAlignment(offsetUs: offsetUs, timeScale: timeScale)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func resize(width: Int64, height: Int64, sideBySide: Bool) async throws -> ComparisonPlaybackDto {
    do {
      try canvas(width, height)
          return try await requirePlayer().resize(width: Int(width), height: Int(height), sideBySide: sideBySide)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func setPractice(practice: ComparisonPracticeDto) async throws -> ComparisonPlaybackDto {
    do {
      return try await requirePlayer().setPractice(practice)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func stepFrame(forward: Bool) async throws -> ComparisonPlaybackDto {
    do {
      return try await requirePlayer().stepFrame(forward: forward)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func getState() async throws -> ComparisonPlaybackDto {
    player?.state() ?? ComparisonPlaybackDto(textureId: -1, phase: "closed", positionUs: 0,
      durationUs: 0, teacherStartUs: 0, offsetUs: 0, speed: 1)
  }

  func inspectCutout(reference: Bool,seed: ComparisonCutoutSeedDto) async throws -> ComparisonCutoutPreviewDto {
    do {
      #if DEBUG
      return try await requirePlayer().inspectCutout(reference:reference,seed:seed)
      #else
      throw invalid("抠像画布正在测试")
      #endif
    } catch { throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison) }
  }
  func prepareCutout(reference: ComparisonCutoutSeedDto,target: ComparisonCutoutSeedDto,layout: ComparisonCanvasLayoutDto?) async throws -> ComparisonCutoutResultDto {
    do {
      #if DEBUG
      return try await requirePlayer().prepareCutout(reference: reference,target: target,layout:layout)
      #else
      throw invalid("抠像画布正在测试")
      #endif
    } catch { throw IOSDiagnosticPrivacy.pigeon(error,stage: .comparison) }
  }
  func getCutoutStatus() async throws -> ComparisonCutoutResultDto? {
    #if DEBUG
    return player?.cutoutStatus()
    #else
    return nil
    #endif
  }
  func saveCutout(projectId:String)async throws->Bool{
    do{
      #if DEBUG
      return try await requirePlayer().saveCutout(projectId:projectId)
      #else
      return false
      #endif
    }catch{throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison)}
  }
  func restoreCutout(projectId:String,layout:ComparisonCanvasLayoutDto?)async throws->ComparisonCutoutResultDto?{
    do{
      #if DEBUG
      return try await requirePlayer().restoreCutout(projectId:projectId,layout:layout)
      #else
      return nil
      #endif
    }catch{throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison)}
  }
  func forgetCutout(projectId:String)async throws{
    #if DEBUG
    do{try await Task.detached(priority:.utility){try IOSComparisonCutoutArchive.forget(projectId)}.value}
    catch{throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison)}
    #endif
  }
  func setCutoutLayout(layout: ComparisonCanvasLayoutDto) async throws -> ComparisonPlaybackDto {
    do {
      #if DEBUG
      return try await requirePlayer().setCutoutLayout(layout)
      #else
      throw invalid("抠像画布正在测试")
      #endif
    } catch {throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison)}
  }
  func clearCutout() async throws -> ComparisonPlaybackDto {
    do { return try await requirePlayer().clearCutout() }
    catch { throw IOSDiagnosticPrivacy.pigeon(error,stage: .comparison) }
  }

  func alignMusic(teacherUri: String, learnerUri: String) async throws -> MusicAlignmentDto {
    do {
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
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
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
    do {
      try canvas(width, height)
          guard player?.hasCutout != true else { throw invalid("抠像画布暂不支持导出") }
          guard gap > 0 && gap <= 64 else { throw invalid("画面间距无效") }
          closePlayer()
          return try await withCheckedThrowingContinuation { continuation in
            exports.start(projectJson: projectJson, startUs: startUs, endUs: endUs,
              width: Int(width), height: Int(height), gap: Int(gap), sideBySide: sideBySide,
              replacePreviousUnsaved: replacePreviousUnsaved) {
              continuation.resume(with: $0)
            }
          }
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func startCutoutExport(projectJson: String,startUs: Int64,endUs: Int64,width: Int64,height: Int64,
    gap: Int64,sideBySide: Bool,replacePreviousUnsaved: Bool) async throws -> ComparisonExportDto {
    do {
      #if DEBUG
      try canvas(width,height)
      guard gap>0,gap<=64 else {throw invalid("画面间距无效")}
      var spec=try IOSComparisonExportSpec.fromProject(projectJson,startUs:startUs,endUs:endUs,
        width:Int(width),height:Int(height),gap:Int(gap),sideBySide:sideBySide)
      spec.cutout=true
      let owner=try requirePlayer()
      let snapshot=try owner.freezeCutoutExport(spec)
      try owner.suspendCutoutForExport()
      do {
        return try await withCheckedThrowingContinuation { continuation in
          exports.startCutout(spec:spec,snapshot:snapshot,replacePreviousUnsaved:replacePreviousUnsaved) {
            continuation.resume(with:$0)
          }
        }
      } catch {
        _ = try? await owner.resumeCutoutPreview()
        throw error
      }
      #else
      throw invalid("抠像画布正在测试")
      #endif
    } catch {throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison)}
  }
  func resumeCutoutPreview() async throws -> ComparisonPlaybackDto {
    do {
      guard !activeExport else {throw invalid("导出尚未结束")}
      return try await requirePlayer().resumeCutoutPreview()
    } catch {throw IOSDiagnosticPrivacy.pigeon(error,stage:.comparison)}
  }
  func getExport() async throws -> ComparisonExportDto? { exports.latest() }
  func cancelExport(jobId: String) async throws -> ComparisonExportDto {
    do {
      return try exports.cancel(jobId: jobId)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func retryExport(jobId: String) async throws -> ComparisonExportDto {
    do {
      guard let job=exports.latest(),job.jobId==jobId,["failed","cancelled","interrupted"].contains(job.state) else {throw invalid("当前任务不能重试")}
      if job.cutout==true {
        guard job.retryAvailable==true else {throw IOSComparisonCutoutExportUnavailable()}
        if player?.hasCutout==true {try player?.suspendCutoutForExport()}
        else {closePlayer()}
      } else {
        guard player?.hasCutout != true else {throw invalid("请先退出抠像画布")}
        closePlayer()
      }
          return try await withCheckedThrowingContinuation { continuation in
            exports.retry(jobId: jobId) { continuation.resume(with: $0) }
          }
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func saveExport(jobId: String) async throws -> ComparisonExportDto {
    do {
      return try await withCheckedThrowingContinuation { continuation in
            exports.save(jobId: jobId) { continuation.resume(with: $0) }
          }
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
  func openExport(jobId: String) async throws {
    do {
      try exports.open(jobId: jobId)
    } catch {
      throw IOSDiagnosticPrivacy.pigeon(error, stage: .comparison)
    }
  }
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
