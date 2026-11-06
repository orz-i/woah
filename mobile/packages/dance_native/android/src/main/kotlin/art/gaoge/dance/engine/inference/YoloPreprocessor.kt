package art.gaoge.dance.engine.inference

import android.graphics.Bitmap
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
        colOrder: RgbaColOrder = RgbaColOrder.LEFT_TO_RIGHT,
        materializeFloatBuffer: Boolean = true
    ): PreprocessResult {
        val expectedBytes = workspace.inputSize * workspace.inputSize * 4
        require(rgbaBuffer.capacity() >= expectedBytes) {
            "RGBA buffer too small: capacity ${rgbaBuffer.capacity()} < expected $expectedBytes"
        }

        val floatBuffer = workspace.floatBuffer
        val byteBuffer = workspace.byteBuffer
        floatBuffer.clear()
        byteBuffer.clear()
        art.gaoge.dance.engine.core.FrameKernel.normalizePixels(
            input = rgbaBuffer, width = workspace.inputSize, height = workspace.inputSize,
            rowStride = workspace.inputSize * 4, output = workspace.floatArray,
            flipRows = rowOrder == RgbaRowOrder.BOTTOM_TO_TOP,
            flipColumns = colOrder == RgbaColOrder.RIGHT_TO_LEFT
        )

        // Preserve the existing PreprocessResult FloatBuffer contract for
        // tests/alternate callers using one bulk native copy instead of 1.2M
        // absolute FloatBuffer.put calls. The LiteRT export hot path writes
        // floatArray directly, so it explicitly skips this otherwise-unused
        // ~4.9 MB materialization per frame.
        if (materializeFloatBuffer) {
            floatBuffer.put(workspace.floatArray)
            floatBuffer.position(0)
            byteBuffer.position(0)
        }


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
        val geometry = art.gaoge.dance.engine.core.FrameKernel.letterbox(srcW, srcH, inputSize, inputSize)
        val numPixels = inputSize * inputSize
        val byteBuffer = ByteBuffer.allocateDirect(numPixels * 3 * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Native bitmap storage transport: ARGB integers -> explicit RGBA bytes.
        for (i in pixels.indices) {
            val value = pixels[i]
            pixels[i] = (value and 0xff00ff00.toInt()) or
                ((value ushr 16) and 255) or ((value and 255) shl 16)
        }
        val rgba = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        rgba.asIntBuffer().put(pixels)
        val letterboxed = if (bitmap.width == inputSize && bitmap.height == inputSize) rgba else {
            ByteBuffer.allocateDirect(inputSize * inputSize * 4).also {
                art.gaoge.dance.engine.core.FrameKernel.resizeRgba(rgba, bitmap.width, bitmap.height,
                    bitmap.width * 4, it, inputSize, inputSize)
            }
        }
        val floats = FloatArray(numPixels * 3)
        art.gaoge.dance.engine.core.FrameKernel.normalizePixels(letterboxed, inputSize, inputSize, inputSize * 4, floats)
        floatBuffer.put(floats)
        floatBuffer.position(0)
        byteBuffer.position(0)

        return PreprocessResult(
            floatBuffer = floatBuffer,
            byteBuffer = byteBuffer,
            scale = geometry[0],
            padLeft = geometry[3],
            padTop = geometry[4],
            srcWidth = srcW,
            srcHeight = srcH,
            inputSize = inputSize
        )
    }

}
