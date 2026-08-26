package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect
import kotlin.math.max
import kotlin.math.min

/**
 * Mask postprocessor for SAM2 soft alpha masks and dynamic BBox derivation.
 */
object Sam2MaskPostprocessor {

    /**
     * Derives a bounding box from a soft probability mask.
     * Matches desktop reference parity: threshold = 0.15, expand_ratio = 0.05.
     */
    fun computeBboxFromMask(
        mask: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = Sam2TensorContract.MASK_THRESHOLD,
        expandRatio: Float = Sam2TensorContract.BBOX_EXPAND_RATIO
    ): FloatRect {
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                if (mask[rowOffset + x] > threshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return FloatRect(0f, 0f, width.toFloat(), height.toFloat())
        }

        val bw = (maxX - minX + 1).toFloat()
        val bh = (maxY - minY + 1).toFloat()
        val expandW = bw * expandRatio
        val expandH = bh * expandRatio

        val left = max(0f, minX - expandW)
        val top = max(0f, minY - expandH)
        val right = min(width.toFloat(), maxX + 1 + expandW)
        val bottom = min(height.toFloat(), maxY + 1 + expandH)

        return FloatRect(left, top, right, bottom)
    }

    /**
     * Bilinear interpolation to resize low-res mask [maskW x maskH] to source [srcW x srcH].
     */
    fun resizeMaskBilinear(
        srcMask: FloatArray,
        maskW: Int,
        maskH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        val scaleX = (maskW - 1).toFloat() / max(1, dstW - 1).toFloat()
        val scaleY = (maskH - 1).toFloat() / max(1, dstH - 1).toFloat()

        for (y in 0 until dstH) {
            val srcY = y * scaleY
            val y0 = srcY.toInt().coerceIn(0, maskH - 1)
            val y1 = (y0 + 1).coerceIn(0, maskH - 1)
            val dy = srcY - y0

            val rowDst = y * dstW
            val row0 = y0 * maskW
            val row1 = y1 * maskW

            for (x in 0 until dstW) {
                val srcX = x * scaleX
                val x0 = srcX.toInt().coerceIn(0, maskW - 1)
                val x1 = (x0 + 1).coerceIn(0, maskW - 1)
                val dx = srcX - x0

                val v00 = srcMask[row0 + x0]
                val v01 = srcMask[row0 + x1]
                val v10 = srcMask[row1 + x0]
                val v11 = srcMask[row1 + x1]

                val v0 = v00 * (1f - dx) + v01 * dx
                val v1 = v10 * (1f - dx) + v11 * dx
                val v = v0 * (1f - dy) + v1 * dy

                out[rowDst + x] = v.coerceIn(0f, 1f)
            }
        }
        return out
    }
}

