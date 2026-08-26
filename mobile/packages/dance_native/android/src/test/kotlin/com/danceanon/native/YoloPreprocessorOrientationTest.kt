package com.danceanon.native

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.PreprocessorWorkspace
import com.danceanon.native.inference.YoloPreprocessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YoloPreprocessorOrientationTest {

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

        // Simulate FBO memory read by glReadPixels where video TOP is rendered at lower FBO rows
        // FBO size = 640x640 RGBA
        val numPixels = 640 * 640
        val rgbaBuffer = ByteBuffer.allocateDirect(numPixels * 4).order(ByteOrder.nativeOrder())

        // In FBO layout produced by InferenceRenderer:
        // rows 0..139: letterbox padding (gray 114)
        // row 140: video TOP (padTop)
        // rows 140..176: top 10% of video containing bright person pixels
        // rows 177..499: remaining video content (black)
        // rows 500..639: letterbox padding (gray 114)
        val gray = 114.toByte()
        val white = 255.toByte()
        val black = 0.toByte()

        for (row in 0 until 640) {
            for (col in 0 until 640) {
                val pixelVal = when {
                    row < 140 -> gray
                    row in 140..176 && col in 66..133 -> white // Person in top 10%
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
        val result = YoloPreprocessor.processRgbaBuffer(rgbaBuffer, mapper, workspace)
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
}
