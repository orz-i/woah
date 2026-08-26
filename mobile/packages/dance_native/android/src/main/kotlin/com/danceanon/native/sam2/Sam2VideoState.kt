package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect

/**
 * Initialization request for SAM2 tracking on Frame 0.
 */
data class Sam2InitRequest(
    val frame: android.graphics.Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val objectId: Int,
    val bbox: FloatRect
)

/**
 * Output tracking result for a single frame.
 */
data class Sam2TrackResult(
    val frameIndex: Int,
    val objectId: Int,
    val softMask: FloatArray,
    val bbox: FloatRect,
    val maskArea: Float,
    val inferenceMs: Long,
    val isValid: Boolean = true
)


