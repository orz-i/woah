import Foundation

final class IOSExportCoordinator {
  typealias StatusObserver = (JobStatusDto) -> Void

  private let pipeline: IOSExportPipeline
  private let observer: StatusObserver
  private let registry: IOSRegenerableCacheRegistry
  private let journal = IOSExportJobJournal()
  private let lock = NSLock()
  private var statuses: [String: JobStatusDto] = [:]
  private var cancellations: [String: IOSExportCancellationFlag] = [:]
  private var tasks: [String: Task<Void, Never>] = [:]

  init(
    analysisCache: IOSAnalysisCache,
    inferenceProvider: IOSExportPipeline.InferenceProvider? = nil,
    faceLocatorProvider: IOSExportPipeline.FaceLocatorProvider? = nil,
    registry: IOSRegenerableCacheRegistry = .shared,
    observer: @escaping StatusObserver
  ) {
    pipeline = IOSExportPipeline(
      analysisCache: analysisCache,
      inferenceProvider: inferenceProvider,
      faceLocatorProvider: faceLocatorProvider
    )
    self.observer = observer
    self.registry = registry
  }

  func start(request: ExportRequestDto) throws -> String {
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
    // Reserve admission atomically. A cancelled job may still own a decoder,
    // writer and cache lease while its current frame unwinds. Accept the retry
    // now, but serialize its actual pipeline behind those runtime owners.
    let admission = withLock { () -> (Bool, [Task<Void, Never>]) in
      guard !statuses.values.contains(where: { !Self.isTerminal($0.state) }) else { return (false, []) }
      let predecessors = Array(tasks.values)
      statuses[jobId] = initial
      cancellations[jobId] = cancellation
      return (true, predecessors)
    }
    guard admission.0 else {
      throw PigeonError(code: "EXPORT_BUSY", message: "An iOS export job is already active.", details: nil)
    }
    let cacheLease: IOSRegenerableCacheRegistry.Lease?
    do {
      cacheLease = request.analysisCacheId.isEmpty ? nil : try registry.acquire(cacheId: request.analysisCacheId)
    } catch {
      withLock { statuses.removeValue(forKey: jobId); cancellations.removeValue(forKey: jobId) }
      throw error
    }
    do {
      try journal.begin(initial)
    } catch {
      cacheLease?.close()
      withLock { statuses.removeValue(forKey: jobId); cancellations.removeValue(forKey: jobId) }
      throw PigeonError(
        code: "EXPORT_FAILED",
        message: "Could not durably record the initial privacy export state.",
        details: nil
      )
    }
    observer(initial)

    // Register runtime ownership before the detached task can finish. Without
    // this launch gate, an immediate validation failure can clean up before the
    // task is inserted and leave a stale task entry that blocks future exports.
    let launchGate = DispatchSemaphore(value: 0)
    let task = Task.detached(priority: .userInitiated) { [weak self, cacheLease, predecessors = admission.1] in
      defer { cacheLease?.close() }
      launchGate.wait()
      guard let self else { return }
      for predecessor in predecessors { await predecessor.value }
      if cancellation.isCancelled {
        self.finishCancelled(jobId: jobId)
        return
      }
      do {
        let output = try await self.pipeline.execute(
          jobId: jobId,
          request: request,
          cancellation: cancellation,
          onStatus: { [weak self] status in
            self?.update(status)
          },
          onOutputPrepared: { [self] final, temp in
            try self.journal.rememberPaths(jobId: jobId, final: final, temp: temp)
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
          message: IOSDiagnosticPrivacy.message(code: error.code, stage: .export)
        )
      } catch {
        self.finishFailed(
          jobId: jobId,
          code: "EXPORT_FAILED",
          message: IOSDiagnosticPrivacy.message(code: "EXPORT_FAILED", stage: .export)
        )
      }
    }
    withLock { tasks[jobId] = task }
    launchGate.signal()
    return jobId
  }

  func cancel(jobId: String) {
    let cancellation = withLock { cancellations[jobId] }
    // Atomic publication is the commit point. If the pipeline already committed
    // the final file, cancellation is too late and completion owns the terminal
    // state. Before that point, cancel wins and publication is prohibited.
    guard cancellation?.cancel() == true else { return }
    let cancelled = withLock { () -> JobStatusDto? in
      guard let current = statuses[jobId], !Self.isTerminal(current.state) else { return nil }
      let terminal = JobStatusDto(
        jobId: current.jobId, state: "cancelled",
        currentFrame: current.currentFrame, totalFrames: current.totalFrames,
        fps: current.fps, progress: current.progress, outputUri: nil,
        currentPreviewPath: current.currentPreviewPath, errorCode: nil, errorMessage: nil
      )
      statuses[jobId] = terminal
      try? journal.update(terminal)
      return terminal
    }
    guard let cancelled else { return }
    // Publication is prohibited by the cancellation token. Unlink only this
    // job's registered private temporary files now; the retiring worker closes
    // its handles and performs the same idempotent cleanup before a retry runs.
    journal.cleanTerminal(jobId)
    observer(cancelled)
  }

  func status(jobId: String) -> JobStatusDto? {
    withLock { statuses[jobId] } ?? journal.status(jobId)
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
      try? journal.update(status)
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
      errorCode: IOSDiagnosticPrivacy.safeCode(code),
      errorMessage: IOSDiagnosticPrivacy.message(code: code, stage: .export)
    )
    setTerminal(failed)
  }

  private func setTerminal(_ status: JobStatusDto) {
    let accepted = withLock { () -> Bool in
      // Cancellation and a writer failure can finish on different threads.
      // The first terminal snapshot owns the result and cannot be overwritten.
      if let current = statuses[status.jobId], Self.isTerminal(current.state) { return false }
      statuses[status.jobId] = status
      try? journal.update(status)
      return true
    }
    if accepted { observer(status) }
    cleanupRuntime(jobId: status.jobId)
  }

  private func cleanupRuntime(jobId: String) {
    withLock {
      cancellations.removeValue(forKey: jobId)
      tasks.removeValue(forKey: jobId)
    }
    journal.cleanTerminal(jobId)
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
