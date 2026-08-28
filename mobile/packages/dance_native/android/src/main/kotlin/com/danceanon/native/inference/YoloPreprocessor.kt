package com.danceanon.native.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.danceanon.native.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

enum class RgbaRowOrder {
    TOP_TO_BOTTOM,
    BOTTOM_TO_TOP
}

enum class RgbaColOrder {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT
}

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

class PreprocessorWorkspace(val inputSize: Int = 640) {
    val numPixels = inputSize * inputSize
    val floatArray = FloatArray(1 * 3 * numPixels)
    val byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 3 * numPixels * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    val floatBuffer: FloatBuffer = byteBuffer.asFloatBuffer()
}

object YoloPreprocessor {

    const val DEFAULT_INPUT_SIZE = 640

    fun processRgbaBuffer(
        rgbaBuffer: ByteBuffer,
        mapper: ModelCoordinateMapper,
        workspace: PreprocessorWorkspace,
        rowOrder: RgbaRowOrder = RgbaRowOrder.TOP_TO_BOTTOM,
        colOrder: RgbaColOrder = RgbaColOrder.LEFT_TO_RIGHT
    ): PreprocessResult {
        val expectedBytes = workspace.inputSize * workspace.inputSize * 4
        require(rgbaBuffer.capacity() >= expectedBytes) {
            "RGBA buffer too small: capacity ${rgbaBuffer.capacity()} < expected $expectedBytes"
        }

        val inputSize = workspace.inputSize
        val numPixels = workspace.numPixels
        val inputFloats = workspace.floatArray
        val floatBuffer = workspace.floatBuffer
        val byteBuffer = workspace.byteBuffer
        floatBuffer.clear()
        byteBuffer.clear()

        val rOffset = 0
        val gOffset = numPixels
        val bOffset = 2 * numPixels

        val previousOrder = rgbaBuffer.order()
        rgbaBuffer.order(ByteOrder.LITTLE_ENDIAN)
        try {
            for (dstY in 0 until inputSize) {
                val srcY = when (rowOrder) {
                    RgbaRowOrder.TOP_TO_BOTTOM -> dstY
                    RgbaRowOrder.BOTTOM_TO_TOP -> inputSize - 1 - dstY
                }

                val srcRowOffset = srcY * inputSize * 4
                val dstRowOffset = dstY * inputSize
                var srcByteOffset = when (colOrder) {
                    RgbaColOrder.LEFT_TO_RIGHT -> srcRowOffset
                    RgbaColOrder.RIGHT_TO_LEFT -> srcRowOffset + (inputSize - 1) * 4
                }
                val srcStep = if (colOrder == RgbaColOrder.LEFT_TO_RIGHT) 4 else -4

                for (dstX in 0 until inputSize) {
                    // RGBA bytes read as a little-endian Int become 0xAABBGGRR.
                    // One absolute getInt replaces three direct-buffer byte gets.
                    val rgba = rgbaBuffer.getInt(srcByteOffset)
                    val dstPixelIndex = dstRowOffset + dstX
                    inputFloats[rOffset + dstPixelIndex] = (rgba and 0xFF) * INV_255
                    inputFloats[gOffset + dstPixelIndex] = ((rgba ushr 8) and 0xFF) * INV_255
                    inputFloats[bOffset + dstPixelIndex] = ((rgba ushr 16) and 0xFF) * INV_255
                    srcByteOffset += srcStep
                }
            }
        } finally {
            rgbaBuffer.order(previousOrder)
        }

        // Preserve the existing PreprocessResult FloatBuffer contract for
        // tests/alternate callers using one bulk native copy instead of 1.2M
        // absolute FloatBuffer.put calls. The LiteRT hot path writes floatArray
        // directly and does not copy this buffer back into another FloatArray.
        floatBuffer.put(inputFloats)
        floatBuffer.position(0)
        byteBuffer.position(0)


        return PreprocessResult(
            floatBuffer = floatBuffer,
            byteBuffer = byteBuffer,
            scale = mapper.scale,
            padLeft = mapper.padLeft,
            padTop = mapper.padTop,
            srcWidth = mapper.srcWidth,
            srcHeight = mapper.srcHeight,
            inputSize = mapper.modelInputSize
        )
    }


    fun processBitmap(
        bitmap: Bitmap,
        inputSize: Int = DEFAULT_INPUT_SIZE,
        origWidth: Int = bitmap.width,
        origHeight: Int = bitmap.height
    ): PreprocessResult {
        val srcW = origWidth
        val srcH = origHeight

        val scale = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        val padLeft = (inputSize - scaledW) / 2f
        val padTop = (inputSize - scaledH) / 2f

        val numPixels = inputSize * inputSize
        val byteBuffer = ByteBuffer.allocateDirect(1 * 3 * numPixels * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val pixels = IntArray(numPixels)

        if (bitmap.width == inputSize && bitmap.height == inputSize) {
            bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        } else {
            val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(letterboxed)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawColor(Color.rgb(114, 114, 114))

            val srcScaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
            canvas.drawBitmap(srcScaled, padLeft, padTop, paint)
            if (srcScaled != bitmap) {
                srcScaled.recycle()
            }

            letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            letterboxed.recycle()
        }

        val rOffset = 0
        val gOffset = numPixels
        val bOffset = 2 * numPixels

        for (i in 0 until numPixels) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            floatBuffer.put(rOffset + i, r)
            floatBuffer.put(gOffset + i, g)
            floatBuffer.put(bOffset + i, b)
        }
        floatBuffer.position(0)
        byteBuffer.position(0)

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

    private const val INV_255 = 1.0f / 255.0f
}
