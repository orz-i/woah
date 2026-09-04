package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalFaceRoiSamplerTest {
    @Test
    fun `bottom up canonical buffer becomes top down face roi`() {
        val mapper = ModelCoordinateMapper(srcWidth = 4, srcHeight = 4, modelInputSize = 4, protoSize = 1)
        val input = ByteBuffer.allocateDirect(4 * 4 * 4)
        // Buffer row 0 is visual bottom. Encode visual y in red so orientation is observable.
        for (bufferY in 0 until 4) {
            val visualY = 3 - bufferY
            repeat(4) {
                input.put((visualY * 40).toByte())
                input.put(0)
                input.put(0)
                input.put(255.toByte())
            }
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(4 * 4 * 4)

        CanonicalFaceRoiSampler.sampleTopDown(
            canonicalRgbaBottomUp = input,
            mapper = mapper,
            sourceRect = FloatRect(0f, 0f, 4f, 4f),
            outputSize = 4,
            output = output
        )

        assertEquals(0, output.get(0).toInt() and 0xff)
        assertEquals(40, output.get(4 * 4).toInt() and 0xff)
        assertEquals(80, output.get(2 * 4 * 4).toInt() and 0xff)
        assertEquals(120, output.get(3 * 4 * 4).toInt() and 0xff)
    }

    @Test
    fun `same canonical pixels and source rect produce byte exact roi`() {
        val mapper = ModelCoordinateMapper(srcWidth = 8, srcHeight = 4, modelInputSize = 8, protoSize = 2)
        val input = ByteBuffer.allocateDirect(8 * 8 * 4)
        repeat(8 * 8) { pixel ->
            input.put((pixel and 0xff).toByte())
            input.put(((pixel * 3) and 0xff).toByte())
            input.put(((pixel * 7) and 0xff).toByte())
            input.put(255.toByte())
        }
        input.rewind()
        val a = ByteBuffer.allocateDirect(6 * 6 * 4)
        val b = ByteBuffer.allocateDirect(6 * 6 * 4)
        val rect = FloatRect(1.25f, 0.5f, 6.75f, 3.75f)

        CanonicalFaceRoiSampler.sampleTopDown(input, mapper, rect, 6, a)
        CanonicalFaceRoiSampler.sampleTopDown(input, mapper, rect, 6, b)

        for (i in 0 until 6 * 6 * 4) {
            assertEquals(a.get(i), b.get(i), "byte $i")
        }
    }
}
