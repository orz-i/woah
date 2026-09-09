import AVFoundation
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation

/// Release-readiness media regression smoke owned exclusively by Phase 7.
///
/// Earlier phase smokes remain frozen. This lane covers release-shaped media
/// behaviors that were previously represented only by static contracts:
/// video-only input, failure cleanup, preferred-transform orientation, and VFR
/// input rebasing into the fixed H.264/30fps export contract.
enum IOSReleasePhase7Smoke {
  private static let sourceWidth = 320
  private static let sourceHeight = 180
  private static let timescale: Int32 = 600
  private static let cfrFrameCount = 18

  private enum InjectedFailure: Error {
    case inference
  }

  private struct TimelineInfo {
    let frameCount: Int64
    let firstSeconds: Double
    let lastSeconds: Double
    let averageIntervalSeconds: Double
    let distinctIntervals: Int
  }

  private struct AssetInfo {
    let codec: CMVideoCodecType
    let width: Int
    let height: Int
    let displayWidth: Int
    let displayHeight: Int
    let durationSeconds: Double
    let hasAudio: Bool
    let nominalFrameRate: Double
    let timeline: TimelineInfo
  }

  static func run() async throws -> [String: Any] {
    let fileManager = FileManager.default
    let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? fileManager.temporaryDirectory
    let directory = root.appendingPathComponent("phase7_release_smoke", isDirectory: true)
    try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    let nonce = UUID().uuidString.lowercased()

    let landscapeSource = directory.appendingPathComponent("landscape_no_audio_\(nonce).mp4")
    let landscapeOutput = directory.appendingPathComponent("landscape_output_\(nonce).mp4")
    let portraitVfrSource = directory.appendingPathComponent("portrait_vfr_\(nonce).mp4")
    let portraitVfrOutput = directory.appendingPathComponent("portrait_vfr_output_\(nonce).mp4")
    let failureOutput = directory.appendingPathComponent("failure_output_\(nonce).mp4")
    defer {
      for url in [
        landscapeSource,
        landscapeOutput,
        portraitVfrSource,
        portraitVfrOutput,
        failureOutput
      ] {
        try? fileManager.removeItem(at: url)
      }
    }

    let cfrTimestamps = (0..<cfrFrameCount).map {
      CMTime(value: Int64($0 * 20), timescale: timescale)
    }
    try await createVideoOnlyFixture(
      url: landscapeSource,
      timestamps: cfrTimestamps,
      preferredTransform: .identity
    )
    let landscapeSourceInfo = try await inspectAsset(landscapeSource)
    guard !landscapeSourceInfo.hasAudio else {
      throw smokeError("PHASE7_NO_AUDIO_FIXTURE_HAS_AUDIO", "Video-only fixture unexpectedly contains audio.")
    }
    guard landscapeSourceInfo.displayWidth > landscapeSourceInfo.displayHeight else {
      throw smokeError("PHASE7_LANDSCAPE_FIXTURE_ORIENTATION", "Landscape fixture is not landscape after transform.")
    }

    let landscapeReport = try await runSuccessfulExport(
      sourceURL: landscapeSource,
      outputURL: landscapeOutput,
      cacheId: "phase7_landscape_\(nonce)",
      expectedWidth: 1920,
      expectedHeight: 1080,
      requireVfrSource: false
    )

    let vfrTimestamps = makeVfrTimestamps()
    let portraitTransform = CGAffineTransform(
      a: 0,
      b: 1,
      c: -1,
      d: 0,
      tx: CGFloat(sourceHeight),
      ty: 0
    )
    try await createVideoOnlyFixture(
      url: portraitVfrSource,
      timestamps: vfrTimestamps,
      preferredTransform: portraitTransform
    )
    let portraitSourceInfo = try await inspectAsset(portraitVfrSource)
    guard portraitSourceInfo.displayHeight > portraitSourceInfo.displayWidth else {
      throw smokeError(
        "PHASE7_PORTRAIT_TRANSFORM_FIXTURE",
        "Preferred-transform fixture is not portrait after applying the track transform."
      )
    }
    guard portraitSourceInfo.timeline.distinctIntervals >= 2 else {
      throw smokeError("PHASE7_VFR_FIXTURE_NOT_VFR", "VFR fixture did not contain multiple presentation intervals.")
    }

    let portraitVfrReport = try await runSuccessfulExport(
      sourceURL: portraitVfrSource,
      outputURL: portraitVfrOutput,
      cacheId: "phase7_portrait_vfr_\(nonce)",
      expectedWidth: 1080,
      expectedHeight: 1920,
      requireVfrSource: true
    )

    let failureReport = try await runFailureCleanup(
      sourceURL: landscapeSource,
      outputURL: failureOutput,
      cacheId: "phase7_failure_\(nonce)",
      directory: directory
    )

    return [
      "status": "pass",
      "no_audio_landscape": landscapeReport,
      "portrait_vfr": portraitVfrReport,
      "failure_cleanup": failureReport,
    ]
  }

  private static func runSuccessfulExport(
    sourceURL: URL,
    outputURL: URL,
    cacheId: String,
    expectedWidth: Int,
    expectedHeight: Int,
    requireVfrSource: Bool
  ) async throws -> [String: Any] {
    let cache = IOSAnalysisCache()
    defer { try? cache.clearAnalysisCache(cacheId: cacheId) }
    try seedCache(cache: cache, cacheId: cacheId, sourceURL: sourceURL)

    let sourceInfo = try await inspectAsset(sourceURL)
    if requireVfrSource, sourceInfo.timeline.distinctIntervals < 2 {
      throw smokeError("PHASE7_VFR_SOURCE_COLLAPSED", "Expected VFR source presentation intervals before export.")
    }

    let inference = InferenceState()
    let pipeline = IOSExportPipeline(
      analysisCache: cache,
      inferenceProvider: { image in try inference.infer(image: image) }
    )
    let output = try await pipeline.execute(
      jobId: "phase7_success_\(UUID().uuidString.lowercased())",
      request: exportRequest(sourceURL: sourceURL, cacheId: cacheId, outputURL: outputURL),
      cancellation: IOSExportCancellationFlag(),
      onStatus: { _ in }
    )
    guard output == outputURL, FileManager.default.fileExists(atPath: outputURL.path) else {
      throw smokeError("PHASE7_SUCCESS_OUTPUT_MISSING", "Phase 7 successful export did not commit the requested final path.")
    }

    let outputInfo = try await inspectAsset(outputURL)
    guard outputInfo.codec == kCMVideoCodecType_H264 else {
      throw smokeError("PHASE7_OUTPUT_CODEC", "Phase 7 output is not H.264.")
    }
    guard outputInfo.width == expectedWidth, outputInfo.height == expectedHeight else {
      throw smokeError(
        "PHASE7_OUTPUT_DIMENSIONS",
        "Phase 7 output is \(outputInfo.width)x\(outputInfo.height), expected \(expectedWidth)x\(expectedHeight)."
      )
    }
    guard !outputInfo.hasAudio else {
      throw smokeError("PHASE7_NO_AUDIO_OUTPUT_HAS_AUDIO", "Video-only source unexpectedly produced an audio track.")
    }
    let expectedFrames = max(1, Int64(floor(sourceInfo.durationSeconds * 30.0 + 0.0001)))
    guard outputInfo.timeline.frameCount == expectedFrames else {
      throw smokeError(
        "PHASE7_OUTPUT_FRAME_COUNT",
        "Phase 7 output has \(outputInfo.timeline.frameCount) frames, expected \(expectedFrames) from source duration."
      )
    }
    let measuredFps = outputInfo.timeline.averageIntervalSeconds > 0
      ? 1.0 / outputInfo.timeline.averageIntervalSeconds
      : 0
    guard measuredFps >= 28.5, measuredFps <= 31.5 else {
      throw smokeError("PHASE7_OUTPUT_MEASURED_FPS", "Phase 7 output measured FPS is \(measuredFps), expected 30fps.")
    }
    guard outputInfo.nominalFrameRate >= 29.0, outputInfo.nominalFrameRate <= 31.0 else {
      throw smokeError(
        "PHASE7_OUTPUT_NOMINAL_FPS",
        "Phase 7 output nominal FPS is \(outputInfo.nominalFrameRate), expected 30fps."
      )
    }
    return [
      "source_duration_seconds": sourceInfo.durationSeconds,
      "source_frame_count": sourceInfo.timeline.frameCount,
      "source_distinct_intervals": sourceInfo.timeline.distinctIntervals,
      "source_display_width": sourceInfo.displayWidth,
      "source_display_height": sourceInfo.displayHeight,
      "output_width": outputInfo.width,
      "output_height": outputInfo.height,
      "output_frame_count": outputInfo.timeline.frameCount,
      "output_measured_fps": measuredFps,
      "output_nominal_fps": outputInfo.nominalFrameRate,
      "output_has_audio": outputInfo.hasAudio,
    ]
  }

  private static func runFailureCleanup(
    sourceURL: URL,
    outputURL: URL,
    cacheId: String,
    directory: URL
  ) async throws -> [String: Any] {
    let cache = IOSAnalysisCache()
    defer { try? cache.clearAnalysisCache(cacheId: cacheId) }
    try seedCache(cache: cache, cacheId: cacheId, sourceURL: sourceURL)
    let pipeline = IOSExportPipeline(
      analysisCache: cache,
      inferenceProvider: { _ in throw InjectedFailure.inference }
    )
    do {
      _ = try await pipeline.execute(
        jobId: "phase7_failure_\(UUID().uuidString.lowercased())",
        request: exportRequest(sourceURL: sourceURL, cacheId: cacheId, outputURL: outputURL),
        cancellation: IOSExportCancellationFlag(),
        onStatus: { _ in }
      )
      throw smokeError("PHASE7_INJECTED_FAILURE_NOT_OBSERVED", "Injected inference failure unexpectedly completed.")
    } catch InjectedFailure.inference {
      // Expected: IOSExportPipeline must clean its partial file before the
      // injected error escapes this deterministic smoke.
    }

    guard !FileManager.default.fileExists(atPath: outputURL.path) else {
      throw smokeError("PHASE7_FAILURE_FINAL_LEAK", "Failed export left a final output file.")
    }
    let partialPrefix = ".\(outputURL.deletingPathExtension().lastPathComponent)."
    let leakedPartials = (try? FileManager.default.contentsOfDirectory(
      at: directory,
      includingPropertiesForKeys: nil
    ))?.filter {
      $0.lastPathComponent.hasPrefix(partialPrefix)
        && $0.lastPathComponent.hasSuffix(".partial.mp4")
    } ?? []
    guard leakedPartials.isEmpty else {
      throw smokeError(
        "PHASE7_FAILURE_PARTIAL_LEAK",
        "Failed export left partial MP4 files: \(leakedPartials.map(\.lastPathComponent))."
      )
    }
    return [
      "injected_failure": true,
      "final_clean": true,
      "partial_clean": true,
    ]
  }

  private static func seedCache(
    cache: IOSAnalysisCache,
    cacheId: String,
    sourceURL: URL
  ) throws {
    try cache.saveVideoUri(cacheId: cacheId, videoUri: sourceURL.absoluteString)
    try cache.saveMetadata(
      cacheId: cacheId,
      metadata: IOSAnalysisMetadata(
        schemaVersion: 1,
        sourceUri: sourceURL.absoluteString,
        persons: [
          IOSCachedPerson(
            id: 0,
            bbox: IOSCachedBBox(left: 0.20, top: 0.10, right: 0.80, bottom: 0.90),
            confidence: 0.99
          ),
        ]
      )
    )
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
      targetWidth: 0,
      targetHeight: 0,
      targetFps: 30,
      videoBitrate: 6_000_000,
      processingProfile: "quality",
      enableLivePreview: false,
      faceOnlyPersonIds: [],
      trimStartMs: 0,
      trimEndMs: nil
    )
  }

  private final class InferenceState {
    func infer(image: CGImage) throws -> IOSYoloInferenceResult {
      let preprocess = try IOSYoloPreprocessor.process(image: image)
      let x1 = Float32(image.width) * 0.20
      let y1 = Float32(image.height) * 0.10
      let x2 = Float32(image.width) * 0.80
      let y2 = Float32(image.height) * 0.90
      return IOSYoloInferenceResult(
        detections: [
          IOSYoloDetection(
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
          ),
        ],
        runtime: IOSYoloRuntimeInfo(
          effectiveBackend: .xnnpack,
          initializationMs: 0,
          inferenceMs: 0,
          fallbackReasons: ["phase7_release_deterministic_fixture"],
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

  private static func makeVfrTimestamps() -> [CMTime] {
    let deltas: [Int64] = [20, 32, 24, 36, 20, 28]
    var ticks: Int64 = 0
    var values: [CMTime] = [.zero]
    var index = 0
    while true {
      let next = ticks + deltas[index % deltas.count]
      if next >= 360 { break }
      ticks = next
      values.append(CMTime(value: ticks, timescale: timescale))
      index += 1
    }
    return values
  }

  private static func createVideoOnlyFixture(
    url: URL,
    timestamps: [CMTime],
    preferredTransform: CGAffineTransform
  ) async throws {
    guard !timestamps.isEmpty else {
      throw smokeError("PHASE7_FIXTURE_EMPTY", "Phase 7 fixture requires presentation timestamps.")
    }
    let fileManager = FileManager.default
    try? fileManager.removeItem(at: url)
    let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
    let input = AVAssetWriterInput(
      mediaType: .video,
      outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.h264,
        AVVideoWidthKey: sourceWidth,
        AVVideoHeightKey: sourceHeight,
        AVVideoCompressionPropertiesKey: [
          AVVideoAverageBitRateKey: 600_000,
          AVVideoExpectedSourceFrameRateKey: 30,
          AVVideoMaxKeyFrameIntervalKey: 30,
        ],
      ]
    )
    input.expectsMediaDataInRealTime = false
    input.transform = preferredTransform
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: input,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: sourceWidth,
        kCVPixelBufferHeightKey as String: sourceHeight,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      ]
    )
    guard writer.canAdd(input) else {
      throw smokeError("PHASE7_FIXTURE_INPUT", "Could not add Phase 7 fixture video input.")
    }
    writer.add(input)
    guard writer.startWriting() else {
      throw smokeError("PHASE7_FIXTURE_WRITER", writer.error.map(String.init(describing:)) ?? "fixture writer failed")
    }
    writer.startSession(atSourceTime: .zero)
    for (index, timestamp) in timestamps.enumerated() {
      while !input.isReadyForMoreMediaData {
        Thread.sleep(forTimeInterval: 0.001)
      }
      let pixelBuffer = try fixturePixelBuffer(adaptor: adaptor, frameIndex: index)
      guard adaptor.append(pixelBuffer, withPresentationTime: timestamp) else {
        writer.cancelWriting()
        throw smokeError("PHASE7_FIXTURE_APPEND", "Could not append Phase 7 fixture frame \(index).")
      }
    }
    input.markAsFinished()
    await finishWriter(writer)
    guard writer.status == .completed else {
      throw smokeError(
        "PHASE7_FIXTURE_WRITER",
        writer.error.map(String.init(describing:)) ?? "fixture writer did not complete"
      )
    }
  }

  private static func fixturePixelBuffer(
    adaptor: AVAssetWriterInputPixelBufferAdaptor,
    frameIndex: Int
  ) throws -> CVPixelBuffer {
    guard let pool = adaptor.pixelBufferPool else {
      throw smokeError("PHASE7_FIXTURE_POOL", "Phase 7 fixture pixel buffer pool is unavailable.")
    }
    var optional: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &optional) == kCVReturnSuccess,
          let pixelBuffer = optional else {
      throw smokeError("PHASE7_FIXTURE_POOL", "Could not allocate Phase 7 fixture pixel buffer.")
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
      throw smokeError("PHASE7_FIXTURE_CONTEXT", "Could not create Phase 7 fixture CGContext.")
    }
    let phase = CGFloat(frameIndex % 8) / 8.0
    context.setFillColor(red: 0.20 + phase * 0.30, green: 0.35, blue: 0.55, alpha: 1.0)
    context.fill(CGRect(x: 0, y: 0, width: sourceWidth, height: sourceHeight))
    context.setFillColor(red: 0.85, green: 0.70, blue: 0.20, alpha: 1.0)
    context.fill(CGRect(x: 64, y: 18, width: 192, height: 144))
    return pixelBuffer
  }

  private static func inspectAsset(_ url: URL) async throws -> AssetInfo {
    let asset = AVURLAsset(url: url)
    let duration = try await asset.load(.duration)
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw smokeError("PHASE7_INSPECT_VIDEO", "Inspected Phase 7 media has no video track.")
    }
    let naturalSize = try await track.load(.naturalSize)
    let transform = try await track.load(.preferredTransform)
    let displayRect = CGRect(origin: .zero, size: naturalSize).applying(transform).standardized
    let descriptions = try await track.load(.formatDescriptions)
    let codec = descriptions.first.map(CMFormatDescriptionGetMediaSubType) ?? 0
    let audio = try await asset.loadTracks(withMediaType: .audio)
    let nominal = try await track.load(.nominalFrameRate)
    return AssetInfo(
      codec: codec,
      width: Int(abs(naturalSize.width).rounded()),
      height: Int(abs(naturalSize.height).rounded()),
      displayWidth: Int(abs(displayRect.width).rounded()),
      displayHeight: Int(abs(displayRect.height).rounded()),
      durationSeconds: CMTimeGetSeconds(duration),
      hasAudio: !audio.isEmpty,
      nominalFrameRate: Double(nominal),
      timeline: try inspectTimeline(asset: asset, track: track)
    )
  }

  private static func inspectTimeline(
    asset: AVAsset,
    track: AVAssetTrack
  ) throws -> TimelineInfo {
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      ]
    )
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else {
      throw smokeError("PHASE7_TIMELINE_READER", "Could not add Phase 7 decoded timeline output.")
    }
    reader.add(output)
    guard reader.startReading() else {
      throw smokeError("PHASE7_TIMELINE_READER", "Could not start Phase 7 decoded timeline reader.")
    }
    var timestamps: [Double] = []
    while let sample = output.copyNextSampleBuffer() {
      let value = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
      if value.isFinite { timestamps.append(value) }
    }
    guard reader.status != .failed, !timestamps.isEmpty else {
      throw smokeError("PHASE7_TIMELINE_READER", "Phase 7 decoded timeline is unavailable.")
    }
    var deltas: [Double] = []
    if timestamps.count > 1 {
      for index in 1..<timestamps.count {
        let delta = timestamps[index] - timestamps[index - 1]
        guard delta > 0 else {
          throw smokeError("PHASE7_TIMELINE_NON_MONOTONIC", "Phase 7 source/output PTS are not strictly increasing.")
        }
        deltas.append(delta)
      }
    }
    let average = deltas.isEmpty ? 0 : deltas.reduce(0, +) / Double(deltas.count)
    let distinct = Set(deltas.map { Int(($0 * 1_000_000.0).rounded()) }).count
    return TimelineInfo(
      frameCount: Int64(timestamps.count),
      firstSeconds: timestamps[0],
      lastSeconds: timestamps[timestamps.count - 1],
      averageIntervalSeconds: average,
      distinctIntervals: distinct
    )
  }

  private static func finishWriter(_ writer: AVAssetWriter) async {
    await withCheckedContinuation { continuation in
      writer.finishWriting { continuation.resume() }
    }
  }

  private static func smokeError(_ code: String, _ message: String) -> PigeonError {
    PigeonError(code: code, message: message, details: nil)
  }
}
