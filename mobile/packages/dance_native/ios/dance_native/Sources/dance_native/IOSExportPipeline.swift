import AVFoundation
import CoreGraphics
import CoreImage
import CoreMedia
import CoreVideo
import Foundation

final class IOSExportCancellationFlag {
  private let lock = NSLock()
  private var value = false
  private var committed = false

  @discardableResult
  func cancel() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    if committed { return false }
    value = true
    return true
  }

  func commitIfNotCancelled(_ body: () throws -> Void) rethrows -> Bool {
    lock.lock()
    defer { lock.unlock() }
    if value { return false }
    try body()
    committed = true
    return true
  }

  var isCancelled: Bool {
    lock.lock()
    defer { lock.unlock() }
    return value
  }
}

enum IOSExportPipelineError: Error {
  case cancelled
}

/// Feeds PCM on an independent writer-input queue while the video task runs
/// YOLO/Metal and H.264. AVAssetWriter must receive BOTH tracks concurrently:
/// serially filling a single track can exhaust its internal muxing queue even
/// when there are only a few source frames left to encode.
private final class IOSAsyncAudioWriter: @unchecked Sendable {
  private let reader: AVAssetReader
  private let output: AVAssetReaderTrackOutput
  private let input: AVAssetWriterInput
  private let writer: AVAssetWriter
  private let cancellation: IOSExportCancellationFlag
  private let rebase: (CMSampleBuffer) throws -> CMSampleBuffer
  private let queue = DispatchQueue(label: "art.gaoge.dance.ios-export-audio", qos: .userInitiated)
  private let finished = DispatchSemaphore(value: 0)
  private let lock = NSLock()
  private var stopRequested = false
  private var terminalError: Error?

  init(
    reader: AVAssetReader,
    output: AVAssetReaderTrackOutput,
    input: AVAssetWriterInput,
    writer: AVAssetWriter,
    cancellation: IOSExportCancellationFlag,
    rebase: @escaping (CMSampleBuffer) throws -> CMSampleBuffer
  ) {
    self.reader = reader
    self.output = output
    self.input = input
    self.writer = writer
    self.cancellation = cancellation
    self.rebase = rebase
  }

  func requestStop() {
    lock.lock()
    stopRequested = true
    lock.unlock()
  }

  private var shouldStop: Bool {
    lock.lock()
    defer { lock.unlock() }
    return stopRequested || cancellation.isCancelled
  }

  private func fail(_ message: String) -> PigeonError {
    PigeonError(code: "EXPORT_FAILED", message: message, details: nil)
  }

  func start() {
    queue.async { [self] in
      do {
        while let sample = output.copyNextSampleBuffer() {
          if shouldStop { throw IOSExportPipelineError.cancelled }
          let deadline = CFAbsoluteTimeGetCurrent() + 60.0
          while !input.isReadyForMoreMediaData {
            if shouldStop { throw IOSExportPipelineError.cancelled }
            if writer.status == .failed {
              throw fail("iOS audio writer input failed.")
            }
            if CFAbsoluteTimeGetCurrent() > deadline {
              throw fail("iOS audio input remained blocked for more than 60 seconds.")
            }
            Thread.sleep(forTimeInterval: 0.002)
          }
          let rebased = try rebase(sample)
          guard input.append(rebased) else {
            throw fail("Could not append iOS export audio sample.")
          }
        }
        if reader.status == .failed {
          throw fail("iOS audio decoding failed.")
        }
        if shouldStop { throw IOSExportPipelineError.cancelled }
        input.markAsFinished()
      } catch {
        reader.cancelReading()
        lock.lock()
        terminalError = error
        lock.unlock()
      }
      finished.signal()
    }
  }

  func waitForCompletion(timeout: TimeInterval = 120.0) throws {
    if finished.wait(timeout: .now() + timeout) != .success {
      requestStop()
      throw fail("iOS audio encoder did not finish within the bounded wait.")
    }
    lock.lock()
    let error = terminalError
    lock.unlock()
    if let error { throw error }
  }
}

final class IOSExportPipeline {
  typealias InferenceProvider = (CGImage) throws -> IOSYoloInferenceResult
  typealias FaceLocatorProvider = () -> IOSFaceLocating

  private let analysisCache: IOSAnalysisCache
  private let fileManager: FileManager
  private let ciContext = CIContext()
  private let inferenceProvider: InferenceProvider?
  private let faceLocatorProvider: FaceLocatorProvider?

  init(
    analysisCache: IOSAnalysisCache,
    fileManager: FileManager = .default,
    inferenceProvider: InferenceProvider? = nil,
    faceLocatorProvider: FaceLocatorProvider? = nil
  ) {
    self.analysisCache = analysisCache
    self.fileManager = fileManager
    self.inferenceProvider = inferenceProvider
    self.faceLocatorProvider = faceLocatorProvider
  }

  func execute(
    jobId: String,
    request: ExportRequestDto,
    cancellation: IOSExportCancellationFlag,
    onStatus: @escaping (JobStatusDto) -> Void,
    onOutputPrepared: ((URL, URL) throws -> Void)? = nil
  ) async throws -> URL {
    try validate(request: request)
    if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }

    let sourceURL = IOSVideoProbe.assetURL(from: request.sourceUri)
    let securityScoped = sourceURL.isFileURL && sourceURL.startAccessingSecurityScopedResource()
    defer {
      if securityScoped { sourceURL.stopAccessingSecurityScopedResource() }
    }

    let asset = AVURLAsset(url: sourceURL)
    let duration = try await asset.load(.duration)
    let durationSeconds = CMTimeGetSeconds(duration)
    guard durationSeconds.isFinite, durationSeconds > 0 else {
      throw exportError("INVALID_VIDEO", "Source video has no usable duration.")
    }
    guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
      throw exportError("INVALID_VIDEO", "Source video has no video track.")
    }
    let audioTrack = try await asset.loadTracks(withMediaType: .audio).first
    let naturalSize = try await videoTrack.load(.naturalSize)
    let preferredTransform = try await videoTrack.load(.preferredTransform)
    let sourceNominalFrameRate = Double(try await videoTrack.load(.nominalFrameRate))
    let displaySize = transformedSize(naturalSize, transform: preferredTransform)

    let durationMs = Int64((durationSeconds * 1000.0).rounded())
    let trimStartMs = min(max(0, request.trimStartMs), durationMs)
    let requestedEnd = request.trimEndMs ?? durationMs
    let trimEndMs = min(max(trimStartMs, requestedEnd), durationMs)
    guard trimEndMs > trimStartMs else {
      throw exportError("INVALID_TRIM_RANGE", "trimEndMs must be greater than trimStartMs.")
    }
    let trimStart = CMTime(value: trimStartMs, timescale: 1000)
    let trimmedDuration = CMTime(value: trimEndMs - trimStartMs, timescale: 1000)
    let trimmedDurationSeconds = CMTimeGetSeconds(trimmedDuration)

    let target = targetSize(
      request: request,
      displaySize: displaySize
    )
    let targetFps: Double
    if request.targetFps.isFinite, request.targetFps > 0 {
      targetFps = request.targetFps
    } else if sourceNominalFrameRate.isFinite, sourceNominalFrameRate > 0 {
      targetFps = sourceNominalFrameRate
    } else {
      targetFps = 30.0
    }
    let totalFrames = max(1, Int64(floor(trimmedDurationSeconds * targetFps + 0.0001)))
    let bitrate = max(2_000_000, min(80_000_000, Int(request.videoBitrate)))
    let expectedFrameRate = max(1, Int(targetFps.rounded()))
    let output = try outputURLs(request: request)
    // Journal the exact temporary path before AVAssetWriter can create it, so
    // recovery can remove only this job's orphan after a force-kill.
    try onOutputPrepared?(output.final, output.temp)
    try? fileManager.removeItem(at: output.temp)

    let writer = try AVAssetWriter(outputURL: output.temp, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true
    let videoInput = AVAssetWriterInput(
      mediaType: .video,
      outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.h264,
        AVVideoWidthKey: target.width,
        AVVideoHeightKey: target.height,
        AVVideoCompressionPropertiesKey: [
          AVVideoAverageBitRateKey: bitrate,
          AVVideoExpectedSourceFrameRateKey: expectedFrameRate,
          AVVideoMaxKeyFrameIntervalKey: max(1, expectedFrameRate * 2),
          AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
        ],
      ]
    )
    videoInput.expectsMediaDataInRealTime = false
    guard writer.canAdd(videoInput) else {
      throw exportError("ENCODER_UNAVAILABLE", "AVAssetWriter rejected the H.264 video input.")
    }
    writer.add(videoInput)
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: videoInput,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: target.width,
        kCVPixelBufferHeightKey as String: target.height,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      ]
    )

    let audioInput: AVAssetWriterInput?
    if let audioTrack {
      let format = try await audioFormat(track: audioTrack)
      let created = AVAssetWriterInput(
        mediaType: .audio,
        outputSettings: [
          AVFormatIDKey: kAudioFormatMPEG4AAC,
          AVSampleRateKey: format.sampleRate,
          AVNumberOfChannelsKey: format.channels,
          AVEncoderBitRateKey: 128_000,
        ]
      )
      created.expectsMediaDataInRealTime = false
      if writer.canAdd(created) {
        writer.add(created)
        audioInput = created
      } else {
        throw exportError("AUDIO_WRITER_UNAVAILABLE", "AVAssetWriter rejected the source audio track.")
      }
    } else {
      audioInput = nil
    }

    guard writer.startWriting() else {
      throw writerError(writer, fallback: "Could not start the iOS H.264 writer.")
    }
    writer.startSession(atSourceTime: .zero)

    let initialStatus = JobStatusDto(
      jobId: jobId,
      state: "exporting",
      currentFrame: 0,
      totalFrames: totalFrames,
      fps: 0,
      progress: 0,
      outputUri: nil,
      currentPreviewPath: nil,
      errorCode: nil,
      errorMessage: nil
    )
    onStatus(initialStatus)

    var audioFeed: IOSAsyncAudioWriter?
    do {
      let metadata = try analysisCache.loadMetadata(cacheId: request.analysisCacheId)
      let runner = inferenceProvider == nil ? IOSYoloRunner() : nil
      let renderer = try IOSMetalPreviewRenderer()
      let fullBodyIds = Set(request.selectedPersonIds.map { Int($0) })
      let faceOnlyIds = Set((request.faceOnlyPersonIds ?? []).map { Int($0) })
        .subtracting(fullBodyIds)
      let identityFrameWidth = max(1, Int(displaySize.width.rounded()))
      let identityFrameHeight = max(1, Int(displaySize.height.rounded()))
      let followTargetId = request.follow.enabled ? request.follow.targetPersonId.map { Int($0) } : nil
      let initialIds = Set(metadata?.persons.map(\.id) ?? [])
      let freshSceneCoverage = !request.follow.enabled && !initialIds.isEmpty
        && IOSHighResolutionPrivacyCoverage.hasDetailResolution(width: identityFrameWidth, height: identityFrameHeight)
        && ((faceOnlyIds.isEmpty && fullBodyIds.isSuperset(of: initialIds))
          || (fullBodyIds.isEmpty && faceOnlyIds.isSuperset(of: initialIds)))
      let currentSceneFaceCoverage = IOSPrivacySelectionScope.coversAllOtherFaces(
        metadata: metadata, fullBodyIds: fullBodyIds, faceOnlyIds: faceOnlyIds, followTargetId: followTargetId)
      let camera = IOSSubjectReframer()
      var cameraHasFrame = false
      var followSeed: SIMD4<Float>?
      if request.follow.enabled {
        guard let followTargetId,
              let root = metadata?.persons.first(where: { $0.id == followTargetId }) else {
          throw exportError("FOLLOW_TARGET_UNRESOLVED", "Follow target is missing from the analysis cache.")
        }
        followSeed = SIMD4<Float>(Float(root.bbox.left), Float(root.bbox.top), Float(root.bbox.right), Float(root.bbox.bottom))
      }
      let tracker = IOSTemporalIdentityTracker(
        metadata: metadata,
        fullBodyIds: freshSceneCoverage ? [] : fullBodyIds,
        faceOnlyIds: freshSceneCoverage ? [] : faceOnlyIds,
        frameWidth: identityFrameWidth,
        frameHeight: identityFrameHeight,
        followTargetId: followTargetId,
        usesCurrentSceneFaceCoverage: currentSceneFaceCoverage
      )
      // Temporal full-body class continuity never upgrades a FACE_ONLY effect
      // to whole-body masking. Uniform scene face coverage above uses only
      // current face geometry and keeps the strict hero identity separate.
      let privacyClassTracker = !freshSceneCoverage && !fullBodyIds.isEmpty && faceOnlyIds.isEmpty
        ? IOSPrivacyClassTemporalTracker()
        : nil
#if DEBUG && targetEnvironment(simulator)
      let identityRecorder = IOSPrivacyIdentityFrameRecorder(
        sourceURL: sourceURL, metadata: metadata,
        fullBodyIds: fullBodyIds, faceOnlyIds: faceOnlyIds,
        followId: followTargetId, frameWidth: identityFrameWidth,
        frameHeight: identityFrameHeight
      )
      // Read-only geometry: the independent pixel auditor projects separately
      // captured YOLO masks into the actual followed output coordinate system.
      let cropRecorder = IOSPixelAuditCropRecorder(
        sourceName: sourceURL.lastPathComponent,
        outputWidth: target.width, outputHeight: target.height
      )
#endif
      var privacyClassHardRootsSent = false
      let facePrivacyResolver: IOSFacePrivacyTemporalResolver?
      if !IOSFrameKernel.needsFaceProtection(body: !fullBodyIds.isEmpty, face: !faceOnlyIds.isEmpty) {
        facePrivacyResolver = nil
      } else if let faceLocatorProvider {
        facePrivacyResolver = IOSFacePrivacyTemporalResolver(locator: faceLocatorProvider())
      } else {
        facePrivacyResolver = IOSFacePrivacyTemporalResolver()
      }

      let videoReader = try makeVideoReader(
        asset: asset,
        track: videoTrack,
        timeRange: CMTimeRange(start: trimStart, duration: trimmedDuration)
      )
      guard videoReader.reader.startReading() else {
        throw readerError(videoReader.reader, fallback: "Could not start iOS video decoding.")
      }
      // Both writer inputs advance independently, avoiding muxer backpressure.
      if let audioTrack, let audioInput {
        let prepared = try makeAudioReader(
          asset: asset,
          track: audioTrack,
          timeRange: CMTimeRange(start: trimStart, duration: trimmedDuration)
        )
        guard prepared.reader.startReading() else {
          throw readerError(prepared.reader, fallback: "Could not start iOS audio decoding.")
        }
        let feeder = IOSAsyncAudioWriter(
          reader: prepared.reader,
          output: prepared.output,
          input: audioInput,
          writer: writer,
          cancellation: cancellation,
          rebase: { [self] sample in
            try rebasedSampleBuffer(sample, subtracting: trimStart)
          }
        )
        audioFeed = feeder
        feeder.start()
      }

      let startedAt = CFAbsoluteTimeGetCurrent()
      var outputFrameIndex: Int64 = 0
      var sawVideoFrame = false
      var lastPresentation = CMTime.invalid
      let fallbackFrameDuration = CMTime(
        seconds: 1.0 / targetFps,
        preferredTimescale: 60_000
      )
      while true {
        // A detached Swift task has no per-iteration Objective-C pool. Core
        // Image/Vision temporaries otherwise accumulate for the entire movie
        // (tens of GiB for 4K), even after Swift frame values leave scope.
        let hasFrame = try autoreleasepool { () throws -> Bool in
          guard let sample = videoReader.output.copyNextSampleBuffer() else { return false }
          if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }
          let sourcePTS = CMSampleBufferGetPresentationTimeStamp(sample)
          var presentation = CMTimeSubtract(sourcePTS, trimStart)
          if !presentation.isValid || CMTimeCompare(presentation, .zero) < 0 {
            presentation = .zero
          }
          if CMTimeCompare(presentation, trimmedDuration) >= 0 { return true }
          if lastPresentation.isValid, CMTimeCompare(presentation, lastPresentation) <= 0 {
            presentation = CMTimeAdd(lastPresentation, fallbackFrameDuration)
          }
          guard let sourcePixelBuffer = CMSampleBufferGetImageBuffer(sample) else { return true }
          let frame = try orientedImage(
            pixelBuffer: sourcePixelBuffer,
            preferredTransform: preferredTransform
          )
          let inference: IOSYoloInferenceResult
          if let inferenceProvider {
            inference = try inferenceProvider(frame)
          } else {
            guard let runner else {
              throw exportError("IOS_INFERENCE_RUNTIME_UNAVAILABLE", "iOS YOLO runner was not initialized.")
            }
            // Production export identity/detection stays on XNNPACK until real
            // iPhone delegate parity is accepted, matching the analysis root.
            let primary = try runner.run(image: frame, preferredBackend: .xnnpack)
            if (freshSceneCoverage || currentSceneFaceCoverage)
                && IOSHighResolutionPrivacyCoverage.applies(width: frame.width, height: frame.height,
                  initialCount: metadata?.persons.count ?? 0) {
              inference = try IOSHighResolutionPrivacyCoverage.enhance(image: frame, primary: primary, runner: runner)
            } else {
              inference = primary
            }
          }
          let timestampUs = Int64((CMTimeGetSeconds(sourcePTS) * 1_000_000.0).rounded())
  #if DEBUG && targetEnvironment(simulator)
          identityRecorder?.recordInput(inference, timestampUs: timestampUs)
  #endif
          let tracked: [IOSPreviewPerson]
          do {
            tracked = try tracker.update(
              detections: inference.detections,
              preprocess: inference.preprocess,
              timestampUs: timestampUs
            )
          } catch {
  #if DEBUG && targetEnvironment(simulator)
            identityRecorder?.recordFailure(timestampUs: timestampUs, error: error, tracker: tracker)
  #endif
            throw error
          }
          var freshFullBodyPrivacyEvidence: [IOSFreshPrivacyClassEvidence] = []
          if let privacyClassTracker {
            var hardRoots: [Int: IOSPrivacySelectionClass] = [:]
            if !privacyClassHardRootsSent, !inference.detections.isEmpty {
              let rootPersons = try IOSPreviewIdentityMatcher.assign(
                detections: inference.detections,
                metadata: metadata,
                frameWidth: identityFrameWidth,
                frameHeight: identityFrameHeight
              )
              if rootPersons.count == inference.detections.count {
                for index in inference.detections.indices {
                  hardRoots[index] = fullBodyIds.contains(rootPersons[index].id)
                    ? .selected
                    : .unselected
                }
                privacyClassHardRootsSent = true
              }
            }
            freshFullBodyPrivacyEvidence = privacyClassTracker.update(
              detections: inference.detections,
              preprocess: inference.preprocess,
              hardClassByDetectionIndex: hardRoots,
              timestampUs: timestampUs
            )
          }
  #if DEBUG && targetEnvironment(simulator)
          identityRecorder?.recordTracking(tracker: tracker, timestampUs: timestampUs,
                                           classEvidence: freshFullBodyPrivacyEvidence)
  #endif
          // A uniform all-person selection has no identity exemptions. Each
          // current observation gets an isolated face lease; stale roots must not
          // borrow a neighbour's localization cache or paint a floating head.
          let useCurrentObservations = freshSceneCoverage || currentSceneFaceCoverage
          // Only the exact currently observed hero can be exempt. A camera
          // occlusion proxy/handoff never grants an identity/privacy exemption.
          let observedHero = currentSceneFaceCoverage ? followTargetId.flatMap { tracker.observedBounds(for: $0) } : nil
          let renderPersons = useCurrentObservations
            ? IOSPrivacySelectionScope.currentPersons(detections: inference.detections,
                heroId: currentSceneFaceCoverage ? followTargetId : nil,
                observedHeroBounds: observedHero, frameIndex: Int(outputFrameIndex))
            : tracked
          let unmaskedPersonId = currentSceneFaceCoverage && renderPersons.contains(where: { $0.id == followTargetId })
            ? followTargetId : nil
          let sceneIds = Set(renderPersons.map(\.id))
          let renderFullBodyIds = freshSceneCoverage && !fullBodyIds.isEmpty ? sceneIds : fullBodyIds
          let renderFaceOnlyIds = currentSceneFaceCoverage ? sceneIds.subtracting(unmaskedPersonId.map { [$0] } ?? [])
            : (freshSceneCoverage && !faceOnlyIds.isEmpty ? sceneIds : faceOnlyIds)
          let coveredHeadFallbackIds = useCurrentObservations
            ? IOSHighResolutionPrivacyCoverage.coveredHeadFallbackIds(persons: renderPersons, preprocess: inference.preprocess) : []
          let faceRegions = facePrivacyResolver?.resolve(
            image: frame,
            persons: renderPersons,
            // Full-body segmentation can omit a profile, a jaw or a mirror
            // face. Its privacy mask must also include the selected faces.
            faceOnlyIds: IOSFrameKernel.faceProtectionIds(body: renderFullBodyIds, face: renderFaceOnlyIds),
            preprocess: inference.preprocess,
            freshPrivacyClassEvidence: useCurrentObservations ? [] : tracker.facePrivacyClassEvidence(),
            protectUnassignedFaces: useCurrentObservations && (!faceOnlyIds.isEmpty || !fullBodyIds.isEmpty),
            unmaskedPersonId: unmaskedPersonId,
            coveredHeadFallbackIds: coveredHeadFallbackIds,
            timestampUs: timestampUs
          ) ?? [:]
          if useCurrentObservations && (!faceOnlyIds.isEmpty || !fullBodyIds.isEmpty) && facePrivacyResolver?.localizationFailed == true {
            throw exportError("EXPORT_PRIVACY_UNRESOLVED", "Current scene face localization failed.")
          }
          var sourceCrop = SIMD4<Float>(0, 0, 1, 1)
          if let followTargetId {
            let sourceAspect = Float(frame.width) / Float(frame.height)
            let followBounds = tracker.followBounds(for: followTargetId).map { box in
              SIMD4<Float>(box.x / Float(frame.width), box.y / Float(frame.height), box.z / Float(frame.width), box.w / Float(frame.height))
            }
            sourceCrop = camera.crop(
              target: followBounds ?? (cameraHasFrame ? nil : followSeed),
              presentationTimeUs: timestampUs,
              sourceAspectRatio: sourceAspect,
              outputAspectRatio: request.follow.outputAspectRatio.map { Float($0) } ?? sourceAspect,
              zoom: Float(request.follow.zoom),
              smoothFactor: Float(request.follow.smoothFactor)
            )
            cameraHasFrame = true
          }
  #if DEBUG && targetEnvironment(simulator)
          cropRecorder?.record(timestampUs: timestampUs, crop: sourceCrop,
            faceRegions: faceRegions, persons: renderPersons, unmaskedPersonId: unmaskedPersonId)
  #endif
          let rendered = try renderer.render(
            source: frame,
            persons: renderPersons,
            preprocess: inference.preprocess,
            fullBodyIds: renderFullBodyIds,
            faceOnlyIds: IOSPrivacySelectionScope.compositorFaceIds(selected: renderFaceOnlyIds,
              coveredHeadFallbackIds: coveredHeadFallbackIds, explicitFaceRegionIds: Set(faceRegions.keys)),
            effects: request.effects,
            faceRegions: faceRegions,
            freshFullBodyPrivacyEvidence: freshFullBodyPrivacyEvidence,
            preferFreshFullBodyClassPrimary: privacyClassTracker != nil
              && !freshFullBodyPrivacyEvidence.isEmpty,
            tightMask: !fullBodyIds.isEmpty,
            outputWidth: target.width,
            outputHeight: target.height,
            sourceCrop: sourceCrop,
            legStretchTargetId: followTargetId,
            cropClarityScale: request.cropClarityScale ?? 1.0
          )
          let outputPixelBuffer = try makePixelBuffer(
            adaptor: adaptor,
            image: rendered,
            width: target.width,
            height: target.height
          )

          try waitUntilReady(videoInput, cancellation: cancellation, writer: writer)
          guard adaptor.append(outputPixelBuffer, withPresentationTime: presentation) else {
            throw writerError(writer, fallback: "Failed to append an iOS export video frame.")
          }
          lastPresentation = presentation
          sawVideoFrame = true
          outputFrameIndex += 1
          emitProgress(
            jobId: jobId,
            currentFrame: outputFrameIndex,
            totalFrames: totalFrames,
            mediaProgress: min(
              0.99,
              max(0, CMTimeGetSeconds(presentation) / trimmedDurationSeconds)
            ),
            startedAt: startedAt,
            onStatus: onStatus
          )
          return true
        }
        if !hasFrame { break }
      }
      if videoReader.reader.status == .failed {
        throw readerError(videoReader.reader, fallback: "iOS video decoding failed.")
      }
      guard sawVideoFrame else {
        throw exportError("DECODE_FRAME_FAILED", "No video frames were decoded in the trim range.")
      }
      videoInput.markAsFinished()

      // The audio queue has its own AVAssetWriterInput and marks it finished
      // after the trimmed PCM tail. Completion is bounded to avoid a deadlock.
      try audioFeed?.waitForCompletion()

      if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }
      try await finish(writer: writer)
      guard writer.status == .completed else {
        throw writerError(writer, fallback: "iOS export writer did not complete.")
      }
      let committed = try cancellation.commitIfNotCancelled {
        try atomicFinalize(temp: output.temp, final: output.final)
      }
      guard committed else { throw IOSExportPipelineError.cancelled }
      return output.final
    } catch {
      audioFeed?.requestStop()
      writer.cancelWriting()
      try? fileManager.removeItem(at: output.temp)
      throw error
    }
  }

  private func validate(request: ExportRequestDto) throws {
    guard !request.sourceUri.isEmpty else {
      throw exportError("INVALID_ARGUMENT", "sourceUri is required.")
    }
    guard !request.analysisCacheId.isEmpty else {
      throw exportError("INVALID_ARGUMENT", "analysisCacheId is required.")
    }
    guard !request.outputFilePath.isEmpty else {
      throw exportError("INVALID_ARGUMENT", "outputFilePath is required.")
    }
    // Do not silently downgrade Android-only transform semantics in the first
    // iOS export closure. Unsupported effects are rejected explicitly.
    if request.follow.enabled, let ratio = request.follow.outputAspectRatio,
       !ratio.isFinite || ratio <= 0 {
      throw exportError("INVALID_ARGUMENT", "Invalid follow output aspect ratio.")
    }
    if request.effects.skinWhiten > 0 {
      throw exportError(
        "PLATFORM_NOT_SUPPORTED",
        "iOS does not yet support skin whitening during export."
      )
    }
    // iOS Metal supports selected-protagonist leg stretch, but it requires
    // an actual follow root to source the person's current segmentation.
    // A crafted/legacy request with the effect enabled and no root must not
    // silently export the unmodified video as though the effect succeeded.
    if request.effects.legStretchEnabled &&
       (!request.follow.enabled || request.follow.targetPersonId == nil) {
      throw exportError(
        "INVALID_ARGUMENT",
        "Leg stretch requires an enabled protagonist follow target."
      )
    }
  }

  private func targetSize(
    request: ExportRequestDto,
    displaySize: CGSize
  ) -> (width: Int, height: Int) {
    var width = Int(request.targetWidth)
    var height = Int(request.targetHeight)
    if width <= 0 || height <= 0 {
      width = max(2, Int(abs(displaySize.width).rounded(.down)))
      height = max(2, Int(abs(displaySize.height).rounded(.down)))
    }
    // The shared ExportPlan already applies encoder capability fallback. Keep
    // this layer deterministic and only normalize to even H.264 dimensions.
    width = max(2, (width / 2) * 2)
    height = max(2, (height / 2) * 2)
    return (width, height)
  }

  private func outputURLs(request: ExportRequestDto) throws -> (final: URL, temp: URL) {
    let rawPath = request.outputFilePath
    let final: URL
    if rawPath.hasPrefix("/") {
      final = URL(fileURLWithPath: rawPath)
    } else {
      let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
        ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
      let directory = root.appendingPathComponent("exports", isDirectory: true)
      try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
      let requestedName = URL(fileURLWithPath: rawPath).lastPathComponent
      let name = requestedName.lowercased().hasSuffix(".mp4") && !requestedName.isEmpty
        ? requestedName
        : "export_\(Int64(Date().timeIntervalSince1970 * 1000)).mp4"
      final = directory.appendingPathComponent(name)
    }
    try fileManager.createDirectory(
      at: final.deletingLastPathComponent(),
      withIntermediateDirectories: true
    )
    let temp = final.deletingLastPathComponent().appendingPathComponent(
      ".\(final.deletingPathExtension().lastPathComponent).\(UUID().uuidString).partial.mp4"
    )
    return (final, temp)
  }

  private func makeVideoReader(
    asset: AVAsset,
    track: AVAssetTrack,
    timeRange: CMTimeRange
  ) throws -> (reader: AVAssetReader, output: AVAssetReaderTrackOutput) {
    let reader = try AVAssetReader(asset: asset)
    reader.timeRange = timeRange
    let output = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      ]
    )
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else {
      throw exportError("DECODER_UNAVAILABLE", "AVAssetReader rejected the iOS video output.")
    }
    reader.add(output)
    return (reader, output)
  }

  private func makeAudioReader(
    asset: AVAsset,
    track: AVAssetTrack,
    timeRange: CMTimeRange
  ) throws -> (reader: AVAssetReader, output: AVAssetReaderTrackOutput) {
    let reader = try AVAssetReader(asset: asset)
    reader.timeRange = timeRange
    let output = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsFloatKey: false,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsNonInterleaved: false,
      ]
    )
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else {
      throw exportError("AUDIO_DECODER_UNAVAILABLE", "AVAssetReader rejected the source audio track.")
    }
    reader.add(output)
    return (reader, output)
  }

  private func audioFormat(track: AVAssetTrack) async throws -> (sampleRate: Double, channels: Int) {
    let descriptions = try await track.load(.formatDescriptions)
    for description in descriptions {
      if let stream = CMAudioFormatDescriptionGetStreamBasicDescription(description) {
        let sampleRate = stream.pointee.mSampleRate > 0 ? stream.pointee.mSampleRate : 44_100
        let channels = max(1, Int(stream.pointee.mChannelsPerFrame))
        return (sampleRate, channels)
      }
    }
    return (44_100, 2)
  }

  private func rebasedSampleBuffer(
    _ sample: CMSampleBuffer,
    subtracting start: CMTime
  ) throws -> CMSampleBuffer {
    var needed = 0
    var status = CMSampleBufferGetSampleTimingInfoArray(
      sample,
      entryCount: 0,
      arrayToFill: nil,
      entriesNeededOut: &needed
    )
    guard status == noErr, needed > 0 else {
      throw exportError("AUDIO_TIMING_FAILED", "Could not read source audio timing.")
    }
    var timings = [CMSampleTimingInfo](
      repeating: CMSampleTimingInfo(
        duration: .invalid,
        presentationTimeStamp: .invalid,
        decodeTimeStamp: .invalid
      ),
      count: needed
    )
    status = timings.withUnsafeMutableBufferPointer { buffer in
      CMSampleBufferGetSampleTimingInfoArray(
        sample,
        entryCount: needed,
        arrayToFill: buffer.baseAddress,
        entriesNeededOut: &needed
      )
    }
    guard status == noErr else {
      throw exportError("AUDIO_TIMING_FAILED", "Could not copy source audio timing.")
    }
    for index in timings.indices {
      if timings[index].presentationTimeStamp.isValid {
        timings[index].presentationTimeStamp = CMTimeSubtract(
          timings[index].presentationTimeStamp,
          start
        )
      }
      if timings[index].decodeTimeStamp.isValid {
        timings[index].decodeTimeStamp = CMTimeSubtract(
          timings[index].decodeTimeStamp,
          start
        )
      }
    }
    var output: CMSampleBuffer?
    status = timings.withUnsafeBufferPointer { buffer in
      CMSampleBufferCreateCopyWithNewTiming(
        allocator: kCFAllocatorDefault,
        sampleBuffer: sample,
        sampleTimingEntryCount: buffer.count,
        sampleTimingArray: buffer.baseAddress!,
        sampleBufferOut: &output
      )
    }
    guard status == noErr, let output else {
      throw exportError("AUDIO_TIMING_FAILED", "Could not rebase source audio timing.")
    }
    return output
  }

  private func orientedImage(
    pixelBuffer: CVPixelBuffer,
    preferredTransform: CGAffineTransform
  ) throws -> CGImage {
    let source = CIImage(cvPixelBuffer: pixelBuffer)
    let transformed = source.transformed(by: preferredTransform)
    let extent = transformed.extent.standardized
    let normalized = transformed.transformed(
      by: CGAffineTransform(translationX: -extent.origin.x, y: -extent.origin.y)
    )
    let normalizedExtent = normalized.extent.integral
    guard let image = ciContext.createCGImage(normalized, from: normalizedExtent) else {
      throw exportError("FRAME_CONVERSION_FAILED", "Could not create an oriented iOS export frame.")
    }
    return image
  }

  private func transformedSize(_ size: CGSize, transform: CGAffineTransform) -> CGSize {
    let rect = CGRect(origin: .zero, size: size).applying(transform).standardized
    return CGSize(width: abs(rect.width), height: abs(rect.height))
  }

  private func makePixelBuffer(
    adaptor: AVAssetWriterInputPixelBufferAdaptor,
    image: CGImage,
    width: Int,
    height: Int
  ) throws -> CVPixelBuffer {
    guard let pool = adaptor.pixelBufferPool else {
      throw exportError("ENCODER_UNAVAILABLE", "AVAssetWriter pixel buffer pool is unavailable.")
    }
    var optional: CVPixelBuffer?
    let result = CVPixelBufferPoolCreatePixelBuffer(nil, pool, &optional)
    guard result == kCVReturnSuccess, let pixelBuffer = optional else {
      throw exportError("ENCODER_UNAVAILABLE", "Could not allocate an iOS writer pixel buffer.")
    }
    CVPixelBufferLockBaseAddress(pixelBuffer, [])
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
    guard let base = CVPixelBufferGetBaseAddress(pixelBuffer),
          let context = CGContext(
            data: base,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue
              | CGImageAlphaInfo.premultipliedFirst.rawValue
          ) else {
      throw exportError("FRAME_CONVERSION_FAILED", "Could not draw into the iOS writer pixel buffer.")
    }
    // The renderer's RGBA CGImage and Metal masks both use top-row-first
    // pixels. Keep that convention when copying to AVAssetWriter; a second
    // Core Graphics Y flip would invert face privacy relative to the movie.
    context.interpolationQuality = .high
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    return pixelBuffer
  }

  private func waitUntilReady(
    _ input: AVAssetWriterInput,
    cancellation: IOSExportCancellationFlag,
    writer: AVAssetWriter? = nil
  ) throws {
    let deadline = CFAbsoluteTimeGetCurrent() + 60.0
    while !input.isReadyForMoreMediaData {
      if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }
      if let writer, writer.status == .failed {
        throw writerError(writer, fallback: "iOS writer failed while waiting for media input.")
      }
      if CFAbsoluteTimeGetCurrent() > deadline {
        throw exportError("EXPORT_FAILED", "iOS writer input remained blocked for over 60 seconds.")
      }
      Thread.sleep(forTimeInterval: 0.002)
    }
  }

  private func emitProgress(
    jobId: String,
    currentFrame: Int64,
    totalFrames: Int64,
    mediaProgress: Double? = nil,
    startedAt: CFAbsoluteTime,
    onStatus: (JobStatusDto) -> Void
  ) {
    let elapsed = max(0.001, CFAbsoluteTimeGetCurrent() - startedAt)
    onStatus(JobStatusDto(
      jobId: jobId,
      state: "exporting",
      currentFrame: currentFrame,
      totalFrames: totalFrames,
      fps: Double(currentFrame) / elapsed,
      progress: mediaProgress ?? min(1, Double(currentFrame) / Double(max(1, totalFrames))),
      outputUri: nil,
      currentPreviewPath: nil,
      errorCode: nil,
      errorMessage: nil
    ))
  }

  private func finish(writer: AVAssetWriter) async throws {
    await withCheckedContinuation { continuation in
      writer.finishWriting {
        continuation.resume()
      }
    }
    if writer.status == .failed {
      throw writerError(writer, fallback: "iOS AVAssetWriter finalization failed.")
    }
  }

  private func atomicFinalize(temp: URL, final: URL) throws {
    if fileManager.fileExists(atPath: final.path) {
      _ = try fileManager.replaceItemAt(final, withItemAt: temp)
    } else {
      try fileManager.moveItem(at: temp, to: final)
    }
  }

  private func readerError(_ reader: AVAssetReader, fallback: String) -> PigeonError {
    exportError(
      "DECODE_FAILED",
      reader.error.map { "\(fallback) \($0)" } ?? fallback
    )
  }

  private func writerError(_ writer: AVAssetWriter, fallback: String) -> PigeonError {
    exportError(
      "EXPORT_FAILED",
      writer.error.map { "\(fallback) \($0)" } ?? fallback
    )
  }

  private func exportError(_ code: String, _ message: String) -> PigeonError {
    PigeonError(code: code, message: message, details: nil)
  }
}
