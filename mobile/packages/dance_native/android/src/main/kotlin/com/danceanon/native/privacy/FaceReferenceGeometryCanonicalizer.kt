package com.danceanon.native.privacy

import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.inference.FloatRect
import kotlin.math.roundToInt

/**
 * Deterministic FACE_ONLY geometry lattice used only by the debug CPU-reference
 * validation path. TrackManager and production/release geometry stay untouched.
 */
internal object FaceReferenceGeometryCanonicalizer {
    private const val Q16_PER_HALF_PIXEL = 8

    fun coordinate(value: Float): Float {
        val q16 = (value * 16f).roundToInt()
        val halfPixelBucket = (q16.toFloat() / Q16_PER_HALF_PIXEL).roundToInt()
        return (halfPixelBucket * Q16_PER_HALF_PIXEL) / 16f
    }

    fun rect(rect: FloatRect): FloatRect = FloatRect(
        left = coordinate(rect.left),
        top = coordinate(rect.top),
        right = coordinate(rect.right),
        bottom = coordinate(rect.bottom)
    )

    fun ellipse(region: FacePrivacyEllipse): FacePrivacyEllipse = region.copy(
        centerX = coordinate(region.centerX),
        centerY = coordinate(region.centerY),
        radiusX = coordinate(region.radiusX).coerceAtLeast(1f),
        radiusY = coordinate(region.radiusY).coerceAtLeast(1f)
    )

    fun plan(plan: FaceHeadRoiPlan): FaceHeadRoiPlan {
        val sourceAnchorX = plan.sourceRect.left + plan.anchorX * plan.sourceRect.width
        val sourceAnchorY = plan.sourceRect.top + plan.anchorY * plan.sourceRect.height
        val canonicalRect = rect(plan.sourceRect)
        if (canonicalRect.width <= 1f || canonicalRect.height <= 1f) return plan
        val canonicalAnchorX = coordinate(sourceAnchorX)
        val canonicalAnchorY = coordinate(sourceAnchorY)
        return plan.copy(
            sourceRect = canonicalRect,
            anchorX = ((canonicalAnchorX - canonicalRect.left) / canonicalRect.width).coerceIn(0f, 1f),
            anchorY = ((canonicalAnchorY - canonicalRect.top) / canonicalRect.height).coerceIn(0f, 1f)
        )
    }
}
