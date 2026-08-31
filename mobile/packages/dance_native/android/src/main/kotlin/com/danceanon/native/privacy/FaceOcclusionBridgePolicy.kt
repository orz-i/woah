package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect

/**
 * Very short render-only bridge for temporary face appearance occlusion.
 *
 * It is intentionally narrower than the pixel tracklet lifetime: only visual
 * appearance failures (low correlation / ambiguous peaks) are eligible. The
 * bridge never refreshes the pixel template or identity and expires quickly.
 */
internal object FaceOcclusionBridgePolicy {
    const val MAX_HOLD_AGE_US = 100_000L

    fun isAppearanceOcclusionReject(reason: FacePixelMotionTracker.RoiRejectReason?): Boolean =
        reason == FacePixelMotionTracker.RoiRejectReason.LOW_CORRELATION ||
            reason == FacePixelMotionTracker.RoiRejectReason.AMBIGUOUS_PEAK

    fun projectHold(
        trustedRegion: FacePrivacyEllipse,
        trustedPersonBbox: FloatRect,
        currentPersonBbox: FloatRect,
        personObservedThisFrame: Boolean,
        ageUs: Long
    ): FacePrivacyEllipse? {
        if (ageUs !in 0L..MAX_HOLD_AGE_US) return null

        val translation = if (
            personObservedThisFrame &&
            trustedPersonBbox.width > 1f && trustedPersonBbox.height > 1f &&
            currentPersonBbox.width > 1f && currentPersonBbox.height > 1f
        ) {
            PersonBboxMotionEstimator.estimate(trustedPersonBbox, currentPersonBbox)
        } else {
            PersonBboxTranslation(0f, 0f)
        }

        return FacePrivacyEllipse(
            centerX = trustedRegion.centerX + translation.dx,
            centerY = trustedRegion.centerY + translation.dy,
            radiusX = trustedRegion.radiusX.coerceAtLeast(1f),
            radiusY = trustedRegion.radiusY.coerceAtLeast(1f),
            source = FacePrivacyRegionSource.PREDICTED_FACE
        )
    }
}
