import AVFoundation
import Foundation

enum IOSYoloPhase1Probe {
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
      preferredBackend: requestedBackend
    )
    let width = max(1, generated.image.width)
    let height = max(1, generated.image.height)
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

    return [
      "phase": "ios_yolo_phase1",
      "requested_backend": requestedBackend?.rawValue ?? "auto",
      "requested_timestamp_ms": timestampMs,
      "actual_timestamp_ms": Int64((CMTimeGetSeconds(generated.actualTime) * 1000.0).rounded()),
      "frame_width": width,
      "frame_height": height,
      "frame_decode_ms": frameDecodeMs,
      "effective_backend": inference.runtime.effectiveBackend.rawValue,
      "initialization_ms": inference.runtime.initializationMs,
      "inference_ms": inference.runtime.inferenceMs,
      "fallback_reasons": inference.runtime.fallbackReasons,
      "input_shape": inference.runtime.inputShape,
      "output_shapes": inference.runtime.outputShapes,
      "detection_count": detections.count,
      "detections": detections,
    ]
  }
}
