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
     * Supports mapping from compact mask dimensions to source visual frame dimensions.
     * Returns null if mask is empty / no pixels exceed threshold.
     */
    fun computeBboxFromMaskStrict(
        mask: FloatArray,
        width: Int,
        height: Int,
        srcWidth: Int = width,
        srcHeight: Int = height,
        threshold: Float = Sam2TensorContract.MASK_THRESHOLD,
        expandRatio: Float = Sam2TensorContract.BBOX_EXPAND_RATIO
    ): FloatRect? {
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
            return null
        }

        val scaleX = srcWidth.toFloat() / width.toFloat()
        val scaleY = srcHeight.toFloat() / height.toFloat()

        val origMinX = minX * scaleX
        val origMaxX = (maxX + 1) * scaleX
        val origMinY = minY * scaleY
        val origMaxY = (maxY + 1) * scaleY

        val bw = (origMaxX - origMinX)
        val bh = (origMaxY - origMinY)
        val expandW = bw * expandRatio
        val expandH = bh * expandRatio

        val left = max(0f, origMinX - expandW)
        val top = max(0f, origMinY - expandH)
        val right = min(srcWidth.toFloat(), origMaxX + expandW)
        val bottom = min(srcHeight.toFloat(), origMaxY + expandH)

        return FloatRect(left, top, right, bottom)
    }

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
        return computeBboxFromMaskStrict(mask, width, height, width, height, threshold, expandRatio)
            ?: FloatRect(0f, 0f, width.toFloat(), height.toFloat())
    }

    /**
     * Compute active mask area above threshold.
     */
    fun computeMaskArea(
        mask: FloatArray,
        threshold: Float = Sam2TensorContract.MASK_THRESHOLD
    ): Float {
        var count = 0
        for (v in mask) {
            if (v > threshold) {
                count++
            }
        }
        return count.toFloat()
    }

    /**
     * Applies fast sigmoid activation in-place.
     */
    fun fastSigmoidInPlace(arr: FloatArray) {
        for (i in arr.indices) {
            val x = arr[i]
            if (x >= 4.0f) {
                arr[i] = 1.0f
            } else if (x <= -4.0f) {
                arr[i] = 0.0f
            } else {
                arr[i] = 1.0f / (1.0f + kotlin.math.exp(-x))
            }
        }
    }

    /**
     * Applies standard sigmoid activation in-place.
     */
    fun sigmoidInPlace(arr: FloatArray) {
        fastSigmoidInPlace(arr)
    }



    /**
     * Bilinear interpolation to resize low-res mask [maskW x maskH] to source [srcW x srcH].
     * Uses OpenCV-exact pixel-center bilinear interpolation.
     */
    fun resizeMaskBilinear(
        srcMask: FloatArray,
        maskW: Int,
        maskH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        val scaleX = maskW.toFloat() / dstW.toFloat()
        val scaleY = maskH.toFloat() / dstH.toFloat()

        for (y in 0 until dstH) {
            val srcY = (y + 0.5f) * scaleY - 0.5f
            val srcYClamped = srcY.coerceIn(0f, (maskH - 1).toFloat())
            val y0 = kotlin.math.floor(srcYClamped).toInt()
            val y1 = kotlin.math.min(y0 + 1, maskH - 1)
            val dy = srcYClamped - y0

            val rowDst = y * dstW
            val row0 = y0 * maskW
            val row1 = y1 * maskW

            for (x in 0 until dstW) {
                val srcX = (x + 0.5f) * scaleX - 0.5f
                val srcXClamped = srcX.coerceIn(0f, (maskW - 1).toFloat())
                val x0 = kotlin.math.floor(srcXClamped).toInt()
                val x1 = kotlin.math.min(x0 + 1, maskW - 1)
                val dx = srcXClamped - x0

                val v00 = srcMask[row0 + x0]
                val v01 = srcMask[row0 + x1]
                val v10 = srcMask[row1 + x0]
                val v11 = srcMask[row1 + x1]

                val v0 = v00 * (1f - dx) + v01 * dx
                val v1 = v10 * (1f - dx) + v11 * dx
                val v = v0 * (1f - dy) + v1 * dy

                out[rowDst + x] = v
            }
        }
        return out
    }

}

