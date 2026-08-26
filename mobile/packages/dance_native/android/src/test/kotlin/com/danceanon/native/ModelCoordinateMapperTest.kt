package com.danceanon.native

import com.danceanon.native.geometry.ModelCoordinateMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCoordinateMapperTest {

    @Test
    fun test16x9LandscapeMapping() {
        val mapper = ModelCoordinateMapper(srcWidth = 1920, srcHeight = 1080, modelInputSize = 640, protoSize = 160)
        // scale = 640 / 1920 = 1/3
        // scaledW = 640, scaledH = 360, padLeft = 0, padTop = (640 - 360) / 2 = 140
        assertEquals(0f, mapper.padLeft, 0.01f)
        assertEquals(140f, mapper.padTop, 0.01f)

        // Center of source: (960, 540) -> (320, 320)
        val modelX = mapper.sourceToModelX(960f)
        val modelY = mapper.sourceToModelY(540f)
        assertEquals(320f, modelX, 0.1f)
        assertEquals(320f, modelY, 0.1f)

        // Roundtrip
        val srcX = mapper.modelToSourceX(modelX)
        val srcY = mapper.modelToSourceY(modelY)
        assertEquals(960f, srcX, 0.1f)
        assertEquals(540f, srcY, 0.1f)
    }

    @Test
    fun test9x16PortraitMapping() {
        val mapper = ModelCoordinateMapper(srcWidth = 1080, srcHeight = 1920, modelInputSize = 640, protoSize = 160)
        // scale = 640 / 1920 = 1/3
        // scaledW = 360, scaledH = 640, padLeft = 140, padTop = 0
        assertEquals(140f, mapper.padLeft, 0.01f)
        assertEquals(0f, mapper.padTop, 0.01f)

        val modelX = mapper.sourceToModelX(540f)
        val modelY = mapper.sourceToModelY(960f)
        assertEquals(320f, modelX, 0.1f)
        assertEquals(320f, modelY, 0.1f)

        val srcX = mapper.modelToSourceX(modelX)
        val srcY = mapper.modelToSourceY(modelY)
        assertEquals(540f, srcX, 0.1f)
        assertEquals(960f, srcY, 0.1f)
    }

    @Test
    fun testProtoMapping() {
        val mapper = ModelCoordinateMapper(srcWidth = 1920, srcHeight = 1080, modelInputSize = 640, protoSize = 160)
        val protoX = mapper.modelToProtoX(320f)
        val protoY = mapper.modelToProtoY(320f)
        assertEquals(80, protoX)
        assertEquals(80, protoY)
    }

    @Test
    fun testMultiAspectRatioLetterboxInvariants() {
        val testCases = listOf(
            Pair(1920, 1080), // Case A: 16:9 Landscape
            Pair(1280, 720),  // Case B: 16:9 Landscape
            Pair(1080, 1920), // Case C: 9:16 Portrait
            Pair(1024, 1024), // Case D: 1:1 Square
            Pair(1440, 1080), // Case E: 4:3
            Pair(2560, 1080), // 21:9 Ultrawide
            Pair(720, 1280),  // 9:16 Portrait
            Pair(3840, 2160)  // 4K UHD
        )

        val modelSizes = listOf(640, 320, 160)

        for ((srcW, srcH) in testCases) {
            for (mSize in modelSizes) {
                val mapper = ModelCoordinateMapper(srcWidth = srcW, srcHeight = srcH, modelInputSize = mSize)

                // Invariant 1: Inside model bounds
                assertTrue(mapper.scaledW <= mSize + 0.01f, "scaledW exceeds modelSize for ${srcW}x${srcH}")
                assertTrue(mapper.scaledH <= mSize + 0.01f, "scaledH exceeds modelSize for ${srcW}x${srcH}")

                // Invariant 2: Non-negative padding
                assertTrue(mapper.padLeft >= -0.01f, "padLeft is negative for ${srcW}x${srcH}")
                assertTrue(mapper.padTop >= -0.01f, "padTop is negative for ${srcW}x${srcH}")

                // Invariant 3: Aspect ratio preservation
                val srcAspect = srcW.toFloat() / srcH.toFloat()
                val scaledAspect = mapper.scaledW / mapper.scaledH
                assertEquals(srcAspect, scaledAspect, 0.01f, "Aspect ratio mismatch for ${srcW}x${srcH}")

                // Invariant 4: Fit-inside (at least one dimension reaches modelInputSize, no crop)
                val reachesW = kotlin.math.abs(mapper.scaledW - mSize) < 0.01f
                val reachesH = kotlin.math.abs(mapper.scaledH - mSize) < 0.01f
                assertTrue(reachesW || reachesH, "Fit-inside failed (neither dimension filled) for ${srcW}x${srcH}")

                // Invariant 5: Symmetry of letterbox padding
                assertEquals(mSize.toFloat(), mapper.scaledW + 2 * mapper.padLeft, 0.01f)
                assertEquals(mSize.toFloat(), mapper.scaledH + 2 * mapper.padTop, 0.01f)
            }
        }
    }
}

