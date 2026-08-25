package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import kotlin.math.max
import kotlin.math.min

class SmoothFollower(
    private var cameraX: Float = 0.5f,
    private var cameraY: Float = 0.5f,
    private var initialized: Boolean = false
) {

    fun reset() {
        cameraX = 0.5f
        cameraY = 0.5f
        initialized = false
    }

    fun update(targetCenterX: Float, targetCenterY: Float, smoothFactor: Float = 0.10f) {
        val alpha = smoothFactor.coerceIn(0.01f, 1.0f)
        if (!initialized) {
            cameraX = targetCenterX
            cameraY = targetCenterY
            initialized = true
        } else {
            cameraX = alpha * targetCenterX + (1f - alpha) * cameraX
            cameraY = alpha * targetCenterY + (1f - alpha) * cameraY
        }
    }

    /**
     * Compute normalized UV crop bounds [0.0, 1.0] centered at (cameraX, cameraY) with zoom.
     */
    fun computeCropRect(zoom: Float = 1.0f): FloatRect {
        val safeZoom = max(1.0f, zoom)
        val halfW = (0.5f / safeZoom)
        val halfH = (0.5f / safeZoom)

        val left = (cameraX - halfW).coerceIn(0f, 1f - 2 * halfW)
        val top = (cameraY - halfH).coerceIn(0f, 1f - 2 * halfH)
        val right = left + 2 * halfW
        val bottom = top + 2 * halfH

        return FloatRect(left = left, top = top, right = right, bottom = bottom)
    }
}
