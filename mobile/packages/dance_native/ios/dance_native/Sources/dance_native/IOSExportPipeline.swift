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
    onStatus: @escaping (JobStatusDto) -> Void
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
    let targetFps = 30.0
    let totalFrames = max(1, Int64(floor(trimmedDurationSeconds * targetFps + 0.0001)))
    let bitrate = max(2_000_000, min(20_000_000, Int(request.videoBitrate)))
    let output = try outputURLs(request: request)
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
          AVVideoExpectedSourceFrameRateKey: 30,
          AVVideoMaxKeyFrameIntervalKey: 60,
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

    do {
      let metadata = try analysisCache.loadMetadata(cacheId: request.analysisCacheId)
      let runner = inferenceProvider == nil ? IOSYoloRunner() : nil
      let renderer = try IOSMetalPreviewRenderer()
      let fullBodyIds = Set(request.selectedPersonIds.map { Int($0) })
      let faceOnlyIds = Set((request.faceOnlyPersonIds ?? []).map { Int($0) })
        .subtracting(fullBodyIds)
      let identityFrameWidth = max(1, Int(displaySize.width.rounded()))
      let identityFrameHeight = max(1, Int(displaySize.height.rounded()))
      let tracker = IOSTemporalIdentityTracker(
        metadata: metadata,
        fullBodyIds: fullBodyIds,
        faceOnlyIds: faceOnlyIds,
        frameWidth: identityFrameWidth,
        frameHeight: identityFrameHeight
      )
      // Match Android's production boundary: identity-independent privacy-class
      // continuity may drive only the FULL_BODY-only compositor. Mixed/FACE_ONLY
      // composition stays rooted exclusively in exact temporal track IDs.
      let privacyClassTracker = !fullBodyIds.isEmpty && faceOnlyIds.isEmpty
        ? IOSPrivacyClassTemporalTracker()
        : nil
      var privacyClassHardRootsSent = false
      let facePrivacyResolver: IOSFacePrivacyTemporalResolver?
      if faceOnlyIds.isEmpty {
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

      let startedAt = CFAbsoluteTimeGetCurrent()
      var outputFrameIndex: Int64 = 0
      var lastPixelBuffer: CVPixelBuffer?
      while let sample = videoReader.output.copyNextSampleBuffer() {
        if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }
        let sourcePTS = CMSampleBufferGetPresentationTimeStamp(sample)
        let relative = CMTimeSubtract(sourcePTS, trimStart)
        let relativeSeconds = max(0, CMTimeGetSeconds(relative))
        let dueIndex = min(
          totalFrames - 1,
          Int64(floor(relativeSeconds * targetFps + 0.0001))
        )
        guard dueIndex >= outputFrameIndex else { continue }
        guard let sourcePixelBuffer = CMSampleBufferGetImageBuffer(sample) else { continue }
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
          inference = try runner.run(image: frame, preferredBackend: .xnnpack)
        }
        let timestampUs = Int64((CMTimeGetSeconds(sourcePTS) * 1_000_000.0).rounded())
        let tracked = try tracker.update(
          detections: inference.detections,
          preprocess: inference.preprocess,
          timestampUs: timestampUs
        )
        var freshFullBodyPrivacyEvidence: [IOSFreshPrivacyClassEvidence] = []
        if let privacyClassTracker {
          var hardRoots: [Int: IOSPrivacySelectionClass] = [:]
          if !privacyClassHardRootsSent, !inference.detections.isEmpty {
            let rootPersons = IOSPreviewIdentityMatcher.assign(
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
        let faceRegions = facePrivacyResolver?.resolve(
          image: frame,
          persons: tracked,
          faceOnlyIds: faceOnlyIds,
          preprocess: inference.preprocess,
          freshPrivacyClassEvidence: tracker.facePrivacyClassEvidence(),
          timestampUs: timestampUs
        ) ?? [:]
        let rendered = try renderer.render(
          source: frame,
          persons: tracked,
          preprocess: inference.preprocess,
          fullBodyIds: fullBodyIds,
          faceOnlyIds: faceOnlyIds,
          effects: request.effects,
          faceRegions: faceRegions,
          freshFullBodyPrivacyEvidence: freshFullBodyPrivacyEvidence,
          preferFreshFullBodyClassPrimary: privacyClassTracker != nil
            && !freshFullBodyPrivacyEvidence.isEmpty,
          outputWidth: target.width,
          outputHeight: target.height
        )
        let outputPixelBuffer = try makePixelBuffer(
          adaptor: adaptor,
          image: rendered,
          width: target.width,
          height: target.height
        )
        lastPixelBuffer = outputPixelBuffer

        while outputFrameIndex <= dueIndex && outputFrameIndex < totalFrames {
          try waitUntilReady(videoInput, cancellation: cancellation)
          let presentation = CMTime(value: outputFrameIndex, timescale: 30)
          guard adaptor.append(outputPixelBuffer, withPresentationTime: presentation) else {
            throw writerError(writer, fallback: "Failed to append an iOS export video frame.")
          }
          outputFrameIndex += 1
          emitProgress(
            jobId: jobId,
            currentFrame: outputFrameIndex,
            totalFrames: totalFrames,
            startedAt: startedAt,
            onStatus: onStatus
          )
        }
      }
      if videoReader.reader.status == .failed {
        throw readerError(videoReader.reader, fallback: "iOS video decoding failed.")
      }
      guard let lastPixelBuffer else {
        throw exportError("DECODE_FRAME_FAILED", "No video frames were decoded in the trim range.")
      }
      while outputFrameIndex < totalFrames {
        if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }
        try waitUntilReady(videoInput, cancellation: cancellation)
        let presentation = CMTime(value: outputFrameIndex, timescale: 30)
        guard adaptor.append(lastPixelBuffer, withPresentationTime: presentation) else {
          throw writerError(writer, fallback: "Failed to pad the final iOS export frame.")
        }
        outputFrameIndex += 1
        emitProgress(
          jobId: jobId,
          currentFrame: outputFrameIndex,
          totalFrames: totalFrames,
          startedAt: startedAt,
          onStatus: onStatus
        )
      }
      videoInput.markAsFinished()

      if let audioTrack, let audioInput {
        try appendAudio(
          asset: asset,
          track: audioTrack,
          input: audioInput,
          writer: writer,
          trimStart: trimStart,
          trimmedDuration: trimmedDuration,
          cancellation: cancellation
        )
        audioInput.markAsFinished()
      }

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
    guard request.processingProfile.lowercased() != "sam2" else {
      throw exportError(
        "PLATFORM_NOT_SUPPORTED",
        "iOS Phase 4 export currently supports the stable YOLO profile only."
      )
    }
    // Do not silently downgrade Android-only transform semantics in the first
    // iOS export closure. Unsupported effects are rejected explicitly.
    if request.follow.enabled || request.effects.skinWhiten > 0 || request.effects.legStretchEnabled {
      throw exportError(
        "PLATFORM_NOT_SUPPORTED",
        "iOS Phase 4 does not yet support follow, skin whitening, or leg stretch during export."
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
      if displaySize.height > displaySize.width {
        width = 1080
        height = 1920
      } else {
        width = 1920
        height = 1080
      }
    }
    let longest = max(width, height)
    if longest > 1920 {
      let scale = 1920.0 / Double(longest)
      width = max(2, Int((Double(width) * scale).rounded()))
      height = max(2, Int((Double(height) * scale).rounded()))
    }
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

  private func appendAudio(
    asset: AVAsset,
    track: AVAssetTrack,
    input: AVAssetWriterInput,
    writer: AVAssetWriter,
    trimStart: CMTime,
    trimmedDuration: CMTime,
    cancellation: IOSExportCancellationFlag
  ) throws {
    let reader = try AVAssetReader(asset: asset)
    reader.timeRange = CMTimeRange(start: trimStart, duration: trimmedDuration)
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
    guard reader.startReading() else {
      throw readerError(reader, fallback: "Could not start iOS audio decoding.")
    }
    while let sample = output.copyNextSampleBuffer() {
      if cancellation.isCancelled {
        reader.cancelReading()
        throw IOSExportPipelineError.cancelled
      }
      try waitUntilReady(input, cancellation: cancellation)
      let rebased = try rebasedSampleBuffer(sample, subtracting: trimStart)
      guard input.append(rebased) else {
        reader.cancelReading()
        throw writerError(writer, fallback: "Failed to append iOS export audio.")
      }
    }
    if reader.status == .failed {
      throw readerError(reader, fallback: "iOS audio decoding failed.")
    }
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
    context.translateBy(x: 0, y: CGFloat(height))
    context.scaleBy(x: 1, y: -1)
    context.interpolationQuality = .high
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    return pixelBuffer
  }

  private func waitUntilReady(
    _ input: AVAssetWriterInput,
    cancellation: IOSExportCancellationFlag
  ) throws {
    while !input.isReadyForMoreMediaData {
      if cancellation.isCancelled { throw IOSExportPipelineError.cancelled }
      Thread.sleep(forTimeInterval: 0.002)
    }
  }

  private func emitProgress(
    jobId: String,
    currentFrame: Int64,
    totalFrames: Int64,
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
      progress: min(1, Double(currentFrame) / Double(max(1, totalFrames))),
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
