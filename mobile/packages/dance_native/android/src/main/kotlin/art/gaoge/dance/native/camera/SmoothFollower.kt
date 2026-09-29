package art.gaoge.dance.native.camera

import art.gaoge.dance.native.inference.FloatRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** A source-space camera. Identity selection belongs to the caller, never the camera. */
class SmoothFollower {
    internal data class MotionState(
        val cameraX: Float,
        val cameraY: Float,
        val targetX: Float,
        val targetY: Float,
        val velocityX: Float,
        val velocityY: Float
    )

    private data class AxisStep(
        val position: Float,
        val velocity: Float
    )

    private var cameraX = 0.5f
    private var cameraY = 0.5f
    private var targetX = 0.5f
    private var targetY = 0.5f
    private var velocityX = 0f
    private var velocityY = 0f
    private var initialized = false
    private var lastTimeUs: Long? = null

    fun reset() {
        cameraX = 0.5f
        cameraY = 0.5f
        targetX = 0.5f
        targetY = 0.5f
        velocityX = 0f
        velocityY = 0f
        initialized = false
        lastTimeUs = null
    }

    internal fun motionState(): MotionState = MotionState(
        cameraX = cameraX,
        cameraY = cameraY,
        targetX = targetX,
        targetY = targetY,
        velocityX = velocityX,
        velocityY = velocityY
    )

    /**
     * Returns visual top-left normalized source bounds. Missing observations keep
     * the last trusted aim point; camera velocity settles toward it without
     * extrapolating the person or switching identity.
     *
     * Motion is PTS-based and acceleration-limited. The target response keeps the
     * historical 30 Hz reference alpha, but converts it into a desired velocity
     * before applying acceleration/braking limits. This avoids instant starts and
     * stops after HELD/reacquire intervals while still allowing large pans to catch
     * up faster than small composition corrections.
     */
    fun cropForFrame(
        target: FloatRect?,
        presentationTimeUs: Long,
        sourceAspectRatio: Float,
        outputAspectRatio: Float,
        zoom: Float = 1f,
        smoothFactor: Float = 0.1f
    ): FloatRect {
        require(sourceAspectRatio.isFinite() && sourceAspectRatio > 0f)
        require(outputAspectRatio.isFinite() && outputAspectRatio > 0f)
        if (lastTimeUs?.let { presentationTimeUs < it } == true) reset()

        val safeZoom = if (zoom.isFinite()) zoom.coerceIn(1f, 3f) else 1f
        val cropWidth = min(1f, outputAspectRatio / sourceAspectRatio) / safeZoom
        val cropHeight = min(1f, sourceAspectRatio / outputAspectRatio) / safeZoom
        val halfW = cropWidth / 2f
        val halfH = cropHeight / 2f

        val validTarget = target?.takeIf {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() &&
                it.bottom.isFinite() && it.width > 0f && it.height > 0f
        }
        if (validTarget != null) {
            targetX = validTarget.centerX.coerceIn(halfW, 1f - halfW)
            targetY = validTarget.centerY.coerceIn(halfH, 1f - halfH)
            if (!initialized) {
                cameraX = targetX
                cameraY = targetY
                velocityX = 0f
                velocityY = 0f
                initialized = true
            }
        }

        val dt = lastTimeUs?.let {
            ((presentationTimeUs - it).coerceAtLeast(0L) / 1_000_000f).coerceAtMost(MAX_STEP_SECONDS)
        } ?: 0f
        lastTimeUs = presentationTimeUs

        if (initialized && dt > 0f) {
            val referenceAlpha = if (smoothFactor.isFinite()) {
                smoothFactor.coerceIn(0.01f, 1f)
            } else {
                0.1f
            }
            val alpha = 1f - (1f - referenceAlpha).pow(dt * 30f)

            val xStep = advance(
                current = cameraX,
                target = targetX,
                velocity = velocityX,
                span = cropWidth,
                alpha = alpha,
                dt = dt
            )
            cameraX = xStep.position
            velocityX = xStep.velocity

            val yStep = advance(
                current = cameraY,
                target = targetY,
                velocity = velocityY,
                span = cropHeight,
                alpha = alpha,
                dt = dt
            )
            cameraY = yStep.position
            velocityY = yStep.velocity
        }

        val unclampedX = cameraX
        val unclampedY = cameraY
        val left = (cameraX - halfW).coerceIn(0f, 1f - cropWidth)
        val top = (cameraY - halfH).coerceIn(0f, 1f - cropHeight)
        cameraX = left + halfW
        cameraY = top + halfH
        if (abs(cameraX - unclampedX) > EDGE_EPSILON) velocityX = 0f
        if (abs(cameraY - unclampedY) > EDGE_EPSILON) velocityY = 0f

        return FloatRect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun advance(
        current: Float,
        target: Float,
        velocity: Float,
        span: Float,
        alpha: Float,
        dt: Float
    ): AxisStep {
        if (span <= 0f || dt <= 0f) return AxisStep(current, velocity)

        val delta = target - current
        val distance = abs(delta)
        val direction = sign(delta)
        val deadZone = span * DEAD_ZONE_RATIO
        val excess = (distance - deadZone).coerceAtLeast(0f)

        val desiredVelocity = if (excess <= 0f || direction == 0f) {
            0f
        } else {
            val lag = (excess / (span * FAR_LAG_RATIO)).coerceIn(0f, 1f)
            val easedLag = lag * lag * (3f - 2f * lag)
            val maxSpansPerSecond =
                NEAR_MAX_SPANS_PER_SECOND +
                    (FAR_MAX_SPANS_PER_SECOND - NEAR_MAX_SPANS_PER_SECOND) * easedLag
            val responseVelocity = excess * alpha / dt
            direction * min(responseVelocity, span * maxSpansPerSecond)
        }

        val braking =
            desiredVelocity == 0f ||
                (velocity != 0f && desiredVelocity != 0f && sign(velocity) != sign(desiredVelocity))
        val accelerationPerSecond = span * if (braking) {
            BRAKE_SPANS_PER_SECOND_SQUARED
        } else {
            ACCEL_SPANS_PER_SECOND_SQUARED
        }
        val nextVelocity = moveToward(
            current = velocity,
            target = desiredVelocity,
            maxDelta = accelerationPerSecond * dt
        )

        var nextPosition = current + nextVelocity * dt
        var boundedVelocity = nextVelocity

        // Never overshoot the trusted aim point. Any remaining sub-dead-zone
        // error is intentionally left for composition stability.
        if (delta != 0f && (target - current) * (target - nextPosition) <= 0f) {
            nextPosition = target
            boundedVelocity = 0f
        }

        return AxisStep(nextPosition, boundedVelocity)
    }

    private fun moveToward(current: Float, target: Float, maxDelta: Float): Float {
        val delta = target - current
        if (abs(delta) <= maxDelta) return target
        return current + sign(delta) * maxDelta
    }

    private companion object {
        const val MAX_STEP_SECONDS = 0.1f
        const val DEAD_ZONE_RATIO = 0.02f
        const val FAR_LAG_RATIO = 0.45f
        const val NEAR_MAX_SPANS_PER_SECOND = 0.65f
        const val FAR_MAX_SPANS_PER_SECOND = 1.50f
        const val ACCEL_SPANS_PER_SECOND_SQUARED = 5.5f
        const val BRAKE_SPANS_PER_SECOND_SQUARED = 7.5f
        const val EDGE_EPSILON = 1e-6f
    }
}
