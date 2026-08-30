package com.danceanon.native.face

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A detector-owned face observation. It deliberately has no identity field:
 * person identity remains owned by the YOLO/TrackManager pipeline.
 */
data class FaceObservation(
    val bbox: FloatRect,
    val confidence: Float,
    val keypoints: List<FacePoint> = emptyList()
) {
    /**
     * BlazeFace full-range emits six approximate points in this order:
     * left eye, right eye, nose tip, mouth, left tragion, right tragion.
     *
     * The detector bbox is useful for scale, but its axis-aligned center moves
     * noticeably with yaw/pose.  Use the central facial features as the
     * localization anchor when available so identity gating and sticker
     * placement follow the actual face rather than bbox shape changes.
     */
    fun localizationCenter(): FacePoint {
        if (keypoints.size < 4) {
            return FacePoint(bbox.centerX, bbox.centerY)
        }
        val leftEye = keypoints[0]
        val rightEye = keypoints[1]
        val nose = keypoints[2]
        val mouth = keypoints[3]
        val eyeMidX = (leftEye.x + rightEye.x) * 0.5f
        val eyeMidY = (leftEye.y + rightEye.y) * 0.5f
        return FacePoint(
            x = (eyeMidX + nose.x + mouth.x) / 3f,
            y = (eyeMidY + nose.y + mouth.y) / 3f
        )
    }
}

data class FacePoint(
    val x: Float,
    val y: Float
)

data class FacePersonMatch(
    val trackId: Int,
    val faceIndex: Int,
    val score: Float,
    val faceInsidePersonRatio: Float,
    val faceInsideHeadRegionRatio: Float
)

data class FaceAssociationResult(
    val matches: List<FacePersonMatch>,
    val unmatchedFaceIndices: List<Int>,
    val unmatchedTrackIds: List<Int>
)

/**
 * Geometry-only face-to-person association.
 *
 * This intentionally does not create a second tracker. Face detections are
 * ephemeral position evidence that must be attached to an existing YOLO person
 * identity. The gate is conservative enough to reject a face located in the
 * lower body of a nearby person during crossings.
 */
object FacePersonAssociator {
    private const val MIN_ASSOCIATION_SCORE = 0.45f
    private const val MAX_HEAD_CENTER_Y_RATIO = 0.62f

    fun associate(
        faces: List<FaceObservation>,
        persons: List<TrackedPerson>
    ): FaceAssociationResult {
        val eligiblePersons = persons.filter { it.state != TrackState.REMOVED && it.bbox.width > 1f && it.bbox.height > 1f }
        if (eligiblePersons.isEmpty()) {
            return FaceAssociationResult(
                matches = emptyList(),
                unmatchedFaceIndices = faces.indices.toList(),
                unmatchedTrackIds = emptyList()
            )
        }
        if (faces.isEmpty()) {
            return FaceAssociationResult(
                matches = emptyList(),
                unmatchedFaceIndices = emptyList(),
                unmatchedTrackIds = eligiblePersons.map { it.id }
            )
        }

        val diagnostics = Array(eligiblePersons.size) { arrayOfNulls<PairDiagnostics>(faces.size) }
        val costs = Array(eligiblePersons.size) { pIdx ->
            FloatArray(faces.size) { fIdx ->
                val pair = scorePair(eligiblePersons[pIdx].bbox, faces[fIdx])
                diagnostics[pIdx][fIdx] = pair
                if (pair.accepted) 1f - pair.score else 1f
            }
        }

        val matchResult = HungarianSolver.match(
            costMatrix = costs,
            maxCostThreshold = 1f - MIN_ASSOCIATION_SCORE
        )

        val matches = matchResult.matches.mapNotNull { (personIndex, faceIndex) ->
            val diag = diagnostics[personIndex][faceIndex] ?: return@mapNotNull null
            if (!diag.accepted || diag.score < MIN_ASSOCIATION_SCORE) return@mapNotNull null
            FacePersonMatch(
                trackId = eligiblePersons[personIndex].id,
                faceIndex = faceIndex,
                score = diag.score,
                faceInsidePersonRatio = diag.insidePersonRatio,
                faceInsideHeadRegionRatio = diag.insideHeadRatio
            )
        }

        val matchedFaces = matches.map { it.faceIndex }.toSet()
        val matchedTracks = matches.map { it.trackId }.toSet()
        return FaceAssociationResult(
            matches = matches,
            unmatchedFaceIndices = faces.indices.filterNot { matchedFaces.contains(it) },
            unmatchedTrackIds = eligiblePersons.map { it.id }.filterNot { matchedTracks.contains(it) }
        )
    }

    private data class PairDiagnostics(
        val accepted: Boolean,
        val score: Float,
        val insidePersonRatio: Float,
        val insideHeadRatio: Float
    )

    private fun scorePair(person: FloatRect, face: FaceObservation): PairDiagnostics {
        val faceArea = area(face.bbox).coerceAtLeast(1f)
        val insidePersonRatio = intersectionArea(person, face.bbox) / faceArea

        // The head search region intentionally extends above and sideways from
        // the YOLO box so partially clipped heads are still attachable.
        val headRegion = FloatRect(
            left = person.left - person.width * 0.18f,
            top = person.top - person.height * 0.12f,
            right = person.right + person.width * 0.18f,
            bottom = person.top + person.height * 0.48f
        )
        val insideHeadRatio = intersectionArea(headRegion, face.bbox) / faceArea
        val normalizedFaceCenterY = (face.bbox.centerY - person.top) / person.height

        // A genuine face may be slightly outside the person box, but it must
        // strongly overlap the expanded head region and stay in the upper body.
        val geometryGate =
            insideHeadRatio >= 0.45f &&
                normalizedFaceCenterY <= MAX_HEAD_CENTER_Y_RATIO &&
                normalizedFaceCenterY >= -0.20f
        if (!geometryGate) {
            return PairDiagnostics(false, 0f, insidePersonRatio, insideHeadRatio)
        }

        val expectedHeadX = person.centerX
        val expectedHeadY = person.top + person.height * 0.16f
        val dx = face.bbox.centerX - expectedHeadX
        val dy = face.bbox.centerY - expectedHeadY
        val reference = max(person.width * 0.70f, person.height * 0.30f).coerceAtLeast(1f)
        val normalizedDistance = sqrt(dx * dx + dy * dy) / reference
        val proximityScore = (1f - normalizedDistance).coerceIn(0f, 1f)

        val faceToPersonHeight = face.bbox.height / person.height
        val targetRatio = 0.18f
        val ratioError = abs(faceToPersonHeight - targetRatio) / targetRatio
        val sizeScore = (1f - ratioError * 0.55f).coerceIn(0f, 1f)
        val confidenceScore = face.confidence.coerceIn(0f, 1f)

        val score = (
            0.40f * insideHeadRatio.coerceIn(0f, 1f) +
                0.25f * insidePersonRatio.coerceIn(0f, 1f) +
                0.20f * proximityScore +
                0.10f * sizeScore +
                0.05f * confidenceScore
            ).coerceIn(0f, 1f)

        return PairDiagnostics(
            accepted = score >= MIN_ASSOCIATION_SCORE,
            score = score,
            insidePersonRatio = insidePersonRatio,
            insideHeadRatio = insideHeadRatio
        )
    }

    private fun area(rect: FloatRect): Float = rect.width * rect.height

    private fun intersectionArea(a: FloatRect, b: FloatRect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = kotlin.math.min(a.right, b.right)
        val bottom = kotlin.math.min(a.bottom, b.bottom)
        return (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
    }
}
