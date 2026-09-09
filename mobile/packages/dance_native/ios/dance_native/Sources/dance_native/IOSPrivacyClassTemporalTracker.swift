import Foundation

enum IOSPrivacySelectionClass: String {
  case selected = "SELECTED"
  case unselected = "UNSELECTED"
}

struct IOSFreshPrivacyClassEvidence {
  let selectionClass: IOSPrivacySelectionClass
  let detectionIndex: Int
  let detection: IOSYoloDetection
  let conservativeUnknown: Bool
}

/// Identity-independent selected/unselected continuity for fresh YOLO detections.
///
/// This mirrors Android PrivacyClassTemporalTracker. The first non-empty hard
/// class map is the immutable privacy root. Later runtime identity labels are
/// intentionally ignored: this tracker owns only privacy class, never person ID.
/// Unknown current detections are emitted SELECTED fail-closed for rendering but
/// never update class prototypes.
final class IOSPrivacyClassTemporalTracker {
  private final class Prototype {
    let selectionClass: IOSPrivacySelectionClass
    var detection: IOSYoloDetection
    var preprocess: IOSYoloPreprocessResult
    var velocityX: Float32 = 0
    var velocityY: Float32 = 0
    var misses: Int = 0
    var reliability: Float32

    init(
      selectionClass: IOSPrivacySelectionClass,
      detection: IOSYoloDetection,
      preprocess: IOSYoloPreprocessResult,
      reliability: Float32
    ) {
      self.selectionClass = selectionClass
      self.detection = detection
      self.preprocess = preprocess
      self.reliability = reliability
    }

    func predictedDetection() -> IOSYoloDetection {
      IOSYoloDetection(
        x1: detection.x1 + velocityX,
        y1: detection.y1 + velocityY,
        x2: detection.x2 + velocityX,
        y2: detection.y2 + velocityY,
        confidence: detection.confidence,
        mask: detection.mask
      )
    }
  }

  private struct SimilarityKey: Hashable {
    let prototype: ObjectIdentifier
    let detectionIndex: Int
  }

  private struct WarpedMaskSupport {
    let width: Int
    let height: Int
    let sampleStride: Int
    let sampledForeground: [Bool]
  }

  private let minClassScore: Float32
  private let minSingleClassScore: Float32
  private let minClassMargin: Float32
  private let maxPrototypeMisses: Int
  private let reuseFrameSimilarityCache: Bool
  private let countSimilarityEvaluations: Bool

  private var prototypes: [Prototype] = []
  private var rootSeeded = false
  private(set) var lastSimilarityEvaluationCount = 0

  init(
    minClassScore: Float32 = 0.42,
    minSingleClassScore: Float32 = 0.65,
    minClassMargin: Float32 = 0.12,
    maxPrototypeMisses: Int = 4,
    reuseFrameSimilarityCache: Bool = true,
    countSimilarityEvaluations: Bool = false
  ) {
    self.minClassScore = minClassScore
    self.minSingleClassScore = minSingleClassScore
    self.minClassMargin = minClassMargin
    self.maxPrototypeMisses = maxPrototypeMisses
    self.reuseFrameSimilarityCache = reuseFrameSimilarityCache
    self.countSimilarityEvaluations = countSimilarityEvaluations
  }

  func reset() {
    prototypes.removeAll()
    rootSeeded = false
    lastSimilarityEvaluationCount = 0
  }

  func update(
    detections: [IOSYoloDetection],
    preprocess: IOSYoloPreprocessResult,
    hardClassByDetectionIndex: [Int: IOSPrivacySelectionClass],
    timestampUs: Int64
  ) -> [IOSFreshPrivacyClassEvidence] {
    _ = timestampUs // parity/debug surface; class motion is frame-relative.
    lastSimilarityEvaluationCount = 0
    if detections.isEmpty {
      advanceMissingPrototypes()
      return []
    }

    let rootClassByDetectionIndex: [Int: IOSPrivacySelectionClass]
    if !rootSeeded && !hardClassByDetectionIndex.isEmpty {
      rootSeeded = true
      rootClassByDetectionIndex = hardClassByDetectionIndex
    } else {
      rootClassByDetectionIndex = [:]
    }

    var classified: [Int: IOSPrivacySelectionClass] = [:]
    let hasSelectedHistory = prototypes.contains { $0.selectionClass == .selected }
    let hasUnselectedHistory = prototypes.contains { $0.selectionClass == .unselected }
    var similarityCache: [SimilarityKey: Float32] = [:]
    var warpedMaskSupportCache: [ObjectIdentifier: WarpedMaskSupport] = [:]

    for (index, selectionClass) in rootClassByDetectionIndex where detections.indices.contains(index) {
      classified[index] = selectionClass
    }

    for index in detections.indices where classified[index] == nil {
      let selectedScore = bestClassScore(
        selectionClass: .selected,
        detectionIndex: index,
        detection: detections[index],
        detections: detections,
        similarityCache: &similarityCache,
        warpedMaskSupportCache: &warpedMaskSupportCache
      )
      let unselectedScore = bestClassScore(
        selectionClass: .unselected,
        detectionIndex: index,
        detection: detections[index],
        detections: detections,
        similarityCache: &similarityCache,
        warpedMaskSupportCache: &warpedMaskSupportCache
      )

      let inferred: IOSPrivacySelectionClass?
      if hasSelectedHistory && hasUnselectedHistory,
         selectedScore >= minClassScore,
         selectedScore - unselectedScore >= minClassMargin {
        inferred = .selected
      } else if hasSelectedHistory && hasUnselectedHistory,
                unselectedScore >= minClassScore,
                unselectedScore - selectedScore >= minClassMargin {
        inferred = .unselected
      } else if hasSelectedHistory && !hasUnselectedHistory,
                selectedScore >= minSingleClassScore {
        inferred = .selected
      } else if hasUnselectedHistory && !hasSelectedHistory,
                unselectedScore >= minSingleClassScore {
        inferred = .unselected
      } else {
        inferred = nil
      }
      if let inferred { classified[index] = inferred }
    }

    updatePrototypes(
      detections: detections,
      preprocess: preprocess,
      classified: classified,
      hardClassByDetectionIndex: rootClassByDetectionIndex,
      similarityCache: &similarityCache,
      warpedMaskSupportCache: &warpedMaskSupportCache
    )

    return detections.indices.compactMap { index in
      let detection = detections[index]
      guard detection.mask.count == IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize else {
        return nil
      }
      return IOSFreshPrivacyClassEvidence(
        selectionClass: classified[index] ?? .selected,
        detectionIndex: index,
        detection: detection,
        conservativeUnknown: classified[index] == nil
      )
    }
  }

  private func bestClassScore(
    selectionClass: IOSPrivacySelectionClass,
    detectionIndex: Int,
    detection: IOSYoloDetection,
    detections: [IOSYoloDetection],
    similarityCache: inout [SimilarityKey: Float32],
    warpedMaskSupportCache: inout [ObjectIdentifier: WarpedMaskSupport]
  ) -> Float32 {
    var best: Float32 = 0
    for prototype in prototypes where prototype.selectionClass == selectionClass {
      let score = similarityForFrame(
        prototype: prototype,
        detectionIndex: detectionIndex,
        detection: detection,
        detections: detections,
        similarityCache: &similarityCache,
        warpedMaskSupportCache: &warpedMaskSupportCache
      ) * min(1, max(0, prototype.reliability))
      best = max(best, score)
    }
    return best
  }

  private func similarityForFrame(
    prototype: Prototype,
    detectionIndex: Int,
    detection: IOSYoloDetection,
    detections: [IOSYoloDetection],
    similarityCache: inout [SimilarityKey: Float32],
    warpedMaskSupportCache: inout [ObjectIdentifier: WarpedMaskSupport]
  ) -> Float32 {
    let key = SimilarityKey(
      prototype: ObjectIdentifier(prototype),
      detectionIndex: detectionIndex
    )
    if reuseFrameSimilarityCache, let cached = similarityCache[key] { return cached }
    if countSimilarityEvaluations { lastSimilarityEvaluationCount += 1 }
    let value = similarity(
      prototype: prototype,
      detection: detection,
      warpedMaskSupportCache: &warpedMaskSupportCache
    )
    if reuseFrameSimilarityCache { similarityCache[key] = value }
    return value
  }

  private func similarity(
    prototype: Prototype,
    detection: IOSYoloDetection,
    warpedMaskSupportCache: inout [ObjectIdentifier: WarpedMaskSupport]
  ) -> Float32 {
    let predicted = prototype.predictedDetection()
    let bbox = bboxIoU(predicted, detection)
    let predictedCenterX = (predicted.x1 + predicted.x2) * 0.5
    let predictedCenterY = (predicted.y1 + predicted.y2) * 0.5
    let detectionCenterX = (detection.x1 + detection.x2) * 0.5
    let detectionCenterY = (detection.y1 + detection.y2) * 0.5
    let dx = predictedCenterX - detectionCenterX
    let dy = predictedCenterY - detectionCenterY
    let distance = sqrt(dx * dx + dy * dy)
    let reference = max(
      max(
        max(predicted.x2 - predicted.x1, predicted.y2 - predicted.y1),
        max(detection.x2 - detection.x1, detection.y2 - detection.y1)
      ),
      1
    )
    let distanceScore = min(1, max(0, 1 - distance / (reference * 1.5)))
    let mask = warpedMaskIoU(
      prototype: prototype,
      predicted: predicted,
      candidateMask: detection.mask,
      warpedMaskSupportCache: &warpedMaskSupportCache
    )
    return min(1, max(0, bbox * 0.40 + mask * 0.40 + distanceScore * 0.20))
  }

  private func warpedMaskIoU(
    prototype: Prototype,
    predicted: IOSYoloDetection,
    candidateMask: [UInt8],
    warpedMaskSupportCache: inout [ObjectIdentifier: WarpedMaskSupport]
  ) -> Float32 {
    let proto = IOSYoloPostprocessor.protoSize
    guard prototype.detection.mask.count == proto * proto,
          candidateMask.count == proto * proto else {
      return 0
    }
    let key = ObjectIdentifier(prototype)
    let support: WarpedMaskSupport
    if let cached = warpedMaskSupportCache[key] {
      support = cached
    } else {
      support = buildWarpedMaskSupport(
        sourceMask: prototype.detection.mask,
        previous: prototype.detection,
        predicted: predicted,
        preprocess: prototype.preprocess,
        sampleStride: 4
      )
      warpedMaskSupportCache[key] = support
    }

    var intersection = 0
    var union = 0
    var sampleIndex = 0
    var y = 0
    while y < support.height {
      let row = y * support.width
      var x = 0
      while x < support.width {
        let first = support.sampledForeground[sampleIndex]
        sampleIndex += 1
        let second = candidateMask[row + x] > 128
        if first && second { intersection += 1 }
        if first || second { union += 1 }
        x += support.sampleStride
      }
      y += support.sampleStride
    }
    return union == 0 ? 1 : Float32(intersection) / Float32(union)
  }

  private func buildWarpedMaskSupport(
    sourceMask: [UInt8],
    previous: IOSYoloDetection,
    predicted: IOSYoloDetection,
    preprocess: IOSYoloPreprocessResult,
    sampleStride: Int
  ) -> WarpedMaskSupport {
    let proto = IOSYoloPostprocessor.protoSize
    let stride = max(1, sampleStride)
    let previousWidth = max(1, previous.x2 - previous.x1)
    let previousHeight = max(1, previous.y2 - previous.y1)
    let predictedWidth = max(1, predicted.x2 - predicted.x1)
    let predictedHeight = max(1, predicted.y2 - predicted.y1)
    let scaleX = predictedWidth / previousWidth
    let scaleY = predictedHeight / previousHeight
    let previousCenterX = sourceToProtoX((previous.x1 + previous.x2) * 0.5, preprocess: preprocess)
    let previousCenterY = sourceToProtoY((previous.y1 + previous.y2) * 0.5, preprocess: preprocess)
    let predictedCenterX = sourceToProtoX((predicted.x1 + predicted.x2) * 0.5, preprocess: preprocess)
    let predictedCenterY = sourceToProtoY((predicted.y1 + predicted.y2) * 0.5, preprocess: preprocess)
    var sampled: [Bool] = []
    sampled.reserveCapacity(((proto + stride - 1) / stride) * ((proto + stride - 1) / stride))

    var y = 0
    while y < proto {
      let floatY = (Float32(y) - predictedCenterY) / scaleY + previousCenterY
      let y0 = Int(floor(Double(floatY)))
      let y1 = y0 + 1
      let wy1 = min(1, max(0, floatY - Float32(y0)))
      let wy0: Float32 = 1 - wy1
      var x = 0
      while x < proto {
        let floatX = (Float32(x) - predictedCenterX) / scaleX + previousCenterX
        let x0 = Int(floor(Double(floatX)))
        let x1 = x0 + 1
        let wx1 = min(1, max(0, floatX - Float32(x0)))
        let wx0: Float32 = 1 - wx1
        func sample(_ sx: Int, _ sy: Int) -> Float32 {
          guard sx >= 0, sx < proto, sy >= 0, sy < proto else { return 0 }
          return Float32(sourceMask[sy * proto + sx])
        }
        let top = sample(x0, y0) * wx0 + sample(x1, y0) * wx1
        let bottom = sample(x0, y1) * wx0 + sample(x1, y1) * wx1
        sampled.append(top * wy0 + bottom * wy1 > 128)
        x += stride
      }
      y += stride
    }
    return WarpedMaskSupport(
      width: proto,
      height: proto,
      sampleStride: stride,
      sampledForeground: sampled
    )
  }

  private func updatePrototypes(
    detections: [IOSYoloDetection],
    preprocess: IOSYoloPreprocessResult,
    classified: [Int: IOSPrivacySelectionClass],
    hardClassByDetectionIndex: [Int: IOSPrivacySelectionClass],
    similarityCache: inout [SimilarityKey: Float32],
    warpedMaskSupportCache: inout [ObjectIdentifier: WarpedMaskSupport]
  ) {
    var updated = Set<ObjectIdentifier>()
    for selectionClass in [IOSPrivacySelectionClass.selected, .unselected] {
      let old = prototypes.filter { $0.selectionClass == selectionClass }
      let current = classified
        .filter { $0.value == selectionClass }
        .map { $0.key }
        .sorted()
      var matchedCurrent = Set<Int>()

      if !old.isEmpty && !current.isEmpty {
        var candidates: [(prototypeIndex: Int, currentIndex: Int, cost: Float32)] = []
        for row in old.indices {
          for column in current.indices {
            let detectionIndex = current[column]
            let score = similarityForFrame(
              prototype: old[row],
              detectionIndex: detectionIndex,
              detection: detections[detectionIndex],
              detections: detections,
              similarityCache: &similarityCache,
              warpedMaskSupportCache: &warpedMaskSupportCache
            )
            let cost = 1 - score
            if cost <= 0.75 {
              candidates.append((row, column, cost))
            }
          }
        }
        candidates.sort {
          if $0.cost != $1.cost { return $0.cost < $1.cost }
          if $0.prototypeIndex != $1.prototypeIndex { return $0.prototypeIndex < $1.prototypeIndex }
          return $0.currentIndex < $1.currentIndex
        }
        var matchedRows = Set<Int>()
        for candidate in candidates {
          guard !matchedRows.contains(candidate.prototypeIndex),
                !matchedCurrent.contains(current[candidate.currentIndex]) else {
            continue
          }
          let prototype = old[candidate.prototypeIndex]
          let detectionIndex = current[candidate.currentIndex]
          let detection = detections[detectionIndex]
          let oldCenterX = (prototype.detection.x1 + prototype.detection.x2) * 0.5
          let oldCenterY = (prototype.detection.y1 + prototype.detection.y2) * 0.5
          let newCenterX = (detection.x1 + detection.x2) * 0.5
          let newCenterY = (detection.y1 + detection.y2) * 0.5
          prototype.velocityX = prototype.velocityX * 0.45 + (newCenterX - oldCenterX) * 0.55
          prototype.velocityY = prototype.velocityY * 0.45 + (newCenterY - oldCenterY) * 0.55
          prototype.detection = detection
          prototype.preprocess = preprocess
          prototype.misses = 0
          prototype.reliability = hardClassByDetectionIndex[detectionIndex] == selectionClass
            ? 1
            : min(0.92, prototype.reliability + 0.08)
          matchedRows.insert(candidate.prototypeIndex)
          matchedCurrent.insert(detectionIndex)
          updated.insert(ObjectIdentifier(prototype))
        }
      }

      for detectionIndex in current where !matchedCurrent.contains(detectionIndex) {
        let prototype = Prototype(
          selectionClass: selectionClass,
          detection: detections[detectionIndex],
          preprocess: preprocess,
          reliability: hardClassByDetectionIndex[detectionIndex] == selectionClass ? 1 : 0.78
        )
        prototypes.append(prototype)
        updated.insert(ObjectIdentifier(prototype))
      }
    }

    prototypes.removeAll { prototype in
      guard !updated.contains(ObjectIdentifier(prototype)) else { return false }
      let predicted = prototype.predictedDetection()
      prototype.detection = predicted
      prototype.velocityX *= 0.75
      prototype.velocityY *= 0.75
      prototype.misses += 1
      prototype.reliability *= 0.72
      return prototype.misses > maxPrototypeMisses || prototype.reliability < 0.18
    }
  }

  private func advanceMissingPrototypes() {
    prototypes.removeAll { prototype in
      prototype.detection = prototype.predictedDetection()
      prototype.velocityX *= 0.75
      prototype.velocityY *= 0.75
      prototype.misses += 1
      prototype.reliability *= 0.72
      return prototype.misses > maxPrototypeMisses || prototype.reliability < 0.18
    }
  }

  private func bboxIoU(_ first: IOSYoloDetection, _ second: IOSYoloDetection) -> Float32 {
    let left = max(first.x1, second.x1)
    let top = max(first.y1, second.y1)
    let right = min(first.x2, second.x2)
    let bottom = min(first.y2, second.y2)
    let intersection = max(0, right - left) * max(0, bottom - top)
    let firstArea = max(0, first.x2 - first.x1) * max(0, first.y2 - first.y1)
    let secondArea = max(0, second.x2 - second.x1) * max(0, second.y2 - second.y1)
    let union = firstArea + secondArea - intersection
    return union > 0 ? intersection / union : 0
  }

  private func sourceToProtoX(
    _ sourceX: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> Float32 {
    guard preprocess.inputSize > 0 else { return 0 }
    let modelX = sourceX * preprocess.scale + preprocess.padLeft
    return modelX / Float32(preprocess.inputSize) * Float32(IOSYoloPostprocessor.protoSize)
  }

  private func sourceToProtoY(
    _ sourceY: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> Float32 {
    guard preprocess.inputSize > 0 else { return 0 }
    let modelY = sourceY * preprocess.scale + preprocess.padTop
    return modelY / Float32(preprocess.inputSize) * Float32(IOSYoloPostprocessor.protoSize)
  }
}
