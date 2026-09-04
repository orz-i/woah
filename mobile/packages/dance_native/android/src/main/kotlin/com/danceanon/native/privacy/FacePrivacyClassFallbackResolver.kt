package com.danceanon.native.privacy

import com.danceanon.native.render.FaceStickerPlacement
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.PrivacySelectionClass

internal data class FacePrivacyClassFallback(
    val syntheticTrackId: Int,
    val detectionIndex: Int,
    val residualTrackIds: Set<Int>,
    val region: FacePrivacyEllipse
)

/**
 * Fail-closed FACE_ONLY coverage for fresh detections whose exact identity is
 * unresolved but whose complete residual owner set is already Face-selected.
 *
 * This never assigns or updates identity. It only creates anonymous temporary
 * privacy geometry for fresh selected-class evidence that is not already covered
 * by a normal sticker placement.
 */
internal object FacePrivacyClassFallbackResolver {
    private const val SYNTHETIC_TRACK_ID_BASE = -1_000_000

    fun resolve(
        evidence: List<FreshPrivacyClassEvidence>,
        faceOnlyTrackIds: Set<Int>,
        dormantSuppressedTrackIds: Set<Int>,
        existingPlacements: List<FaceStickerPlacement>,
        canonicalizeReferenceGeometry: Boolean
    ): List<FacePrivacyClassFallback> {
        if (evidence.isEmpty() || dormantSuppressedTrackIds.isEmpty()) return emptyList()

        val seenDetectionIndices = mutableSetOf<Int>()
        return evidence
            .asSequence()
            .filter { it.selectionClass == PrivacySelectionClass.SELECTED }
            .filter { item ->
                item.residualTrackIds.isNotEmpty() &&
                    item.residualTrackIds.all { faceOnlyTrackIds.contains(it) } &&
                    item.residualTrackIds.any { dormantSuppressedTrackIds.contains(it) }
            }
            .filter { seenDetectionIndices.add(it.detectionIndex) }
            .sortedBy { it.detectionIndex }
            .mapNotNull { item ->
                val personBbox = if (canonicalizeReferenceGeometry) {
                    FaceReferenceGeometryCanonicalizer.rect(item.detection.bbox)
                } else {
                    item.detection.bbox
                }
                val rawRegion = FacePrivacyRegionResolver.resolve(
                    personBbox = personBbox,
                    roiPlan = null,
                    selectedFace = null
                ) ?: return@mapNotNull null
                val region = if (canonicalizeReferenceGeometry) {
                    FaceReferenceGeometryCanonicalizer.ellipse(rawRegion)
                } else {
                    rawRegion
                }

                // Existing selected sticker coverage already protects this fresh
                // head location; avoid drawing a visually duplicated sticker.
                if (existingPlacements.any { placement ->
                        val rect = placement.sourceRect
                        region.centerX >= rect.left && region.centerX <= rect.right &&
                            region.centerY >= rect.top && region.centerY <= rect.bottom
                    }
                ) {
                    return@mapNotNull null
                }

                FacePrivacyClassFallback(
                    syntheticTrackId = SYNTHETIC_TRACK_ID_BASE - item.detectionIndex,
                    detectionIndex = item.detectionIndex,
                    residualTrackIds = item.residualTrackIds,
                    region = region
                )
            }
            .toList()
    }
}
