package com.danceanon.native.privacy

import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.face.FaceRoiCandidateSelection
import com.danceanon.native.inference.FloatRect
import kotlin.math.max

enum class FacePrivacyRegionSource {
    DETECTED_FACE,
    PREDICTED_FACE,
    YOLO_HEAD_FALLBACK
}

data class FacePrivacyEllipse(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val source: FacePrivacyRegionSource
)

/**
 * Converts face-location evidence into a conservative source-space privacy region.
 *
 * The face detector never owns identity. A detected region is only accepted after
 * FaceRoiCandidateSelector has already attached it to a YOLO person. If that
 * selection is absent (miss or ambiguity), privacy falls back to a YOLO-derived
 * head ellipse rather than disappearing.
 */
object FacePrivacyRegionResolver {
    // These factors multiply the detector's full face width/height, then become
    // ellipse radii. The previous 0.90/1.05 values therefore rendered roughly
    // 1.8x face width and 2.1x face height, which was visibly oversized once the
    // mask itself became the sticker surface. Keep a conservative margin without
    // turning a detected face into a head-and-shoulders sticker.
    private const val DETECTED_RADIUS_X_FACTOR = 0.66f
    private const val DETECTED_RADIUS_Y_FACTOR = 0.74f
    private const val DETECTED_CENTER_Y_SHIFT = -0.04f

    private const val FALLBACK_CENTER_Y_RATIO = 0.14f
    private const val FALLBACK_RADIUS_X_FROM_WIDTH = 0.34f
    private const val FALLBACK_RADIUS_X_FROM_HEIGHT = 0.065f
    private const val FALLBACK_RADIUS_Y_FROM_WIDTH = 0.39f
    private const val FALLBACK_RADIUS_Y_FROM_HEIGHT = 0.090f

    fun resolve(
        personBbox: FloatRect,
        roiPlan: FaceHeadRoiPlan?,
        selectedFace: FaceRoiCandidateSelection?
    ): FacePrivacyEllipse? {
        if (personBbox.width <= 1f || personBbox.height <= 1f) return null

        if (roiPlan != null && selectedFace != null) {
            detectedEllipse(roiPlan, selectedFace)?.let { return it }
        }

        return fallbackEllipse(personBbox)
    }

    private fun detectedEllipse(
        roiPlan: FaceHeadRoiPlan,
        selectedFace: FaceRoiCandidateSelection
    ): FacePrivacyEllipse? {
        val outputSize = roiPlan.outputSize.toFloat().coerceAtLeast(1f)
        val local = selectedFace.face.bbox
        if (local.width <= 1f || local.height <= 1f || roiPlan.sourceRect.width <= 1f) return null

        fun sourceX(localX: Float): Float =
            roiPlan.sourceRect.left + (localX / outputSize) * roiPlan.sourceRect.width

        fun sourceY(localY: Float): Float =
            roiPlan.sourceRect.top + (localY / outputSize) * roiPlan.sourceRect.height

        val left = sourceX(local.left)
        val right = sourceX(local.right)
        val top = sourceY(local.top)
        val bottom = sourceY(local.bottom)
        val faceWidth = (right - left).coerceAtLeast(1f)
        val faceHeight = (bottom - top).coerceAtLeast(1f)

        return FacePrivacyEllipse(
            centerX = (left + right) * 0.5f,
            centerY = (top + bottom) * 0.5f + faceHeight * DETECTED_CENTER_Y_SHIFT,
            radiusX = faceWidth * DETECTED_RADIUS_X_FACTOR,
            radiusY = faceHeight * DETECTED_RADIUS_Y_FACTOR,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )
    }

    private fun fallbackEllipse(personBbox: FloatRect): FacePrivacyEllipse {
        return FacePrivacyEllipse(
            centerX = personBbox.centerX,
            centerY = personBbox.top + personBbox.height * FALLBACK_CENTER_Y_RATIO,
            radiusX = max(
                personBbox.width * FALLBACK_RADIUS_X_FROM_WIDTH,
                personBbox.height * FALLBACK_RADIUS_X_FROM_HEIGHT
            ),
            radiusY = max(
                personBbox.width * FALLBACK_RADIUS_Y_FROM_WIDTH,
                personBbox.height * FALLBACK_RADIUS_Y_FROM_HEIGHT
            ),
            source = FacePrivacyRegionSource.YOLO_HEAD_FALLBACK
        )
    }
}
