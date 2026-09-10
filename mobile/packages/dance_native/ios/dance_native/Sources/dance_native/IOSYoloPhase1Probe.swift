import AVFoundation
import CryptoKit
import Foundation
import ImageIO

enum IOSYoloPhase1Probe {
  // The synthetic Phase 1 parity fixture is intentionally not a natural
  // photograph. Its tracked LiteRT parity gate uses 0.001 to obtain a stable
  // person-class candidate. Keep this diagnostic threshold isolated from the
  // production runner default (0.25).
  static let bundledFixtureConfidenceThreshold: Float32 = 0.001

  static func run(
    videoUri: String,
    timestampMs: Int64,
    requestedBackend: IOSYoloBackend?,
    runner: IOSYoloRunner
  ) async throws -> [String: Any] {
    let sourceURL = IOSVideoProbe.assetURL(from: videoUri)
    let securityScoped = sourceURL.isFileURL && sourceURL.startAccessingSecurityScopedResource()
    defer {
      if securityScoped {
        sourceURL.stopAccessingSecurityScopedResource()
      }
    }

    let asset = AVURLAsset(url: sourceURL)
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero

    let requestedTime = CMTime(value: max(0, timestampMs), timescale: 1000)
    let frameStart = CFAbsoluteTimeGetCurrent()
    let generated = try await generator.image(at: requestedTime)
    let frameDecodeMs = (CFAbsoluteTimeGetCurrent() - frameStart) * 1000.0

    let inference = try runner.run(
      image: generated.image,
      preferredBackend: requestedBackend,
      confidenceThreshold: IOSYoloRunner.defaultConfidenceThreshold
    )
    return makeReport(
      image: generated.image,
      inference: inference,
      requestedBackend: requestedBackend,
      confidenceThreshold: IOSYoloRunner.defaultConfidenceThreshold,
      metadata: [
        "fixture": "video",
        "requested_timestamp_ms": timestampMs,
        "actual_timestamp_ms": Int64((CMTimeGetSeconds(generated.actualTime) * 1000.0).rounded()),
        "frame_decode_ms": frameDecodeMs,
      ]
    )
  }

  private static func sha256(_ url: URL) throws -> String {
    let data = try Data(contentsOf: url, options: .mappedIfSafe)
    return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }

  static func runBundledFixture(
    requestedBackend: IOSYoloBackend?,
    runner: IOSYoloRunner
  ) throws -> [String: Any] {
    guard let modelURL = IOSModelResources.yoloModelURL() else {
      throw PigeonError(code: "MODEL_NOT_FOUND", message: "Bundled YOLO model is missing.", details: nil)
    }
    guard let fixtureURL = IOSModelResources.yoloPhase1FixtureURL() else {
      throw PigeonError(
        code: "IOS_PHASE1_FIXTURE_NOT_FOUND",
        message: "The bundled Phase 1 YOLO fixture is missing.",
        details: nil
      )
    }
    guard let source = CGImageSourceCreateWithURL(fixtureURL as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
      throw PigeonError(
        code: "IOS_PHASE1_FIXTURE_DECODE_FAILED",
        message: "Could not decode the bundled Phase 1 YOLO fixture.",
        details: fixtureURL.lastPathComponent
      )
    }
    let inference = try runner.run(
      image: image,
      preferredBackend: requestedBackend,
      confidenceThreshold: Self.bundledFixtureConfidenceThreshold
    )
    return makeReport(
      image: image,
      inference: inference,
      requestedBackend: requestedBackend,
      confidenceThreshold: Self.bundledFixtureConfidenceThreshold,
      metadata: [
        "fixture": fixtureURL.lastPathComponent,
        "model_sha256": try sha256(modelURL),
        "fixture_sha256": try sha256(fixtureURL),
        "requested_timestamp_ms": 0,
        "actual_timestamp_ms": 0,
        "frame_decode_ms": 0.0,
      ]
    )
  }

  private static func makeReport(
    image: CGImage,
    inference: IOSYoloInferenceResult,
    requestedBackend: IOSYoloBackend?,
    confidenceThreshold: Float32,
    metadata: [String: Any]
  ) -> [String: Any] {
    let width = max(1, image.width)
    let height = max(1, image.height)
    let maskPixels = IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
    let detections: [[String: Any]] = inference.detections.enumerated().map { index, detection in
      let nonzero = detection.mask.reduce(into: 0) { count, value in
        if value != 0 { count += 1 }
      }
      return [
        "id": index,
        "confidence": Double(detection.confidence),
        "bbox": [
          Double(detection.x1) / Double(width),
          Double(detection.y1) / Double(height),
          Double(detection.x2) / Double(width),
          Double(detection.y2) / Double(height),
        ],
        "bbox_px": [
          Double(detection.x1),
          Double(detection.y1),
          Double(detection.x2),
          Double(detection.y2),
        ],
        "mask_nonzero": nonzero,
        "mask_coverage": Double(nonzero) / Double(maskPixels),
      ]
    }

    var report: [String: Any] = [
      "phase": "ios_yolo_phase1",
      "requested_backend": requestedBackend?.rawValue ?? "auto",
      "confidence_threshold": Double(confidenceThreshold),
      "frame_width": width,
      "frame_height": height,
      "effective_backend": inference.runtime.effectiveBackend.rawValue,
      "initialization_ms": inference.runtime.initializationMs,
      "inference_ms": inference.runtime.inferenceMs,
      "fallback_reasons": inference.runtime.fallbackReasons,
      "input_shape": inference.runtime.inputShape,
      "output_shapes": inference.runtime.outputShapes,
      "detection_count": detections.count,
      "detections": detections,
    ]
    for (key, value) in metadata {
      report[key] = value
    }
    return report
  }
}
