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
}
