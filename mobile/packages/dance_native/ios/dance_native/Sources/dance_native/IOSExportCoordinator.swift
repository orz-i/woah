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
  private var lifecycles: [String: IOSFrameKernel.WorkLifecycle] = [:]
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
      guard !lifecycles.values.contains(where: { !$0.canAdmitReplacement }) else { return (false, []) }
      let predecessors = Array(tasks.values)
      statuses[jobId] = initial
      cancellations[jobId] = cancellation
      lifecycles[jobId] = IOSFrameKernel.WorkLifecycle()
      return (true, predecessors)
    }
    guard admission.0 else {
      throw PigeonError(code: "EXPORT_BUSY", message: "An iOS export job is already active.", details: nil)
    }
    let cacheLease: IOSRegenerableCacheRegistry.Lease?
    do {
      cacheLease = request.analysisCacheId.isEmpty ? nil : try registry.acquire(cacheId: request.analysisCacheId)
    } catch {
      withLock { statuses.removeValue(forKey: jobId); cancellations.removeValue(forKey: jobId); lifecycles.removeValue(forKey: jobId) }
      throw error
    }
    do {
      try journal.begin(initial)
    } catch {
      cacheLease?.close()
      withLock { statuses.removeValue(forKey: jobId); cancellations.removeValue(forKey: jobId); lifecycles.removeValue(forKey: jobId) }
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
        cacheLease?.close()
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
        cacheLease?.close()
        if cancellation.isCancelled {
          self.finishCancelled(jobId: jobId)
        } else {
          self.finishCompleted(jobId: jobId, outputURL: output)
        }
      } catch IOSExportPipelineError.cancelled {
        cacheLease?.close()
        self.finishCancelled(jobId: jobId)
      } catch let error as PigeonError {
        cacheLease?.close()
        self.finishFailed(
          jobId: jobId,
          code: error.code,
          message: IOSDiagnosticPrivacy.message(code: error.code, stage: .export)
        )
      } catch {
        cacheLease?.close()
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
    withLock { _ = lifecycles[jobId]?.requestCancel() }
    journal.cleanCancelledTemporary(jobId)
  }

  func status(jobId: String) -> JobStatusDto? {
    withLock { statuses[jobId] } ?? journal.status(jobId)
  }

  func waitForRuntime(jobId: String) async {
    guard let task = withLock({ tasks[jobId] }) else { return }
    await task.value
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
    let terminal = withLock { () -> JobStatusDto? in
      guard let lifecycle = lifecycles[status.jobId],
            let current = statuses[status.jobId], !Self.isTerminal(current.state) else { return nil }
      lifecycle.observe(status.state)
      let outcome = lifecycle.release()
      let result = JobStatusDto(
        jobId: status.jobId, state: outcome,
        currentFrame: status.currentFrame, totalFrames: status.totalFrames,
        fps: status.fps, progress: status.progress,
        outputUri: outcome == "completed" ? status.outputUri : nil,
        currentPreviewPath: nil,
        errorCode: outcome == "failed" ? status.errorCode : nil,
        errorMessage: outcome == "failed" ? status.errorMessage : nil
      )
      // The pipeline has returned and closed its native handles. Hold admission
      // and status reads until this job's private files and runtime owners drain.
      try? journal.update(result)
      journal.cleanTerminal(status.jobId)
      cancellations.removeValue(forKey: status.jobId)
      tasks.removeValue(forKey: status.jobId)
      lifecycles.removeValue(forKey: status.jobId)
      statuses[status.jobId] = result
      return result
    }
    if let terminal { observer(terminal) }
  }

  private func cleanupRuntime(jobId: String) {
    withLock {
      cancellations.removeValue(forKey: jobId)
      tasks.removeValue(forKey: jobId)
      lifecycles.removeValue(forKey: jobId)
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
