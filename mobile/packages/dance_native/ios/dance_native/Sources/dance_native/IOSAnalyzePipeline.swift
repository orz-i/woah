import AVFoundation
import Foundation

final class IOSAnalyzePipeline {
  private let cache: IOSAnalysisCache
  private let runner: IOSYoloRunner

  init(cache: IOSAnalysisCache, runner: IOSYoloRunner) {
    self.cache = cache
    self.runner = runner
  }

  func analyze(request: AnalyzeRequestDto) async throws -> AnalyzeResultDto {
    let videoInfo = try await IOSVideoProbe.probe(uri: request.videoUri)
    let cacheId = Self.makeCacheId()
    var committed = false
    defer {
      if !committed {
        try? cache.clearAnalysisCache(cacheId: cacheId)
      }
    }

    let sourceURL = IOSVideoProbe.assetURL(from: request.videoUri)
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

    let requestedMs = max(Int64(0), min(request.trimStartMs, videoInfo.durationMs))
    let requestedTime = CMTime(value: requestedMs, timescale: 1000)
    let generated: (image: CGImage, actualTime: CMTime)
    do {
      generated = try await generator.image(at: requestedTime)
    } catch {
      throw PigeonError(
        code: "VIDEO_OPEN_FAILED",
        message: "Failed to decode the iOS analysis frame at \(requestedMs) ms.",
        details: String(describing: error)
      )
    }

    let inference = try runner.run(image: generated.image)
    let frameWidth = max(1, generated.image.width)
    let frameHeight = max(1, generated.image.height)

    try cache.saveVideoUri(cacheId: cacheId, videoUri: request.videoUri)

    var persons: [DetectedPersonDto] = []
    var cachedPersons: [IOSCachedPerson] = []
    persons.reserveCapacity(inference.detections.count)
    cachedPersons.reserveCapacity(inference.detections.count)

    // IOSYoloPostprocessor already emits a deterministic left-to-right order.
    // IDs assigned here become the identity roots for the rest of the project,
    // matching Android AnalyzePipeline's one-shot selection semantics.
    for (index, detection) in inference.detections.enumerated() {
      let normalizedX1 = clamp01(Double(detection.x1) / Double(frameWidth))
      let normalizedY1 = clamp01(Double(detection.y1) / Double(frameHeight))
      let normalizedX2 = clamp01(Double(detection.x2) / Double(frameWidth))
      let normalizedY2 = clamp01(Double(detection.y2) / Double(frameHeight))
      guard normalizedX2 > normalizedX1, normalizedY2 > normalizedY1 else { continue }

      let thumbnailPath = try cache.savePersonThumbnail(
        cacheId: cacheId,
        personId: index,
        frame: generated.image,
        x1: detection.x1,
        y1: detection.y1,
        x2: detection.x2,
        y2: detection.y2
      )
      persons.append(DetectedPersonDto(
        id: Int64(index),
        x1: normalizedX1,
        y1: normalizedY1,
        x2: normalizedX2,
        y2: normalizedY2,
        thumbnailPath: thumbnailPath,
        confidence: Double(detection.confidence)
      ))
      cachedPersons.append(IOSCachedPerson(
        id: index,
        bbox: IOSCachedBBox(
          left: normalizedX1,
          top: normalizedY1,
          right: normalizedX2,
          bottom: normalizedY2
        ),
        confidence: Double(detection.confidence)
      ))
    }

    try cache.saveMetadata(
      cacheId: cacheId,
      metadata: IOSAnalysisMetadata(
        schemaVersion: 1,
        sourceUri: request.videoUri,
        persons: cachedPersons
      )
    )
    committed = true
    return AnalyzeResultDto(
      analysisCacheId: cacheId,
      videoInfo: videoInfo,
      persons: persons
    )
  }

  private static func makeCacheId() -> String {
    let milliseconds = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
    let suffix = UUID().uuidString.prefix(8).lowercased()
    return "analysis_\(milliseconds)_\(suffix)"
  }

  private func clamp01(_ value: Double) -> Double {
    max(0.0, min(1.0, value))
  }
}
