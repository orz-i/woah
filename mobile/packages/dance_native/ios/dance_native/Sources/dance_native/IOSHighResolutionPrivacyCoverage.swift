import CoreGraphics
import Foundation

/// Extra spatial detail; detections stay in the original scene coordinates.
/// This supplies observations, never an identity or an exemption.
enum IOSHighResolutionPrivacyCoverage {
  static func hasDetailResolution(width: Int, height: Int) -> Bool {
    max(width, height) >= 1920 && min(width, height) >= 1080
  }

  static func applies(width: Int, height: Int, initialCount: Int) -> Bool {
    hasDetailResolution(width: width, height: height) && initialCount > 1
  }

  static func views(width: Int, height: Int) -> [CGRect] {
    IOSFrameKernel.views(width: width, height: height)
  }

  static func enhance(image: CGImage, primary: IOSYoloInferenceResult,
                      runner: IOSYoloRunner) throws -> IOSYoloInferenceResult {
    var extra: [IOSYoloDetection] = []
    for view in views(width: image.width, height: image.height) {
      guard let crop = image.cropping(to: view) else { continue }
      let detail = try runner.run(image: crop, preferredBackend: .xnnpack)
      for detection in detail.detections where isComplete(detection, view: view,
          width: image.width, height: image.height) {
        extra.append(toScene(detection, view: view, local: detail.preprocess,
                             scene: primary.preprocess))
      }
    }
    return IOSYoloInferenceResult(detections: merge(primary.detections, extra: extra),
                                   runtime: primary.runtime, preprocess: primary.preprocess)
  }

  static func isComplete(_ d: IOSYoloDetection, view: CGRect, width: Int, height: Int) -> Bool {
    IOSFrameKernel.isComplete(d, view: view, width: width, height: height)
  }

  static func toScene(_ d: IOSYoloDetection, view: CGRect,
                      local: IOSYoloPreprocessResult, scene: IOSYoloPreprocessResult) -> IOSYoloDetection {
    let mask = IOSFrameKernel.reproject(mask: d.mask, view: view, local: local, scene: scene)
    return IOSYoloDetection(x1: d.x1 + Float32(view.minX), y1: d.y1 + Float32(view.minY),
      x2: d.x2 + Float32(view.minX), y2: d.y2 + Float32(view.minY), confidence: d.confidence, mask: mask)
  }

  static func merge(_ primary: [IOSYoloDetection], extra: [IOSYoloDetection]) -> [IOSYoloDetection] {
    var result = primary
    for d in extra.sorted(by: { $0.confidence > $1.confidence }) {
      if !result.contains(where: { iou($0, d) >= 0.45 }) { result.append(d) }
    }
    return result.sorted { ($0.x1 + $0.x2) < ($1.x1 + $1.x2) }
  }

  /// A large foreground body may fully cover a guessed background head.
  /// Current detected faces always win over this segmentation-only hint.
  static func coveredHeadFallbackIds(persons: [IOSPreviewPerson],
                                    preprocess: IOSYoloPreprocessResult) -> Set<Int> {
    let size = IOSYoloPostprocessor.protoSize
    return Set(persons.filter { person in
      let d = person.detection
      let width = d.x2 - d.x1, height = d.y2 - d.y1
      let cx = ((d.x1 + d.x2) * 0.5 * preprocess.scale + preprocess.padLeft)
        / Float32(preprocess.inputSize) * Float32(size)
      let cy = ((d.y1 + height * 0.14) * preprocess.scale + preprocess.padTop)
        / Float32(preprocess.inputSize) * Float32(size)
      let rx = max(width * 0.22, height * 0.045) * preprocess.scale
        / Float32(preprocess.inputSize) * Float32(size) * 0.6
      let ry = max(width * 0.26, height * 0.060) * preprocess.scale
        / Float32(preprocess.inputSize) * Float32(size) * 0.6
      guard rx > 0, ry > 0 else { return false }
      return persons.contains { other in
        let o = other.detection
        guard other.id != person.id, o.mask.count == size * size,
              (o.x2-o.x1)*(o.y2-o.y1) >= width * height * 2 else { return false }
        var samples = 0
        let y1 = max(0, min(size, Int(floor(cy - ry))))
        let y2 = max(y1, min(size, Int(ceil(cy + ry))))
        let x1 = max(0, min(size, Int(floor(cx - rx))))
        let x2 = max(x1, min(size, Int(ceil(cx + rx))))
        for y in y1..<y2 {
          for x in x1..<x2 {
            let dx = (Float32(x) + 0.5 - cx) / rx
            let dy = (Float32(y) + 0.5 - cy) / ry
            if dx * dx + dy * dy > 1 { continue }
            if o.mask[y * size + x] < 128 { return false }
            samples += 1
          }
        }
        return samples >= 4
      }
    }.map(\.id))
  }

  private static func iou(_ a: IOSYoloDetection, _ b: IOSYoloDetection) -> Float32 {
    IOSFrameKernel.iou(a, b)
  }
}
