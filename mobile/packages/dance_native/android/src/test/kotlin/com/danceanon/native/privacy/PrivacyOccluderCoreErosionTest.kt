package com.danceanon.native.privacy

import com.danceanon.native.inference.NativeMask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class PrivacyOccluderCoreErosionTest {

    private fun createSolidRectMask(size: Int, xRange: IntRange, yRange: IntRange): NativeMask {
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                buf.put(if (x in xRange && y in yRange) 255.toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(width = size, height = size, buffer = buf, originalWidth = 640, originalHeight = 640)
    }

    @Test
    fun testErodeReducesMaskDimensionsByRadius() {
        val size = 64
        // 20x20 solid block: x from 10 to 29 (20 px), y from 10 to 29 (20 px) = 400 pixels
        val rawMask = createSolidRectMask(size, 10..29, 10..29)

        val erodedMask = MaskPrivacyProcessor.erode(rawMask, radius = 1)

        val eBuf = erodedMask.buffer
        eBuf.rewind()
        var nonZeroCount = 0
        for (i in 0 until size * size) {
            if ((eBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }

        // After radius=1 erosion: 18x18 solid block = 324 pixels
        assertEquals(324, nonZeroCount, "Erosion with radius=1 on 20x20 block must produce 18x18 (324 pixels) core")
    }
}
