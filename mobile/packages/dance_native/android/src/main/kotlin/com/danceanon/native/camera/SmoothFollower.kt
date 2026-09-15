package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** A source-space camera. Identity selection belongs to the caller, never the camera. */
class SmoothFollower {
    private var cameraX = 0.5f
    private var cameraY = 0.5f
    private var targetX = 0.5f
    private var targetY = 0.5f
    private var initialized = false
    private var lastTimeUs: Long? = null

    fun reset() {
        cameraX = 0.5f
        cameraY = 0.5f
        targetX = 0.5f
        targetY = 0.5f
        initialized = false
        lastTimeUs = null
    }

    /**
     * Returns visual top-left normalized source bounds. Missing observations hold
     * the last trusted target (no extrapolation and no switch to another person).
     * The 30 Hz reference alpha is converted using PTS, not frame count.
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
                initialized = true
            }
        }
        val dt = lastTimeUs?.let {
            ((presentationTimeUs - it).coerceAtLeast(0L) / 1_000_000f).coerceAtMost(0.1f)
        } ?: 0f
        lastTimeUs = presentationTimeUs
        if (initialized && dt > 0f) {
            val referenceAlpha = if (smoothFactor.isFinite()) smoothFactor.coerceIn(0.01f, 1f) else 0.1f
            val alpha = 1f - (1f - referenceAlpha).pow(dt * 30f)
            cameraX = advance(cameraX, targetX, cropWidth, alpha, dt)
            cameraY = advance(cameraY, targetY, cropHeight, alpha, dt)
        }
        val left = (cameraX - halfW).coerceIn(0f, 1f - cropWidth)
        val top = (cameraY - halfH).coerceIn(0f, 1f - cropHeight)
        cameraX = left + halfW
        cameraY = top + halfH
        return FloatRect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun advance(current: Float, target: Float, span: Float, alpha: Float, dt: Float): Float {
        val delta = target - current
        // Ignore small body-box/limb jitter; bound pans to 0.8 source spans/sec.
        val excess = max(0f, abs(delta) - span * 0.03f) * sign(delta)
        return current + (excess * alpha).coerceIn(-0.8f * dt, 0.8f * dt)
    }
}
