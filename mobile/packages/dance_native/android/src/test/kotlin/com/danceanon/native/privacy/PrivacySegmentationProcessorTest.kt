package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivacySegmentationProcessorTest {

    @Test
    fun testUnifiedPrivacySafetyAppliesIdenticalDilation() {
        val size = 7
        val buf = ByteBuffer.allocateDirect(size * size).order(ByteOrder.nativeOrder())
        for (i in 0 until size * size) buf.put(0.toByte())
        // Center pixel (3, 3) is 255
        buf.put(3 * size + 3, 255.toByte())
        buf.rewind()

        val origMask = NativeMask(
            width = size,
            height = size,
            buffer = buf,
            originalWidth = 100,
            originalHeight = 100
        )

        val detection = PersonDetection(
            bbox = FloatRect(10f, 10f, 50f, 50f),
            confidence = 0.92f,
            mask = origMask,
            footY = 50f
        )

        val processor = PrivacySegmentationProcessor.DEFAULT
        val result = processor.applyPrivacySafety(listOf(detection))

        assertEquals(1, result.size)
        val safePerson = result.first()
        assertEquals(detection.bbox, safePerson.bbox)
        assertEquals(detection.confidence, safePerson.confidence)
        assertEquals(detection.footY, safePerson.footY)

        val safeMask = safePerson.mask
        assertNotNull(safeMask)

        // 3x3 region around (3, 3) must be 255
        val outBuf = safeMask.buffer
        outBuf.rewind()
        for (y in 2..4) {
            for (x in 2..4) {
                val b = outBuf.get(y * size + x).toInt() and 0xFF
                assertEquals(255, b, "Pixel at ($x, $y) should be dilated to 255")
            }
        }

        // Corner (0, 0) must be 0
        assertEquals(0, outBuf.get(0).toInt() and 0xFF)
    }

    @Test
    fun testHandlesEmptyAndNullMaskDetections() {
        val processor = PrivacySegmentationProcessor.DEFAULT
        assertTrue(processor.applyPrivacySafety(emptyList()).isEmpty())

        val detNoMask = PersonDetection(FloatRect(0f, 0f, 10f, 10f), 0.8f, mask = null)
        val result = processor.applyPrivacySafety(listOf(detNoMask))
        assertEquals(1, result.size)
        assertEquals(null, result[0].mask)
    }
}
