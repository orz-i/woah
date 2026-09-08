import Foundation

struct IOSPreviewPerson {
  let id: Int
  let detection: IOSYoloDetection
}

enum IOSPreviewIdentityMatcher {
  private struct CandidateMatch {
    let cachedIndex: Int
    let detectionIndex: Int
    let cost: Float32
  }

  /// Mirrors Android HungarianSolver.match, which is intentionally a greedy
  /// globally-sorted cost matcher with a 0.70 maximum cost for preview.
  static func assign(
    detections: [IOSYoloDetection],
    metadata: IOSAnalysisMetadata?,
    frameWidth: Int,
    frameHeight: Int,
    maxCostThreshold: Float32 = 0.70
  ) -> [IOSPreviewPerson] {
    guard !detections.isEmpty else { return [] }
    guard let cached = metadata?.persons, !cached.isEmpty else {
      return detections.enumerated().map { index, detection in
        IOSPreviewPerson(id: index, detection: detection)
      }
    }

    var candidates: [CandidateMatch] = []
    candidates.reserveCapacity(cached.count * detections.count)
    for cachedIndex in cached.indices {
      let cachedBox = sourceRect(
        cached[cachedIndex].bbox,
        frameWidth: frameWidth,
        frameHeight: frameHeight
      )
      for detectionIndex in detections.indices {
        let cost = 1.0 - bboxIoU(cachedBox, detections[detectionIndex])
        if cost <= maxCostThreshold {
          candidates.append(CandidateMatch(
            cachedIndex: cachedIndex,
            detectionIndex: detectionIndex,
            cost: cost
          ))
        }
      }
    }
    candidates.sort {
      if $0.cost != $1.cost { return $0.cost < $1.cost }
      if $0.cachedIndex != $1.cachedIndex { return $0.cachedIndex < $1.cachedIndex }
      return $0.detectionIndex < $1.detectionIndex
    }

    var matchedCached = Set<Int>()
    var matchedDetections = Set<Int>()
    var assignedIds = [Int](repeating: -1, count: detections.count)
    var usedIds = Set<Int>()
    for candidate in candidates {
      guard !matchedCached.contains(candidate.cachedIndex),
            !matchedDetections.contains(candidate.detectionIndex) else {
        continue
      }
      let cachedId = cached[candidate.cachedIndex].id
      matchedCached.insert(candidate.cachedIndex)
      matchedDetections.insert(candidate.detectionIndex)
      assignedIds[candidate.detectionIndex] = cachedId
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

  private static func sourceRect(
    _ bbox: IOSCachedBBox,
    frameWidth: Int,
    frameHeight: Int
  ) -> (x1: Float32, y1: Float32, x2: Float32, y2: Float32) {
    (
      Float32(bbox.left * Double(frameWidth)),
      Float32(bbox.top * Double(frameHeight)),
      Float32(bbox.right * Double(frameWidth)),
      Float32(bbox.bottom * Double(frameHeight))
    )
  }

  private static func bboxIoU(
    _ cached: (x1: Float32, y1: Float32, x2: Float32, y2: Float32),
    _ detection: IOSYoloDetection
  ) -> Float32 {
    let left = max(cached.x1, detection.x1)
    let top = max(cached.y1, detection.y1)
    let right = min(cached.x2, detection.x2)
    let bottom = min(cached.y2, detection.y2)
    let intersection = max(0, right - left) * max(0, bottom - top)
    let cachedArea = max(0, cached.x2 - cached.x1) * max(0, cached.y2 - cached.y1)
    let detectionArea = max(0, detection.x2 - detection.x1) * max(0, detection.y2 - detection.y1)
    let union = cachedArea + detectionArea - intersection
    return union > 0 ? intersection / union : 0
  }
}
