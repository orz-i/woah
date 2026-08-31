package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceOcclusionBridgePolicyTest {
    @Test
    fun `only appearance occlusion rejects are eligible`() {
        assertTrue(
            FaceOcclusionBridgePolicy.isAppearanceOcclusionReject(
                FacePixelMotionTracker.RoiRejectReason.LOW_CORRELATION
            )
        )
        assertTrue(
            FaceOcclusionBridgePolicy.isAppearanceOcclusionReject(
                FacePixelMotionTracker.RoiRejectReason.AMBIGUOUS_PEAK
            )
        )
        assertTrue(
            !FaceOcclusionBridgePolicy.isAppearanceOcclusionReject(
                FacePixelMotionTracker.RoiRejectReason.EVIDENCE_GAP_EXPIRED
            )
        )
    }

    @Test
    fun `short hold follows only coherent observed body translation`() {
        val held = assertNotNull(
            FaceOcclusionBridgePolicy.projectHold(
                trustedRegion = FacePrivacyEllipse(
                    200f, 100f, 20f, 22f, FacePrivacyRegionSource.DETECTED_FACE
                ),
                trustedPersonBbox = FloatRect(100f, 50f, 300f, 550f),
                currentPersonBbox = FloatRect(112f, 43f, 312f, 543f),
                personObservedThisFrame = true,
                ageUs = 50_000L
            )
        )
        assertEquals(212f, held.centerX)
        assertEquals(93f, held.centerY)
        assertEquals(20f, held.radiusX)
        assertEquals(22f, held.radiusY)
    }

    @Test
    fun `unobserved hold stays local and expires at one hundred milliseconds`() {
        val region = FacePrivacyEllipse(200f, 100f, 20f, 22f, FacePrivacyRegionSource.DETECTED_FACE)
        val held = assertNotNull(
            FaceOcclusionBridgePolicy.projectHold(
                trustedRegion = region,
                trustedPersonBbox = FloatRect(100f, 50f, 300f, 550f),
                currentPersonBbox = FloatRect(180f, 120f, 380f, 620f),
                personObservedThisFrame = false,
                ageUs = FaceOcclusionBridgePolicy.MAX_HOLD_AGE_US
            )
        )
        assertEquals(200f, held.centerX)
        assertEquals(100f, held.centerY)
        assertNull(
            FaceOcclusionBridgePolicy.projectHold(
                trustedRegion = region,
                trustedPersonBbox = FloatRect(100f, 50f, 300f, 550f),
                currentPersonBbox = FloatRect(100f, 50f, 300f, 550f),
                personObservedThisFrame = true,
                ageUs = FaceOcclusionBridgePolicy.MAX_HOLD_AGE_US + 1L
            )
        )
    }
}
