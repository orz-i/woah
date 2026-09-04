package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Deterministically samples a top-down square FACE ROI from the canonical 640x640
 * bottom-up RGBA buffer used by the CPU YOLO reference path.
 *
 * The canonical buffer follows the glReadPixels bottom-up contract. The returned
 * ROI is top-down because FaceLocator and FacePixelMotionTracker consume top-down
 * RGBA. Fixed-point bilinear weights keep the resampling identical across devices.
 */
internal object CanonicalFaceRoiSampler {
    private const val FP = 256

    fun sampleTopDown(
        canonicalRgbaBottomUp: ByteBuffer,
        mapper: ModelCoordinateMapper,
        sourceRect: FloatRect,
        outputSize: Int,
        output: ByteBuffer
    ): ByteBuffer {
        require(outputSize > 0)
        val modelSize = mapper.modelInputSize
        val requiredInputBytes = modelSize * modelSize * 4
        val requiredOutputBytes = outputSize * outputSize * 4
        require(canonicalRgbaBottomUp.capacity() >= requiredInputBytes)
        require(output.capacity() >= requiredOutputBytes)

        val input = canonicalRgbaBottomUp.duplicate()
        val dst = output.duplicate()
        dst.clear()

        val rectWidth = sourceRect.width.coerceAtLeast(1f)
        val rectHeight = sourceRect.height.coerceAtLeast(1f)
        for (outY in 0 until outputSize) {
            val srcY = sourceRect.top + ((outY + 0.5f) / outputSize) * rectHeight
            val modelCenterY = mapper.sourceToModelY(srcY)
            val modelPixelY = (modelCenterY - 0.5f).coerceIn(0f, (modelSize - 1).toFloat())
            val y0 = floor(modelPixelY.toDouble()).toInt()
            val y1 = (y0 + 1).coerceAtMost(modelSize - 1)
            val wy1 = ((modelPixelY - y0) * FP).roundToInt().coerceIn(0, FP)
            val wy0 = FP - wy1
            val bufferY0 = modelSize - 1 - y0
            val bufferY1 = modelSize - 1 - y1

            for (outX in 0 until outputSize) {
                val srcX = sourceRect.left + ((outX + 0.5f) / outputSize) * rectWidth
                val modelCenterX = mapper.sourceToModelX(srcX)
                val modelPixelX = (modelCenterX - 0.5f).coerceIn(0f, (modelSize - 1).toFloat())
                val x0 = floor(modelPixelX.toDouble()).toInt()
                val x1 = (x0 + 1).coerceAtMost(modelSize - 1)
                val wx1 = ((modelPixelX - x0) * FP).roundToInt().coerceIn(0, FP)
                val wx0 = FP - wx1

                val p00 = (bufferY0 * modelSize + x0) * 4
                val p10 = (bufferY0 * modelSize + x1) * 4
                val p01 = (bufferY1 * modelSize + x0) * 4
                val p11 = (bufferY1 * modelSize + x1) * 4

                repeat(4) { channel ->
                    val v00 = input.get(p00 + channel).toInt() and 0xff
                    val v10 = input.get(p10 + channel).toInt() and 0xff
                    val v01 = input.get(p01 + channel).toInt() and 0xff
                    val v11 = input.get(p11 + channel).toInt() and 0xff
                    val top = v00 * wx0 + v10 * wx1
                    val bottom = v01 * wx0 + v11 * wx1
                    val value = ((top * wy0 + bottom * wy1 + (FP * FP / 2)) / (FP * FP))
                        .coerceIn(0, 255)
                    dst.put(value.toByte())
                }
            }
        }
        output.position(0)
        output.limit(requiredOutputBytes)
        return output
    }
}
