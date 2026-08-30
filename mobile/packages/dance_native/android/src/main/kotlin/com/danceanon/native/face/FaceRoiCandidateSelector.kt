package com.danceanon.native.face

import kotlin.math.sqrt

data class FaceRoiCandidateSelection(
    val faceIndex: Int,
    val face: FaceObservation,
    val anchorDistanceRatio: Float
)

/**
 * Selects the face owned by a YOLO person inside that person's source ROI.
 *
 * A wide upper-head crop can intentionally contain a neighboring face during
 * crossings. Identity is therefore never taken from the face detector itself:
 * the accepted face must stay close to the target person's planned head anchor.
 */
object FaceRoiCandidateSelector {
    private const val MAX_ANCHOR_DISTANCE_RATIO = 0.22f
    private const val MIN_ANCHOR_SEPARATION_RATIO = 0.04f

    fun select(
        faces: List<FaceObservation>,
        roiWidth: Int,
        roiHeight: Int,
        anchorX: Float,
        anchorY: Float,
        maxAnchorDistanceRatio: Float = MAX_ANCHOR_DISTANCE_RATIO,
        minAnchorSeparationRatio: Float = MIN_ANCHOR_SEPARATION_RATIO
    ): FaceRoiCandidateSelection? {
        if (faces.isEmpty() || roiWidth <= 1 || roiHeight <= 1) return null

        val anchorPxX = anchorX.coerceIn(0f, 1f) * roiWidth
        val anchorPxY = anchorY.coerceIn(0f, 1f) * roiHeight
        val reference = minOf(roiWidth, roiHeight).toFloat().coerceAtLeast(1f)

        val candidates = faces.mapIndexedNotNull { index, face ->
            val localizationCenter = face.localizationCenter()
            val dx = localizationCenter.x - anchorPxX
            val dy = localizationCenter.y - anchorPxY
            val distanceRatio = sqrt(dx * dx + dy * dy) / reference
            if (distanceRatio > maxAnchorDistanceRatio) {
                null
            } else {
                FaceRoiCandidateSelection(
                    faceIndex = index,
                    face = face,
                    anchorDistanceRatio = distanceRatio
                )
            }
        }

        val ranked = candidates.sortedWith(
            compareBy<FaceRoiCandidateSelection> { it.anchorDistanceRatio }
                .thenByDescending { it.face.confidence }
        )
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (
            second != null &&
            second.anchorDistanceRatio - best.anchorDistanceRatio < minAnchorSeparationRatio
        ) {
            // Two plausible faces are too close to the same YOLO-owned head
            // anchor. Do not let detector confidence guess identity; the caller
            // must fall back to the conservative YOLO head privacy region.
            return null
        }
        return best
    }
}
