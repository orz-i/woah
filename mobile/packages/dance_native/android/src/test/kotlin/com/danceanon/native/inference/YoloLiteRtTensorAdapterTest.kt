package com.danceanon.native.inference

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YoloLiteRtTensorAdapterTest {

    @Test
    fun `array production path matches buffer compatibility path exactly`() {
        val output0 = FloatArray(YoloLiteRtTensorAdapter.ATTR_COUNT * YoloLiteRtTensorAdapter.ANCHOR_COUNT)
        val output1 = FloatArray(
            YoloLiteRtTensorAdapter.PROTO_CHANNELS *
                YoloLiteRtTensorAdapter.PROTO_SIZE *
                YoloLiteRtTensorAdapter.PROTO_SIZE
        ) { index ->
            ((index * 17 % 41) - 20) / 23f
        }

        val anchor = 7
        output0[0 * YoloLiteRtTensorAdapter.ANCHOR_COUNT + anchor] = 0.50f
        output0[1 * YoloLiteRtTensorAdapter.ANCHOR_COUNT + anchor] = 0.50f
        output0[2 * YoloLiteRtTensorAdapter.ANCHOR_COUNT + anchor] = 0.25f
        output0[3 * YoloLiteRtTensorAdapter.ANCHOR_COUNT + anchor] = 0.50f
        output0[4 * YoloLiteRtTensorAdapter.ANCHOR_COUNT + anchor] = 0.90f
        for (c in 0 until YoloLiteRtTensorAdapter.PROTO_CHANNELS) {
            output0[(84 + c) * YoloLiteRtTensorAdapter.ANCHOR_COUNT + anchor] =
                ((c * 11 % 19) - 9) / 13f
        }

        val adapter = YoloLiteRtTensorAdapter(
            output0Shape = listOf(1, YoloLiteRtTensorAdapter.ATTR_COUNT, YoloLiteRtTensorAdapter.ANCHOR_COUNT),
            output1Shape = listOf(
                1,
                YoloLiteRtTensorAdapter.PROTO_CHANNELS,
                YoloLiteRtTensorAdapter.PROTO_SIZE,
                YoloLiteRtTensorAdapter.PROTO_SIZE
            )
        )
        val preprocess = PreprocessResult(
            floatBuffer = FloatBuffer.allocate(0),
            byteBuffer = ByteBuffer.allocate(0),
            scale = 1f,
            padLeft = 0f,
            padTop = 0f,
            srcWidth = 640,
            srcHeight = 640,
            inputSize = 640
        )

        val bufferResult = adapter.parseDetections(
            output0Buffer = FloatBuffer.wrap(output0),
            output1Buffer = FloatBuffer.wrap(output1),
            preprocess = preprocess
        )
        val arrayResult = adapter.parseDetections(
            output0 = output0,
            output1 = output1,
            preprocess = preprocess
        )

        assertEquals(bufferResult.size, arrayResult.size)
        assertEquals(1, arrayResult.size)
        val bufferDetection = bufferResult.single()
        val arrayDetection = arrayResult.single()
        assertEquals(bufferDetection.bbox, arrayDetection.bbox)
        assertEquals(bufferDetection.confidence, arrayDetection.confidence)
        assertEquals(bufferDetection.footY, arrayDetection.footY)
        val bufferMask = assertNotNull(bufferDetection.mask)
        val arrayMask = assertNotNull(arrayDetection.mask)
        assertEquals(bufferMask.width, arrayMask.width)
        assertEquals(bufferMask.height, arrayMask.height)
        assertTrue(maskBytes(bufferMask).contentEquals(maskBytes(arrayMask)))
    }

    private fun maskBytes(mask: NativeMask): ByteArray {
        val duplicate = mask.buffer.duplicate()
        duplicate.rewind()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

    @Test
    fun `array production path exposes decode substage telemetry`() {
        val adapter = YoloLiteRtTensorAdapter(
            output0Shape = listOf(1, YoloLiteRtTensorAdapter.ATTR_COUNT, YoloLiteRtTensorAdapter.ANCHOR_COUNT),
            output1Shape = listOf(1, YoloLiteRtTensorAdapter.PROTO_CHANNELS, YoloLiteRtTensorAdapter.PROTO_SIZE, YoloLiteRtTensorAdapter.PROTO_SIZE)
        )
        val output0 = FloatArray(YoloLiteRtTensorAdapter.ATTR_COUNT * YoloLiteRtTensorAdapter.ANCHOR_COUNT)
        val output1 = FloatArray(
            YoloLiteRtTensorAdapter.PROTO_CHANNELS *
                YoloLiteRtTensorAdapter.PROTO_SIZE *
                YoloLiteRtTensorAdapter.PROTO_SIZE
        )
        val preprocess = PreprocessResult(
            floatBuffer = FloatBuffer.allocate(0),
            byteBuffer = ByteBuffer.allocate(0),
            scale = 1f,
            padLeft = 0f,
            padTop = 0f,
            srcWidth = 640,
            srcHeight = 640,
            inputSize = 640
        )
        val timings = linkedMapOf<String, Long>()

        adapter.parseDetections(
            output0 = output0,
            output1 = output1,
            preprocess = preprocess,
            stageTimingsMs = timings
        )

        assertTrue(timings.containsKey("yoloCandidateScan"))
        assertTrue(timings.containsKey("yoloMaskAwareNms"))
        assertTrue(timings.containsKey("yoloMaskDecode"))
        assertTrue(timings.containsKey("yoloMaskLogitDecode"))
        assertTrue(timings.containsKey("yoloMaskSoftMaterialize"))
        assertTrue(timings.containsKey("yoloMaskIouScan"))
        assertTrue(timings.containsKey("yoloMaskMaterialize"))
    }
}
