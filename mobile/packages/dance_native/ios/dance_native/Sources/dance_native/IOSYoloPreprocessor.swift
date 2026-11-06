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

    // Native image decoding/color transport only; resize, padding and tensor
    // layout use the same portable kernel as Android.
    var rgba = [UInt8](repeating: 0, count: sourceWidth * sourceHeight * 4)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue
      | CGImageAlphaInfo.premultipliedLast.rawValue
    let created = rgba.withUnsafeMutableBytes { rawBuffer -> Bool in
      guard let base = rawBuffer.baseAddress,
            let context = CGContext(
              data: base,
              width: sourceWidth,
              height: sourceHeight,
              bitsPerComponent: 8,
              bytesPerRow: sourceWidth * 4,
              space: colorSpace,
              bitmapInfo: bitmapInfo
            ) else {
        return false
      }

      context.draw(image, in: CGRect(x: 0, y: 0, width: sourceWidth, height: sourceHeight))
      return true
    }
    guard created else {
      throw PigeonError(
        code: "INFERENCE_PREPROCESS_FAILED",
        message: "Could not create the canonical YOLO bitmap context.",
        details: nil
      )
    }

    let resized = try IOSFrameKernel.resizeRgba(rgba, width: sourceWidth, height: sourceHeight,
      outputWidth: inputSize, outputHeight: inputSize, fill: letterboxValue)
    let input = try IOSFrameKernel.normalizePixels(resized.pixels, width: inputSize, height: inputSize)

    return IOSYoloPreprocessResult(
      input: input,
      scale: resized.geometry[0],
      padLeft: resized.geometry[3],
      padTop: resized.geometry[4],
      sourceWidth: sourceWidth,
      sourceHeight: sourceHeight,
      inputSize: inputSize
    )
  }
}
