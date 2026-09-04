package com.danceanon.native.privacy

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceOnlyDetectorCadencePolicyTest {

    @Test
    fun usablePixelStateThrottlesOnlyNonDormantDetectorRefresh() {
        assertEquals(
            FaceOnlyPrivacyFrameProcessor.PIXEL_ASSISTED_DETECTOR_INTERVAL_US,
            FaceOnlyPrivacyFrameProcessor.effectiveDetectorIntervalUs(
                baseIntervalUs = FaceOnlyPrivacyFrameProcessor.DEFAULT_DETECTOR_INTERVAL_US,
                roiStateStatus = FacePixelMotionTracker.RoiStateStatus.USABLE,
                renderMode = FaceOnlyRenderMode.DIRECT
            )
        )
        assertEquals(
            FaceOnlyPrivacyFrameProcessor.PIXEL_ASSISTED_DETECTOR_INTERVAL_US,
            FaceOnlyPrivacyFrameProcessor.effectiveDetectorIntervalUs(
                baseIntervalUs = FaceOnlyPrivacyFrameProcessor.DEFAULT_DETECTOR_INTERVAL_US,
                roiStateStatus = FacePixelMotionTracker.RoiStateStatus.USABLE,
                renderMode = FaceOnlyRenderMode.BODY_MASK_COMPENSATED
            )
        )
        assertEquals(
            FaceOnlyPrivacyFrameProcessor.DEFAULT_DETECTOR_INTERVAL_US,
            FaceOnlyPrivacyFrameProcessor.effectiveDetectorIntervalUs(
                baseIntervalUs = FaceOnlyPrivacyFrameProcessor.DEFAULT_DETECTOR_INTERVAL_US,
                roiStateStatus = FacePixelMotionTracker.RoiStateStatus.USABLE,
                renderMode = FaceOnlyRenderMode.DORMANT
            )
        )
    }

    @Test
    fun missingOrExpiredPixelStateKeepsNormalDetectorCadence() {
        for (status in listOf(
            null,
            FacePixelMotionTracker.RoiStateStatus.MISSING,
            FacePixelMotionTracker.RoiStateStatus.EVIDENCE_GAP_EXPIRED,
            FacePixelMotionTracker.RoiStateStatus.DETECTOR_SEED_EXPIRED
        )) {
            assertEquals(
                FaceOnlyPrivacyFrameProcessor.DEFAULT_DETECTOR_INTERVAL_US,
                FaceOnlyPrivacyFrameProcessor.effectiveDetectorIntervalUs(
                    baseIntervalUs = FaceOnlyPrivacyFrameProcessor.DEFAULT_DETECTOR_INTERVAL_US,
                    roiStateStatus = status,
                    renderMode = FaceOnlyRenderMode.DIRECT
                )
            )
        }
    }

    @Test
    fun callerWithSlowerBaseCadenceIsNeverAccelerated() {
        assertEquals(
            80_000L,
            FaceOnlyPrivacyFrameProcessor.effectiveDetectorIntervalUs(
                baseIntervalUs = 80_000L,
                roiStateStatus = FacePixelMotionTracker.RoiStateStatus.USABLE,
                renderMode = FaceOnlyRenderMode.DIRECT
            )
        )
    }
}
