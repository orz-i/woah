package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

class FacePrivacyClassFallbackContinuityTest {
    @Test
    fun `guided availability toggle is bounded for one anonymous selected owner`() {
        val continuity = FacePrivacyClassFallbackContinuity()
        val bbox = FloatRect(100f, 100f, 400f, 700f)

        val first = continuity.stabilize(
            listOf(fallback(centerX = 300f, centerY = 180f, bbox = bbox, guided = false)),
            ptsUs = 0L,
            canonicalizeReferenceGeometry = false
        ).single()
        val guided = continuity.stabilize(
            listOf(fallback(centerX = 360f, centerY = 210f, bbox = bbox, guided = true)),
            ptsUs = 16_667L,
            canonicalizeReferenceGeometry = false
        ).single()
        val rawAgain = continuity.stabilize(
            listOf(fallback(centerX = 302f, centerY = 181f, bbox = bbox, guided = false)),
            ptsUs = 33_355L,
            canonicalizeReferenceGeometry = false
        ).single()

        val firstStep = hypot(
            guided.region.centerX - first.region.centerX,
            guided.region.centerY - first.region.centerY
        )
        val secondStep = hypot(
            rawAgain.region.centerX - guided.region.centerX,
            rawAgain.region.centerY - guided.region.centerY
        )
        assertTrue(firstStep < 35f, "guided transition should be gated: $firstStep")
        assertTrue(secondStep < 35f, "raw fallback transition should be gated: $secondStep")
    }

    @Test
    fun `unguided frame follows body translation instead of raw body head center`() {
        val continuity = FacePrivacyClassFallbackContinuity()
        val firstBbox = FloatRect(100f, 100f, 400f, 700f)
        val movedBbox = FloatRect(106f, 103f, 406f, 703f)

        val first = continuity.stabilize(
            listOf(fallback(centerX = 300f, centerY = 180f, bbox = firstBbox, guided = true)),
            ptsUs = 0L,
            canonicalizeReferenceGeometry = false
        ).single()
        val second = continuity.stabilize(
            listOf(fallback(centerX = 390f, centerY = 245f, bbox = movedBbox, guided = false)),
            ptsUs = 16_667L,
            canonicalizeReferenceGeometry = false
        ).single()

        assertTrue(second.region.centerX in 305f..307f)
        assertTrue(second.region.centerY in 182f..184f)
        assertTrue(
            second.region.centerX < 330f,
            "weak raw body-head center must not pull anonymous fallback away: ${second.region.centerX}"
        )
        assertTrue(first.region.centerX == 300f)
    }

    private fun fallback(
        centerX: Float,
        centerY: Float,
        bbox: FloatRect,
        guided: Boolean
    ) = FacePrivacyClassFallback(
        syntheticTrackId = -1_000_005,
        detectionIndex = 5,
        residualTrackIds = setOf(6),
        personBbox = bbox,
        region = FacePrivacyEllipse(
            centerX = centerX,
            centerY = centerY,
            radiusX = 28f,
            radiusY = 28f,
            source = FacePrivacyRegionSource.YOLO_HEAD_FALLBACK
        ),
        bodyMaskGuided = guided
    )
}
