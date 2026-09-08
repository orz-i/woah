import AVFoundation
import AVFAudio
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ImageIO

enum IOSExportPhase4Smoke {
  private static let sourceWidth = 320
  private static let sourceHeight = 180
  private static let sourceFps: Int32 = 30
  private static let sourceDurationSeconds = 1.0
  private static let trimStartMs: Int64 = 200
  private static let trimEndMs: Int64 = 800
  private static let expectedOutputFrames: Int64 = 18

  static func run() async throws -> [String: Any] {
    let fileManager = FileManager.default
    let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    let directory = root.appendingPathComponent("phase4_export_smoke", isDirectory: true)
    try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    let nonce = UUID().uuidString.lowercased()
    let sourceURL = directory.appendingPathComponent("source_\(nonce).mp4")
    let outputURL = directory.appendingPathComponent("output_\(nonce).mp4")
    let cancelURL = directory.appendingPathComponent("cancel_\(nonce).mp4")
    let toneURL = directory.appendingPathComponent("tone_\(nonce).caf")
    defer {
      try? fileManager.removeItem(at: sourceURL)
      try? fileManager.removeItem(at: outputURL)
      try? fileManager.removeItem(at: cancelURL)
      try? fileManager.removeItem(at: toneURL)
    }

    try await createSourceFixture(videoURL: sourceURL, toneURL: toneURL)
    let sourceInfo = try await inspectAsset(sourceURL)
    guard sourceInfo.hasAudio else {
      throw smokeError("EXPORT_SMOKE_SOURCE_AUDIO_MISSING", "Generated Phase 4 MP4 fixture has no audio track.")
    }
    guard sourceInfo.codec == kCMVideoCodecType_H264 else {
      throw smokeError("EXPORT_SMOKE_SOURCE_CODEC_MISMATCH", "Generated Phase 4 fixture is not H.264.")
    }

    let cache = IOSAnalysisCache()
    let cacheId = "phase4_smoke_\(nonce)"
    defer { try? cache.clearAnalysisCache(cacheId: cacheId) }
    try cache.saveVideoUri(cacheId: cacheId, videoUri: sourceURL.absoluteString)
    try cache.saveMetadata(
      cacheId: cacheId,
      metadata: IOSAnalysisMetadata(
        schemaVersion: 1,
        sourceUri: sourceURL.absoluteString,
        persons: [
          IOSCachedPerson(
            id: 0,
            bbox: IOSCachedBBox(left: 0.25, top: 0.12, right: 0.75, bottom: 0.90),
            confidence: 0.99
          ),
        ]
      )
    )

    let inferenceState = InferenceState()
    let provider: IOSExportPipeline.InferenceProvider = { image in
      try inferenceState.infer(image: image)
    }
    let statusRecorder = StatusRecorder()
    let coordinator = IOSExportCoordinator(
      analysisCache: cache,
      inferenceProvider: provider,
      observer: { status in statusRecorder.append(status) }
    )
    let request = exportRequest(
      sourceURL: sourceURL,
      cacheId: cacheId,
      outputURL: outputURL
    )
    let jobId = try coordinator.start(request: request)

    var sawIntermediateProgress = false
    var finalStayedHiddenDuringProgress = false
    let completed = try await waitForTerminal(
      coordinator: coordinator,
      jobId: jobId,
      timeoutSeconds: 180,
      onPoll: { status in
        if status.state == "exporting", status.progress > 0, status.progress < 1 {
          sawIntermediateProgress = true
          if !fileManager.fileExists(atPath: outputURL.path) {
            finalStayedHiddenDuringProgress = true
          }
        }
      }
    )
    guard completed.state == "completed" else {
      throw smokeError(
        "EXPORT_SMOKE_JOB_FAILED",
        "Phase 4 export job ended as \(completed.state): \(completed.errorCode ?? "") \(completed.errorMessage ?? "")"
      )
    }
    guard sawIntermediateProgress else {
      throw smokeError("EXPORT_SMOKE_PROGRESS_MISSING", "Phase 4 export emitted no intermediate progress status.")
    }
    guard finalStayedHiddenDuringProgress else {
      throw smokeError(
        "EXPORT_SMOKE_ATOMIC_OUTPUT_FAILED",
        "Final export path became visible before writer finalization."
      )
    }
    guard fileManager.fileExists(atPath: outputURL.path) else {
      throw smokeError("EXPORT_SMOKE_OUTPUT_MISSING", "Completed Phase 4 export file is missing.")
    }
    guard completed.outputUri == outputURL.absoluteString else {
      throw smokeError("EXPORT_SMOKE_OUTPUT_URI_MISMATCH", "Completed job returned an unexpected output URI.")
    }

    let outputInfo = try await inspectAsset(outputURL)
    guard outputInfo.codec == kCMVideoCodecType_H264 else {
      throw smokeError("EXPORT_SMOKE_CODEC_MISMATCH", "Phase 4 output codec is not H.264.")
    }
    guard outputInfo.width == 1920, outputInfo.height == 1080 else {
      throw smokeError(
        "EXPORT_SMOKE_DIMENSIONS_MISMATCH",
        "Phase 4 output dimensions are \(outputInfo.width)x\(outputInfo.height), expected 1920x1080."
      )
    }
    guard outputInfo.hasAudio else {
      throw smokeError("EXPORT_SMOKE_AUDIO_MISSING", "Phase 4 output did not preserve an audio track.")
    }
    guard outputInfo.audioDurationSeconds >= 0.50,
          outputInfo.audioDurationSeconds <= 0.68 else {
      throw smokeError(
        "EXPORT_SMOKE_AUDIO_TRIM_MISMATCH",
        "Phase 4 output audio duration \(outputInfo.audioDurationSeconds) does not match the trimmed media range."
      )
    }
    guard abs(outputInfo.durationSeconds - 0.60) <= 0.08 else {
      throw smokeError(
        "EXPORT_SMOKE_TRIM_MISMATCH",
        "Phase 4 output duration \(outputInfo.durationSeconds) does not match the 600 ms trim range."
      )
    }
    guard outputInfo.videoSampleCount == expectedOutputFrames else {
      throw smokeError(
        "EXPORT_SMOKE_FRAME_COUNT_MISMATCH",
        "Phase 4 output has \(outputInfo.videoSampleCount) video samples, expected \(expectedOutputFrames)."
      )
    }
    let measuredFps = Double(outputInfo.videoSampleCount) / outputInfo.durationSeconds
    guard measuredFps >= 28.5, measuredFps <= 31.5 else {
      throw smokeError(
        "EXPORT_SMOKE_FPS_MISMATCH",
        "Phase 4 measured output FPS \(measuredFps) is outside the 30fps gate."
      )
    }

    let privacyPixel = try await readOutputCenterPixel(outputURL)
    guard privacyPixel.r >= 235,
          privacyPixel.g <= 35,
          privacyPixel.b <= 35,
          privacyPixel.a >= 235 else {
      throw smokeError(
        "EXPORT_SMOKE_PRIVACY_PIXEL_MISMATCH",
        "Phase 4 output center is not the expected opaque red privacy pixel: \(privacyPixel)."
      )
    }
    guard inferenceState.missingObservationCount >= 1 else {
      throw smokeError(
        "EXPORT_SMOKE_TEMPORAL_FALLBACK_NOT_EXERCISED",
        "The real-video gate did not exercise a missing-observation privacy fallback frame."
      )
    }

    while coordinator.hasActiveRuntime(jobId: jobId) {
      try await Task.sleep(nanoseconds: 10_000_000)
    }
    let cancelJobId = try coordinator.start(request: exportRequest(
      sourceURL: sourceURL,
      cacheId: cacheId,
      outputURL: cancelURL
    ))
    coordinator.cancel(jobId: cancelJobId)
    let cancelled = try await waitForTerminal(
      coordinator: coordinator,
      jobId: cancelJobId,
      timeoutSeconds: 30,
      onPoll: { _ in }
    )
    guard cancelled.state == "cancelled" else {
      throw smokeError("EXPORT_SMOKE_CANCEL_STATE_MISMATCH", "cancelJob did not produce a cancelled terminal state.")
    }
    let cancelCleanupDeadline = Date().addingTimeInterval(30)
    while coordinator.hasActiveRuntime(jobId: cancelJobId), Date() < cancelCleanupDeadline {
      try await Task.sleep(nanoseconds: 20_000_000)
    }
    guard !coordinator.hasActiveRuntime(jobId: cancelJobId) else {
      throw smokeError("EXPORT_SMOKE_CANCEL_TIMEOUT", "Cancelled export runtime did not unwind in time.")
    }
    guard !fileManager.fileExists(atPath: cancelURL.path) else {
      throw smokeError("EXPORT_SMOKE_CANCEL_OUTPUT_LEAK", "Cancelled export left a final output file.")
    }
    let partialPrefix = ".\(cancelURL.deletingPathExtension().lastPathComponent)."
    let leakedPartial = (try? fileManager.contentsOfDirectory(
      at: directory,
      includingPropertiesForKeys: nil
    ))?.contains { item in
      item.lastPathComponent.hasPrefix(partialPrefix)
        && item.lastPathComponent.hasSuffix(".partial.mp4")
    } ?? false
    guard !leakedPartial else {
      throw smokeError("EXPORT_SMOKE_CANCEL_PARTIAL_LEAK", "Cancelled export left a partial MP4 file.")
    }

    let statuses = statusRecorder.snapshot()
    return [
      "status": "pass",
      "job_id": jobId,
      "source_codec": fourCC(sourceInfo.codec),
      "output_codec": fourCC(outputInfo.codec),
      "output_width": outputInfo.width,
      "output_height": outputInfo.height,
      "output_duration_seconds": outputInfo.durationSeconds,
      "output_video_samples": outputInfo.videoSampleCount,
      "output_measured_fps": measuredFps,
      "output_has_audio": outputInfo.hasAudio,
      "output_audio_duration_seconds": outputInfo.audioDurationSeconds,
      "progress_events": statuses.filter { $0.jobId == jobId }.count,
      "atomic_output": finalStayedHiddenDuringProgress,
      "temporal_missing_observations": inferenceState.missingObservationCount,
      "privacy_pixel_rgba": [privacyPixel.r, privacyPixel.g, privacyPixel.b, privacyPixel.a],
      "cancel_state": cancelled.state,
      "cancel_partial_clean": !leakedPartial,
    ]
  }

  private static func exportRequest(
    sourceURL: URL,
    cacheId: String,
    outputURL: URL
  ) -> ExportRequestDto {
    ExportRequestDto(
      sourceUri: sourceURL.absoluteString,
      analysisCacheId: cacheId,
      outputFilePath: outputURL.path,
      selectedPersonIds: [0],
      effects: EffectConfigDto(
        fillMode: "solid",
        fillColorArgb: Int64(0xFFFF0000),
        borderColorArgb: 0,
        opacity: 1,
        borderWidth: 0,
        blurStrength: 1,
        faceStickerEnabled: false,
        stickerAssetId: nil,
        stickerScale: 1,
        skinWhiten: 0,
        legStretchEnabled: false,
        legStretch: 0,
        legZoneTop: 0,
        legZoneBottom: 1
      ),
      follow: FollowConfigDto(
        enabled: false,
        targetPersonId: nil,
        zoom: 1,
        smoothFactor: 0.15
      ),
      targetWidth: 1920,
      targetHeight: 1080,
      targetFps: 30,
      videoBitrate: 8_000_000,
      processingProfile: "quality",
      enableLivePreview: false,
      faceOnlyPersonIds: [],
      trimStartMs: trimStartMs,
      trimEndMs: trimEndMs
    )
  }

  private static func waitForTerminal(
    coordinator: IOSExportCoordinator,
    jobId: String,
    timeoutSeconds: TimeInterval,
    onPoll: (JobStatusDto) -> Void
  ) async throws -> JobStatusDto {
    let deadline = Date().addingTimeInterval(timeoutSeconds)
    while Date() < deadline {
      if let status = coordinator.status(jobId: jobId) {
        onPoll(status)
        if status.state == "completed" || status.state == "failed" || status.state == "cancelled" {
          return status
        }
      }
      try await Task.sleep(nanoseconds: 20_000_000)
    }
    throw smokeError("EXPORT_SMOKE_TIMEOUT", "Timed out waiting for Phase 4 export job \(jobId).")
  }

  private final class StatusRecorder {
    private let lock = NSLock()
    private var values: [JobStatusDto] = []

    func append(_ value: JobStatusDto) {
      lock.lock()
      values.append(value)
      lock.unlock()
    }

    func snapshot() -> [JobStatusDto] {
      lock.lock()
      defer { lock.unlock() }
      return values
    }
  }

  private final class InferenceState {
    private let lock = NSLock()
    private var callCount = 0
    private(set) var missingObservationCount = 0

    func infer(image: CGImage) throws -> IOSYoloInferenceResult {
      lock.lock()
      callCount += 1
      let call = callCount
      lock.unlock()
      let preprocess = try IOSYoloPreprocessor.process(image: image)
      let detections: [IOSYoloDetection]
      // One deliberate detector gap exercises the temporal selected-identity
      // fallback on a real decoded video frame without making identity ambiguous.
      if call == 6 {
        lock.lock()
        missingObservationCount += 1
        lock.unlock()
        detections = []
      } else {
        let shift = Float32((call % 5) - 2) * 0.003 * Float32(image.width)
        let x1 = Float32(image.width) * 0.25 + shift
        let y1 = Float32(image.height) * 0.12
        let x2 = Float32(image.width) * 0.75 + shift
        let y2 = Float32(image.height) * 0.90
        detections = [IOSYoloDetection(
          x1: x1,
          y1: y1,
          x2: x2,
          y2: y2,
          confidence: 0.99,
          mask: detectionMask(
            x1: x1,
            y1: y1,
            x2: x2,
            y2: y2,
            preprocess: preprocess
          )
        )]
      }
      return IOSYoloInferenceResult(
        detections: detections,
        runtime: IOSYoloRuntimeInfo(
          effectiveBackend: .xnnpack,
          initializationMs: 0,
          inferenceMs: 0,
          fallbackReasons: ["phase4_simulator_deterministic_fixture"],
          inputShape: [1, 3, 640, 640],
          outputShapes: [[1, 116, 8400], [1, 32, 160, 160]]
        ),
        preprocess: preprocess
      )
    }
  }

  private static func detectionMask(
    x1: Float32,
    y1: Float32,
    x2: Float32,
    y2: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> [UInt8] {
    let proto = IOSYoloPostprocessor.protoSize
    var mask = [UInt8](repeating: 0, count: proto * proto)
    let factor = Float32(proto) / Float32(preprocess.inputSize)
    let px1 = max(0, min(proto - 1, Int(floor(Double((x1 * preprocess.scale + preprocess.padLeft) * factor)))))
    let py1 = max(0, min(proto - 1, Int(floor(Double((y1 * preprocess.scale + preprocess.padTop) * factor)))))
    let px2 = max(px1 + 1, min(proto, Int(ceil(Double((x2 * preprocess.scale + preprocess.padLeft) * factor)))))
    let py2 = max(py1 + 1, min(proto, Int(ceil(Double((y2 * preprocess.scale + preprocess.padTop) * factor)))))
    for y in py1..<py2 {
      let row = y * proto
      for x in px1..<px2 { mask[row + x] = 255 }
    }
    return mask
  }

  private static func createSourceFixture(
    videoURL: URL,
    toneURL: URL
  ) async throws {
    let fileManager = FileManager.default
    try? fileManager.removeItem(at: videoURL)
    try? fileManager.removeItem(at: toneURL)
    try createToneFile(url: toneURL)

    let writer = try AVAssetWriter(outputURL: videoURL, fileType: .mp4)
    let videoInput = AVAssetWriterInput(
      mediaType: .video,
      outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.h264,
        AVVideoWidthKey: sourceWidth,
        AVVideoHeightKey: sourceHeight,
        AVVideoCompressionPropertiesKey: [
          AVVideoAverageBitRateKey: 700_000,
          AVVideoExpectedSourceFrameRateKey: 30,
          AVVideoMaxKeyFrameIntervalKey: 30,
        ],
      ]
    )
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: videoInput,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: sourceWidth,
        kCVPixelBufferHeightKey as String: sourceHeight,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      ]
    )
    guard writer.canAdd(videoInput) else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_VIDEO_INPUT_FAILED", "Could not add fixture H.264 writer input.")
    }
    writer.add(videoInput)

    let audioInput = AVAssetWriterInput(
      mediaType: .audio,
      outputSettings: [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 44_100,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 96_000,
      ]
    )
    guard writer.canAdd(audioInput) else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_AUDIO_INPUT_FAILED", "Could not add fixture AAC writer input.")
    }
    writer.add(audioInput)
    guard writer.startWriting() else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_WRITER_FAILED", writer.error.map(String.init(describing:)) ?? "fixture writer failed")
    }
    writer.startSession(atSourceTime: .zero)

    let image = try fixtureImage()
    let videoFrames = Int(sourceDurationSeconds * Double(sourceFps))
    for frame in 0..<videoFrames {
      while !videoInput.isReadyForMoreMediaData {
        Thread.sleep(forTimeInterval: 0.001)
      }
      let pixelBuffer = try fixturePixelBuffer(adaptor: adaptor, image: image)
      let pts = CMTime(value: Int64(frame), timescale: sourceFps)
      guard adaptor.append(pixelBuffer, withPresentationTime: pts) else {
        writer.cancelWriting()
        throw smokeError("EXPORT_SMOKE_FIXTURE_VIDEO_APPEND_FAILED", "Could not append fixture video frame \(frame).")
      }
    }
    videoInput.markAsFinished()

    let toneAsset = AVURLAsset(url: toneURL)
    guard let toneTrack = try await toneAsset.loadTracks(withMediaType: .audio).first else {
      writer.cancelWriting()
      throw smokeError("EXPORT_SMOKE_FIXTURE_TONE_MISSING", "Generated tone file has no audio track.")
    }
    let reader = try AVAssetReader(asset: toneAsset)
    let audioOutput = AVAssetReaderTrackOutput(
      track: toneTrack,
      outputSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVLinearPCMBitDepthKey: 32,
        AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsNonInterleaved: false,
      ]
    )
    guard reader.canAdd(audioOutput) else {
      writer.cancelWriting()
      throw smokeError("EXPORT_SMOKE_FIXTURE_AUDIO_READER_FAILED", "Could not add fixture tone reader output.")
    }
    reader.add(audioOutput)
    guard reader.startReading() else {
      writer.cancelWriting()
      throw smokeError("EXPORT_SMOKE_FIXTURE_AUDIO_READER_FAILED", "Could not start fixture tone reader.")
    }
    while let sample = audioOutput.copyNextSampleBuffer() {
      while !audioInput.isReadyForMoreMediaData {
        Thread.sleep(forTimeInterval: 0.001)
      }
      guard audioInput.append(sample) else {
        reader.cancelReading()
        writer.cancelWriting()
        throw smokeError("EXPORT_SMOKE_FIXTURE_AUDIO_APPEND_FAILED", "Could not append fixture audio sample.")
      }
    }
    audioInput.markAsFinished()
    await finishWriter(writer)
    guard writer.status == .completed else {
      throw smokeError(
        "EXPORT_SMOKE_FIXTURE_WRITER_FAILED",
        writer.error.map(String.init(describing:)) ?? "fixture writer did not complete"
      )
    }
  }

  private static func createToneFile(url: URL) throws {
    guard let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1),
          let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(44_100 * sourceDurationSeconds)
          ) else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_TONE_FAILED", "Could not allocate fixture audio format/buffer.")
    }
    buffer.frameLength = buffer.frameCapacity
    guard let channel = buffer.floatChannelData?[0] else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_TONE_FAILED", "Fixture audio buffer has no writable channel.")
    }
    for frame in 0..<Int(buffer.frameLength) {
      let t = Double(frame) / 44_100.0
      channel[frame] = Float(sin(2.0 * Double.pi * 440.0 * t) * 0.10)
    }
    let file = try AVAudioFile(forWriting: url, settings: format.settings)
    try file.write(from: buffer)
  }

  private static func fixtureImage() throws -> CGImage {
    if let url = IOSModelResources.yoloPhase1FixtureURL(),
       let source = CGImageSourceCreateWithURL(url as CFURL, nil),
       let image = CGImageSourceCreateImageAtIndex(source, 0, nil) {
      return image
    }
    var bytes = [UInt8](repeating: 0, count: sourceWidth * sourceHeight * 4)
    for y in 0..<sourceHeight {
      for x in 0..<sourceWidth {
        let offset = (y * sourceWidth + x) * 4
        bytes[offset] = UInt8((x * 255) / max(1, sourceWidth - 1))
        bytes[offset + 1] = UInt8((y * 255) / max(1, sourceHeight - 1))
        bytes[offset + 2] = 120
        bytes[offset + 3] = 255
      }
    }
    let data = Data(bytes) as CFData
    guard let provider = CGDataProvider(data: data),
          let image = CGImage(
            width: sourceWidth,
            height: sourceHeight,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: sourceWidth * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Big.union(
              CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
            ),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
          ) else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_IMAGE_FAILED", "Could not create fallback fixture image.")
    }
    return image
  }

  private static func fixturePixelBuffer(
    adaptor: AVAssetWriterInputPixelBufferAdaptor,
    image: CGImage
  ) throws -> CVPixelBuffer {
    guard let pool = adaptor.pixelBufferPool else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_POOL_FAILED", "Fixture writer pixel buffer pool is unavailable.")
    }
    var optional: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &optional) == kCVReturnSuccess,
          let pixelBuffer = optional else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_POOL_FAILED", "Could not allocate fixture pixel buffer.")
    }
    CVPixelBufferLockBaseAddress(pixelBuffer, [])
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
    guard let base = CVPixelBufferGetBaseAddress(pixelBuffer),
          let context = CGContext(
            data: base,
            width: sourceWidth,
            height: sourceHeight,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue
              | CGImageAlphaInfo.premultipliedFirst.rawValue
          ) else {
      throw smokeError("EXPORT_SMOKE_FIXTURE_FRAME_FAILED", "Could not create fixture pixel buffer context.")
    }
    context.setFillColor(red: 0.05, green: 0.10, blue: 0.20, alpha: 1)
    context.fill(CGRect(x: 0, y: 0, width: sourceWidth, height: sourceHeight))
    context.translateBy(x: 0, y: CGFloat(sourceHeight))
    context.scaleBy(x: 1, y: -1)
    context.interpolationQuality = .high
    let scale = max(
      CGFloat(sourceWidth) / CGFloat(image.width),
      CGFloat(sourceHeight) / CGFloat(image.height)
    )
    let drawWidth = CGFloat(image.width) * scale
    let drawHeight = CGFloat(image.height) * scale
    context.draw(
      image,
      in: CGRect(
        x: (CGFloat(sourceWidth) - drawWidth) * 0.5,
        y: (CGFloat(sourceHeight) - drawHeight) * 0.5,
        width: drawWidth,
        height: drawHeight
      )
    )
    return pixelBuffer
  }

  private struct AssetInfo {
    let codec: CMVideoCodecType
    let width: Int
    let height: Int
    let durationSeconds: Double
    let videoSampleCount: Int64
    let hasAudio: Bool
    let audioDurationSeconds: Double
  }

  private static func inspectAsset(_ url: URL) async throws -> AssetInfo {
    let asset = AVURLAsset(url: url)
    let duration = try await asset.load(.duration)
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw smokeError("EXPORT_SMOKE_INSPECT_VIDEO_MISSING", "Inspected MP4 has no video track.")
    }
    let size = try await track.load(.naturalSize)
    let descriptions = try await track.load(.formatDescriptions)
    let codec = descriptions.first.map(CMFormatDescriptionGetMediaSubType) ?? 0
    let audio = try await asset.loadTracks(withMediaType: .audio)
    let audioDurationSeconds: Double
    if let audioTrack = audio.first {
      let audioTimeRange = try await audioTrack.load(.timeRange)
      audioDurationSeconds = CMTimeGetSeconds(audioTimeRange.duration)
    } else {
      audioDurationSeconds = 0
    }
    let sampleCount = try countVideoSamples(asset: asset, track: track)
    return AssetInfo(
      codec: codec,
      width: Int(abs(size.width).rounded()),
      height: Int(abs(size.height).rounded()),
      durationSeconds: CMTimeGetSeconds(duration),
      videoSampleCount: sampleCount,
      hasAudio: !audio.isEmpty,
      audioDurationSeconds: audioDurationSeconds
    )
  }

  private static func countVideoSamples(
    asset: AVAsset,
    track: AVAssetTrack
  ) throws -> Int64 {
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
    guard reader.canAdd(output) else {
      throw smokeError("EXPORT_SMOKE_SAMPLE_COUNT_FAILED", "Could not inspect encoded video samples.")
    }
    reader.add(output)
    guard reader.startReading() else {
      throw smokeError("EXPORT_SMOKE_SAMPLE_COUNT_FAILED", "Could not start encoded video sample inspection.")
    }
    var count: Int64 = 0
    while output.copyNextSampleBuffer() != nil { count += 1 }
    if reader.status == .failed {
      throw smokeError("EXPORT_SMOKE_SAMPLE_COUNT_FAILED", "Encoded video sample inspection failed.")
    }
    return count
  }

  private static func readOutputCenterPixel(
    _ url: URL
  ) async throws -> (r: Int, g: Int, b: Int, a: Int) {
    let asset = AVURLAsset(url: url)
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero
    let generated = try await generator.image(at: CMTime(value: 300, timescale: 1000))
    let image = generated.image
    guard let center = image.cropping(to: CGRect(
      x: image.width / 2,
      y: image.height / 2,
      width: 1,
      height: 1
    )) else {
      throw smokeError("EXPORT_SMOKE_PRIVACY_READBACK_FAILED", "Could not crop output privacy pixel.")
    }
    var bytes = [UInt8](repeating: 0, count: 4)
    let rendered = bytes.withUnsafeMutableBytes { raw -> Bool in
      guard let base = raw.baseAddress,
            let context = CGContext(
              data: base,
              width: 1,
              height: 1,
              bitsPerComponent: 8,
              bytesPerRow: 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
        return false
      }
      context.draw(center, in: CGRect(x: 0, y: 0, width: 1, height: 1))
      return true
    }
    guard rendered else {
      throw smokeError("EXPORT_SMOKE_PRIVACY_READBACK_FAILED", "Could not read output privacy pixel.")
    }
    return (Int(bytes[0]), Int(bytes[1]), Int(bytes[2]), Int(bytes[3]))
  }

  private static func finishWriter(_ writer: AVAssetWriter) async {
    await withCheckedContinuation { continuation in
      writer.finishWriting { continuation.resume() }
    }
  }

  private static func fourCC(_ value: FourCharCode) -> String {
    let bytes: [UInt8] = [
      UInt8((value >> 24) & 0xff),
      UInt8((value >> 16) & 0xff),
      UInt8((value >> 8) & 0xff),
      UInt8(value & 0xff),
    ]
    return String(bytes: bytes, encoding: .ascii) ?? String(value)
  }

  private static func smokeError(_ code: String, _ message: String) -> PigeonError {
    PigeonError(code: code, message: message, details: nil)
  }
}
