package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect

/** Source-space camera transport; subject identity remains owned by the caller. */
class SmoothFollower {
    internal data class MotionState(val cameraX: Float, val cameraY: Float, val targetX: Float,
        val targetY: Float, val velocityX: Float, val velocityY: Float)
    private val values = FloatArray(6)
    private val clock = LongArray(3)
    init { reset() }
    fun reset() = FrameKernel.followReset(values, clock)
    internal fun motionState() = MotionState(values[0], values[1], values[2], values[3], values[4], values[5])
    fun cropForFrame(target: FloatRect?, presentationTimeUs: Long, sourceAspectRatio: Float,
        outputAspectRatio: Float, zoom: Float = 1f, smoothFactor: Float = 0.1f): FloatRect =
        FrameKernel.followStep(values, clock, target, presentationTimeUs, sourceAspectRatio,
            outputAspectRatio, zoom, smoothFactor)
}
