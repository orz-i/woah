package com.danceanon.native.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.geometry.ModelCoordinateMapper
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class YoloOnnxSegmenterSmokeTest {

    @Test
    fun testYoloOnnxSegmenterInitializationAndBufferInferenceSmoke() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val segmenter = YoloOnnxSegmenter(context)

        try {
            // 1. Initialize ONNX runtime session and load model from assets
            segmenter.initialize()

            // 2. Prepare 640x640 dummy RGBA DirectByteBuffer
            val width = 640
            val height = 640
            val rgbaBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

            // Fill with mid-gray test pattern
            for (i in 0 until width * height) {
                rgbaBuffer.put(128.toByte()) // R
                rgbaBuffer.put(128.toByte()) // G
                rgbaBuffer.put(128.toByte()) // B
                rgbaBuffer.put(255.toByte()) // A
            }
            rgbaBuffer.rewind()

            val mapper = ModelCoordinateMapper(
                srcWidth = width,
                srcHeight = height,
                modelInputSize = 640,
                protoSize = 160
            )

            // 3. Perform inference
            val result = segmenter.segmentRgbaSync(
                rgbaBuffer = rgbaBuffer,
                mapper = mapper,
                timestampUs = 0L
            )

            // 4. Validate output integrity
            assertNotNull(result, "Inference result must not be null")
            assertTrue(result.inferenceTimeMs >= 0, "Inference timing must be non-negative")
            assertNotNull(result.persons, "Detections list must be non-null")

            for (detection in result.persons) {
                assertNotNull(detection.bbox, "Detection bbox must be present")
                assertTrue(detection.confidence in 0f..1f, "Confidence must be within [0, 1]")
                val mask = detection.mask
                if (mask != null) {
                    assertEquals(160, mask.width)
                    assertEquals(160, mask.height)
                    assertNotNull(mask.buffer)
                }
            }
        } finally {
            // 5. Ensure close() releases native sessions without leaks
            segmenter.close()
        }
    }
}
