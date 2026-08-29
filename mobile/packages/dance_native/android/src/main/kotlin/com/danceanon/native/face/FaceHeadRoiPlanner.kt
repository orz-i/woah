package com.danceanon.native.face

import com.danceanon.native.inference.FloatRect
import kotlin.math.max
import kotlin.math.min

data class FaceHeadRoiPlan(
    val sourceRect: FloatRect,
    val anchorX: Float,
    val anchorY: Float,
    val outputSize: Int = 256
)

/**
 * Plans the source-resolution square crop used by the face locator.
 *
 * The constants are intentionally derived from the real-device "upper" ROI
 * benchmark, which preserved enough source detail for all four distant people
 * in the current fixture. The crop is shifted rather than truncated at frame
 * edges so the downstream 256x256 detector input remains square.
 */
object FaceHeadRoiPlanner {
    const val OUTPUT_SIZE = 256

    private const val WIDTH_FACTOR = 2.20f
    private const val HEIGHT_FACTOR = 0.90f
    private const val HEAD_CENTER_Y_RATIO = 0.22f

    fun plan(
        personBbox: FloatRect,
        frameWidth: Int,
        frameHeight: Int
    ): FaceHeadRoiPlan? {
        if (frameWidth <= 1 || frameHeight <= 1 || personBbox.width <= 1f || personBbox.height <= 1f) {
            return null
        }

        val requestedSide = max(
            personBbox.width * WIDTH_FACTOR,
            personBbox.height * HEIGHT_FACTOR
        )
        val side = min(requestedSide, min(frameWidth, frameHeight).toFloat()).coerceAtLeast(2f)

        val anchorSourceX = personBbox.centerX.coerceIn(0f, frameWidth.toFloat())
        val anchorSourceY = (personBbox.top + personBbox.height * HEAD_CENTER_Y_RATIO)
            .coerceIn(0f, frameHeight.toFloat())

        val maxLeft = (frameWidth.toFloat() - side).coerceAtLeast(0f)
        val maxTop = (frameHeight.toFloat() - side).coerceAtLeast(0f)
        val left = (anchorSourceX - side * 0.5f).coerceIn(0f, maxLeft)
        val top = (anchorSourceY - side * 0.5f).coerceIn(0f, maxTop)
        val rect = FloatRect(left, top, left + side, top + side)

        val anchorX = ((anchorSourceX - rect.left) / side).coerceIn(0f, 1f)
        val anchorY = ((anchorSourceY - rect.top) / side).coerceIn(0f, 1f)
        return FaceHeadRoiPlan(
            sourceRect = rect,
            anchorX = anchorX,
            anchorY = anchorY,
            outputSize = OUTPUT_SIZE
        )
    }
}
