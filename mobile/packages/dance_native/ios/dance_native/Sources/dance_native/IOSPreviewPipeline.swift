import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

final class IOSPreviewPipeline {
  private struct CachedFrameAnalysis {
    let image: CGImage
    let persons: [IOSPreviewPerson]
    let preprocess: IOSYoloPreprocessResult
  }

  private let analysisCache: IOSAnalysisCache
  private let runner: IOSYoloRunner
  private let fileManager: FileManager
  private let cacheLock = NSLock()
  private var frameAnalysisCache: [String: CachedFrameAnalysis] = [:]
  private var facePrivacyResolvers: [String: IOSFacePrivacyTemporalResolver] = [:]
  private var metalRenderer: IOSMetalPreviewRenderer?

  init(
    analysisCache: IOSAnalysisCache,
    runner: IOSYoloRunner,
    fileManager: FileManager = .default
  ) {
    self.analysisCache = analysisCache
    self.runner = runner
    self.fileManager = fileManager
  }

  func render(request: PreviewRequestDto) async throws -> PreviewFrameDto {
    let started = CFAbsoluteTimeGetCurrent()
    guard !request.analysisCacheId.isEmpty else {
      throw PigeonError(
        code: "INVALID_CACHE_ID",
        message: "analysisCacheId is required for iOS preview.",
        details: nil
      )
    }
    guard let sourceUri = try analysisCache.loadVideoUri(cacheId: request.analysisCacheId),
          !sourceUri.isEmpty else {
      throw PigeonError(
        code: "CACHE_NOT_FOUND",
        message: "Analysis cache not found for cacheId: \(request.analysisCacheId)",
        details: nil
      )
    }

    let requestedTimestampMs = max(Int64(0), request.timestampMs)
    let cacheKey = "\(request.analysisCacheId)_\(requestedTimestampMs)"
    let frameAnalysis: CachedFrameAnalysis
    if let cached = cachedFrame(key: cacheKey) {
      frameAnalysis = cached
    } else {
      frameAnalysis = try await analyzeFrame(
        sourceUri: sourceUri,
        cacheId: request.analysisCacheId,
        timestampMs: requestedTimestampMs
      )
      storeFrame(frameAnalysis, key: cacheKey)
    }

    let fullBodyIds = Set(request.selectedPersonIds.map { Int($0) })
    let faceOnlyIds = Set((request.faceOnlyPersonIds ?? []).map { Int($0) })
      .subtracting(fullBodyIds)
    let requestedPrivacyIds = fullBodyIds.union(faceOnlyIds)
    let resolvedIds = Set(frameAnalysis.persons.map(\.id))
    let unresolvedIds = requestedPrivacyIds.subtracting(resolvedIds)
    guard unresolvedIds.isEmpty else {
      throw PigeonError(
        code: "PREVIEW_PRIVACY_UNRESOLVED",
        message: "Selected iOS preview target(s) could not be resolved safely.",
        details: unresolvedIds.sorted()
      )
    }

    let faceRegions = faceOnlyIds.isEmpty
      ? [:]
      : facePrivacyResolver(cacheId: request.analysisCacheId).resolve(
        image: frameAnalysis.image,
        persons: frameAnalysis.persons,
        faceOnlyIds: faceOnlyIds,
        timestampUs: requestedTimestampMs * 1_000
      )
    let renderer = try renderer()
    let rendered = try renderer.render(
      source: frameAnalysis.image,
      persons: frameAnalysis.persons,
      preprocess: frameAnalysis.preprocess,
      fullBodyIds: fullBodyIds,
      faceOnlyIds: faceOnlyIds,
      effects: request.effects,
      faceRegions: faceRegions
    )
    let previewPath = try savePreview(
      rendered,
      cacheId: request.analysisCacheId,
      timestampMs: requestedTimestampMs
    )
    let elapsedMs = (CFAbsoluteTimeGetCurrent() - started) * 1000.0
    return PreviewFrameDto(
      thumbnailPath: previewPath,
      timestampMs: requestedTimestampMs,
      renderTimeMs: elapsedMs
    )
  }

  func clearForAnalysis(cacheId: String) {
    cacheLock.lock()
    frameAnalysisCache = frameAnalysisCache.filter { key, _ in
      !key.hasPrefix("\(cacheId)_")
    }
    facePrivacyResolvers.removeValue(forKey: cacheId)?.reset()
    cacheLock.unlock()
    removePreviewFiles(cacheId: cacheId)
  }

  private func facePrivacyResolver(cacheId: String) -> IOSFacePrivacyTemporalResolver {
    cacheLock.lock()
    defer { cacheLock.unlock() }
    if let existing = facePrivacyResolvers[cacheId] {
      return existing
    }
    let created = IOSFacePrivacyTemporalResolver()
    facePrivacyResolvers[cacheId] = created
    return created
  }

  private func analyzeFrame(
    sourceUri: String,
    cacheId: String,
    timestampMs: Int64
  ) async throws -> CachedFrameAnalysis {
    let url = IOSVideoProbe.assetURL(from: sourceUri)
    let securityScoped = url.isFileURL && url.startAccessingSecurityScopedResource()
    defer {
      if securityScoped { url.stopAccessingSecurityScopedResource() }
    }

    let asset = AVURLAsset(url: url)
    let duration = try await asset.load(.duration)
    let durationSeconds = CMTimeGetSeconds(duration)
    let maxTimestampMs: Int64 = durationSeconds.isFinite && durationSeconds > 0
      ? Int64((durationSeconds * 1000.0).rounded())
      : timestampMs
    let clampedTimestampMs = min(timestampMs, maxTimestampMs)

    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero
    let generated: (image: CGImage, actualTime: CMTime)
    do {
      generated = try await generator.image(
        at: CMTime(value: clampedTimestampMs, timescale: 1000)
      )
    } catch {
      throw PigeonError(
        code: "DECODE_FRAME_FAILED",
        message: "Failed to decode iOS preview frame at \(clampedTimestampMs) ms.",
        details: String(describing: error)
      )
    }

    // Keep preview inference deterministic until delegate parity is accepted
    // on real iPhones. Phase 3 acceleration is the Metal compositor itself.
    let inference = try runner.run(
      image: generated.image,
      preferredBackend: .xnnpack
    )
    let metadata = try analysisCache.loadMetadata(cacheId: cacheId)
    let persons = IOSPreviewIdentityMatcher.assign(
      detections: inference.detections,
      metadata: metadata,
      frameWidth: generated.image.width,
      frameHeight: generated.image.height
    )
    return CachedFrameAnalysis(
      image: generated.image,
      persons: persons,
      preprocess: inference.preprocess
    )
  }

  private func renderer() throws -> IOSMetalPreviewRenderer {
    if let metalRenderer { return metalRenderer }
    let created = try IOSMetalPreviewRenderer()
    metalRenderer = created
    return created
  }

  private func cachedFrame(key: String) -> CachedFrameAnalysis? {
    cacheLock.lock()
    defer { cacheLock.unlock() }
    return frameAnalysisCache[key]
  }

  private func storeFrame(
    _ entry: CachedFrameAnalysis,
    key: String
  ) {
    cacheLock.lock()
    // Effect-editor previews are pinned to one frame. Keep only a tiny cache
    // so changing solid/blur/mosaic/sticker does not rerun YOLO each time.
    if frameAnalysisCache.count >= 3 {
      frameAnalysisCache.removeAll(keepingCapacity: true)
    }
    frameAnalysisCache[key] = entry
    cacheLock.unlock()
  }

  private func savePreview(
    _ image: CGImage,
    cacheId: String,
    timestampMs: Int64
  ) throws -> String {
    removePreviewFiles(cacheId: cacheId)
    let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    let nonce = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
    let file = root.appendingPathComponent(
      "preview_\(cacheId)_\(timestampMs)_\(nonce).jpg"
    )
    guard let destination = CGImageDestinationCreateWithURL(
      file as CFURL,
      UTType.jpeg.identifier as CFString,
      1,
      nil
    ) else {
      throw PigeonError(
        code: "PREVIEW_SAVE_FAILED",
        message: "Could not create iOS preview JPEG destination.",
        details: nil
      )
    }
    CGImageDestinationAddImage(
      destination,
      image,
      [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary
    )
    guard CGImageDestinationFinalize(destination) else {
      throw PigeonError(
        code: "PREVIEW_SAVE_FAILED",
        message: "Could not finalize iOS preview JPEG.",
        details: nil
      )
    }
    return file.path
  }

  private func removePreviewFiles(cacheId: String) {
    let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    let prefix = "preview_\(cacheId)_"
    guard let files = try? fileManager.contentsOfDirectory(
      at: root,
      includingPropertiesForKeys: nil
    ) else { return }
    for file in files where file.lastPathComponent.hasPrefix(prefix)
      && file.pathExtension.lowercased() == "jpg" {
      try? fileManager.removeItem(at: file)
    }
  }
}
