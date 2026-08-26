package com.danceanon.native.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InferenceFrameReader(
    private val width: Int,
    private val height: Int,
    private val targetSize: Int = 640
) : AutoCloseable {

    private val pixelBuffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    private var fullBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var scaledBitmap: Bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    private val scaledCanvas: Canvas = Canvas(scaledBitmap)
    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val transformMatrix: Matrix = Matrix().apply {
        val scale = targetSize.toFloat() / maxOf(width, height).coerceAtLeast(1)
        // Flip vertically because glReadPixels reads from bottom-left
        postScale(scale, -scale)
        postTranslate(0f, targetSize.toFloat())
    }

    fun captureFrame(): Bitmap {
        pixelBuffer.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
        pixelBuffer.rewind()
        fullBitmap.copyPixelsFromBuffer(pixelBuffer)

        scaledCanvas.drawBitmap(fullBitmap, transformMatrix, paint)
        return scaledBitmap
    }

    override fun close() {
        if (!fullBitmap.isRecycled) {
            fullBitmap.recycle()
        }
        if (!scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
        }
    }
}
