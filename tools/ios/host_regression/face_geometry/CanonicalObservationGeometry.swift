// Test-only native implementation captured before its Rust migration.
import Foundation
import CoreGraphics
enum CanonicalObservationGeometry {
  static func currentMaskSupport(_ region: IOSFacePrivacyEllipse, person: IOSYoloDetection,
                                 preprocess: IOSYoloPreprocessResult) -> (distinctPixels: Int, samples: Int) {
    let size = IOSYoloPostprocessor.protoSize
    guard person.mask.count == size * size, preprocess.scale > 0, preprocess.inputSize > 0 else { return (0, 0) }
    var supported = 0
    var pixels = Set<Int>()
    for y in 0..<4 {
      for x in 0..<4 {
        let sx = region.centerX + (Float32(x) - 1.5) * 0.15 * region.radiusX
        let sy = region.centerY + (Float32(y) - 1.5) * 0.15 * region.radiusY
        let px = Int(floor((sx * preprocess.scale + preprocess.padLeft) / Float32(preprocess.inputSize) * Float32(size)))
        let py = Int(floor((sy * preprocess.scale + preprocess.padTop) / Float32(preprocess.inputSize) * Float32(size)))
        guard px >= 0, px < size, py >= 0, py < size else { continue }
        let index = py * size + px
        pixels.insert(index)
        if person.mask[index] >= 128 { supported += 1 }
      }
    }
    return (pixels.count, supported)
  }

  static func upperBodyFaceSearchRegion(_ d: IOSYoloDetection) -> CGRect {
    // Raised arms move the bbox top far above the face. A smaller upper-body
    // view restores face detail without assuming topmost body pixels are a head.
    let width = max(1, d.x2 - d.x1), height = max(1, d.y2 - d.y1)
    let side = max(width * 1.10, height * 0.45)
    return CGRect(x: Double((d.x1 + d.x2 - side) * 0.5), y: Double(d.y1),
                  width: Double(side), height: Double(side))
  }

  static func mergeFaceObservations(_ primary: [IOSFaceCandidate], detail: [IOSFaceCandidate],
                                    ownerOf: ((IOSFaceCandidate) -> Int?)? = nil) -> [IOSFaceCandidate] {
    var result = primary
    var views = primary.map { Set([$0.observationView]) }
    var owners = primary.map { ownerOf?($0) }
    for face in detail {
      let owner = ownerOf?(face)
      let duplicate = result.indices.first { index in
        let other = result[index]
        guard !views[index].contains(face.observationView) else { return false }
        if ownerOf != nil && (owner == nil || owners[index] != owner) { return false }
        let intersection = max(0, min(face.x2, other.x2) - max(face.x1, other.x1))
          * max(0, min(face.y2, other.y2) - max(face.y1, other.y1))
        let a = max(1, (face.x2-face.x1)*(face.y2-face.y1))
        let b = max(1, (other.x2-other.x1)*(other.y2-other.y1))
        let dx = (face.x1+face.x2-other.x1-other.x2) * 0.5
        let dy = (face.y1+face.y2-other.y1-other.y2) * 0.5
        let span = min(max(face.x2-face.x1, face.y2-face.y1), max(other.x2-other.x1, other.y2-other.y1))
        return intersection / max(1, a+b-intersection) >= 0.20 && dx*dx+dy*dy <= span*span*0.25
      }
      if let index = duplicate {
        views[index].insert(face.observationView)
        let other = result[index]
        // Preserve the complete observation; a smaller ROI feature box must
        // never shrink an already observed face or create a second hero face.
        if (face.x2-face.x1)*(face.y2-face.y1) > (other.x2-other.x1)*(other.y2-other.y1) {
          result[index] = face
        }
      } else {
        result.append(face); views.append([face.observationView]); owners.append(owner)
      }
    }
    return result
  }

  static func associationScore(
    face: IOSFaceCandidate,
    person: IOSYoloDetection,
    preprocess: IOSYoloPreprocessResult? = nil,
    requireMaskSupport: Bool = false
  ) -> Float32? {
    if requireMaskSupport {
      guard let preprocess, IOSFacePrivacyGeometry.faceOwnedByCurrentMask(face, person: person, preprocess: preprocess) else { return nil }
    }
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
}
