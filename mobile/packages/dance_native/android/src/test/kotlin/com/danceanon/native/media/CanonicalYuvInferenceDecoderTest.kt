package com.danceanon.native.media

import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalYuvInferenceDecoderTest {

    @Test
    fun limitedRangeNeutralEndpointsMapToBlackAndWhite() {
        assertEquals(
            0x000000,
            CanonicalYuvToRgba.yuvToRgb(
                y = 16,
                u = 128,
                v = 128,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED
            )
        )
        assertEquals(
            0xFFFFFF,
            CanonicalYuvToRgba.yuvToRgb(
                y = 235,
                u = 128,
                v = 128,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED
            )
        )
    }

    @Test
    fun fullRangeNeutralKeepsLumaExactly() {
        assertEquals(
            0x494949,
            CanonicalYuvToRgba.yuvToRgb(
                y = 73,
                u = 128,
                v = 128,
                colorStandard = MediaFormat.COLOR_STANDARD_BT601_NTSC,
                colorRange = MediaFormat.COLOR_RANGE_FULL
            )
        )
    }

    @Test
    fun inverseRotationMapsDisplayCornersBackToSource() {
        assertEquals(0.0 to 0.0, CanonicalYuvToRgba.inverseRotate(0.0, 0.0, 1920, 1080, 0))
        assertEquals(0.0 to 1079.0, CanonicalYuvToRgba.inverseRotate(0.0, 0.0, 1920, 1080, 90))
        assertEquals(1919.0 to 1079.0, CanonicalYuvToRgba.inverseRotate(0.0, 0.0, 1920, 1080, 180))
        assertEquals(1919.0 to 0.0, CanonicalYuvToRgba.inverseRotate(0.0, 0.0, 1920, 1080, 270))
    }

    @Test
    fun optimizedPlaneSamplerMatchesFixedPointBilinearReference() {
        val bytes = byteArrayOf(
            10, 20, 30, 40,
            50, 60, 70, 80,
            90, 100, 110, 120
        )
        val plane = CanonicalYuvToRgba.PlaneSnapshot(bytes, bytes.size, rowStride = 4, pixelStride = 1)
        val x = CanonicalYuvToRgba.AxisSamples(
            i0 = intArrayOf(1),
            i1 = intArrayOf(2),
            w1 = intArrayOf(64)
        )
        val y = CanonicalYuvToRgba.AxisSamples(
            i0 = intArrayOf(0),
            i1 = intArrayOf(1),
            w1 = intArrayOf(128)
        )

        // x=1.25, y=0.5 -> ((20*.75 + 30*.25) + (60*.75 + 70*.25)) / 2 = 42.5 -> 43.
        assertEquals(43, CanonicalYuvToRgba.sampleSnapshot(plane, x, 0, y, 0))
    }

    @Test
    fun packedRgbaIntWritesSameLittleEndianByteContract() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CanonicalYuvToRgba.rgbaLittleEndianInt(0x123456))
        assertEquals(listOf(0x12, 0x34, 0x56, 0xFF), buffer.array().map { it.toInt() and 0xFF })
    }
}
