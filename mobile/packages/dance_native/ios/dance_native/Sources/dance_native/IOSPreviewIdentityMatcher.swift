import Foundation

struct IOSPreviewPerson {
  let id: Int
  let detection: IOSYoloDetection
  let conservativePrivacyFallback: Bool

  init(
    id: Int,
    detection: IOSYoloDetection,
    conservativePrivacyFallback: Bool = false
  ) {
    self.id = id
    self.detection = detection
    self.conservativePrivacyFallback = conservativePrivacyFallback
  }
}

enum IOSPreviewIdentityMatcher {
  /// Mirrors Android HungarianSolver.match, which is intentionally a greedy
  /// globally-sorted cost matcher with a 0.70 maximum cost for preview.
  static func assign(
    detections: [IOSYoloDetection],
    metadata: IOSAnalysisMetadata?,
    frameWidth: Int,
    frameHeight: Int,
    maxCostThreshold: Float32 = 0.70
  ) throws -> [IOSPreviewPerson] {
    guard !detections.isEmpty else { return [] }
    guard let cached = metadata?.persons, !cached.isEmpty else {
      return detections.enumerated().map { index, detection in
        IOSPreviewPerson(id: index, detection: detection)
      }
    }

    let cachedBoxes = cached.map { person in
      SIMD4<Float>(Float(person.bbox.left * Double(frameWidth)), Float(person.bbox.top * Double(frameHeight)),
        Float(person.bbox.right * Double(frameWidth)), Float(person.bbox.bottom * Double(frameHeight)))
    }
    let boxes = detections.map { SIMD4<Float>($0.x1, $0.y1, $0.x2, $0.y2) }
    let matches = try IOSFrameKernel.matchRects(rows: cachedBoxes, columns: boxes, maxCost: maxCostThreshold)
    var assignedIds = [Int](repeating: -1, count: detections.count)
    var usedIds = Set<Int>()
    for pair in matches {
      let cachedId = cached[pair.row].id
      assignedIds[pair.column] = cachedId
      usedIds.insert(cachedId)
    }

    var nextId = (cached.map(\.id).max() ?? -1) + 1
    for index in assignedIds.indices where assignedIds[index] < 0 {
      while usedIds.contains(nextId) { nextId += 1 }
      assignedIds[index] = nextId
      usedIds.insert(nextId)
      nextId += 1
    }

    return detections.indices.map { index in
      IOSPreviewPerson(id: assignedIds[index], detection: detections[index])
    }
  }

}
