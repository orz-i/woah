import Foundation

/// Native transport for the shared source-PTS camera. No independent motion rules.
final class IOSSubjectReframer {
  private let core = IOSFrameKernel.Follower()
  static func exactNineSixteenSize(sourceWidth: Int, sourceHeight: Int, maxHeight: Int = .max) -> (width: Int, height: Int)? {
    IOSFrameKernel.fitEvenRatio(width: sourceWidth, height: sourceHeight, maxHeight: maxHeight, ratioWidth: 9, ratioHeight: 16)
  }
  func reset() { core.reset() }
  func crop(target: SIMD4<Float>?, presentationTimeUs: Int64, sourceAspectRatio: Float,
    outputAspectRatio: Float, zoom: Float = 1, smoothFactor: Float = 0.1) -> SIMD4<Float> {
    core.crop(target: target, ptsUs: presentationTimeUs, sourceAspect: sourceAspectRatio,
      outputAspect: outputAspectRatio, zoom: zoom, smooth: smoothFactor)
  }
}
