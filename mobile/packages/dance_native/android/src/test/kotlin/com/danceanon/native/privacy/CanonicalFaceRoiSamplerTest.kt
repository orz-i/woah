package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random
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

    @Test
    fun `prepared canonical input matches direct sampler byte exactly`() {
        val mapper = ModelCoordinateMapper(srcWidth = 37, srcHeight = 23, modelInputSize = 40, protoSize = 10)
        val input = ByteBuffer.allocateDirect(40 * 40 * 4)
        repeat(40 * 40) { pixel ->
            input.put(((pixel * 11 + 3) and 0xff).toByte())
            input.put(((pixel * 5 + 17) and 0xff).toByte())
            input.put(((pixel * 13 + 29) and 0xff).toByte())
            input.put(((pixel * 7 + 251) and 0xff).toByte())
        }
        input.position(17)
        val originalPosition = input.position()
        val originalOrder = input.order()
        val workspace = CanonicalFaceRoiSampler.Workspace(13)
        val prepared = CanonicalFaceRoiSampler.prepareCanonicalInput(input, 40, workspace)
        assertEquals(originalPosition, input.position())
        assertEquals(originalOrder, input.order())

        val rects = listOf(
            FloatRect(1.25f, 0.5f, 20.75f, 15.5f),
            FloatRect(12.5f, 4.25f, 36.5f, 22.5f),
            FloatRect(0f, 0f, 37f, 23f)
        )
        rects.forEachIndexed { index, rect ->
            val direct = ByteBuffer.allocateDirect(13 * 13 * 4)
            val cached = ByteBuffer.allocateDirect(13 * 13 * 4)
            CanonicalFaceRoiSampler.sampleTopDown(input, mapper, rect, 13, direct, workspace)
            CanonicalFaceRoiSampler.sampleTopDown(
                canonicalRgbaBottomUp = input,
                mapper = mapper,
                sourceRect = rect,
                outputSize = 13,
                output = cached,
                workspace = workspace,
                preparedCanonicalInput = prepared
            )
            for (byteIndex in 0 until 13 * 13 * 4) {
                assertEquals(direct.get(byteIndex), cached.get(byteIndex), "roi=$index byte=$byteIndex")
            }
        }
        assertEquals(originalPosition, input.position())
        assertEquals(originalOrder, input.order())
    }

    @Test
    fun `optimized sampler matches scalar reference for varied deterministic rois`() {
        val mapper = ModelCoordinateMapper(srcWidth = 37, srcHeight = 23, modelInputSize = 40, protoSize = 10)
        val input = ByteBuffer.allocateDirect(40 * 40 * 4)
        repeat(40 * 40) { pixel ->
            input.put(((pixel * 11 + 3) and 0xff).toByte())
            input.put(((pixel * 5 + 17) and 0xff).toByte())
            input.put(((pixel * 13 + 29) and 0xff).toByte())
            input.put(((pixel * 7 + 251) and 0xff).toByte())
        }
        input.rewind()
        val random = Random(20260904)
        repeat(32) {
            val left = random.nextFloat() * 18f
            val top = random.nextFloat() * 10f
            val width = 4f + random.nextFloat() * (37f - left - 4f)
            val height = 4f + random.nextFloat() * (23f - top - 4f)
            val rect = FloatRect(left, top, left + width, top + height)
            val size = 3 + random.nextInt(14)
            val expected = ByteBuffer.allocateDirect(size * size * 4)
            val actual = ByteBuffer.allocateDirect(size * size * 4)

            scalarReference(input, mapper, rect, size, expected)
            CanonicalFaceRoiSampler.sampleTopDown(input, mapper, rect, size, actual)

            for (i in 0 until size * size * 4) {
                assertEquals(expected.get(i), actual.get(i), "roi=$it byte=$i rect=$rect")
            }
        }
    }

    private fun scalarReference(
        inputBuffer: ByteBuffer,
        mapper: ModelCoordinateMapper,
        sourceRect: FloatRect,
        outputSize: Int,
        output: ByteBuffer
    ) {
        val fp = 256
        val modelSize = mapper.modelInputSize
        val input = inputBuffer.duplicate()
        val dst = output.duplicate().apply { clear() }
        val rectWidth = sourceRect.width.coerceAtLeast(1f)
        val rectHeight = sourceRect.height.coerceAtLeast(1f)
        for (outY in 0 until outputSize) {
            val srcY = sourceRect.top + ((outY + 0.5f) / outputSize) * rectHeight
            val modelPixelY = (mapper.sourceToModelY(srcY) - 0.5f)
                .coerceIn(0f, (modelSize - 1).toFloat())
            val y0 = floor(modelPixelY.toDouble()).toInt()
            val y1 = (y0 + 1).coerceAtMost(modelSize - 1)
            val wy1 = ((modelPixelY - y0) * fp).roundToInt().coerceIn(0, fp)
            val wy0 = fp - wy1
            val bufferY0 = modelSize - 1 - y0
            val bufferY1 = modelSize - 1 - y1
            for (outX in 0 until outputSize) {
                val srcX = sourceRect.left + ((outX + 0.5f) / outputSize) * rectWidth
                val modelPixelX = (mapper.sourceToModelX(srcX) - 0.5f)
                    .coerceIn(0f, (modelSize - 1).toFloat())
                val x0 = floor(modelPixelX.toDouble()).toInt()
                val x1 = (x0 + 1).coerceAtMost(modelSize - 1)
                val wx1 = ((modelPixelX - x0) * fp).roundToInt().coerceIn(0, fp)
                val wx0 = fp - wx1
                val p00 = (bufferY0 * modelSize + x0) * 4
                val p10 = (bufferY0 * modelSize + x1) * 4
                val p01 = (bufferY1 * modelSize + x0) * 4
                val p11 = (bufferY1 * modelSize + x1) * 4
                repeat(4) { channel ->
                    val v00 = input.get(p00 + channel).toInt() and 0xff
                    val v10 = input.get(p10 + channel).toInt() and 0xff
                    val v01 = input.get(p01 + channel).toInt() and 0xff
                    val v11 = input.get(p11 + channel).toInt() and 0xff
                    val topMix = v00 * wx0 + v10 * wx1
                    val bottomMix = v01 * wx0 + v11 * wx1
                    val value = ((topMix * wy0 + bottomMix * wy1 + (fp * fp / 2)) / (fp * fp))
                        .coerceIn(0, 255)
                    dst.put(value.toByte())
                }
            }
        }
        output.position(0)
        output.limit(outputSize * outputSize * 4)
    }
}
