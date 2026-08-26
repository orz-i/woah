package com.danceanon.native.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InferenceFrameReader(
    val width: Int,
    val height: Int,
    private val targetSize: Int = 640
) : AutoCloseable {

    private val pixelBuffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    private var fullBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var scaledBitmap: Bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    private val scaledCanvas: Canvas = Canvas(scaledBitmap)
    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)

    val scale: Float = targetSize.toFloat() / maxOf(width, height).coerceAtLeast(1)
    val scaledW: Float = width * scale
    val scaledH: Float = height * scale
    val padLeft: Float = (targetSize - scaledW) / 2f
    val padTop: Float = (targetSize - scaledH) / 2f

    private val transformMatrix: Matrix = Matrix().apply {
        // glReadPixels reads from bottom to top (OpenGL coordinate system).
        // To produce a normal top-down image letterboxed into [padLeft, padTop, padLeft + scaledW, padTop + scaledH]:
        // 1. Invert Y with scale: y -> -scale * y
        // 2. Translate by (padLeft, padTop + scaledH) so that y=height (top of video) maps to padTop,
        //    and y=0 (bottom of video) maps to padTop + scaledH.
        postScale(scale, -scale)
        postTranslate(padLeft, padTop + scaledH)
    }

    fun captureFrame(): Bitmap {
        pixelBuffer.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
        pixelBuffer.rewind()
        fullBitmap.copyPixelsFromBuffer(pixelBuffer)

        scaledCanvas.drawColor(Color.rgb(114, 114, 114))
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
