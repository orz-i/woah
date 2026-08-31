package com.danceanon.native

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.PreprocessorWorkspace
import com.danceanon.native.inference.RgbaRowOrder
import com.danceanon.native.inference.YoloPreprocessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YoloPreprocessorOrientationTest {

    @Test
    fun glReadPixelsBottomUpBufferIsConvertedToTopDownYoloInput() {
        // Visual image (2x2):
        // Visual Row 0 (TOP):    RED   RED
        // Visual Row 1 (BOTTOM): BLUE  BLUE
        //
        // But OpenGL glReadPixels returns bottom-up buffer:
        // FBO Row 0 (BOTTOM): BLUE  BLUE
        // FBO Row 1 (TOP):    RED   RED
        val workspace = PreprocessorWorkspace(inputSize = 2)
        val mapper = ModelCoordinateMapper(srcWidth = 2, srcHeight = 2, modelInputSize = 2, protoSize = 2)

        val glReadPixelsBuffer = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder())
        // Row 0 (FBO bottom = Blue):
        glReadPixelsBuffer.put(0.toByte()).put(0.toByte()).put(255.toByte()).put(255.toByte()) // (0,0) Blue
        glReadPixelsBuffer.put(0.toByte()).put(0.toByte()).put(255.toByte()).put(255.toByte()) // (1,0) Blue
        // Row 1 (FBO top = Red):
        glReadPixelsBuffer.put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte()) // (0,1) Red
        glReadPixelsBuffer.put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte()) // (1,1) Red
        glReadPixelsBuffer.rewind()

        val result = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = glReadPixelsBuffer,
            mapper = mapper,
            workspace = workspace,
            rowOrder = RgbaRowOrder.BOTTOM_TO_TOP
        )
        val floatBuf = result.floatBuffer
        floatBuf.rewind()

        val numPixels = 4
        val rOffset = 0
        val bOffset = 2 * numPixels

        // NCHW Tensor Row 0 (Visual TOP) should be RED (R=1.0, B=0.0):
        assertEquals(1.0f, floatBuf.get(rOffset + 0), "Tensor pixel 0 R should be 1.0 (RED)")
        assertEquals(1.0f, floatBuf.get(rOffset + 1), "Tensor pixel 1 R should be 1.0 (RED)")
        assertEquals(0.0f, floatBuf.get(bOffset + 0), "Tensor pixel 0 B should be 0.0 (not BLUE)")
        assertEquals(0.0f, floatBuf.get(bOffset + 1), "Tensor pixel 1 B should be 0.0 (not BLUE)")

        // NCHW Tensor Row 1 (Visual BOTTOM) should be BLUE (R=0.0, B=1.0):
        assertEquals(0.0f, floatBuf.get(rOffset + 2), "Tensor pixel 2 R should be 0.0 (not RED)")
        assertEquals(0.0f, floatBuf.get(rOffset + 3), "Tensor pixel 3 R should be 0.0 (not RED)")
        assertEquals(1.0f, floatBuf.get(bOffset + 2), "Tensor pixel 2 B should be 1.0 (BLUE)")
        assertEquals(1.0f, floatBuf.get(bOffset + 3), "Tensor pixel 3 B should be 1.0 (BLUE)")
    }

    @Test
    fun topDownRgbaBufferIsNotFlipped() {
        // Standard top-down buffer:
        // Row 0: RED   RED
        // Row 1: BLUE  BLUE
        val workspace = PreprocessorWorkspace(inputSize = 2)
        val mapper = ModelCoordinateMapper(srcWidth = 2, srcHeight = 2, modelInputSize = 2, protoSize = 2)

        val topDownBuffer = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder())
        // Row 0 (Top = Red):
        topDownBuffer.put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte())
        topDownBuffer.put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte())
        // Row 1 (Bottom = Blue):
        topDownBuffer.put(0.toByte()).put(0.toByte()).put(255.toByte()).put(255.toByte())
        topDownBuffer.put(0.toByte()).put(0.toByte()).put(255.toByte()).put(255.toByte())
        topDownBuffer.rewind()

        val result = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = topDownBuffer,
            mapper = mapper,
            workspace = workspace,
            rowOrder = RgbaRowOrder.TOP_TO_BOTTOM
        )
        val floatBuf = result.floatBuffer
        floatBuf.rewind()

        val numPixels = 4
        val rOffset = 0
        val bOffset = 2 * numPixels

        // NCHW Tensor Row 0 should be RED:
        assertEquals(1.0f, floatBuf.get(rOffset + 0))
        assertEquals(1.0f, floatBuf.get(rOffset + 1))
        assertEquals(0.0f, floatBuf.get(bOffset + 0))
        assertEquals(0.0f, floatBuf.get(bOffset + 1))

        // NCHW Tensor Row 1 should be BLUE:
        assertEquals(0.0f, floatBuf.get(rOffset + 2))
        assertEquals(0.0f, floatBuf.get(rOffset + 3))
        assertEquals(1.0f, floatBuf.get(bOffset + 2))
        assertEquals(1.0f, floatBuf.get(bOffset + 3))
    }

    @Test
    fun testNonSymmetricTopObjectOrientationParity() {
        // Video: 1920x1080 (16:9 landscape)
        // Person is strictly located at the TOP 10% of the visual video (y: 0..108, x: 200..400)
        val srcW = 1920
        val srcH = 1080
        val mapper = ModelCoordinateMapper(srcW, srcH, modelInputSize = 640, protoSize = 160)

        // Expected letterbox metrics
        assertEquals(0f, mapper.padLeft)
        assertEquals(140f, mapper.padTop)
        assertEquals(640f, mapper.scaledW)
        assertEquals(360f, mapper.scaledH)

        // In FBO memory read by glReadPixels (bottom-up):
        // FBO rows 0..139: bottom letterbox padding (gray 114)
        // FBO rows 140..463: bottom 90% of video content (black)
        // FBO rows 464..499: top 10% of video containing bright person pixels (padTop area in top-down)
        // FBO rows 500..639: top letterbox padding (gray 114)
        val numPixels = 640 * 640
        val rgbaBuffer = ByteBuffer.allocateDirect(numPixels * 4).order(ByteOrder.nativeOrder())
        val gray = 114.toByte()
        val white = 255.toByte()
        val black = 0.toByte()

        for (row in 0 until 640) {
            for (col in 0 until 640) {
                val pixelVal = when {
                    row < 140 -> gray
                    row in 464..499 && col in 66..133 -> white // Person in top 10% (located near top of FBO)
                    row in 140 until 500 -> black
                    else -> gray
                }
                rgbaBuffer.put(pixelVal) // R
                rgbaBuffer.put(pixelVal) // G
                rgbaBuffer.put(pixelVal) // B
                rgbaBuffer.put(255.toByte()) // A
            }
        }
        rgbaBuffer.rewind()

        val workspace = PreprocessorWorkspace(640)
        val result = YoloPreprocessor.processRgbaBuffer(rgbaBuffer, mapper, workspace, rowOrder = RgbaRowOrder.BOTTOM_TO_TOP)
        val floatBuffer = result.floatBuffer
        floatBuffer.rewind()

        // In the resulting NCHW tensor, row 0..639 represents image from TOP to BOTTOM
        // Verify padding at top:
        val topPaddingVal = floatBuffer.get(50 * 640 + 320)
        assertTrue(topPaddingVal in 0.44f..0.46f, "Row 50 should be gray padding")

        // Verify person is at row 150 (top part of the 640x640 model input):
        val personPixelVal = floatBuffer.get(150 * 640 + 100)
        assertEquals(1.0f, personPixelVal, "Person should be detected at row 150 (visual TOP)")

        // Verify bottom part of video content (row 400) is black:
        val bottomContentVal = floatBuffer.get(400 * 640 + 100)
        assertEquals(0.0f, bottomContentVal, "Row 400 should be black background")

        // Verify bottom padding (row 600) is gray:
        val bottomPaddingVal = floatBuffer.get(600 * 640 + 320)
        assertTrue(bottomPaddingVal in 0.44f..0.46f, "Row 600 should be gray padding")
    }

    @Test
    fun rightToLeftColOrderFlipsHorizontalAxis() {
        // 2x2 image:
        // Row 0: RED   GREEN
        // Row 1: BLUE  YELLOW
        val workspace = PreprocessorWorkspace(inputSize = 2)
        val mapper = ModelCoordinateMapper(srcWidth = 2, srcHeight = 2, modelInputSize = 2, protoSize = 2)

        val buffer = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder())
        // Row 0: Red, Green
        buffer.put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte()) // (0,0) Red
        buffer.put(0.toByte()).put(255.toByte()).put(0.toByte()).put(255.toByte()) // (1,0) Green
        // Row 1: Blue, Yellow
        buffer.put(0.toByte()).put(0.toByte()).put(255.toByte()).put(255.toByte()) // (0,1) Blue
        buffer.put(255.toByte()).put(255.toByte()).put(0.toByte()).put(255.toByte()) // (1,1) Yellow
        buffer.rewind()

        val result = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = buffer,
            mapper = mapper,
            workspace = workspace,
            rowOrder = RgbaRowOrder.TOP_TO_BOTTOM,
            colOrder = com.danceanon.native.inference.RgbaColOrder.RIGHT_TO_LEFT
        )
        val floatBuf = result.floatBuffer
        floatBuf.rewind()

        val numPixels = 4
        val rOffset = 0
        val gOffset = numPixels

        // After RIGHT_TO_LEFT:
        // Row 0 pixel 0 should be GREEN (was pixel 1):
        assertEquals(0.0f, floatBuf.get(rOffset + 0))
        assertEquals(1.0f, floatBuf.get(gOffset + 0))

        // Row 0 pixel 1 should be RED (was pixel 0):
        assertEquals(1.0f, floatBuf.get(rOffset + 1))
        assertEquals(0.0f, floatBuf.get(gOffset + 1))
    }

    @Test
    fun optimizedRgbaArrayMatchesReferenceNormalizationAndFloatBufferContract() {
        val inputSize = 16
        val numPixels = inputSize * inputSize
        val workspace = PreprocessorWorkspace(inputSize)
        val mapper = ModelCoordinateMapper(
            srcWidth = inputSize,
            srcHeight = inputSize,
            modelInputSize = inputSize,
            protoSize = inputSize
        )
        val buffer = ByteBuffer.allocateDirect(numPixels * 4).order(ByteOrder.BIG_ENDIAN)

        for (value in 0 until numPixels) {
            val r = value and 0xFF
            val g = 255 - r
            val b = (r * 37) and 0xFF
            buffer.put(r.toByte())
            buffer.put(g.toByte())
            buffer.put(b.toByte())
            buffer.put(255.toByte())
        }
        buffer.rewind()

        val result = YoloPreprocessor.processRgbaBuffer(
            rgbaBuffer = buffer,
            mapper = mapper,
            workspace = workspace,
            rowOrder = RgbaRowOrder.TOP_TO_BOTTOM
        )

        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order(), "preprocessor must restore caller byte order")
        val floatBuffer = result.floatBuffer
        val rOffset = 0
        val gOffset = numPixels
        val bOffset = 2 * numPixels

        for (i in 0 until numPixels) {
            val r = i and 0xFF
            val g = 255 - r
            val b = (r * 37) and 0xFF
            val expectedR = r / 255.0f
            val expectedG = g / 255.0f
            val expectedB = b / 255.0f

            assertTrue(abs(workspace.floatArray[rOffset + i] - expectedR) <= 1e-7f)
            assertTrue(abs(workspace.floatArray[gOffset + i] - expectedG) <= 1e-7f)
            assertTrue(abs(workspace.floatArray[bOffset + i] - expectedB) <= 1e-7f)
            assertEquals(workspace.floatArray[rOffset + i], floatBuffer.get(rOffset + i))
            assertEquals(workspace.floatArray[gOffset + i], floatBuffer.get(gOffset + i))
            assertEquals(workspace.floatArray[bOffset + i], floatBuffer.get(bOffset + i))
        }
    }

    @Test
    fun productionNonMaterializedPathMatchesMaterializedFloatArrayForAllOrientations() {
        val inputSize = 8
        val numPixels = inputSize * inputSize
        val mapper = ModelCoordinateMapper(
            srcWidth = inputSize,
            srcHeight = inputSize,
            modelInputSize = inputSize,
            protoSize = inputSize
        )
        val buffer = ByteBuffer.allocateDirect(numPixels * 4).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until numPixels) {
            buffer.put(((i * 13) and 0xFF).toByte())
            buffer.put(((255 - i * 7) and 0xFF).toByte())
            buffer.put(((i * 29 + 11) and 0xFF).toByte())
            buffer.put(255.toByte())
        }
        buffer.rewind()

        for (rowOrder in RgbaRowOrder.entries) {
            for (colOrder in com.danceanon.native.inference.RgbaColOrder.entries) {
                val materializedWorkspace = PreprocessorWorkspace(inputSize)
                val productionWorkspace = PreprocessorWorkspace(inputSize)
                YoloPreprocessor.processRgbaBuffer(
                    rgbaBuffer = buffer,
                    mapper = mapper,
                    workspace = materializedWorkspace,
                    rowOrder = rowOrder,
                    colOrder = colOrder,
                    materializeFloatBuffer = true
                )
                YoloPreprocessor.processRgbaBuffer(
                    rgbaBuffer = buffer,
                    mapper = mapper,
                    workspace = productionWorkspace,
                    rowOrder = rowOrder,
                    colOrder = colOrder,
                    materializeFloatBuffer = false
                )
                for (i in 0 until numPixels * 3) {
                    assertEquals(
                        materializedWorkspace.floatArray[i],
                        productionWorkspace.floatArray[i],
                        "row=$rowOrder col=$colOrder index=$i"
                    )
                }
                assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
            }
        }
    }
}


