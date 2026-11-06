import Foundation

/// Expands the canonical proto mask without repeating the same coordinate
/// calculation and union for every output pixel. The result is byte-identical
/// to nearest-neighbour projection, including cropped and letterboxed scenes.
enum IOSPrivacyMaskProjector {
  static func project(mask: [UInt8], preprocess: IOSYoloPreprocessResult,
                      sourceWidth: Int, sourceHeight: Int, width: Int, height: Int,
                      crop: SIMD4<Float>) -> [UInt8] {
    let size = IOSYoloPostprocessor.protoSize
    guard width > 0, height > 0, mask.count == size * size else { return [] }
    let xs = (0..<width).map { x -> Int in
      let sx = (Float32(x) + 0.5) * Float32(sourceWidth) / Float32(width)
        * (crop.z - crop.x) + crop.x * Float32(sourceWidth)
      return Int(floor(Double((sx * preprocess.scale + preprocess.padLeft)
        / Float32(preprocess.inputSize) * Float32(size))))
    }
    var rows: [Int: [UInt8]] = [:]
    var result = [UInt8](repeating: 0, count: width * height)
    for y in 0..<height {
      let sy = (Float32(y) + 0.5) * Float32(sourceHeight) / Float32(height)
        * (crop.w - crop.y) + crop.y * Float32(sourceHeight)
      let py = Int(floor(Double((sy * preprocess.scale + preprocess.padTop)
        / Float32(preprocess.inputSize) * Float32(size))))
      guard py >= 0, py < size else { continue }
      let row: [UInt8]
      if let cached = rows[py] { row = cached } else {
        row = xs.map { px in px >= 0 && px < size ? mask[py * size + px] : 0 }
        rows[py] = row
      }
      result.replaceSubrange(y * width..<(y + 1) * width, with: row)
    }
    return result
  }
}
