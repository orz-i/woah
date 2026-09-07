import CoreGraphics
import Foundation

struct IOSYoloPreprocessResult {
  let input: [Float32]
  let scale: Float32
  let padLeft: Float32
  let padTop: Float32
  let sourceWidth: Int
  let sourceHeight: Int
  let inputSize: Int
}

enum IOSYoloPreprocessor {
  static let inputSize = 640
  private static let letterboxValue: UInt8 = 114

  static func process(image: CGImage) throws -> IOSYoloPreprocessResult {
    let sourceWidth = image.width
    let sourceHeight = image.height
    guard sourceWidth > 0, sourceHeight > 0 else {
      throw PigeonError(
        code: "INVALID_INFERENCE_FRAME",
        message: "YOLO input image has invalid dimensions.",
        details: nil
      )
    }

    let size = inputSize
    let scale = min(
      Float32(size) / Float32(sourceWidth),
      Float32(size) / Float32(sourceHeight)
    )
    let scaledWidth = max(1, Int(Float32(sourceWidth) * scale))
    let scaledHeight = max(1, Int(Float32(sourceHeight) * scale))
    let padLeft = Float32(size - scaledWidth) / 2.0
    let padTop = Float32(size - scaledHeight) / 2.0

    var rgba = [UInt8](repeating: letterboxValue, count: size * size * 4)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue
      | CGImageAlphaInfo.premultipliedLast.rawValue
    let created = rgba.withUnsafeMutableBytes { rawBuffer -> Bool in
      guard let base = rawBuffer.baseAddress,
            let context = CGContext(
              data: base,
              width: size,
              height: size,
              bitsPerComponent: 8,
              bytesPerRow: size * 4,
              space: colorSpace,
              bitmapInfo: bitmapInfo
            ) else {
        return false
      }

      let gray = CGFloat(letterboxValue) / 255.0
      context.setFillColor(red: gray, green: gray, blue: gray, alpha: 1.0)
      context.fill(CGRect(x: 0, y: 0, width: size, height: size))

      // Express draw coordinates in the same top-left/downward convention used
      // by Android's canonical visual frame before converting pixels to NCHW.
      context.translateBy(x: 0, y: CGFloat(size))
      context.scaleBy(x: 1.0, y: -1.0)
      context.interpolationQuality = .high
      context.draw(
        image,
        in: CGRect(
          x: CGFloat(padLeft),
          y: CGFloat(padTop),
          width: CGFloat(scaledWidth),
          height: CGFloat(scaledHeight)
        )
      )
      return true
    }
    guard created else {
      throw PigeonError(
        code: "INFERENCE_PREPROCESS_FAILED",
        message: "Could not create the canonical YOLO bitmap context.",
        details: nil
      )
    }

    let pixels = size * size
    var input = [Float32](repeating: 0, count: pixels * 3)
    let rOffset = 0
    let gOffset = pixels
    let bOffset = pixels * 2
    let inv255: Float32 = 1.0 / 255.0
    for index in 0..<pixels {
      let rgbaOffset = index * 4
      input[rOffset + index] = Float32(rgba[rgbaOffset]) * inv255
      input[gOffset + index] = Float32(rgba[rgbaOffset + 1]) * inv255
      input[bOffset + index] = Float32(rgba[rgbaOffset + 2]) * inv255
    }

    return IOSYoloPreprocessResult(
      input: input,
      scale: scale,
      padLeft: padLeft,
      padTop: padTop,
      sourceWidth: sourceWidth,
      sourceHeight: sourceHeight,
      inputSize: size
    )
  }
}
