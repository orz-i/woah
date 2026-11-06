import Foundation

/// Expands the canonical proto mask without repeating the same coordinate
/// calculation and union for every output pixel. The result is byte-identical
/// to nearest-neighbour projection, including cropped and letterboxed scenes.
enum IOSPrivacyMaskProjector {
  static func project(mask: [UInt8], preprocess: IOSYoloPreprocessResult,
                      sourceWidth: Int, sourceHeight: Int, width: Int, height: Int,
                      crop: SIMD4<Float>) -> [UInt8] {
    IOSPrivacyCore.project(mask: mask, preprocess: preprocess, sourceWidth: sourceWidth,
      sourceHeight: sourceHeight, width: width, height: height, crop: crop)
  }
}
