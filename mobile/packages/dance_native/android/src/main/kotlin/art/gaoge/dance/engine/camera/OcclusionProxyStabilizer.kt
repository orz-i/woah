package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Stabilizes camera-only occlusion proxy centers without changing tracker identity.
 *
 * Short alternating bbox motion is held. A larger breakout moves immediately,
 * while a smaller same-direction drift must persist for a short PTS-based window
 * before it is allowed through. Once moving, a much smaller release dead-band is
 * used so genuine motion can converge without leaving a large composition offset.
 */
class OcclusionProxyStabilizer {
    internal data class State(
        val centerX: Float?,
        val centerY: Float?,
        val trackingX: Boolean,
        val trackingY: Boolean
    )

    private data class AxisState(
        var center: Float = 0f,
        var tracking: Boolean = false,
        var pendingDirection: Float = 0f,
        var pendingDurationSeconds: Float = 0f
    )

    private val x = AxisState()
    private val y = AxisState()
    private var initialized = false
    private var lastTimeUs: Long? = null

    fun reset() {
        x.center = 0f
        x.tracking = false
        x.pendingDirection = 0f
        x.pendingDurationSeconds = 0f
        y.center = 0f
        y.tracking = false
        y.pendingDirection = 0f
        y.pendingDurationSeconds = 0f
        initialized = false
        lastTimeUs = null
    }

    fun stabilize(target: FloatRect?, presentationTimeUs: Long): FloatRect? {
        val valid = target?.takeIf {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() &&
                it.bottom.isFinite() && it.width > 0f && it.height > 0f
        } ?: return null

        if (lastTimeUs?.let { presentationTimeUs < it } == true) reset()

        if (!initialized) {
            x.center = valid.centerX
            y.center = valid.centerY
            initialized = true
            lastTimeUs = presentationTimeUs
            return valid
        }

        val dt = lastTimeUs?.let {
            ((presentationTimeUs - it).coerceAtLeast(0L) / 1_000_000f)
                .coerceAtMost(MAX_STEP_SECONDS)
        } ?: 0f
        lastTimeUs = presentationTimeUs

        if (dt > 0f) {
            val alpha = 1f - (1f - REFERENCE_ALPHA).pow(dt * 30f)
            advanceAxis(
                axis = x,
                raw = valid.centerX,
                breakout = BREAKOUT_X,
                sustained = SUSTAINED_X,
                release = RELEASE_X,
                alpha = alpha,
                dt = dt
            )
            advanceAxis(
                axis = y,
                raw = valid.centerY,
                breakout = BREAKOUT_Y,
                sustained = SUSTAINED_Y,
                release = RELEASE_Y,
                alpha = alpha,
                dt = dt
            )
        }

        return centeredRect(valid, x.center, y.center)
    }

    internal fun state(): State = State(
        centerX = if (initialized) x.center else null,
        centerY = if (initialized) y.center else null,
        trackingX = x.tracking,
        trackingY = y.tracking
    )

    private fun advanceAxis(
        axis: AxisState,
        raw: Float,
        breakout: Float,
        sustained: Float,
        release: Float,
        alpha: Float,
        dt: Float
    ) {
        val delta = raw - axis.center
        val distance = abs(delta)
        val direction = sign(delta)

        if (!axis.tracking) {
            if (distance >= breakout) {
                axis.tracking = true
                clearPending(axis)
            } else if (distance >= sustained && direction != 0f) {
                if (axis.pendingDirection == direction) {
                    axis.pendingDurationSeconds += dt
                } else {
                    axis.pendingDirection = direction
                    axis.pendingDurationSeconds = dt
                }
                if (axis.pendingDurationSeconds >= SUSTAIN_CONFIRM_SECONDS) {
                    axis.tracking = true
                    clearPending(axis)
                }
            } else {
                clearPending(axis)
            }
        }

        if (!axis.tracking) return

        if (distance <= release || direction == 0f) {
            axis.tracking = false
            clearPending(axis)
            return
        }

        val desired = raw - direction * release
        axis.center += (desired - axis.center) * alpha

        if (abs(raw - axis.center) <= release) {
            axis.tracking = false
            clearPending(axis)
        }
    }

    private fun clearPending(axis: AxisState) {
        axis.pendingDirection = 0f
        axis.pendingDurationSeconds = 0f
    }

    private fun centeredRect(source: FloatRect, centerX: Float, centerY: Float): FloatRect {
        val width = source.width.coerceAtMost(1f)
        val height = source.height.coerceAtMost(1f)
        val halfW = width / 2f
        val halfH = height / 2f
        val clampedCenterX = centerX.coerceIn(halfW, 1f - halfW)
        val clampedCenterY = centerY.coerceIn(halfH, 1f - halfH)
        return FloatRect(
            left = clampedCenterX - halfW,
            top = clampedCenterY - halfH,
            right = clampedCenterX + halfW,
            bottom = clampedCenterY + halfH
        )
    }

    private companion object {
        const val MAX_STEP_SECONDS = 0.1f
        const val REFERENCE_ALPHA = 0.50f

        const val BREAKOUT_X = 0.015f
        const val SUSTAINED_X = 0.007f
        const val RELEASE_X = 0.004f

        const val BREAKOUT_Y = 0.012f
        const val SUSTAINED_Y = 0.006f
        const val RELEASE_Y = 0.0035f

        const val SUSTAIN_CONFIRM_SECONDS = 0.15f
    }
}
