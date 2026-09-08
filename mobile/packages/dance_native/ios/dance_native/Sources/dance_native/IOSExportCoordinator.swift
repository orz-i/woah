import Foundation

final class IOSExportCoordinator {
  typealias StatusObserver = (JobStatusDto) -> Void

  private let pipeline: IOSExportPipeline
  private let observer: StatusObserver
  private let lock = NSLock()
  private var statuses: [String: JobStatusDto] = [:]
  private var cancellations: [String: IOSExportCancellationFlag] = [:]
  private var tasks: [String: Task<Void, Never>] = [:]

  init(
    analysisCache: IOSAnalysisCache,
    inferenceProvider: IOSExportPipeline.InferenceProvider? = nil,
    observer: @escaping StatusObserver
  ) {
    pipeline = IOSExportPipeline(
      analysisCache: analysisCache,
      inferenceProvider: inferenceProvider
    )
    self.observer = observer
  }

  func start(request: ExportRequestDto) throws -> String {
    let active = withLock {
      !tasks.isEmpty || statuses.values.contains { !Self.isTerminal($0.state) }
    }
    guard !active else {
      throw PigeonError(
        code: "EXPORT_BUSY",
        message: "An iOS export job is already active.",
        details: nil
      )
    }

    let jobId = "job_\(Int64(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(8))"
    let cancellation = IOSExportCancellationFlag()
    let initial = JobStatusDto(
      jobId: jobId,
      state: "preparing",
      currentFrame: 0,
      totalFrames: 0,
      fps: 0,
      progress: 0,
      outputUri: nil,
      currentPreviewPath: nil,
      errorCode: nil,
      errorMessage: nil
    )
    withLock {
      statuses[jobId] = initial
      cancellations[jobId] = cancellation
    }
    observer(initial)

    // Register runtime ownership before the detached task can finish. Without
    // this launch gate, an immediate validation failure can clean up before the
    // task is inserted and leave a stale task entry that blocks future exports.
    let launchGate = DispatchSemaphore(value: 0)
    let task = Task.detached(priority: .userInitiated) { [weak self] in
      launchGate.wait()
      guard let self else { return }
      do {
        let output = try await self.pipeline.execute(
          jobId: jobId,
          request: request,
          cancellation: cancellation,
          onStatus: { [weak self] status in
            self?.update(status)
          }
        )
        if cancellation.isCancelled {
          self.finishCancelled(jobId: jobId)
        } else {
          self.finishCompleted(jobId: jobId, outputURL: output)
        }
      } catch IOSExportPipelineError.cancelled {
        self.finishCancelled(jobId: jobId)
      } catch let error as PigeonError {
        self.finishFailed(
          jobId: jobId,
          code: error.code,
          message: error.message ?? "iOS export failed."
        )
      } catch {
        self.finishFailed(
          jobId: jobId,
          code: "EXPORT_FAILED",
          message: String(describing: error)
        )
      }
    }
    withLock { tasks[jobId] = task }
    launchGate.signal()
    return jobId
  }

  func cancel(jobId: String) {
    let cancellation = withLock { cancellations[jobId] }
    cancellation?.cancel()
    guard let current = status(jobId: jobId), !Self.isTerminal(current.state) else { return }
    let cancelled = JobStatusDto(
      jobId: current.jobId,
      state: "cancelled",
      currentFrame: current.currentFrame,
      totalFrames: current.totalFrames,
      fps: current.fps,
      progress: current.progress,
      outputUri: nil,
      currentPreviewPath: current.currentPreviewPath,
      errorCode: nil,
      errorMessage: nil
    )
    withLock { statuses[jobId] = cancelled }
    observer(cancelled)
  }

  func status(jobId: String) -> JobStatusDto? {
    withLock { statuses[jobId] }
  }

  func hasActiveRuntime(jobId: String) -> Bool {
    withLock { tasks[jobId] != nil }
  }

  private func update(_ status: JobStatusDto) {
    let accepted = withLock { () -> Bool in
      if let existing = statuses[status.jobId], Self.isTerminal(existing.state) {
        return false
      }
      statuses[status.jobId] = status
      return true
    }
    if accepted { observer(status) }
  }

  private func finishCompleted(jobId: String, outputURL: URL) {
    guard let current = status(jobId: jobId), !Self.isTerminal(current.state) else {
      cleanupRuntime(jobId: jobId)
      return
    }
    let completed = JobStatusDto(
      jobId: current.jobId,
      state: "completed",
      currentFrame: max(current.currentFrame, current.totalFrames),
      totalFrames: current.totalFrames,
      fps: current.fps,
      progress: 1,
      outputUri: outputURL.absoluteString,
      currentPreviewPath: current.currentPreviewPath,
      errorCode: nil,
      errorMessage: nil
    )
    setTerminal(completed)
  }

  private func finishCancelled(jobId: String) {
    guard let current = status(jobId: jobId) else {
      cleanupRuntime(jobId: jobId)
      return
    }
    if current.state != "cancelled" {
      setTerminal(JobStatusDto(
        jobId: current.jobId,
        state: "cancelled",
        currentFrame: current.currentFrame,
        totalFrames: current.totalFrames,
        fps: current.fps,
        progress: current.progress,
        outputUri: nil,
        currentPreviewPath: current.currentPreviewPath,
        errorCode: nil,
        errorMessage: nil
      ))
    } else {
      cleanupRuntime(jobId: jobId)
    }
  }

  private func finishFailed(jobId: String, code: String, message: String) {
    guard let current = status(jobId: jobId), current.state != "cancelled" else {
      cleanupRuntime(jobId: jobId)
      return
    }
    let failed = JobStatusDto(
      jobId: current.jobId,
      state: "failed",
      currentFrame: current.currentFrame,
      totalFrames: current.totalFrames,
      fps: current.fps,
      progress: current.progress,
      outputUri: nil,
      currentPreviewPath: current.currentPreviewPath,
      errorCode: code,
      errorMessage: message
    )
    setTerminal(failed)
  }

  private func setTerminal(_ status: JobStatusDto) {
    withLock { statuses[status.jobId] = status }
    observer(status)
    cleanupRuntime(jobId: status.jobId)
  }

  private func cleanupRuntime(jobId: String) {
    withLock {
      cancellations.removeValue(forKey: jobId)
      tasks.removeValue(forKey: jobId)
    }
  }

  private func withLock<T>(_ body: () -> T) -> T {
    lock.lock()
    defer { lock.unlock() }
    return body()
  }

  private static func isTerminal(_ state: String) -> Bool {
    state == "completed" || state == "failed" || state == "cancelled"
  }
}
