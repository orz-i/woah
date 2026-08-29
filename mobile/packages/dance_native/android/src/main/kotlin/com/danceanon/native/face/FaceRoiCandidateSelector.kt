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

    fun select(
        faces: List<FaceObservation>,
        roiWidth: Int,
        roiHeight: Int,
        anchorX: Float,
        anchorY: Float,
        maxAnchorDistanceRatio: Float = MAX_ANCHOR_DISTANCE_RATIO
    ): FaceRoiCandidateSelection? {
        if (faces.isEmpty() || roiWidth <= 1 || roiHeight <= 1) return null

        val anchorPxX = anchorX.coerceIn(0f, 1f) * roiWidth
        val anchorPxY = anchorY.coerceIn(0f, 1f) * roiHeight
        val reference = minOf(roiWidth, roiHeight).toFloat().coerceAtLeast(1f)

        val candidates = faces.mapIndexedNotNull { index, face ->
            val dx = face.bbox.centerX - anchorPxX
            val dy = face.bbox.centerY - anchorPxY
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

        return candidates.minWithOrNull(
            compareBy<FaceRoiCandidateSelection> { it.anchorDistanceRatio }
                .thenByDescending { it.face.confidence }
        )
    }
}
