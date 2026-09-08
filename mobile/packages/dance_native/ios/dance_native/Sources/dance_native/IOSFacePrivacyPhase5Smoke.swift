import CoreGraphics
import Foundation

private final class IOSPhase5SequenceFaceLocator: IOSFaceLocating {
  private let batches: [[IOSFaceCandidate]]
  private var index = 0

  init(batches: [[IOSFaceCandidate]]) {
    self.batches = batches
  }

  func locateFaces(in image: CGImage) throws -> [IOSFaceCandidate] {
    guard !batches.isEmpty else { return [] }
    let current = batches[min(index, batches.count - 1)]
    index += 1
    return current
  }
}

enum IOSFacePrivacyPhase5Smoke {
  static func run() throws -> [String: Any] {
    let width = 64
    let height = 64
    let source = try makeSolidImage(width: width, height: height, rgba: (0, 0, 255, 255))
    let visionSource = try makeSolidImage(width: 256, height: 256, rgba: (0, 0, 255, 255))
    let visionRuntimeFaces: [IOSFaceCandidate]
    do {
      visionRuntimeFaces = try IOSVisionFaceLocator().locateFaces(in: visionSource)
    } catch {
      throw smokeFailure("Vision FACE_ONLY runtime probe failed: \(error)")
    }
    let personDetection = makeDetection(x1: 8, y1: 4, x2: 56, y2: 60)
    let person = IOSPreviewPerson(id: 0, detection: personDetection)
    let trustedFace = IOSFaceCandidate(
      x1: 27,
      y1: 8,
      x2: 37,
      y2: 20,
      confidence: 0.99
    )

    let resolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace], []])
    )
    let detected = resolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      timestampUs: 0
    )
    guard let detectedRegion = detected[0], detectedRegion.source == .detectedFace else {
      throw smokeFailure("A clear face candidate was not accepted for the YOLO-owned selected identity.")
    }
    try assertNear(detectedRegion.centerX, 32, tolerance: 0.01, label: "detected centerX")
    try assertNear(detectedRegion.centerY, 13.52, tolerance: 0.05, label: "detected centerY")
    try assertNear(detectedRegion.radiusX, 6.6, tolerance: 0.05, label: "detected radiusX")
    try assertNear(detectedRegion.radiusY, 8.88, tolerance: 0.05, label: "detected radiusY")

    let missed = resolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      timestampUs: 33_333
    )
    guard let missedRegion = missed[0], missedRegion.source == .yoloHeadFallback else {
      throw smokeFailure("A detector miss did not retain YOLO-owned FACE_ONLY fallback privacy.")
    }
    guard missedRegion.radiusX > 0, missedRegion.radiusY > 0 else {
      throw smokeFailure("Detector-miss fallback produced an empty privacy region.")
    }

    let ambiguousResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[
        IOSFaceCandidate(x1: 20, y1: 8, x2: 30, y2: 20, confidence: 0.99),
        IOSFaceCandidate(x1: 34, y1: 8, x2: 44, y2: 20, confidence: 0.99),
      ]])
    )
    let ambiguous = ambiguousResolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      timestampUs: 0
    )
    guard ambiguous[0]?.source == .yoloHeadFallback else {
      throw smokeFailure("Near-tie face candidates must defer localization and use the privacy fallback.")
    }

    let neighborResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace]])
    )
    let selectedOverlap = IOSPreviewPerson(
      id: 0,
      detection: makeDetection(x1: 4, y1: 4, x2: 44, y2: 60)
    )
    let unselectedOverlap = IOSPreviewPerson(
      id: 1,
      detection: makeDetection(x1: 20, y1: 4, x2: 60, y2: 60)
    )
    let neighborCompetition = neighborResolver.resolve(
      image: source,
      persons: [selectedOverlap, unselectedOverlap],
      faceOnlyIds: [0],
      timestampUs: 0
    )
    guard neighborCompetition[0]?.source == .yoloHeadFallback else {
      throw smokeFailure("An unselected observed neighbor must participate in face ownership ambiguity.")
    }

    let predictedResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace]])
    )
    let predictedPerson = IOSPreviewPerson(
      id: 0,
      detection: personDetection,
      conservativePrivacyFallback: true
    )
    let predicted = predictedResolver.resolve(
      image: source,
      persons: [predictedPerson],
      faceOnlyIds: [0],
      timestampUs: 0
    )
    guard predicted[0]?.source == .yoloHeadFallback else {
      throw smokeFailure("An unobserved predicted body must not consume fresh face evidence as identity evidence.")
    }

    let renderer = try IOSMetalPreviewRenderer()
    let preprocess = IOSYoloPreprocessResult(
      input: [],
      scale: Float32(IOSYoloPreprocessor.inputSize) / Float32(width),
      padLeft: 0,
      padTop: 0,
      sourceWidth: width,
      sourceHeight: height,
      inputSize: IOSYoloPreprocessor.inputSize
    )
    let effects = EffectConfigDto(
      fillMode: "solid",
      fillColorArgb: Int64(0xFFFF0000),
      borderColorArgb: 0,
      opacity: 1.0,
      borderWidth: 0,
      blurStrength: 1.0,
      faceStickerEnabled: false,
      stickerAssetId: nil,
      stickerScale: 1.0,
      skinWhiten: 0,
      legStretchEnabled: false,
      legStretch: 0,
      legZoneTop: 0,
      legZoneBottom: 1
    )
    let renderRegion = IOSFacePrivacyEllipse(
      centerX: 32,
      centerY: 32,
      radiusX: 10,
      radiusY: 12,
      source: .detectedFace
    )
    let rendered = try renderer.render(
      source: source,
      persons: [person],
      preprocess: preprocess,
      fullBodyIds: [],
      faceOnlyIds: [0],
      effects: effects,
      faceRegions: [0: renderRegion]
    )
    let centerPixel = try pixel(rendered, x: 32, y: 32)
    guard centerPixel.r >= 240, centerPixel.g <= 20, centerPixel.b <= 20 else {
      throw smokeFailure("FACE_ONLY ellipse center was not covered by the opaque privacy layer.")
    }
    let cornerPixel = try pixel(rendered, x: 23, y: 21)
    guard cornerPixel.b >= 230, cornerPixel.r <= 30 else {
      throw smokeFailure("FACE_ONLY privacy regressed to a rectangular mask instead of ellipse geometry.")
    }

    return [
      "status": "pass",
      "detected_source": detectedRegion.source.rawValue,
      "miss_source": missedRegion.source.rawValue,
      "ambiguous_source": ambiguous[0]?.source.rawValue ?? "missing",
      "neighbor_competition_source": neighborCompetition[0]?.source.rawValue ?? "missing",
      "predicted_body_source": predicted[0]?.source.rawValue ?? "missing",
      "vision_runtime_face_count": visionRuntimeFaces.count,
      "center_pixel": [centerPixel.r, centerPixel.g, centerPixel.b, centerPixel.a],
      "corner_pixel": [cornerPixel.r, cornerPixel.g, cornerPixel.b, cornerPixel.a],
    ]
  }

  private static func makeDetection(
    x1: Float32,
    y1: Float32,
    x2: Float32,
    y2: Float32
  ) -> IOSYoloDetection {
    IOSYoloDetection(
      x1: x1,
      y1: y1,
      x2: x2,
      y2: y2,
      confidence: 0.99,
      mask: [UInt8](
        repeating: 255,
        count: IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
      )
    )
  }

  private static func assertNear(
    _ actual: Float32,
    _ expected: Float32,
    tolerance: Float32,
    label: String
  ) throws {
    guard abs(actual - expected) <= tolerance else {
      throw smokeFailure("\(label) expected=\(expected) actual=\(actual)")
    }
  }

  private static func makeSolidImage(
    width: Int,
    height: Int,
    rgba: (UInt8, UInt8, UInt8, UInt8)
  ) throws -> CGImage {
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    for index in 0..<(width * height) {
      let offset = index * 4
      bytes[offset] = rgba.0
      bytes[offset + 1] = rgba.1
      bytes[offset + 2] = rgba.2
      bytes[offset + 3] = rgba.3
    }
    let data = Data(bytes) as CFData
    guard let provider = CGDataProvider(data: data),
          let image = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(
              rawValue: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
          ) else {
      throw smokeFailure("Could not create the Phase 5C FACE_ONLY smoke source image.")
    }
    return image
  }

  private static func pixel(
    _ image: CGImage,
    x: Int,
    y: Int
  ) throws -> (r: Int, g: Int, b: Int, a: Int) {
    let width = image.width
    let height = image.height
    guard x >= 0, x < width, y >= 0, y < height else {
      throw smokeFailure("Requested FACE_ONLY smoke readback pixel is outside the image.")
    }
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    let rendered = bytes.withUnsafeMutableBytes { raw -> Bool in
      guard let base = raw.baseAddress,
            let context = CGContext(
              data: base,
              width: width,
              height: height,
              bitsPerComponent: 8,
              bytesPerRow: width * 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
        return false
      }
      context.interpolationQuality = .none
      context.translateBy(x: 0, y: CGFloat(height))
      context.scaleBy(x: 1, y: -1)
      context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
      return true
    }
    guard rendered else {
      throw smokeFailure("Could not read back the Phase 5C FACE_ONLY Metal output.")
    }
    let offset = (y * width + x) * 4
    return (
      Int(bytes[offset]),
      Int(bytes[offset + 1]),
      Int(bytes[offset + 2]),
      Int(bytes[offset + 3])
    )
  }

  private static func smokeFailure(_ message: String) -> PigeonError {
    PigeonError(
      code: "IOS_PHASE5_FACE_PRIVACY_SMOKE_FAILED",
      message: message,
      details: nil
    )
  }
}
