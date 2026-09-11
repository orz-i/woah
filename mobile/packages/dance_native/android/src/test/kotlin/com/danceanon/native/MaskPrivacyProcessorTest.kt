package com.danceanon.native

import com.danceanon.native.inference.NativeMask
import com.danceanon.native.privacy.MaskPrivacyProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MaskPrivacyProcessorTest {

    @Test
    fun thresholdProducesCanonicalBinaryContour() {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(0.toByte())
            put(127.toByte())
            put(128.toByte())
            put(255.toByte())
            rewind()
        }
        val mask = NativeMask(
            width = 2,
            height = 2,
            buffer = buf,
            originalWidth = 20,
            originalHeight = 20
        )

        val thresholded = MaskPrivacyProcessor.threshold(mask, thresholdByte = 128)
        val out = thresholded.buffer.apply { rewind() }
        assertEquals(0, out.get().toInt() and 0xFF)
        assertEquals(0, out.get().toInt() and 0xFF)
        assertEquals(255, out.get().toInt() and 0xFF)
        assertEquals(255, out.get().toInt() and 0xFF)
    }

    @Test
    fun testDilationExpandsSinglePixel() {
        val size = 5
        val buf = ByteBuffer.allocateDirect(size * size).order(ByteOrder.nativeOrder())
        // Fill all zeros
        for (i in 0 until size * size) buf.put(0.toByte())
        // Put 1 white pixel at center (2, 2)
        buf.put(2 * size + 2, 255.toByte())
        buf.rewind()

        val mask = NativeMask(
            width = size,
            height = size,
            buffer = buf,
            originalWidth = 100,
            originalHeight = 100
        )

        val dilated = MaskPrivacyProcessor.dilate(mask, radius = 1)
        val outBuf = dilated.buffer
        outBuf.rewind()

        // 3x3 block around (2, 2) should now be 255
        for (y in 0 until size) {
            for (x in 0 until size) {
                val b = outBuf.get(y * size + x).toInt() and 0xFF
                if (x in 1..3 && y in 1..3) {
                    assertEquals(255, b, "Pixel at ($x, $y) should be dilated to 255")
                } else {
                    assertEquals(0, b, "Pixel at ($x, $y) outside dilation should be 0")
                }
            }
        }
    }
}
