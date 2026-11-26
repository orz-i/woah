import CoreGraphics
import Foundation
import Metal

enum IOSMetalPhase3Smoke {
  static func run() throws -> [String: Any] {
    guard let device = MTLCreateSystemDefaultDevice() else {
      throw PigeonError(
        code: "METAL_SMOKE_DEVICE_UNAVAILABLE",
        message: "No Metal device is available for the Phase 3 smoke test.",
        details: nil
      )
    }

    let width = 8
    let height = 8
    let source = try makeSolidImage(
      width: width,
      height: height,
      rgba: (0, 0, 255, 255)
    )
    let fullMask = [UInt8](
      repeating: 255,
      count: IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
    )
    let detection = IOSYoloDetection(
      x1: 0,
      y1: 0,
      x2: Float32(width),
      y2: Float32(height),
      confidence: 1.0,
      mask: fullMask
    )
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

    let renderer = try IOSMetalPreviewRenderer()
    let start = CFAbsoluteTimeGetCurrent()
    let rendered = try renderer.render(
      source: source,
      persons: [IOSPreviewPerson(id: 0, detection: detection)],
      preprocess: preprocess,
      fullBodyIds: [0],
      faceOnlyIds: [],
      effects: effects
    )
    let elapsedMs = (CFAbsoluteTimeGetCurrent() - start) * 1000.0
    let rgba = try readRGBA(rendered)
    guard rgba.r >= 240, rgba.g <= 20, rgba.b <= 20, rgba.a >= 240 else {
      throw PigeonError(
        code: "METAL_SMOKE_PIXEL_MISMATCH",
        message: "Phase 3 Metal smoke output did not contain the expected opaque red privacy pixel.",
        details: [
          "r": rgba.r,
          "g": rgba.g,
          "b": rgba.b,
          "a": rgba.a,
        ]
      )
    }

    // Exercise the exact production compositor on asymmetric content, privacy
    // masks, stickers and an off-center crop. A mirror must equal the reflected
    // *protected* output, not a reflection of just the source texture.
    let mirroredCases = try verifyHorizontalMirror(renderer: renderer, effects: effects)

    return [
      "horizontal_mirror_cases": mirroredCases,
      "status": "pass",
      "device_name": device.name,
      "render_ms": elapsedMs,
      "width": rendered.width,
      "height": rendered.height,
      "pixel_rgba": [rgba.r, rgba.g, rgba.b, rgba.a],
    ]
  }

  private static func verifyHorizontalMirror(
    renderer: IOSMetalPreviewRenderer, effects: EffectConfigDto
  ) throws -> Int {
    let size = 64
    let source = try makeSolidImage(width: size, height: size, rgba: (0, 0, 255, 255), asymmetric: true)
    let proto = IOSYoloPostprocessor.protoSize
    let leftMask = (0..<(proto * proto)).map { UInt8($0 % proto < proto / 2 ? 255 : 0) }
    let rightMask = (0..<(proto * proto)).map { UInt8($0 % proto >= proto / 2 ? 255 : 0) }
    let body = IOSPreviewPerson(id: 0, detection: IOSYoloDetection(
      x1: 0, y1: 0, x2: 32, y2: 64, confidence: 1, mask: leftMask))
    let face = IOSPreviewPerson(id: 1, detection: IOSYoloDetection(
      x1: 32, y1: 0, x2: 64, y2: 64, confidence: 1, mask: rightMask))
    let preprocess = IOSYoloPreprocessResult(input: [],
      scale: Float32(IOSYoloPreprocessor.inputSize) / Float32(size),
      padLeft: 0, padTop: 0, sourceWidth: size, sourceHeight: size,
      inputSize: IOSYoloPreprocessor.inputSize)
    let faceRegion = IOSFacePrivacyEllipse(centerX: 48, centerY: 17,
      radiusX: 8, radiusY: 11, source: .detectedFace)
    var stickerEffects = effects
    stickerEffects.fillMode = "sticker"
    stickerEffects.faceStickerEnabled = true
    stickerEffects.stickerAssetId = "builtin:sunglasses"
    let full = SIMD4<Float>(0, 0, 1, 1)
    let crop = SIMD4<Float>(0.125, 0, 0.75, 1)
    let cases: [(Set<Int>, Set<Int>, EffectConfigDto, SIMD4<Float>, Int)] = [
      ([], [], effects, full, 64),
      ([0], [], effects, full, 64),
      ([], [1], effects, full, 64),
      ([], [1], stickerEffects, full, 64),
      ([0], [1], effects, crop, 40),
      ([], [1], stickerEffects, crop, 40),
    ]
    for (bodyIds, faceIds, config, rect, outputWidth) in cases {
      func render(_ mirror: Bool) throws -> CGImage {
        try renderer.render(source: source, persons: [body, face], preprocess: preprocess,
          fullBodyIds: bodyIds, faceOnlyIds: faceIds, effects: config,
          faceRegions: [1: faceRegion], tightMask: true, outputWidth: outputWidth,
          outputHeight: size, sourceCrop: rect, mirrorHorizontally: mirror)
      }
      let normal = try render(false)
      let flipped = try render(true)
      guard let normalData = normal.dataProvider?.data, let flippedData = flipped.dataProvider?.data else {
        throw PigeonError(code: "METAL_SMOKE_READBACK_FAILED", message: "Mirror pixel readback failed", details: nil)
      }
      let a = [UInt8](normalData as Data)
      let b = [UInt8](flippedData as Data)
      guard a != b, a.count == outputWidth * size * 4, b.count == a.count else {
        throw PigeonError(code: "METAL_SMOKE_PIXEL_MISMATCH", message: "Mirror must change asymmetric output", details: nil)
      }
      for y in 0..<size {
        for x in 0..<outputWidth {
          let from = (y * outputWidth + x) * 4
          let to = (y * outputWidth + outputWidth - 1 - x) * 4
          guard a[from..<from + 4].elementsEqual(b[to..<to + 4]) else {
            throw PigeonError(code: "METAL_SMOKE_PIXEL_MISMATCH",
              message: "Mirror did not reflect the complete protected pixel",
              details: ["x": x, "y": y, "width": outputWidth])
          }
        }
      }
    }
    return cases.count
  }

  private static func makeSolidImage(
    width: Int,
    height: Int,
    rgba: (UInt8, UInt8, UInt8, UInt8),
    asymmetric: Bool = false
  ) throws -> CGImage {
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    for index in 0..<(width * height) {
      let offset = index * 4
      bytes[offset] = rgba.0
      bytes[offset + 1] = asymmetric ? UInt8((index % width) * 3 % 256) : rgba.1
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
      throw PigeonError(
        code: "METAL_SMOKE_IMAGE_FAILED",
        message: "Could not create the Phase 3 smoke source image.",
        details: nil
      )
    }
    return image
  }

  private static func readRGBA(
    _ image: CGImage
  ) throws -> (r: Int, g: Int, b: Int, a: Int) {
    var pixel = [UInt8](repeating: 0, count: 4)
    let rendered = pixel.withUnsafeMutableBytes { raw -> Bool in
      guard let base = raw.baseAddress,
            let context = CGContext(
              data: base,
              width: 1,
              height: 1,
              bitsPerComponent: 8,
              bytesPerRow: 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
        return false
      }
      context.interpolationQuality = .none
      context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
      return true
    }
    guard rendered else {
      throw PigeonError(
        code: "METAL_SMOKE_READBACK_FAILED",
        message: "Could not read back the Phase 3 Metal smoke pixel.",
        details: nil
      )
    }
    return (Int(pixel[0]), Int(pixel[1]), Int(pixel[2]), Int(pixel[3]))
  }
}
