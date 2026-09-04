package com.danceanon.native.privacy

import com.danceanon.native.render.FaceStickerPlacement
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.PrivacySelectionClass

internal data class FacePrivacyClassFallback(
    val syntheticTrackId: Int,
    val detectionIndex: Int,
    val residualTrackIds: Set<Int>,
    val region: FacePrivacyEllipse,
    val bodyMaskGuided: Boolean = false
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
    private const val TRUSTED_SIZE_FALLBACK_EXPANSION = 1.24f

    fun resolve(
        evidence: List<FreshPrivacyClassEvidence>,
        faceOnlyTrackIds: Set<Int>,
        dormantSuppressedTrackIds: Set<Int>,
        existingPlacements: List<FaceStickerPlacement>,
        trustedFaceGeometryByTrackId: Map<Int, FacePrivacyTrustedGeometry> = emptyMap(),
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
                val trustedGeometry = item.residualTrackIds
                    .singleOrNull()
                    ?.let(trustedFaceGeometryByTrackId::get)
                val maskGuidedRegion = trustedGeometry?.let { trusted ->
                    FaceTrustedMaskFallback.resolve(
                        mask = item.detection.mask,
                        currentPersonBbox = personBbox,
                        trusted = trusted,
                        radiusExpansion = TRUSTED_SIZE_FALLBACK_EXPANSION
                    )
                }
                val sizeBoundedRegion = if (maskGuidedRegion != null) {
                    maskGuidedRegion
                } else if (trustedGeometry != null) {
                    // Exact identity is intentionally unresolved here, but the
                    // privacy sidecar has already reduced the possible owner set
                    // to one selected slot. Prefer a current-mask local head
                    // estimate above; if that current evidence is unavailable,
                    // retain the already validated body-head center and borrow
                    // only the trusted source-space face size.
                    rawRegion.copy(
                        radiusX = (trustedGeometry.radiusX * TRUSTED_SIZE_FALLBACK_EXPANSION)
                            .coerceAtLeast(1f),
                        radiusY = (trustedGeometry.radiusY * TRUSTED_SIZE_FALLBACK_EXPANSION)
                            .coerceAtLeast(1f)
                    )
                } else {
                    rawRegion
                }
                val region = if (canonicalizeReferenceGeometry) {
                    FaceReferenceGeometryCanonicalizer.ellipse(sizeBoundedRegion)
                } else {
                    sizeBoundedRegion
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
                    region = region,
                    bodyMaskGuided = maskGuidedRegion != null
                )
            }
            .toList()
    }
}
