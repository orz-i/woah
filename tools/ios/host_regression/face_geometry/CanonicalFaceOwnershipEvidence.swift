// Test-only original ownership evidence window, before landmark render migration.
import Foundation
import CoreGraphics
enum CanonicalFaceOwnershipEvidence {
  private static let detectedRadiusXFactor: Float32 = 0.66
  private static let detectedRadiusYFactor: Float32 = 0.74
  private static let detectedCenterYShift: Float32 = -0.04

  static func detectedEllipse(_ face: IOSFaceCandidate) -> IOSFacePrivacyEllipse? {
    let width = face.x2 - face.x1
    let height = face.y2 - face.y1
    guard width > 1, height > 1 else { return nil }
    // Vision's profile rectangle can enclose only the frontal features. Use
    // the current measured yaw to include the cheek/chin behind that plane;
    // never borrow another person's face or an old body's predicted head.
    let yaw = face.yaw.isFinite ? max(-Float32.pi / 2, min(Float32.pi / 2, face.yaw)) : 0
    let profileScale = min(Float32(1.75), 1 / max(Float32(1.0 / 1.75), cos(yaw)))
    return IOSFacePrivacyEllipse(
      centerX: (face.x1 + face.x2) * 0.5 - sin(yaw) * width * 0.5,
      centerY: (face.y1 + face.y2) * 0.5 + height * detectedCenterYShift + (profileScale - 1) * height * 0.5,
      radiusX: width * detectedRadiusXFactor * profileScale,
      radiusY: height * detectedRadiusYFactor * profileScale,
      source: .detectedFace
    )
  }
  static func owned(_ face:IOSFaceCandidate,person:IOSYoloDetection,preprocess:IOSYoloPreprocessResult)->Bool {
    guard let region=detectedEllipse(face) else{return false}
    let support=CanonicalObservationGeometry.currentMaskSupport(region,person:person,preprocess:preprocess)
    return support.distinctPixels>=4&&support.samples>=12
  }
}
