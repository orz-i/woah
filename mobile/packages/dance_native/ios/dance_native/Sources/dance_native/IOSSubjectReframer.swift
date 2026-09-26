import Foundation

/// PTS-based source-space camera; kept numerically aligned with SmoothFollower.
final class IOSSubjectReframer {
  private struct AxisStep {
    let position: Float
    let velocity: Float
  }

  private let maxStepSeconds: Float = 0.1
  private let deadZoneRatio: Float = 0.02
  private let farLagRatio: Float = 0.45
  private let nearMaxSpansPerSecond: Float = 0.65
  private let farMaxSpansPerSecond: Float = 1.50
  private let accelSpansPerSecondSquared: Float = 5.5
  private let brakeSpansPerSecondSquared: Float = 7.5
  private let edgeEpsilon: Float = 0.000001

  private var camera = SIMD2<Float>(repeating: 0.5)
  private var targetCenter = SIMD2<Float>(repeating: 0.5)
  private var velocity = SIMD2<Float>(repeating: 0)
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
    velocity = SIMD2<Float>(repeating: 0)
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
        velocity = SIMD2<Float>(repeating: 0)
        initialized = true
      }
    }

    let dt = lastTimeUs.map {
      min(maxStepSeconds, Float(max(0, presentationTimeUs - $0)) / 1_000_000)
    } ?? 0
    lastTimeUs = presentationTimeUs

    if initialized, dt > 0 {
      let referenceAlpha = smoothFactor.isFinite ? min(1, max(0.01, smoothFactor)) : 0.1
      let alpha = 1 - pow(1 - referenceAlpha, dt * 30)

      let xStep = advance(
        current: camera.x,
        target: targetCenter.x,
        velocity: velocity.x,
        span: width,
        alpha: alpha,
        dt: dt
      )
      camera.x = xStep.position
      velocity.x = xStep.velocity

      let yStep = advance(
        current: camera.y,
        target: targetCenter.y,
        velocity: velocity.y,
        span: height,
        alpha: alpha,
        dt: dt
      )
      camera.y = yStep.position
      velocity.y = yStep.velocity
    }

    let unclamped = camera
    let left = min(1 - width, max(0, camera.x - half.x))
    let top = min(1 - height, max(0, camera.y - half.y))
    camera = SIMD2<Float>(left + half.x, top + half.y)
    if abs(camera.x - unclamped.x) > edgeEpsilon { velocity.x = 0 }
    if abs(camera.y - unclamped.y) > edgeEpsilon { velocity.y = 0 }

    return SIMD4<Float>(left, top, left + width, top + height)
  }

  private func advance(
    current: Float,
    target: Float,
    velocity: Float,
    span: Float,
    alpha: Float,
    dt: Float
  ) -> AxisStep {
    guard span > 0, dt > 0 else {
      return AxisStep(position: current, velocity: velocity)
    }

    let delta = target - current
    let distance = abs(delta)
    let direction: Float = delta < 0 ? -1 : (delta > 0 ? 1 : 0)
    let deadZone = span * deadZoneRatio
    let excess = max(0, distance - deadZone)

    let desiredVelocity: Float
    if excess <= 0 || direction == 0 {
      desiredVelocity = 0
    } else {
      let lag = min(1, max(0, excess / (span * farLagRatio)))
      let easedLag = lag * lag * (3 - 2 * lag)
      let maxSpansPerSecond = nearMaxSpansPerSecond
        + (farMaxSpansPerSecond - nearMaxSpansPerSecond) * easedLag
      let responseVelocity = excess * alpha / dt
      desiredVelocity = direction * min(responseVelocity, span * maxSpansPerSecond)
    }

    let braking = desiredVelocity == 0
      || (velocity != 0 && desiredVelocity != 0 && (velocity < 0) != (desiredVelocity < 0))
    let accelerationPerSecond = span * (
      braking ? brakeSpansPerSecondSquared : accelSpansPerSecondSquared
    )
    var nextVelocity = moveToward(
      current: velocity,
      target: desiredVelocity,
      maxDelta: accelerationPerSecond * dt
    )

    var nextPosition = current + nextVelocity * dt
    if delta != 0, (target - current) * (target - nextPosition) <= 0 {
      nextPosition = target
      nextVelocity = 0
    }

    return AxisStep(position: nextPosition, velocity: nextVelocity)
  }

  private func moveToward(current: Float, target: Float, maxDelta: Float) -> Float {
    let delta = target - current
    if abs(delta) <= maxDelta { return target }
    return current + (delta < 0 ? -1 : 1) * maxDelta
  }
}
