package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.render.FaceStickerPlacement
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.PrivacySelectionClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FacePrivacyClassFallbackResolverTest {
    @Test
    fun `selected ambiguous evidence fills uncovered dormant face without identity assignment`() {
        val existingRegion = FacePrivacyRegionResolver.resolve(
            personBbox = FloatRect(100f, 100f, 200f, 400f),
            roiPlan = null,
            selectedFace = null
        )!!
        val existing = FaceStickerPlacement.from(
            trackId = 5,
            region = existingRegion,
            sourceWidth = 640,
            sourceHeight = 480
        )!!
        val evidence = listOf(
            fresh(0, FloatRect(100f, 100f, 200f, 400f), setOf(5, 6)),
            fresh(1, FloatRect(240f, 100f, 340f, 400f), setOf(5, 6))
        )

        val fallback = FacePrivacyClassFallbackResolver.resolve(
            evidence = evidence,
            faceOnlyTrackIds = setOf(5, 6),
            dormantSuppressedTrackIds = setOf(6),
            existingPlacements = listOf(existing),
            canonicalizeReferenceGeometry = true
        )

        assertEquals(1, fallback.size)
        assertEquals(1, fallback.single().detectionIndex)
        assertEquals(setOf(5, 6), fallback.single().residualTrackIds)
        assertEquals(FloatRect(240f, 100f, 340f, 400f), fallback.single().personBbox)
        assertTrue(fallback.single().syntheticTrackId < 0)
        assertEquals(FacePrivacyRegionSource.YOLO_HEAD_FALLBACK, fallback.single().region.source)
    }

    @Test
    fun `evidence with any non selected possible owner never gets class fallback`() {
        val fallback = FacePrivacyClassFallbackResolver.resolve(
            evidence = listOf(fresh(0, FloatRect(100f, 100f, 200f, 400f), setOf(5, 7))),
            faceOnlyTrackIds = setOf(5, 6),
            dormantSuppressedTrackIds = setOf(5),
            existingPlacements = emptyList(),
            canonicalizeReferenceGeometry = true
        )
        assertTrue(fallback.isEmpty())
    }

    @Test
    fun `unique owner fallback keeps fresh center but reuses conservative trusted face size`() {
        val evidence = listOf(
            fresh(5, FloatRect(200f, 100f, 600f, 900f), setOf(6))
        )

        val fallback = FacePrivacyClassFallbackResolver.resolve(
            evidence = evidence,
            faceOnlyTrackIds = setOf(6),
            dormantSuppressedTrackIds = setOf(6),
            existingPlacements = emptyList(),
            trustedFaceGeometryByTrackId = mapOf(
                6 to FacePrivacyTrustedGeometry(
                    centerX = 400f,
                    centerY = 212f,
                    radiusX = 25f,
                    radiusY = 30f,
                    trustedPersonBbox = FloatRect(200f, 100f, 600f, 900f)
                )
            ),
            canonicalizeReferenceGeometry = false
        ).single()

        val rawBodyFallback = FacePrivacyRegionResolver.resolve(
            personBbox = evidence.single().detection.bbox,
            roiPlan = null,
            selectedFace = null
        )!!
        assertEquals(rawBodyFallback.centerX, fallback.region.centerX)
        assertEquals(rawBodyFallback.centerY, fallback.region.centerY)
        assertEquals(31f, fallback.region.radiusX, 0.001f)
        assertEquals(37.2f, fallback.region.radiusY, 0.001f)
        assertTrue(fallback.region.radiusX < rawBodyFallback.radiusX)
        assertTrue(fallback.region.radiusY < rawBodyFallback.radiusY)
    }

    private fun fresh(index: Int, bbox: FloatRect, owners: Set<Int>) =
        FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = index,
            detection = PersonDetection(bbox = bbox, confidence = 0.9f),
            residualTrackIds = owners
        )
}
