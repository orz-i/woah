package com.danceanon.native.sam2

import android.graphics.Bitmap
import com.danceanon.native.inference.FloatRect

/**
 * Preprocessor for SAM2 Hiera Tiny.
 * Strictly decoupled from YOLO preprocessing.
 */
object Sam2Preprocessor {

    /**
     * Converts a visual RGB Bitmap to NCHW [1, 3, IMAGE_SIZE, IMAGE_SIZE] normalized FloatArray.
     * Uses standard ImageNet mean/std normalization.
     */
    fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val targetSize = Sam2TensorContract.IMAGE_SIZE
        val resized = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }

        val pixels = IntArray(targetSize * targetSize)
        resized.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        val channelSize = targetSize * targetSize
        val out = FloatArray(3 * channelSize)

        val meanR = Sam2TensorContract.NORM_MEAN[0]
        val meanG = Sam2TensorContract.NORM_MEAN[1]
        val meanB = Sam2TensorContract.NORM_MEAN[2]

        val stdR = Sam2TensorContract.NORM_STD[0]
        val stdG = Sam2TensorContract.NORM_STD[1]
        val stdB = Sam2TensorContract.NORM_STD[2]

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            out[i] = (r - meanR) / stdR
            out[channelSize + i] = (g - meanG) / stdG
            out[2 * channelSize + i] = (b - meanB) / stdB
        }

        if (resized !== bitmap) {
            resized.recycle()
        }

        return out
    }

    /**
     * Converts a visual RGB Bitmap directly into a pre-allocated Direct FloatBuffer (NCHW).
     */
    fun preprocessBitmapToBuffer(bitmap: Bitmap, targetBuffer: java.nio.FloatBuffer) {
        val targetSize = Sam2TensorContract.IMAGE_SIZE
        val resized = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }

        val pixels = IntArray(targetSize * targetSize)
        resized.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        val channelSize = targetSize * targetSize
        val meanR = Sam2TensorContract.NORM_MEAN[0]
        val meanG = Sam2TensorContract.NORM_MEAN[1]
        val meanB = Sam2TensorContract.NORM_MEAN[2]

        val stdR = Sam2TensorContract.NORM_STD[0]
        val stdG = Sam2TensorContract.NORM_STD[1]
        val stdB = Sam2TensorContract.NORM_STD[2]

        targetBuffer.clear()

        // R plane
        for (i in 0 until channelSize) {
            val r = ((pixels[i] shr 16) and 0xFF) / 255.0f
            targetBuffer.put(i, (r - meanR) / stdR)
        }
        // G plane
        for (i in 0 until channelSize) {
            val g = ((pixels[i] shr 8) and 0xFF) / 255.0f
            targetBuffer.put(channelSize + i, (g - meanG) / stdG)
        }
        // B plane
        for (i in 0 until channelSize) {
            val b = (pixels[i] and 0xFF) / 255.0f
            targetBuffer.put(2 * channelSize + i, (b - meanB) / stdB)
        }

        targetBuffer.position(3 * channelSize)
        targetBuffer.flip()

        if (resized !== bitmap) {
            resized.recycle()
        }
    }


    /**
     * Transforms a bounding box prompt from source visual coordinates [0, srcW] x [0, srcH]
     * to SAM2 model image coordinates [0, 1024] x [0, 1024].
     * Returns floatArrayOf(x1, y1, x2, y2).
     */
    fun transformBboxPrompt(bbox: FloatRect, srcWidth: Int, srcHeight: Int): FloatArray {
        val scaleX = Sam2TensorContract.IMAGE_SIZE.toFloat() / srcWidth.toFloat()
        val scaleY = Sam2TensorContract.IMAGE_SIZE.toFloat() / srcHeight.toFloat()

        val x1 = (bbox.left * scaleX).coerceIn(0f, Sam2TensorContract.IMAGE_SIZE.toFloat())
        val y1 = (bbox.top * scaleY).coerceIn(0f, Sam2TensorContract.IMAGE_SIZE.toFloat())
        val x2 = (bbox.right * scaleX).coerceIn(0f, Sam2TensorContract.IMAGE_SIZE.toFloat())
        val y2 = (bbox.bottom * scaleY).coerceIn(0f, Sam2TensorContract.IMAGE_SIZE.toFloat())

        return floatArrayOf(x1, y1, x2, y2)
    }
}

