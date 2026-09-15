import Foundation

/// PTS-based source-space camera; kept numerically aligned with SmoothFollower.
final class IOSSubjectReframer {
  private var camera = SIMD2<Float>(repeating: 0.5)
  private var targetCenter = SIMD2<Float>(repeating: 0.5)
  private var initialized = false
  private var lastTimeUs: Int64?

  static func exactNineSixteenSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxHeight: Int = .max
  ) -> (width: Int, height: Int)? {
    guard sourceWidth > 0, sourceHeight > 0, maxHeight > 0 else { return nil }
    let units = min(sourceWidth / 18, min(sourceHeight, maxHeight) / 32)
    guard units > 0 else { return nil }
    return (units * 18, units * 32)
  }

  func reset() {
    camera = SIMD2<Float>(repeating: 0.5)
    targetCenter = camera
    initialized = false
    lastTimeUs = nil
  }

  func crop(
    target: SIMD4<Float>?,
    presentationTimeUs: Int64,
    sourceAspectRatio: Float,
    outputAspectRatio: Float,
    zoom: Float = 1,
    smoothFactor: Float = 0.1
  ) -> SIMD4<Float> {
    precondition(sourceAspectRatio.isFinite && sourceAspectRatio > 0)
    precondition(outputAspectRatio.isFinite && outputAspectRatio > 0)
    if let lastTimeUs, presentationTimeUs < lastTimeUs { reset() }
    let safeZoom = zoom.isFinite ? min(3, max(1, zoom)) : 1
    let width = min(1, outputAspectRatio / sourceAspectRatio) / safeZoom
    let height = min(1, sourceAspectRatio / outputAspectRatio) / safeZoom
    let half = SIMD2<Float>(width / 2, height / 2)
    if let target, target.x.isFinite, target.y.isFinite,
       target.z.isFinite, target.w.isFinite,
       target.z > target.x, target.w > target.y {
      targetCenter = SIMD2<Float>(
        min(1 - half.x, max(half.x, (target.x + target.z) / 2)),
        min(1 - half.y, max(half.y, (target.y + target.w) / 2))
      )
      if !initialized {
        camera = targetCenter
        initialized = true
      }
    }
    let dt = lastTimeUs.map { min(0.1, Float(max(0, presentationTimeUs - $0)) / 1_000_000) } ?? 0
    lastTimeUs = presentationTimeUs
    if initialized, dt > 0 {
      let referenceAlpha = smoothFactor.isFinite ? min(1, max(0.01, smoothFactor)) : 0.1
      let alpha = 1 - pow(1 - referenceAlpha, dt * 30)
      camera.x = advance(camera.x, targetCenter.x, width, alpha, dt)
      camera.y = advance(camera.y, targetCenter.y, height, alpha, dt)
    }
    let left = min(1 - width, max(0, camera.x - half.x))
    let top = min(1 - height, max(0, camera.y - half.y))
    camera = SIMD2<Float>(left + half.x, top + half.y)
    return SIMD4<Float>(left, top, left + width, top + height)
  }

  private func advance(_ current: Float, _ target: Float, _ span: Float, _ alpha: Float, _ dt: Float) -> Float {
    let delta = target - current
    let excess = max(0, abs(delta) - span * 0.03) * (delta < 0 ? -1 : 1)
    return current + min(0.8 * dt, max(-0.8 * dt, excess * alpha))
  }
}
