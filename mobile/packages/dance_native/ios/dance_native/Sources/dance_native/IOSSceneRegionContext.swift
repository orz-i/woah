import AVFoundation
import CoreGraphics

/// Native source decoding and inference; time planning and continuity are shared.
enum IOSSceneRegionContext {
  static func seed(asset: AVAsset, timestampUs: Int64, runner: IOSYoloRunner,
                   resolver: IOSFacePrivacyTemporalResolver, preferredBackend: IOSYoloBackend? = nil) throws {
    guard let probe = IOSFrameKernel.regionContextProbeUs(timestampUs) else { return }
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero
    let image = try generator.copyCGImage(at: CMTime(value: probe, timescale: 1_000_000), actualTime: nil)
    let inference = try runner.run(image: image, preferredBackend: preferredBackend)
    let persons = inference.detections.enumerated().map {
      IOSPreviewPerson(id: -4_000_000 - $0.offset, detection: $0.element)
    }
    _ = try resolver.resolve(image: image, persons: persons,
      faceOnlyIds: Set(persons.map(\.id)), preprocess: inference.preprocess,
      protectUnassignedFaces: true, timestampUs: probe)
  }
}
