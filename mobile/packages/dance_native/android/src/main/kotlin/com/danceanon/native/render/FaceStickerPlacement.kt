package com.danceanon.native.render

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.privacy.FacePrivacyEllipse
import com.danceanon.native.privacy.FacePrivacyRegionSource
import kotlin.math.max

/** Source-space placement for an opaque FACE_ONLY privacy sticker. */
data class FaceStickerPlacement(
    val trackId: Int,
    val sourceRect: FloatRect,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val source: FacePrivacyRegionSource
) {
    companion object {
        fun from(
            trackId: Int,
            region: FacePrivacyEllipse,
            sourceWidth: Int,
            sourceHeight: Int,
            overscan: Float = DEFAULT_OVERSCAN
        ): FaceStickerPlacement? {
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            val halfDim = (max(region.radiusX, region.radiusY) * overscan).coerceAtLeast(MIN_HALF_DIM_PX)
            val left = (region.centerX - halfDim).coerceIn(0f, sourceWidth.toFloat())
            val right = (region.centerX + halfDim).coerceIn(0f, sourceWidth.toFloat())
            val top = (region.centerY - halfDim).coerceIn(0f, sourceHeight.toFloat())
            val bottom = (region.centerY + halfDim).coerceIn(0f, sourceHeight.toFloat())
            if (right - left <= 1f || bottom - top <= 1f) return null
            return FaceStickerPlacement(
                trackId = trackId,
                sourceRect = FloatRect(left, top, right, bottom),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                source = region.source
            )
        }

        private const val DEFAULT_OVERSCAN = 1.02f
        private const val MIN_HALF_DIM_PX = 8f
    }
}
