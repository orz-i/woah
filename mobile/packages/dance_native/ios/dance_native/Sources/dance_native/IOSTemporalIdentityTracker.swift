import Foundation

enum IOSTrackState: String {
  case active = "ACTIVE"
  case occluded = "OCCLUDED"
  case lost = "LOST"
  case reacquiring = "REACQUIRING"
}

struct IOSTemporalTrackSnapshot {
  let id: Int
  let state: IOSTrackState
  let missedFrames: Int
  let occlusionGraceRemaining: Int
  let observedThisFrame: Bool
  let identityProtected: Bool
  let privacySelected: Bool
  let predictedX1: Float32
  let predictedY1: Float32
  let predictedX2: Float32
  let predictedY2: Float32
  let lastObservedX1: Float32
  let lastObservedY1: Float32
  let lastObservedX2: Float32
  let lastObservedY2: Float32
}

/// iOS Phase 4 temporal identity tracker.
///
/// This deliberately keeps identity protection separate from privacy selection,
/// matching the Android TrackManager contract. Exact association is conservative:
/// ambiguous protected identities are not reassigned to a nearby detection. A
/// selected identity that temporarily loses observations receives a predicted,
/// bbox-backed privacy fallback; if it cannot be resolved safely for the bounded
/// occlusion window the export fails closed instead of silently exposing frames.
final class IOSTemporalIdentityTracker {
  private struct Track {
    let id: Int
    var detection: IOSYoloDetection
    var predictedX1: Float32
    var predictedY1: Float32
    var predictedX2: Float32
    var predictedY2: Float32
    var velocityX: Float32 = 0
    var velocityY: Float32 = 0
    var state: IOSTrackState = .active
    var missedFrames: Int = 0
    var occlusionGraceRemaining: Int = 0
    var age: Int = 1
    var observedThisFrame: Bool = true
    var lastTimestampUs: Int64
  }

  private struct Candidate {
    let trackIndex: Int
    let detectionIndex: Int
    let score: Float32
    let bboxIoU: Float32
    let maskIoU: Float32
  }

  private let metadata: IOSAnalysisMetadata?
  private let identityProtectedIds: Set<Int>
  private let privacyTargetIds: Set<Int>
  private let frameWidth: Int
  private let frameHeight: Int
  private let maxMissedFrames = 15
  private let maxOcclusionFrames = 90
  private let postOcclusionGraceFrames = 10
  private let protectedGroupActiveMinBBoxIoU: Float32 = 0.35
  private let protectedGroupActiveMinMaskIoU: Float32 = 0.20
  private let protectedGroupReacquireMinBBoxIoU: Float32 = 0.45
  private let protectedGroupReacquireMinMaskIoU: Float32 = 0.25
  private let protectedRecoveryMinBBoxIoU: Float32 = 0.50
  private let protectedRecoveryMinMaskIoU: Float32 = 0.45
  private let protectedUnobservedMaxCenterTravelRatio: Float32 = 0.30
  private let protectedUnobservedMinScale: Float32 = 0.82
  private let protectedUnobservedMaxScale: Float32 = 1.18
  private var tracks: [Track] = []
  private var nextTrackId = 0
  private var initialized = false

  init(
    metadata: IOSAnalysisMetadata?,
    fullBodyIds: Set<Int>,
    faceOnlyIds: Set<Int>,
    frameWidth: Int,
    frameHeight: Int
  ) {
    self.metadata = metadata
    self.frameWidth = max(1, frameWidth)
    self.frameHeight = max(1, frameHeight)
    privacyTargetIds = fullBodyIds.union(faceOnlyIds)
    if faceOnlyIds.isEmpty {
      identityProtectedIds = privacyTargetIds
    } else {
      // Android mixed-mode tracking protects every credible analysis identity,
      // while keeping actual privacy membership separate. This prevents a
      // selected FACE_ONLY identity from being destabilized by weaker neighbor
      // association rules.
      let credible = Set((metadata?.persons ?? [])
        .filter { $0.confidence >= 0.60 }
        .map(\.id))
      identityProtectedIds = privacyTargetIds.union(credible)
    }
    nextTrackId = (metadata?.persons.map(\.id).max() ?? -1) + 1
  }

  func update(
    detections: [IOSYoloDetection],
    preprocess: IOSYoloPreprocessResult,
    timestampUs: Int64
  ) throws -> [IOSPreviewPerson] {
    if !initialized {
      initialized = true
      let assigned = IOSPreviewIdentityMatcher.assign(
        detections: detections,
        metadata: metadata,
        frameWidth: frameWidth,
        frameHeight: frameHeight
      )
      tracks = assigned.map { person in
        nextTrackId = max(nextTrackId, person.id + 1)
        return Track(
          id: person.id,
          detection: person.detection,
          predictedX1: person.detection.x1,
          predictedY1: person.detection.y1,
          predictedX2: person.detection.x2,
          predictedY2: person.detection.y2,
          lastTimestampUs: timestampUs
        )
      }
      try ensureInitialPrivacyRoots()
      return tracks.map { IOSPreviewPerson(id: $0.id, detection: $0.detection) }
    }

    predict(timestampUs: timestampUs)
    let assignments = assign(detections: detections)
    var matchedTracks = Set<Int>()
    var matchedDetections = Set<Int>()

    for candidate in assignments {
      guard !matchedTracks.contains(candidate.trackIndex),
            !matchedDetections.contains(candidate.detectionIndex) else {
        continue
      }
      let protected = identityProtectedIds.contains(tracks[candidate.trackIndex].id)
      if protected && isAmbiguousProtectedAssignment(candidate, detections: detections) {
        continue
      }
      observe(
        trackIndex: candidate.trackIndex,
        detection: detections[candidate.detectionIndex],
        timestampUs: timestampUs
      )
      matchedTracks.insert(candidate.trackIndex)
      matchedDetections.insert(candidate.detectionIndex)
    }

    for index in tracks.indices where !matchedTracks.contains(index) {
      markMissed(trackIndex: index)
    }

    for detectionIndex in detections.indices where !matchedDetections.contains(detectionIndex) {
      let detection = detections[detectionIndex]
      tracks.append(Track(
        id: nextTrackId,
        detection: detection,
        predictedX1: detection.x1,
        predictedY1: detection.y1,
        predictedX2: detection.x2,
        predictedY2: detection.y2,
        lastTimestampUs: timestampUs
      ))
      nextTrackId += 1
    }

    tracks.removeAll { track in
      !identityProtectedIds.contains(track.id) && track.missedFrames > maxMissedFrames
    }

    var output: [IOSPreviewPerson] = []
    output.reserveCapacity(tracks.count)
    for track in tracks {
      if track.observedThisFrame {
        output.append(IOSPreviewPerson(id: track.id, detection: track.detection))
        continue
      }
      guard privacyTargetIds.contains(track.id) else { continue }
      if track.missedFrames > maxOcclusionFrames {
        throw PigeonError(
          code: "EXPORT_PRIVACY_UNRESOLVED",
          message: "Selected iOS export identity could not be resolved safely.",
          details: ["personId": track.id, "missedFrames": track.missedFrames]
        )
      }
      let fallback = conservativeFallbackDetection(track: track, preprocess: preprocess)
      if intersectsFrame(fallback) {
        output.append(IOSPreviewPerson(
          id: track.id,
          detection: fallback,
          conservativePrivacyFallback: true
        ))
      }
    }
    return output
  }

  /// Phase 5 Golden Trace observation surface. This is intentionally internal
  /// to the native module and does not change the Flutter/Pigeon API. It lets
  /// deterministic Simulator tests compare iOS identity/privacy lifecycle
  /// decisions with the Android TrackManager reference contract.
  func paritySnapshots() -> [IOSTemporalTrackSnapshot] {
    tracks.map { track in
      IOSTemporalTrackSnapshot(
        id: track.id,
        state: track.state,
        missedFrames: track.missedFrames,
        occlusionGraceRemaining: track.occlusionGraceRemaining,
        observedThisFrame: track.observedThisFrame,
        identityProtected: identityProtectedIds.contains(track.id),
        privacySelected: privacyTargetIds.contains(track.id),
        predictedX1: track.predictedX1,
        predictedY1: track.predictedY1,
        predictedX2: track.predictedX2,
        predictedY2: track.predictedY2,
        lastObservedX1: track.detection.x1,
        lastObservedY1: track.detection.y1,
        lastObservedX2: track.detection.x2,
        lastObservedY2: track.detection.y2
      )
    }.sorted { $0.id < $1.id }
  }

  private func ensureInitialPrivacyRoots() throws {
    let resolved = Set(tracks.map(\.id))
    let unresolved = privacyTargetIds.subtracting(resolved)
    guard unresolved.isEmpty else {
      throw PigeonError(
        code: "EXPORT_PRIVACY_UNRESOLVED",
        message: "Selected iOS export target(s) were not present at the analysis root.",
        details: unresolved.sorted()
      )
    }
  }

  private func predict(timestampUs: Int64) {
    for index in tracks.indices {
      var track = tracks[index]
      track.observedThisFrame = false
      let deltaSeconds = track.lastTimestampUs > 0
        ? min(0.20, max(0.0, Double(timestampUs - track.lastTimestampUs) / 1_000_000.0))
        : (1.0 / 30.0)
      let frameScale = Float32(deltaSeconds * 30.0)
      let dx = track.velocityX * frameScale
      let dy = track.velocityY * frameScale
      track.predictedX1 += dx
      track.predictedX2 += dx
      track.predictedY1 += dy
      track.predictedY2 += dy
      if identityProtectedIds.contains(track.id), track.missedFrames > 0 {
        boundProtectedPredictionAroundLastObservation(track: &track)
      }
      track.lastTimestampUs = timestampUs
      track.age += 1
      tracks[index] = track
    }
  }

  private func assign(detections: [IOSYoloDetection]) -> [Candidate] {
    guard !tracks.isEmpty, !detections.isEmpty else { return [] }
    var candidates: [Candidate] = []
    candidates.reserveCapacity(tracks.count * detections.count)
    for trackIndex in tracks.indices {
      let track = tracks[trackIndex]
      for detectionIndex in detections.indices {
        let detection = detections[detectionIndex]
        let bbox = bboxIoU(track: track, detection: detection)
        let mask = maskIoU(track.detection.mask, detection.mask)
        let motion = motionScore(track: track, detection: detection)
        let score = bbox * 0.45 + mask * 0.35 + motion * 0.20
        let protected = identityProtectedIds.contains(track.id)
        let threshold: Float32 = protected ? 0.22 : 0.18
        let identityEvidence = protected
          ? protectedIdentityEvidenceSufficient(state: track.state, bboxIoU: bbox, maskIoU: mask)
          : (bbox >= 0.08 || mask >= 0.06)
        let recoveryGeometry = !protected
          || track.state != .lost
          || protectedLostRecoveryGeometrySufficient(track: track, detection: detection, bboxIoU: bbox)
        if score >= threshold && identityEvidence && recoveryGeometry {
          candidates.append(Candidate(
            trackIndex: trackIndex,
            detectionIndex: detectionIndex,
            score: score,
            bboxIoU: bbox,
            maskIoU: mask
          ))
        }
      }
    }
    candidates.sort {
      if $0.score != $1.score { return $0.score > $1.score }
      if $0.trackIndex != $1.trackIndex { return $0.trackIndex < $1.trackIndex }
      return $0.detectionIndex < $1.detectionIndex
    }
    return candidates
  }

  private func isAmbiguousProtectedAssignment(
    _ candidate: Candidate,
    detections: [IOSYoloDetection]
  ) -> Bool {
    let margin: Float32 = 0.05
    let track = tracks[candidate.trackIndex]
    let competingDetectionScore = detections.indices
      .filter { $0 != candidate.detectionIndex }
      .map { detectionIndex -> Float32 in
        let detection = detections[detectionIndex]
        return bboxIoU(track: track, detection: detection) * 0.45
          + maskIoU(track.detection.mask, detection.mask) * 0.35
          + motionScore(track: track, detection: detection) * 0.20
      }
      .max() ?? -1
    if competingDetectionScore >= candidate.score - margin {
      return true
    }

    let detection = detections[candidate.detectionIndex]
    let competingTrackScore = tracks.indices
      .filter { $0 != candidate.trackIndex }
      .map { trackIndex -> Float32 in
        let other = tracks[trackIndex]
        return bboxIoU(track: other, detection: detection) * 0.45
          + maskIoU(other.detection.mask, detection.mask) * 0.35
          + motionScore(track: other, detection: detection) * 0.20
      }
      .max() ?? -1
    return competingTrackScore >= candidate.score - margin
  }

  private func observe(
    trackIndex: Int,
    detection: IOSYoloDetection,
    timestampUs: Int64
  ) {
    var track = tracks[trackIndex]
    let oldCenterX = (track.detection.x1 + track.detection.x2) * 0.5
    let oldCenterY = (track.detection.y1 + track.detection.y2) * 0.5
    let newCenterX = (detection.x1 + detection.x2) * 0.5
    let newCenterY = (detection.y1 + detection.y2) * 0.5
    let observedDx = newCenterX - oldCenterX
    let observedDy = newCenterY - oldCenterY
    track.velocityX = track.velocityX * 0.55 + observedDx * 0.45
    track.velocityY = track.velocityY * 0.55 + observedDy * 0.45
    track.detection = detection
    track.predictedX1 = detection.x1
    track.predictedY1 = detection.y1
    track.predictedX2 = detection.x2
    track.predictedY2 = detection.y2
    track.observedThisFrame = true
    // Android TrackManager exposes a successfully re-observed identity as
    // ACTIVE in that same frame. REACQUIRING is reserved for the interval in
    // which the identity is still unresolved, not the first fresh observation.
    track.state = .active
    track.missedFrames = 0
    track.occlusionGraceRemaining = 0
    track.lastTimestampUs = timestampUs
    tracks[trackIndex] = track
  }

  private func markMissed(trackIndex: Int) {
    var track = tracks[trackIndex]
    track.missedFrames += 1
    let wasLost = track.state == .lost
    let overlapsObservedTrack = tracks.indices.contains { otherIndex in
      guard otherIndex != trackIndex, tracks[otherIndex].observedThisFrame else { return false }
      return bboxOverlapRatio(track: track, other: tracks[otherIndex]) >= 0.30
    }
    if !wasLost && overlapsObservedTrack && track.missedFrames <= maxOcclusionFrames {
      track.state = .occluded
      track.occlusionGraceRemaining = postOcclusionGraceFrames
    } else if !wasLost && track.occlusionGraceRemaining > 0 {
      track.state = .reacquiring
      track.occlusionGraceRemaining -= 1
    } else if wasLost {
      // A LOST protected identity must not become OCCLUDED again merely because
      // an unrelated fresh track happens to overlap its bounded prediction.
      // Android keeps that case in the dedicated strict LOST recovery path.
      track.state = .lost
      track.occlusionGraceRemaining = 0
    } else if track.missedFrames <= maxMissedFrames {
      track.state = .reacquiring
    } else {
      track.state = .lost
      track.occlusionGraceRemaining = 0
    }
    tracks[trackIndex] = track
  }

  private func protectedIdentityEvidenceSufficient(
    state: IOSTrackState,
    bboxIoU: Float32,
    maskIoU: Float32
  ) -> Bool {
    switch state {
    case .lost:
      return bboxIoU >= protectedRecoveryMinBBoxIoU
        || maskIoU >= protectedRecoveryMinMaskIoU
    case .occluded, .reacquiring:
      return bboxIoU >= protectedGroupReacquireMinBBoxIoU
        || maskIoU >= protectedGroupReacquireMinMaskIoU
    case .active:
      return bboxIoU >= protectedGroupActiveMinBBoxIoU
        || maskIoU >= protectedGroupActiveMinMaskIoU
    }
  }

  private func protectedLostRecoveryGeometrySufficient(
    track: Track,
    detection: IOSYoloDetection,
    bboxIoU: Float32
  ) -> Bool {
    let predictedCenterX = (track.predictedX1 + track.predictedX2) * 0.5
    let predictedCenterY = (track.predictedY1 + track.predictedY2) * 0.5
    let observedCenterX = (track.detection.x1 + track.detection.x2) * 0.5
    let observedCenterY = (track.detection.y1 + track.detection.y2) * 0.5
    let detectionCenterX = (detection.x1 + detection.x2) * 0.5
    let detectionCenterY = (detection.y1 + detection.y2) * 0.5

    let dx = predictedCenterX - detectionCenterX
    let dy = predictedCenterY - detectionCenterY
    let distance = sqrt(dx * dx + dy * dy)
    let predictedWidth = max(1, track.predictedX2 - track.predictedX1)
    let predictedHeight = max(1, track.predictedY2 - track.predictedY1)
    let detectionWidth = max(1, detection.x2 - detection.x1)
    let detectionHeight = max(1, detection.y2 - detection.y1)
    let referenceDimension = max(
      max(predictedWidth, predictedHeight),
      max(detectionWidth, detectionHeight)
    )

    let predictionDx = predictedCenterX - observedCenterX
    let predictionDy = predictedCenterY - observedCenterY
    let predictionTravel = sqrt(predictionDx * predictionDx + predictionDy * predictionDy)
    let candidateDx = detectionCenterX - observedCenterX
    let candidateDy = detectionCenterY - observedCenterY
    let predictionProgress: Float32
    if predictionTravel > 0.0001 {
      predictionProgress = (
        candidateDx * predictionDx + candidateDy * predictionDy
      ) / (predictionTravel * predictionTravel)
    } else {
      predictionProgress = 1
    }

    let hasMeaningfulPrediction = predictionTravel >= referenceDimension * 0.10
    let motionConsistent = !hasMeaningfulPrediction
      || bboxIoU > 0.05
      || predictionProgress >= 0.25
    let nearby = bboxIoU > 0.05 || distance < referenceDimension * 0.80
    return nearby && motionConsistent
  }

  private func boundProtectedPredictionAroundLastObservation(track: inout Track) {
    let anchorWidth = max(1, track.detection.x2 - track.detection.x1)
    let anchorHeight = max(1, track.detection.y2 - track.detection.y1)
    let anchorCenterX = (track.detection.x1 + track.detection.x2) * 0.5
    let anchorCenterY = (track.detection.y1 + track.detection.y2) * 0.5
    let predictedCenterX = (track.predictedX1 + track.predictedX2) * 0.5
    let predictedCenterY = (track.predictedY1 + track.predictedY2) * 0.5
    let dx = predictedCenterX - anchorCenterX
    let dy = predictedCenterY - anchorCenterY
    let travel = sqrt(dx * dx + dy * dy)
    let maxTravel = max(anchorWidth, anchorHeight) * protectedUnobservedMaxCenterTravelRatio
    let travelScale = travel > maxTravel && travel > 0.0001 ? maxTravel / travel : 1
    let centerX = anchorCenterX + dx * travelScale
    let centerY = anchorCenterY + dy * travelScale
    let rawPredictedWidth = max(1, track.predictedX2 - track.predictedX1)
    let rawPredictedHeight = max(1, track.predictedY2 - track.predictedY1)
    let predictedWidth = min(
      anchorWidth * protectedUnobservedMaxScale,
      max(anchorWidth * protectedUnobservedMinScale, rawPredictedWidth)
    )
    let predictedHeight = min(
      anchorHeight * protectedUnobservedMaxScale,
      max(anchorHeight * protectedUnobservedMinScale, rawPredictedHeight)
    )
    track.predictedX1 = centerX - predictedWidth * 0.5
    track.predictedY1 = centerY - predictedHeight * 0.5
    track.predictedX2 = centerX + predictedWidth * 0.5
    track.predictedY2 = centerY + predictedHeight * 0.5
  }

  private func conservativeFallbackDetection(
    track: Track,
    preprocess: IOSYoloPreprocessResult
  ) -> IOSYoloDetection {
    let width = max(8, track.predictedX2 - track.predictedX1)
    let height = max(8, track.predictedY2 - track.predictedY1)
    let growth = min(0.18, Float32(track.missedFrames) * 0.006)
    let xPad = width * growth
    let yPad = height * growth
    let x1 = track.predictedX1 - xPad
    let y1 = track.predictedY1 - yPad
    let x2 = track.predictedX2 + xPad
    let y2 = track.predictedY2 + yPad
    let mask = bboxMask(
      x1: x1,
      y1: y1,
      x2: x2,
      y2: y2,
      preprocess: preprocess
    )
    return IOSYoloDetection(
      x1: x1,
      y1: y1,
      x2: x2,
      y2: y2,
      confidence: track.detection.confidence,
      mask: mask
    )
  }

  private func bboxMask(
    x1: Float32,
    y1: Float32,
    x2: Float32,
    y2: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> [UInt8] {
    let proto = IOSYoloPostprocessor.protoSize
    var output = [UInt8](repeating: 0, count: proto * proto)
    let modelX1 = x1 * preprocess.scale + preprocess.padLeft
    let modelY1 = y1 * preprocess.scale + preprocess.padTop
    let modelX2 = x2 * preprocess.scale + preprocess.padLeft
    let modelY2 = y2 * preprocess.scale + preprocess.padTop
    let factor = Float32(proto) / Float32(preprocess.inputSize)
    let px1 = max(0, min(proto - 1, Int(floor(Double(modelX1 * factor)))))
    let py1 = max(0, min(proto - 1, Int(floor(Double(modelY1 * factor)))))
    let px2 = max(px1 + 1, min(proto, Int(ceil(Double(modelX2 * factor)))))
    let py2 = max(py1 + 1, min(proto, Int(ceil(Double(modelY2 * factor)))))
    guard px1 < px2, py1 < py2 else { return output }
    for y in py1..<py2 {
      let row = y * proto
      for x in px1..<px2 {
        output[row + x] = 255
      }
    }
    return output
  }

  private func bboxIoU(track: Track, detection: IOSYoloDetection) -> Float32 {
    rectIoU(
      track.predictedX1,
      track.predictedY1,
      track.predictedX2,
      track.predictedY2,
      detection.x1,
      detection.y1,
      detection.x2,
      detection.y2
    )
  }

  private func bboxOverlapRatio(track: Track, other: Track) -> Float32 {
    let left = max(track.predictedX1, other.predictedX1)
    let top = max(track.predictedY1, other.predictedY1)
    let right = min(track.predictedX2, other.predictedX2)
    let bottom = min(track.predictedY2, other.predictedY2)
    let intersection = max(0, right - left) * max(0, bottom - top)
    let firstArea = max(1, track.predictedX2 - track.predictedX1)
      * max(1, track.predictedY2 - track.predictedY1)
    let secondArea = max(1, other.predictedX2 - other.predictedX1)
      * max(1, other.predictedY2 - other.predictedY1)
    return intersection / min(firstArea, secondArea)
  }

  private func rectIoU(
    _ ax1: Float32,
    _ ay1: Float32,
    _ ax2: Float32,
    _ ay2: Float32,
    _ bx1: Float32,
    _ by1: Float32,
    _ bx2: Float32,
    _ by2: Float32
  ) -> Float32 {
    let left = max(ax1, bx1)
    let top = max(ay1, by1)
    let right = min(ax2, bx2)
    let bottom = min(ay2, by2)
    let intersection = max(0, right - left) * max(0, bottom - top)
    let areaA = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    let areaB = max(0, bx2 - bx1) * max(0, by2 - by1)
    let union = areaA + areaB - intersection
    return union > 0 ? intersection / union : 0
  }

  private func maskIoU(_ first: [UInt8], _ second: [UInt8]) -> Float32 {
    guard first.count == second.count, !first.isEmpty else { return 0 }
    var intersection = 0
    var union = 0
    for index in first.indices {
      let a = first[index] >= 39
      let b = second[index] >= 39
      if a && b { intersection += 1 }
      if a || b { union += 1 }
    }
    return union > 0 ? Float32(intersection) / Float32(union) : 0
  }

  private func motionScore(track: Track, detection: IOSYoloDetection) -> Float32 {
    let predictedCenterX = (track.predictedX1 + track.predictedX2) * 0.5
    let predictedCenterY = (track.predictedY1 + track.predictedY2) * 0.5
    let detectionCenterX = (detection.x1 + detection.x2) * 0.5
    let detectionCenterY = (detection.y1 + detection.y2) * 0.5
    let dx = detectionCenterX - predictedCenterX
    let dy = detectionCenterY - predictedCenterY
    let distance = sqrt(dx * dx + dy * dy)
    let diagonal = max(
      16,
      sqrt(
        max(1, track.predictedX2 - track.predictedX1)
          * max(1, track.predictedX2 - track.predictedX1)
          + max(1, track.predictedY2 - track.predictedY1)
          * max(1, track.predictedY2 - track.predictedY1)
      )
    )
    return max(0, 1 - distance / (diagonal * 1.5))
  }

  private func intersectsFrame(_ detection: IOSYoloDetection) -> Bool {
    detection.x2 > 0
      && detection.y2 > 0
      && detection.x1 < Float32(frameWidth)
      && detection.y1 < Float32(frameHeight)
  }
}
