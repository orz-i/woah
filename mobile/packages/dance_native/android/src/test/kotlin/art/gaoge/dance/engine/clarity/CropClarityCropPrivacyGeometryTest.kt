package art.gaoge.dance.engine.clarity

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic coordinate-only regressions, NOT real detector/GL privacy validation. */
class CropClarityCropPrivacyGeometryTest {
    private val sourceWidth = 160
    private val sourceHeight = 90

    private fun box(left: Float, right: Float): FloatRect =
        FloatRect(left, 12f, right, 80f)

    @Test fun protectedTrackOutsideFinalPortraitIsNotCountedEvenWhenVisibleInSource() {
        val crop = FloatRect(.62f, 0f, .94f, 1f)
        val selected = box(12f, 38f)
        val evidence = CropClarityCropPrivacyGeometry.evaluate(
            crop, sourceWidth, sourceHeight,
            protectedBoxes = listOf(selected), otherBoxes = listOf(box(16f, 40f))
        )
        assertEquals(1, evidence.sourceProtectedCount)
        assertEquals(0, evidence.cropProtectedCount)
        assertFalse(evidence.visibleInFinalCrop)
        assertEquals(0f, evidence.maxCropOverlap)
        assertNull(CropClarityCropPrivacyGeometry.projectToPortrait(selected, crop, sourceWidth, sourceHeight))
    }

    @Test fun selectedPrivacyBoxesAndCrossingAreRecognizedInFinalCropOnly() {
        val crop = FloatRect(.30f, .05f, .68f, .95f)
        val protected = box(65f, 93f)
        val neighbor = box(78f, 102f)
        val evidence = CropClarityCropPrivacyGeometry.evaluate(
            crop, sourceWidth, sourceHeight,
            protectedBoxes = listOf(protected), otherBoxes = listOf(neighbor)
        )
        assertTrue(evidence.visibleInFinalCrop)
        assertEquals(1, evidence.cropProtectedCount)
        assertTrue(evidence.cropProtectedAreaFraction > 0.05f)
        assertTrue(evidence.maxCropOverlap > .08f)
        val projected = assertNotNull(CropClarityCropPrivacyGeometry.projectToPortrait(
            protected, crop, sourceWidth, sourceHeight
        ))
        assertTrue(projected.left >= 0f && projected.right <= 1f)
        assertTrue(projected.top >= 0f && projected.bottom <= 1f)
    }

    @Test fun clippedEdgeSliverMustNotClaimUsefulPrivacyVisibility() {
        val crop = FloatRect(.30f, 0f, .61f, 1f)
        val selected = box(27f, 49f) // crop begins at x=48; one pixel sliver
        val evidence = CropClarityCropPrivacyGeometry.evaluate(
            crop, sourceWidth, sourceHeight, listOf(selected), emptyList()
        )
        assertEquals(0, evidence.cropProtectedCount)
        assertEquals(0f, evidence.cropProtectedAreaFraction)
    }

    @Test fun multipleProtectedTracksCanOverlapWithoutAnyUnselectedBystander() {
        val crop = FloatRect(.2f, 0f, .8f, 1f)
        val evidence = CropClarityCropPrivacyGeometry.evaluate(
            crop, sourceWidth, sourceHeight, listOf(box(60f, 86f), box(70f, 94f)), emptyList()
        )
        assertEquals(2, evidence.cropProtectedCount)
        assertTrue(evidence.maxCropOverlap > .08f)
    }

    @Test fun syntheticMovingCrossingLostAndReacquiredTracksPreserveSourceMaskCropGeometry() {
        // On each synthetic frame, draw privacy in unmodified source space,
        // then crop. For every output pixel the projected privacy rectangle
        // must agree with querying that exact point in the original source.
        // The frame with missing selected-track evidence is deliberately *not*
        // asserted privacy-safe: recovery belongs to the real tracker pipeline.
        val cases = listOf(
            box(8f, 35f) to FloatRect(0f, 0f, .31f, 1f),
            box(35f, 65f) to FloatRect(.14f, 0f, .45f, 1f),
            null to FloatRect(.30f, 0f, .61f, 1f), // LOST / no observed track
            box(80f, 111f) to FloatRect(.40f, 0f, .71f, 1f), // REACQUIRE
            box(115f, 150f) to FloatRect(.67f, 0f, .98f, 1f)
        )
        var sourceCropMismatchIfProtectionAppliedAfterCropWithoutRemap = 0
        for ((sourceBox, crop) in cases) {
            val boxes = listOfNotNull(sourceBox)
            val evidence = CropClarityCropPrivacyGeometry.evaluate(
                crop, sourceWidth, sourceHeight, boxes, listOf(box(90f, 119f))
            )
            if (sourceBox == null) {
                assertEquals(0, evidence.sourceProtectedCount)
                assertFalse(evidence.visibleInFinalCrop)
                continue
            }
            assertTrue(evidence.visibleInFinalCrop)
            val projected = assertNotNull(CropClarityCropPrivacyGeometry.projectToPortrait(
                sourceBox, crop, sourceWidth, sourceHeight
            ))
            for (y in 0 until 32) for (x in 0 until 32) {
                val outX = (x + .5f) / 32f
                val outY = (y + .5f) / 32f
                val (sx, sy) = CropClarityCropPrivacyGeometry.outputToSource(outX, outY, crop)
                val coveredInSource = inside(sourceBox, sx * sourceWidth, sy * sourceHeight)
                val coveredAfterSourceCompositionAndCrop = inside(projected, outX, outY)
                assertEquals(coveredInSource, coveredAfterSourceCompositionAndCrop)
                val incorrectlyCropFirstWithoutRemapping =
                    inside(sourceBox, outX * sourceWidth, outY * sourceHeight)
                if (coveredInSource != incorrectlyCropFirstWithoutRemapping) {
                    sourceCropMismatchIfProtectionAppliedAfterCropWithoutRemap++
                }
            }
        }
        assertTrue(sourceCropMismatchIfProtectionAppliedAfterCropWithoutRemap > 20)
    }

    private fun inside(box: FloatRect, x: Float, y: Float): Boolean =
        x >= box.left && x < box.right && y >= box.top && y < box.bottom
}
