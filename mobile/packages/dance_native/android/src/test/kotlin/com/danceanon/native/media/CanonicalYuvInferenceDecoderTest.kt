package com.danceanon.native.media

import android.media.MediaFormat
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
}
