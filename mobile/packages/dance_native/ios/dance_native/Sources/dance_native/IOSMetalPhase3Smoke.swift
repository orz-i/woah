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

    return [
      "status": "pass",
      "device_name": device.name,
      "render_ms": elapsedMs,
      "width": rendered.width,
      "height": rendered.height,
      "pixel_rgba": [rgba.r, rgba.g, rgba.b, rgba.a],
    ]
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
