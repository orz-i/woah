import Foundation

/// Camera-only occlusion-proxy center stabilizer.
/// Kept numerically aligned with Android OcclusionProxyStabilizer.
final class IOSOcclusionProxyStabilizer {
  private struct AxisState {
    var center: Float = 0
    var tracking = false
    var pendingDirection: Float = 0
    var pendingDurationSeconds: Float = 0
  }

  private let maxStepSeconds: Float = 0.1
  private let referenceAlpha: Float = 0.50
  private let sustainConfirmSeconds: Float = 0.15

  private var x = AxisState()
  private var y = AxisState()
  private var initialized = false
  private var lastTimeUs: Int64?

  func reset() {
    x = AxisState()
    y = AxisState()
    initialized = false
    lastTimeUs = nil
  }

  func stabilize(
    target: SIMD4<Float>?,
    presentationTimeUs: Int64,
    frameWidth: Float,
    frameHeight: Float
  ) -> SIMD4<Float>? {
    guard let target,
          target.x.isFinite,
          target.y.isFinite,
          target.z.isFinite,
          target.w.isFinite,
          target.z > target.x,
          target.w > target.y,
          frameWidth > 0,
          frameHeight > 0 else {
      return nil
    }

    if let lastTimeUs, presentationTimeUs < lastTimeUs {
      reset()
    }

    let rawCenterX = (target.x + target.z) * 0.5
    let rawCenterY = (target.y + target.w) * 0.5
    if !initialized {
      x.center = rawCenterX
      y.center = rawCenterY
      initialized = true
      lastTimeUs = presentationTimeUs
      return target
    }

    let dt = lastTimeUs.map {
      min(maxStepSeconds, Float(max(0, presentationTimeUs - $0)) / 1_000_000)
    } ?? 0
    lastTimeUs = presentationTimeUs

    if dt > 0 {
      let alpha = 1 - pow(1 - referenceAlpha, dt * 30)
      advanceAxis(
        axis: &x,
        raw: rawCenterX,
        breakout: frameWidth * 0.015,
        sustained: frameWidth * 0.007,
        release: frameWidth * 0.004,
        alpha: alpha,
        dt: dt
      )
      advanceAxis(
        axis: &y,
        raw: rawCenterY,
        breakout: frameHeight * 0.012,
        sustained: frameHeight * 0.006,
        release: frameHeight * 0.0035,
        alpha: alpha,
        dt: dt
      )
    }

    return centeredRect(
      source: target,
      centerX: x.center,
      centerY: y.center,
      frameWidth: frameWidth,
      frameHeight: frameHeight
    )
  }

  private func advanceAxis(
    axis: inout AxisState,
    raw: Float,
    breakout: Float,
    sustained: Float,
    release: Float,
    alpha: Float,
    dt: Float
  ) {
    let delta = raw - axis.center
    let distance = abs(delta)
    let direction: Float = delta < 0 ? -1 : (delta > 0 ? 1 : 0)

    if !axis.tracking {
      if distance >= breakout {
        axis.tracking = true
        clearPending(axis: &axis)
      } else if distance >= sustained, direction != 0 {
        if axis.pendingDirection == direction {
          axis.pendingDurationSeconds += dt
        } else {
          axis.pendingDirection = direction
          axis.pendingDurationSeconds = dt
        }
        if axis.pendingDurationSeconds >= sustainConfirmSeconds {
          axis.tracking = true
          clearPending(axis: &axis)
        }
      } else {
        clearPending(axis: &axis)
      }
    }

    guard axis.tracking else { return }

    if distance <= release || direction == 0 {
      axis.tracking = false
      clearPending(axis: &axis)
      return
    }

    let desired = raw - direction * release
    axis.center += (desired - axis.center) * alpha

    if abs(raw - axis.center) <= release {
      axis.tracking = false
      clearPending(axis: &axis)
    }
  }

  private func clearPending(axis: inout AxisState) {
    axis.pendingDirection = 0
    axis.pendingDurationSeconds = 0
  }

  private func centeredRect(
    source: SIMD4<Float>,
    centerX: Float,
    centerY: Float,
    frameWidth: Float,
    frameHeight: Float
  ) -> SIMD4<Float> {
    let width = min(frameWidth, max(1, source.z - source.x))
    let height = min(frameHeight, max(1, source.w - source.y))
    let halfW = width * 0.5
    let halfH = height * 0.5
    let clampedCenterX = min(frameWidth - halfW, max(halfW, centerX))
    let clampedCenterY = min(frameHeight - halfH, max(halfH, centerY))
    return SIMD4<Float>(
      clampedCenterX - halfW,
      clampedCenterY - halfH,
      clampedCenterX + halfW,
      clampedCenterY + halfH
    )
  }
}
