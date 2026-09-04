package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask

internal data class FacePrivacyTrustedGeometry(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val trustedPersonBbox: FloatRect
)

/**
 * Uses an old trusted face only as a local seed while current body-mask pixels
 * remain the actual geometric evidence. The stale face center is never rendered
 * directly: if the current segmentation cannot find a head-like local silhouette,
 * this helper returns null and the caller falls back to its existing policy.
 */
internal object FaceTrustedMaskFallback {
    fun resolve(
        mask: NativeMask?,
        currentPersonBbox: FloatRect,
        trusted: FacePrivacyTrustedGeometry,
        radiusExpansion: Float
    ): FacePrivacyEllipse? {
        if (mask == null || currentPersonBbox.width <= 1f || currentPersonBbox.height <= 1f) return null
        if (trusted.radiusX <= 0f || trusted.radiusY <= 0f) return null

        val translation = PersonBboxMotionEstimator.estimate(
            previous = trusted.trustedPersonBbox,
            current = currentPersonBbox
        )
        val radiusX = (trusted.radiusX * radiusExpansion).coerceAtLeast(1f)
        val radiusY = (trusted.radiusY * radiusExpansion).coerceAtLeast(1f)
        val seedCenterX = trusted.centerX + translation.dx
        val seedCenterY = trusted.centerY + translation.dy
        val currentHead = BodyMaskFaceHeadEstimator.estimate(
            mask = mask,
            personBbox = currentPersonBbox,
            seedCenterX = seedCenterX,
            seedCenterY = seedCenterY,
            seedRadiusX = radiusX,
            seedRadiusY = radiusY
        ) ?: return null

        return FacePrivacyEllipse(
            centerX = currentHead.x,
            centerY = currentHead.y,
            radiusX = radiusX,
            radiusY = radiusY,
            source = FacePrivacyRegionSource.YOLO_HEAD_FALLBACK
        )
    }
}
