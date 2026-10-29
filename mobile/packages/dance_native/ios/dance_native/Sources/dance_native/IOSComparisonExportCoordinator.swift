import AVFoundation
import AVKit
import Foundation
import Photos
import UIKit

/// One durable comparison export, independent of privacy exports and projects.
/// Callbacks return promptly with a job DTO; `latest()` supplies live progress.
final class IOSComparisonExportCoordinator {
  private struct Runtime {
    let id: String
    let cancellation: IOSComparisonExportCancellation
    let background: IOSComparisonExportBackgroundTask
  }

  private let presenter: () -> UIViewController?
  private let lock = NSLock()
  private let directory: URL
  private let metadata: URL
  private var current: IOSComparisonExportRecord?
  private var runtime: Runtime?
  private var storageError: Error?
  private var lastPersist = Date.distantPast

  init(presenter: @escaping () -> UIViewController?) {
    self.presenter = presenter
    directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("WoahComparisonExports", isDirectory: true)
    metadata = directory.appendingPathComponent("latest.json")
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
        attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
      var values = URLResourceValues()
      values.isExcludedFromBackup = true
      var excludedDirectory = directory
      try excludedDirectory.setResourceValues(values)
      if FileManager.default.fileExists(atPath: metadata.path) {
        let record = try JSONDecoder().decode(IOSComparisonExportRecord.self, from: Data(contentsOf: metadata))
        try record.validate()
        let recovered = record.recovered(outputExists: FileManager.default.fileExists(atPath: output(record.id).path))
        current = recovered
        try persistLocked(recovered)
        // Remove only this record's unfinished temporary movie. Completed files
        // are retained even when another export becomes the latest job.
        try? FileManager.default.removeItem(at: partial(record.id))
      }
    } catch {
      // A damaged latest record must not prevent a fresh export. An unwritable
      // directory is detected again by the atomic initial metadata write.
      storageError = error
    }
  }

  func start(projectJson: String, startUs: Int64, endUs: Int64,
    width: Int, height: Int, gap: Int, sideBySide: Bool,
    completion: @escaping (Result<ComparisonExportDto, Error>) -> Void) {
    do {
      let spec = try IOSComparisonExportSpec.fromProject(projectJson, startUs: startUs,
        endUs: endUs, width: width, height: height, gap: gap, sideBySide: sideBySide)
      // Fail before creating a job when its stored source is inaccessible.
      _ = try IOSComparisonFiles.resolve(spec.teacherURI)
      _ = try IOSComparisonFiles.resolve(spec.learnerURI)
      deliver(completion, .success(try launch(spec: spec)))
    } catch { deliver(completion, .failure(error)) }
  }

  func latest() -> ComparisonExportDto? {
    withLock { current.map(dto) }
  }

  func cancel(jobId: String) throws -> ComparisonExportDto {
    let active = try withLock { () throws -> Runtime? in
      _ = try requireLocked(jobId)
      return runtime?.id == jobId ? runtime : nil
    }
    // Local publication is the commit point. Cancelling a finished export or a
    // Photos copy never removes that completed local file.
    if let active, active.cancellation.cancel(.user) {
      try withLock {
        var record = try requireLocked(jobId)
        if ["queued", "encoding", "cancelling"].contains(record.state) {
          record.state = "cancelling"
          record.stage = "正在取消"
          try persistLocked(record)
        }
      }
    }
    return try withLock { dto(try requireLocked(jobId)) }
  }

  func retry(jobId: String, completion: @escaping (Result<ComparisonExportDto, Error>) -> Void) {
    do {
      let spec = try withLock { () throws -> IOSComparisonExportSpec in
        let record = try requireLocked(jobId)
        guard ["failed", "cancelled", "interrupted"].contains(record.state), runtime == nil else {
          throw failure("当前导出不能重试")
        }
        return record.spec
      }
      deliver(completion, .success(try launch(spec: spec, id: jobId)))
    } catch { deliver(completion, .failure(error)) }
  }

  func save(jobId: String, completion: @escaping (Result<ComparisonExportDto, Error>) -> Void) {
    do {
      let shouldSave = try withLock { () throws -> Bool in
        var record = try requireLocked(jobId)
        guard ["completed", "publishing"].contains(record.state),
              FileManager.default.fileExists(atPath: output(jobId).path) else {
          throw failure("导出文件尚未完成，请先完成导出")
        }
        if record.state == "publishing" || record.savedAssetIdentifier != nil || record.photoSaveInFlight { return false }
        record.state = "publishing"
        record.stage = "保存到相册"
        record.error = nil
        // A permission prompt has not started a Photos transaction. If the app
        // exits during that prompt, saving can safely be retried after recovery.
        try persistLocked(record)
        return true
      }
      guard shouldSave else {
        deliver(completion, .success(try withLock { dto(try requireLocked(jobId)) }))
        return
      }
      DispatchQueue.main.async { [self] in
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if status == .notDetermined {
          PHPhotoLibrary.requestAuthorization(for: .addOnly) { [self] result in
            saveAuthorized(jobId: jobId, status: result, completion: completion)
          }
        } else {
          saveAuthorized(jobId: jobId, status: status, completion: completion)
        }
      }
    } catch { deliver(completion, .failure(error)) }
  }

  func open(jobId: String) throws {
    let url = try withLock { () throws -> URL in
      let record = try requireLocked(jobId)
      guard ["completed", "publishing"].contains(record.state),
            FileManager.default.fileExists(atPath: output(jobId).path) else {
        throw failure("导出文件不可用，请重试导出")
      }
      return output(jobId)
    }
    try onMain {
      guard let host = presenter(), host.viewIfLoaded?.window != nil,
            !host.isBeingDismissed else { throw failure("当前无法打开视频播放器") }
      let player = AVPlayerViewController()
      player.player = AVPlayer(url: url)
      host.present(player, animated: true) { player.player?.play() }
    }
  }

  private func launch(spec: IOSComparisonExportSpec, id: String = UUID().uuidString) throws -> ComparisonExportDto {
    let cancellation = IOSComparisonExportCancellation()
    let background = IOSComparisonExportBackgroundTask()
    let initial = try withLock { () throws -> ComparisonExportDto in
      guard runtime == nil, current?.active != true else { throw failure("已有对比视频正在导出") }
      let record = IOSComparisonExportRecord(id: id, spec: spec)
      try record.validate()
      try persistLocked(record)
      runtime = Runtime(id: id, cancellation: cancellation, background: background)
      return dto(record)
    }
    background.begin { [weak self, weak cancellation] in
      guard cancellation?.cancel(.backgroundExpired) == true else { return }
      self?.backgroundExpired(id: id)
    }
    Task.detached(priority: .userInitiated) { [self] in
      await run(id: id, spec: spec, cancellation: cancellation, background: background)
    }
    return initial
  }

  private func run(id: String, spec: IOSComparisonExportSpec,
    cancellation: IOSComparisonExportCancellation, background: IOSComparisonExportBackgroundTask) async {
    defer {
      try? FileManager.default.removeItem(at: partial(id))
      withLock { if runtime?.id == id { runtime = nil } }
      background.end()
    }
    do {
      try? FileManager.default.removeItem(at: partial(id))
      try cancellation.check()
      try withLock {
        var record = try requireLocked(id)
        guard record.state == "queued" else { throw failure("导出已取消") }
        record.state = "encoding"
        record.stage = "合成对比视频"
        try persistLocked(record)
      }
      try await IOSComparisonExportPipeline().execute(spec: spec, outputURL: partial(id),
        cancellation: cancellation) { [weak self] value in self?.progress(id: id, value: value) }
      try cancellation.commit {
        try withLock {
          var record = try requireLocked(id)
          guard !FileManager.default.fileExists(atPath: output(id).path) else {
            throw failure("导出文件已存在，请新建导出")
          }
          // Both paths are in the same directory; a partial file is never
          // exposed as a playable result or passed to Photos.
          try FileManager.default.moveItem(at: partial(id), to: output(id))
          record.state = "completed"
          record.stage = "导出完成"
          record.progress = 1
          record.error = nil
          do { try persistLocked(record) }
          catch {
            record.error = "视频已导出，记录保存失败；请保留本地文件"
            current = record
            storageError = error
          }
        }
      }
    } catch {
      withLock {
        guard var record = current, record.id == id, record.state != "completed" else { return }
        if cancellation.reason == .backgroundExpired {
          record.state = "interrupted"
          record.stage = "后台时间已结束"
          record.error = "iOS 已停止后台导出，回到应用后可以重试"
        } else if cancellation.reason != nil {
          record.state = "cancelled"
          record.stage = "已取消导出"
          record.error = nil
        } else {
          record.state = "failed"
          record.stage = "导出失败"
          record.error = userMessage(error)
        }
        do { try persistLocked(record) }
        catch { current = record; storageError = error }
      }
    }
  }

  private func backgroundExpired(id: String) {
    withLock {
      guard var record = current, record.id == id, record.state != "completed" else { return }
      record.state = "interrupted"
      record.stage = "后台时间已结束"
      record.error = "iOS 已停止后台导出，回到应用后可以重试"
      do { try persistLocked(record) }
      catch { current = record; storageError = error }
    }
  }

  private func progress(id: String, value: Double) {
    withLock {
      guard var record = current, record.id == id, record.state == "encoding" else { return }
      record.progress = max(record.progress, min(0.98, max(0, value)))
      current = record
      if Date().timeIntervalSince(lastPersist) >= 0.5 {
        do { try persistLocked(record) }
        catch { storageError = error }
      }
    }
  }

  private func saveAuthorized(jobId: String, status: PHAuthorizationStatus,
    completion: @escaping (Result<ComparisonExportDto, Error>) -> Void) {
    guard status == .authorized || status == .limited else {
      finishSave(jobId: jobId, identifier: nil,
        error: "视频已导出；未获得添加相册权限，可授权后再次保存", completion: completion)
      return
    }
    do {
      try withLock {
        var record = try requireLocked(jobId)
        guard record.state == "publishing", !record.photoSaveInFlight else {
          throw failure("相册保存状态已变化，请重新打开导出")
        }
        // Persist before invoking Photos. If the app exits before it receives
        // a placeholder, recovery still fences this in-flight transaction.
        record.photoSaveInFlight = true
        try persistLocked(record)
      }
    } catch {
      finishSave(jobId: jobId, identifier: nil,
        error: "视频已导出，暂时无法记录相册保存状态，可以再次保存", completion: completion)
      return
    }
    var identifier: String?
    PHPhotoLibrary.shared().performChanges({ [self] in
      let request = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: output(jobId))
      identifier = request?.placeholderForCreatedAsset?.localIdentifier
      if let identifier {
        withLock {
          guard var record = current, record.id == jobId else { return }
          record.pendingAssetIdentifier = identifier
          do { try persistLocked(record) }
          catch { current = record; storageError = error }
        }
      }
    }) { [self] success, error in
      finishSave(jobId: jobId, identifier: success ? identifier : nil,
        error: success && identifier != nil ? nil :
          "视频已导出，保存相册失败，可以再次保存\(error.map { "（\($0.localizedDescription)）" } ?? "")",
        completion: completion)
    }
  }

  private func finishSave(jobId: String, identifier: String?, error: String?,
    completion: @escaping (Result<ComparisonExportDto, Error>) -> Void) {
    do {
      let result = try withLock { () throws -> ComparisonExportDto in
        var record = try requireLocked(jobId)
        record.state = "completed"
        record.stage = identifier == nil ? "导出完成" : "已保存到相册"
        record.progress = 1
        record.savedAssetIdentifier = identifier
        record.pendingAssetIdentifier = nil
        record.photoSaveInFlight = false
        record.error = error
        do { try persistLocked(record) }
        catch let storageFailure {
          // Preserve successful in-memory idempotency if storage is full. The
          // persisted transaction fence still prevents a duplicate after exit.
          record.error = identifier == nil ? error : "已保存到相册，保存记录暂不可用"
          current = record
          storageError = storageFailure
        }
        return dto(record)
      }
      deliver(completion, .success(result))
    } catch { deliver(completion, .failure(error)) }
  }

  private func requireLocked(_ id: String) throws -> IOSComparisonExportRecord {
    guard let record = current, record.id == id else { throw failure("导出任务已变化，请重新打开导出") }
    return record
  }

  private func persistLocked(_ record: IOSComparisonExportRecord) throws {
    let data = try JSONEncoder().encode(record)
    try data.write(to: metadata, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    current = record
    storageError = nil
    lastPersist = Date()
  }

  private func dto(_ record: IOSComparisonExportRecord) -> ComparisonExportDto {
    let hasOutput = ["completed", "publishing"].contains(record.state) &&
      FileManager.default.fileExists(atPath: output(record.id).path)
    return ComparisonExportDto(jobId: record.id, projectId: record.spec.projectId,
      state: record.state, stage: record.stage, progress: record.progress,
      startUs: record.spec.startUs, endUs: record.spec.endUs,
      width: Int64(record.spec.width), height: Int64(record.spec.height),
      outputPath: hasOutput ? output(record.id).path : nil,
      savedUri: record.savedAssetIdentifier.map { "ph://\($0)" }, error: record.error)
  }

  private func output(_ id: String) -> URL { directory.appendingPathComponent("\(id).mp4") }
  private func partial(_ id: String) -> URL { directory.appendingPathComponent("\(id).partial.mp4") }
  private func failure(_ message: String) -> IOSComparisonExportError {
    IOSComparisonExportError(code: "COMPARISON_EXPORT_STATE", message: message)
  }
  private func userMessage(_ error: Error) -> String {
    let nsError = error as NSError
    if nsError.domain == NSCocoaErrorDomain && nsError.code == NSFileWriteOutOfSpaceError {
      return "存储空间不足，请释放空间后重试"
    }
    if let known = error as? IOSComparisonExportError { return known.message }
    return "导出未完成，请重试或重新关联源视频（\(error.localizedDescription)）"
  }
  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock(); defer { lock.unlock() }
    return try body()
  }
  private func onMain<T>(_ body: () throws -> T) rethrows -> T {
    if Thread.isMainThread { return try body() }
    return try DispatchQueue.main.sync(execute: body)
  }
  private func deliver(_ completion: @escaping (Result<ComparisonExportDto, Error>) -> Void,
    _ result: Result<ComparisonExportDto, Error>) {
    if Thread.isMainThread { completion(result) }
    else { DispatchQueue.main.async { completion(result) } }
  }
}

private final class IOSComparisonExportBackgroundTask {
  private let lock = NSLock()
  private var identifier = UIBackgroundTaskIdentifier.invalid
  private var ended = false

  func begin(expiration: @escaping () -> Void) {
    let start = { [self] in
      lock.lock()
      guard !ended else { lock.unlock(); return }
      lock.unlock()
      let token = UIApplication.shared.beginBackgroundTask(withName: "WoahComparisonExport") { [weak self] in
        expiration()
        self?.end()
      }
      lock.lock()
      let alreadyEnded = ended
      if !alreadyEnded { identifier = token }
      lock.unlock()
      if alreadyEnded, token != .invalid { UIApplication.shared.endBackgroundTask(token) }
    }
    if Thread.isMainThread { start() }
    else { DispatchQueue.main.sync(execute: start) }
  }

  func end() {
    lock.lock()
    ended = true
    let token = identifier
    identifier = .invalid
    lock.unlock()
    guard token != .invalid else { return }
    let stop = { UIApplication.shared.endBackgroundTask(token) }
    if Thread.isMainThread { stop() }
    else { DispatchQueue.main.async(execute: stop) }
  }
}
