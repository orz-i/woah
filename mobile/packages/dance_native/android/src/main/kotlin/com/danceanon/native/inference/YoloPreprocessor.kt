package com.danceanon.native.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

data class PreprocessResult(
    val floatBuffer: FloatBuffer,
    val byteBuffer: ByteBuffer,
    val scale: Float,
    val padLeft: Float,
    val padTop: Float,
    val srcWidth: Int,
    val srcHeight: Int,
    val inputSize: Int = 640
)

object YoloPreprocessor {

    const val DEFAULT_INPUT_SIZE = 640

    fun processBitmap(bitmap: Bitmap, inputSize: Int = DEFAULT_INPUT_SIZE): PreprocessResult {
        val srcW = bitmap.width
        val srcH = bitmap.height

        val scale = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        val padLeft = (inputSize - scaledW) / 2f
        val padTop = (inputSize - scaledH) / 2f

        // Create letterboxed 640x640 Bitmap with grey padding (114, 114, 114)
        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val srcScaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        canvas.drawBitmap(srcScaled, padLeft, padTop, paint)
        if (srcScaled != bitmap) {
            srcScaled.recycle()
        }

        // Convert to (1, 640, 640, 3) Float32 Buffer normalized to [0.0, 1.0]
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        letterboxed.recycle()

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            floatBuffer.put(r)
            floatBuffer.put(g)
            floatBuffer.put(b)
        }
        floatBuffer.rewind()
        byteBuffer.rewind()

        return PreprocessResult(
            floatBuffer = floatBuffer,
            byteBuffer = byteBuffer,
            scale = scale,
            padLeft = padLeft,
            padTop = padTop,
            srcWidth = srcW,
            srcHeight = srcH,
            inputSize = inputSize
        )
    }
}
