import CoreGraphics
import Foundation
import Vision

enum IOSFacePrivacyRegionSource: String {
  case detectedFace = "DETECTED_FACE"
  case predictedFace = "PREDICTED_FACE"
  case yoloHeadFallback = "YOLO_HEAD_FALLBACK"
}

struct IOSFacePrivacyEllipse {
  let centerX: Float32
  let centerY: Float32
  let radiusX: Float32
  let radiusY: Float32
  let source: IOSFacePrivacyRegionSource
}

struct IOSFaceCandidate {
  let x1: Float32
  let y1: Float32
  let x2: Float32
  let y2: Float32
  let confidence: Float32
}

protocol IOSFaceLocating {
  func locateFaces(in image: CGImage) throws -> [IOSFaceCandidate]
}

/// Apple-only FACE_ONLY localization backend. It never assigns person identity;
/// IOSFacePrivacyTemporalResolver accepts a Vision rectangle only after it is
/// attached conservatively to a YOLO-owned person track.
final class IOSVisionFaceLocator: IOSFaceLocating {
  func locateFaces(in image: CGImage) throws -> [IOSFaceCandidate] {
    let request = VNDetectFaceRectanglesRequest()
    let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
    try handler.perform([request])
    let width = Float32(image.width)
    let height = Float32(image.height)
    return (request.results ?? []).compactMap { observation in
      let box = observation.boundingBox
      let x1 = Float32(box.minX) * width
      let x2 = Float32(box.maxX) * width
      // Vision normalized coordinates use a bottom-left origin while the
      // renderer/tracker source space uses top-left coordinates.
      let y1 = Float32(1.0 - box.maxY) * height
      let y2 = Float32(1.0 - box.minY) * height
      guard x2 > x1, y2 > y1 else { return nil }
      return IOSFaceCandidate(
        x1: x1,
        y1: y1,
        x2: x2,
        y2: y2,
        confidence: observation.confidence
      )
    }
  }
}

enum IOSFacePrivacyGeometry {
  // Mirrors Android FacePrivacyRegionResolver. The face detector refines
  // localization only; fallback is always derived from the current YOLO body.
  private static let detectedRadiusXFactor: Float32 = 0.66
  private static let detectedRadiusYFactor: Float32 = 0.74
  private static let detectedCenterYShift: Float32 = -0.04
  private static let fallbackCenterYRatio: Float32 = 0.14
  private static let fallbackRadiusXFromWidth: Float32 = 0.22
  private static let fallbackRadiusXFromHeight: Float32 = 0.045
  private static let fallbackRadiusYFromWidth: Float32 = 0.26
  private static let fallbackRadiusYFromHeight: Float32 = 0.060

  static func detectedEllipse(_ face: IOSFaceCandidate) -> IOSFacePrivacyEllipse? {
    let width = face.x2 - face.x1
    let height = face.y2 - face.y1
    guard width > 1, height > 1 else { return nil }
    return IOSFacePrivacyEllipse(
      centerX: (face.x1 + face.x2) * 0.5,
      centerY: (face.y1 + face.y2) * 0.5 + height * detectedCenterYShift,
      radiusX: width * detectedRadiusXFactor,
      radiusY: height * detectedRadiusYFactor,
      source: .detectedFace
    )
  }

  static func fallbackEllipse(_ detection: IOSYoloDetection) -> IOSFacePrivacyEllipse? {
    let width = detection.x2 - detection.x1
    let height = detection.y2 - detection.y1
    guard width > 1, height > 1 else { return nil }
    return IOSFacePrivacyEllipse(
      centerX: (detection.x1 + detection.x2) * 0.5,
      centerY: detection.y1 + height * fallbackCenterYRatio,
      radiusX: max(
        width * fallbackRadiusXFromWidth,
        height * fallbackRadiusXFromHeight
      ),
      radiusY: max(
        width * fallbackRadiusYFromWidth,
        height * fallbackRadiusYFromHeight
      ),
      source: .yoloHeadFallback
    )
  }
}

/// Privacy-first FACE_ONLY resolver for Phase 5C.
///
/// - Person identity stays owned by YOLO + IOSTemporalIdentityTracker.
/// - A face candidate is accepted only when its geometry is a clear one-to-one
///   match to an observed YOLO person.
/// - Locator failure, ambiguity, or an unobserved predicted person always falls
///   back to the current YOLO-owned head geometry instead of removing privacy.
/// - The temporal stabilizer mirrors Android's trusted-size and residual-motion
///   bounds so detector/fallback transitions do not create large sticker jumps.
final class IOSFacePrivacyTemporalResolver {
  private struct MatchCandidate {
    let personId: Int
    let faceIndex: Int
    let score: Float32
  }

  private struct State {
    let output: IOSFacePrivacyEllipse
    let detectedRadiusX: Float32?
    let detectedRadiusY: Float32?
    let detectedPersonWidth: Float32?
    let detectedPersonHeight: Float32?
    let personDetection: IOSYoloDetection
    let personObservedThisFrame: Bool
    let lastTimestampUs: Int64
  }

  private let locator: IOSFaceLocating
  private let lock = NSLock()
  private var stateByTrackId: [Int: State] = [:]

  init(locator: IOSFaceLocating = IOSVisionFaceLocator()) {
    self.locator = locator
  }

  func reset() {
    lock.lock()
    stateByTrackId.removeAll()
    lock.unlock()
  }

  func resolve(
    image: CGImage,
    persons: [IOSPreviewPerson],
    faceOnlyIds: Set<Int>,
    timestampUs: Int64
  ) -> [Int: IOSFacePrivacyEllipse] {
    guard !faceOnlyIds.isEmpty else {
      reset()
      return [:]
    }

    let selectedPersons = persons.filter { faceOnlyIds.contains($0.id) }
    guard !selectedPersons.isEmpty else {
      lock.lock()
      stateByTrackId = stateByTrackId.filter { faceOnlyIds.contains($0.key) }
      lock.unlock()
      return [:]
    }

    // Detector failure only reduces localization precision. It is never allowed
    // to remove privacy or to create a new identity root.
    let faces = (try? locator.locateFaces(in: image)) ?? []
    let assignments = associate(
      faces: faces,
      // Every observed YOLO person participates in ownership competition even
      // when it is not privacy-selected. Otherwise an unselected neighbor's
      // face could be incorrectly consumed as the selected target's face.
      persons: persons.filter { !$0.conservativePrivacyFallback }
    )

    lock.lock()
    defer { lock.unlock() }
    stateByTrackId = stateByTrackId.filter { faceOnlyIds.contains($0.key) }

    var regions: [Int: IOSFacePrivacyEllipse] = [:]
    for person in selectedPersons {
      let raw: IOSFacePrivacyEllipse?
      if !person.conservativePrivacyFallback,
         let faceIndex = assignments[person.id],
         faces.indices.contains(faceIndex) {
        raw = IOSFacePrivacyGeometry.detectedEllipse(faces[faceIndex])
          ?? IOSFacePrivacyGeometry.fallbackEllipse(person.detection)
      } else {
        raw = IOSFacePrivacyGeometry.fallbackEllipse(person.detection)
      }
      guard let raw else { continue }
      regions[person.id] = stabilize(
        trackId: person.id,
        rawRegion: raw,
        personDetection: person.detection,
        timestampUs: timestampUs,
        personObservedThisFrame: !person.conservativePrivacyFallback
      )
    }
    return regions
  }

  private func associate(
    faces: [IOSFaceCandidate],
    persons: [IOSPreviewPerson]
  ) -> [Int: Int] {
    guard !faces.isEmpty, !persons.isEmpty else { return [:] }
    let ambiguityMargin: Float32 = 0.08
    var candidates: [MatchCandidate] = []
    for person in persons {
      for faceIndex in faces.indices {
        if let score = associationScore(face: faces[faceIndex], person: person.detection) {
          candidates.append(MatchCandidate(
            personId: person.id,
            faceIndex: faceIndex,
            score: score
          ))
        }
      }
    }

    var accepted: [Int: Int] = [:]
    for person in persons {
      let options = candidates
        .filter { $0.personId == person.id }
        .sorted { $0.score > $1.score }
      guard let best = options.first else { continue }
      if options.count > 1 && best.score - options[1].score < ambiguityMargin {
        continue
      }
      let competingPersons = candidates
        .filter { $0.faceIndex == best.faceIndex && $0.personId != person.id }
        .sorted { $0.score > $1.score }
      if let competitor = competingPersons.first,
         best.score - competitor.score < ambiguityMargin {
        continue
      }
      accepted[person.id] = best.faceIndex
    }
    return accepted
  }

  private func associationScore(
    face: IOSFaceCandidate,
    person: IOSYoloDetection
  ) -> Float32? {
    let faceWidth = face.x2 - face.x1
    let faceHeight = face.y2 - face.y1
    let personWidth = person.x2 - person.x1
    let personHeight = person.y2 - person.y1
    guard faceWidth > 1, faceHeight > 1, personWidth > 1, personHeight > 1 else {
      return nil
    }
    let faceWidthRatio = faceWidth / personWidth
    let faceHeightRatio = faceHeight / personHeight
    guard faceWidthRatio >= 0.06, faceWidthRatio <= 0.85,
          faceHeightRatio >= 0.035, faceHeightRatio <= 0.45 else {
      return nil
    }

    let intersectionLeft = max(face.x1, person.x1)
    let intersectionTop = max(face.y1, person.y1)
    let intersectionRight = min(face.x2, person.x2)
    let intersectionBottom = min(face.y2, person.y2)
    let intersection = max(0, intersectionRight - intersectionLeft)
      * max(0, intersectionBottom - intersectionTop)
    let faceArea = faceWidth * faceHeight
    let containment = intersection / max(1, faceArea)
    guard containment >= 0.80 else { return nil }

    let faceCenterX = (face.x1 + face.x2) * 0.5
    let faceCenterY = (face.y1 + face.y2) * 0.5
    guard faceCenterY <= person.y1 + personHeight * 0.62 else { return nil }
    let expectedHeadX = (person.x1 + person.x2) * 0.5
    let expectedHeadY = person.y1 + personHeight * 0.18
    let dx = faceCenterX - expectedHeadX
    let dy = faceCenterY - expectedHeadY
    let distance = sqrt(dx * dx + dy * dy)
    let reference = max(12, max(personWidth, personHeight * 0.35))
    let proximity = max(0, 1 - distance / (reference * 1.25))
    guard proximity > 0 else { return nil }
    return containment * 0.45
      + proximity * 0.45
      + max(0, min(1, face.confidence)) * 0.10
  }

  private func stabilize(
    trackId: Int,
    rawRegion: IOSFacePrivacyEllipse,
    personDetection: IOSYoloDetection,
    timestampUs: Int64,
    personObservedThisFrame: Bool
  ) -> IOSFacePrivacyEllipse {
    let personWidth = max(1, personDetection.x2 - personDetection.x1)
    let personHeight = max(1, personDetection.y2 - personDetection.y1)
    let previous = stateByTrackId[trackId]
    let detectedRadiusX = updatedReference(
      previous?.detectedRadiusX,
      rawRegion.source == .detectedFace ? rawRegion.radiusX : nil
    )
    let detectedRadiusY = updatedReference(
      previous?.detectedRadiusY,
      rawRegion.source == .detectedFace ? rawRegion.radiusY : nil
    )
    let detectedPersonWidth = updatedReference(
      previous?.detectedPersonWidth,
      rawRegion.source == .detectedFace ? personWidth : nil
    )
    let detectedPersonHeight = updatedReference(
      previous?.detectedPersonHeight,
      rawRegion.source == .detectedFace ? personHeight : nil
    )

    var target = rawRegion
    if rawRegion.source == .yoloHeadFallback,
       let detectedRadiusX, let detectedRadiusY,
       let detectedPersonWidth, let detectedPersonHeight {
      let widthRatio = max(0.1, personWidth / max(1, detectedPersonWidth))
      let heightRatio = max(0.1, personHeight / max(1, detectedPersonHeight))
      let boundedScale = min(
        fallbackMaxTrustedScale,
        max(fallbackMinTrustedScale, sqrt(widthRatio * heightRatio))
      )
      let referenceRadiusX = detectedRadiusX * boundedScale * fallbackReferenceExpansion
      let referenceRadiusY = detectedRadiusY * boundedScale * fallbackReferenceExpansion
      target = IOSFacePrivacyEllipse(
        centerX: rawRegion.centerX,
        centerY: rawRegion.centerY,
        radiusX: min(
          rawRegion.radiusX,
          max(referenceRadiusX, detectedRadiusX * fallbackMinTrustedExpansion)
        ),
        radiusY: min(
          rawRegion.radiusY,
          max(referenceRadiusY, detectedRadiusY * fallbackMinTrustedExpansion)
        ),
        source: rawRegion.source
      )
    }

    let output: IOSFacePrivacyEllipse
    if previous == nil || timestampUs < previous!.lastTimestampUs {
      output = target
    } else {
      let prior = previous!
      let dtSeconds = min(
        maxDtSeconds,
        max(minDtSeconds, Double(timestampUs - prior.lastTimestampUs) / 1_000_000.0)
      )
      let sizeTau: Double
      switch target.source {
      case .detectedFace:
        sizeTau = (target.radiusX < prior.output.radiusX || target.radiusY < prior.output.radiusY)
          ? detectedShrinkTimeConstantSeconds
          : detectedGrowTimeConstantSeconds
      case .predictedFace:
        sizeTau = predictedSizeTimeConstantSeconds
      case .yoloHeadFallback:
        sizeTau = fallbackSizeTimeConstantSeconds
      }
      let sizeAlpha = Float32(1.0 - exp(-dtSeconds / sizeTau))
      let smoothedRadiusX = lerp(prior.output.radiusX, target.radiusX, sizeAlpha)
      let smoothedRadiusY = lerp(prior.output.radiusY, target.radiusY, sizeAlpha)

      let rawDtSeconds = max(0, Double(timestampUs - prior.lastTimestampUs) / 1_000_000.0)
      let priorPersonCenterX = (prior.personDetection.x1 + prior.personDetection.x2) * 0.5
      let priorPersonCenterY = (prior.personDetection.y1 + prior.personDetection.y2) * 0.5
      let personCenterX = (personDetection.x1 + personDetection.x2) * 0.5
      let personCenterY = (personDetection.y1 + personDetection.y2) * 0.5
      let rawPersonDx = personCenterX - priorPersonCenterX
      let rawPersonDy = personCenterY - priorPersonCenterY
      let rawPersonStep = sqrt(rawPersonDx * rawPersonDx + rawPersonDy * rawPersonDy)
      let referenceRadius = max(
        max(prior.output.radiusX, prior.output.radiusY),
        max(target.radiusX, target.radiusY)
      )
      let trustWholePersonStep = personObservedThisFrame && prior.personObservedThisFrame
      let maxUnobservedPersonStep = max(
        positionMinUnobservedPersonStepPx,
        referenceRadius * positionMaxUnobservedPersonRadiusStep
      )
      let personScale: Float32
      if !trustWholePersonStep,
         rawDtSeconds <= positionGateMaxDtSeconds,
         rawPersonStep > maxUnobservedPersonStep,
         rawPersonStep > 0.001 {
        personScale = maxUnobservedPersonStep / rawPersonStep
      } else {
        personScale = 1
      }
      let expectedCenterX = prior.output.centerX + rawPersonDx * personScale
      let expectedCenterY = prior.output.centerY + rawPersonDy * personScale
      let residualDx = target.centerX - expectedCenterX
      let residualDy = target.centerY - expectedCenterY
      let residualDistance = sqrt(residualDx * residualDx + residualDy * residualDy)
      let maxResidualStep = max(
        positionMinResidualStepPx,
        referenceRadius * positionMaxRadiusStep
      )
      let clampPosition = rawDtSeconds <= positionGateMaxDtSeconds
        && residualDistance > maxResidualStep
        && residualDistance > 0.001
      let centerScale = clampPosition ? maxResidualStep / residualDistance : 1

      output = IOSFacePrivacyEllipse(
        centerX: clampPosition ? expectedCenterX + residualDx * centerScale : target.centerX,
        centerY: clampPosition ? expectedCenterY + residualDy * centerScale : target.centerY,
        radiusX: max(smoothedRadiusX, target.radiusX * privacyTargetFloor),
        radiusY: max(smoothedRadiusY, target.radiusY * privacyTargetFloor),
        source: target.source
      )
    }

    stateByTrackId[trackId] = State(
      output: output,
      detectedRadiusX: detectedRadiusX,
      detectedRadiusY: detectedRadiusY,
      detectedPersonWidth: detectedPersonWidth,
      detectedPersonHeight: detectedPersonHeight,
      personDetection: personDetection,
      personObservedThisFrame: personObservedThisFrame,
      lastTimestampUs: timestampUs
    )
    return output
  }

  private func updatedReference(_ previous: Float32?, _ observed: Float32?) -> Float32? {
    guard let observed, observed.isFinite, observed > 0 else { return previous }
    guard let previous else { return observed }
    return lerp(previous, observed, detectedReferenceAlpha)
  }

  private func lerp(_ first: Float32, _ second: Float32, _ alpha: Float32) -> Float32 {
    first + (second - first) * alpha
  }

  private let fallbackReferenceExpansion: Float32 = 1.24
  private let fallbackMinTrustedExpansion: Float32 = 1.10
  private let fallbackMinTrustedScale: Float32 = 0.90
  private let fallbackMaxTrustedScale: Float32 = 1.12
  private let detectedReferenceAlpha: Float32 = 0.25
  private let privacyTargetFloor: Float32 = 0.90
  private let positionMinResidualStepPx: Float32 = 10
  private let positionMaxRadiusStep: Float32 = 0.80
  private let positionMinUnobservedPersonStepPx: Float32 = 12
  private let positionMaxUnobservedPersonRadiusStep: Float32 = 0.65
  private let positionGateMaxDtSeconds = 0.10
  private let minDtSeconds = 1.0 / 120.0
  private let maxDtSeconds = 0.25
  private let detectedGrowTimeConstantSeconds = 0.075
  private let detectedShrinkTimeConstantSeconds = 0.24
  private let predictedSizeTimeConstantSeconds = 0.15
  private let fallbackSizeTimeConstantSeconds = 0.18
}
