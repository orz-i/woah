package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivacyOcclusionResolverTest {

    private fun createBinaryMask(
        width: Int = 10,
        height: Int = 10,
        xRange: IntRange,
        yRange: IntRange
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val byteVal = if (x in xRange && y in yRange) 255.toByte() else 0.toByte()
                buf.put(byteVal)
            }
        }
        buf.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buf,
            originalWidth = 100,
            originalHeight = 100,
            mapper = null
        )
    }

    private fun getPixel(mask: NativeMask, x: Int, y: Int): Int {
        val idx = y * mask.width + x
        return mask.buffer.get(idx).toInt() and 0xFF
    }

    @Test
    fun testCase1_ActiveSelectedTargetNeverCarvedByUnselectedPerson() {
        // Selected Person A is ACTIVE: covers (x: 2..7, y: 2..7)
        val maskA = createBinaryMask(10, 10, 2..7, 2..7)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(20f, 20f, 70f, 70f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.ACTIVE,
            occludedByTrackIds = emptySet()
        )

        // Unselected Person B overlaps A: covers (x: 5..8, y: 2..7)
        val maskB = createBinaryMask(10, 10, 5..8, 2..7)
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(50f, 20f, 80f, 70f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE,
            occludedByTrackIds = emptySet()
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)

        // Since Person A is ACTIVE (foreground), Person B must NOT carve holes in A
        for (y in 2..7) {
            for (x in 2..7) {
                assertEquals(255, getPixel(resolved.privacyMask!!, x, y), "ACTIVE person A must retain 255 privacy across entire body")
            }
        }
    }

    @Test
    fun bboxDisjointUnselectedMaskMatchesNoOccluderSelectedUnionPixelForPixel() {
        val maskA = createBinaryMask(10, 10, 1..3, 2..5)
        val maskB = createBinaryMask(10, 10, 5..7, 4..7)
        // Deliberately give the distant unselected person a mask that overlaps
        // selected proto pixels. The historical resolver must still ignore it
        // because its source bbox fails the mandatory overlap prefilter.
        val distantMask = createBinaryMask(10, 10, 0..9, 0..9)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(10f, 20f, 30f, 50f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(50f, 40f, 70f, 70f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )
        val distant = TrackedPerson(
            id = 9,
            bbox = FloatRect(80f, 5f, 95f, 18f),
            mask = distantMask,
            confidence = 0.8f,
            state = TrackState.ACTIVE
        )

        val actual = assertNotNull(
            PrivacyOcclusionResolver.resolveMasks(
                persons = listOf(personA, personB, distant),
                selectedPersonIds = setOf(0, 1),
                applyDilationToPrivacyTargets = true,
                dilationRadius = 1
            ).privacyMask
        )
        val expected = assertNotNull(
            PrivacyOcclusionResolver.mergeMasks(
                listOf(
                    MaskPrivacyProcessor.dilate(maskA, radius = 1),
                    MaskPrivacyProcessor.dilate(maskB, radius = 1)
                )
            )
        )

        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                assertEquals(getPixel(expected, x, y), getPixel(actual, x, y))
            }
        }
    }

    @Test
    fun testCase2_OccludedSelectedTargetCarvedOnlyByExplicitOccluder() {
        // Selected Person A is OCCLUDED by B: covers (x: 2..7, y: 2..7)
        val maskA = createBinaryMask(10, 10, 2..7, 2..7)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(20f, 20f, 70f, 70f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.OCCLUDED,
            occludedByTrackIds = setOf(1)
        )

        // Unselected Person B (Foreground Occluder): covers (x: 5..8, y: 2..7)
        val maskB = createBinaryMask(10, 10, 5..8, 2..7)
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(50f, 20f, 80f, 70f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val eff = resolved.privacyMask!!

        // 1. Non-occluded region of A (x: 3, y: 4): 255
        assertEquals(255, getPixel(eff, 3, 4), "Non-occluded region of selected person must retain full privacy")

        // 2. Binary overlap alone is ambiguous. The explicit tracker relation
        // cannot substitute for per-pixel ownership evidence, so privacy wins.
        assertEquals(255, getPixel(eff, 6, 4), "Ambiguous explicit overlap must preserve selected privacy")
    }

    @Test
    fun testCase3_TwoSelectedPersonsBothAnonymizedEvenWhenOccluding() {
        val maskA = createBinaryMask(10, 10, 2..7, 2..7)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(20f, 20f, 70f, 70f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.OCCLUDED,
            occludedByTrackIds = setOf(1)
        )

        val maskB = createBinaryMask(10, 10, 5..8, 2..7)
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(50f, 20f, 80f, 70f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        // Both A and B are selected!
        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB),
            selectedPersonIds = setOf(0, 1),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val eff = resolved.privacyMask!!

        // Even though A is occluded by B, since B is ALSO selected, overlap must remain 255
        assertEquals(255, getPixel(eff, 6, 4), "Overlap of two selected persons must remain 255 (both anonymized)")
    }

    @Test
    fun allSelectedFastPathMatchesPerTargetDilationThenUnionPixelForPixel() {
        val maskA = createBinaryMask(10, 10, 1..3, 2..5)
        val maskB = createBinaryMask(10, 10, 5..7, 4..7)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(10f, 20f, 30f, 50f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(50f, 40f, 70f, 70f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        val actual = assertNotNull(
            PrivacyOcclusionResolver.resolveMasks(
                persons = listOf(personA, personB),
                selectedPersonIds = setOf(0, 1),
                applyDilationToPrivacyTargets = true,
                dilationRadius = 1
            ).privacyMask
        )

        // This is the historical semantic order used before the fast path:
        // dilate each selected mask independently and then union the results.
        val expected = assertNotNull(
            PrivacyOcclusionResolver.mergeMasks(
                listOf(
                    MaskPrivacyProcessor.dilate(maskA, radius = 1),
                    MaskPrivacyProcessor.dilate(maskB, radius = 1)
                )
            )
        )

        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                assertEquals(
                    getPixel(expected, x, y),
                    getPixel(actual, x, y),
                    "fast path must preserve exact privacy pixel at ($x,$y)"
                )
            }
        }
    }

    @Test
    fun testCase4_MultiTargetIsolationOccluderDoesNotAffectUnrelatedTarget() {
        // Selected Person A is OCCLUDED by B
        val maskA = createBinaryMask(10, 10, 1..3, 1..3)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(10f, 10f, 30f, 30f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.OCCLUDED,
            occludedByTrackIds = setOf(1)
        )

        // Unselected Person B occludes A (and happens to overlap with (x: 2..3, y: 1..3))
        val maskB = createBinaryMask(10, 10, 2..5, 1..3)
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(20f, 10f, 50f, 30f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        // Selected Person C is separate and ACTIVE (x: 7..9, y: 7..9)
        val maskC = createBinaryMask(10, 10, 7..9, 7..9)
        val personC = TrackedPerson(
            id = 2,
            bbox = FloatRect(70f, 70f, 90f, 90f),
            mask = maskC,
            confidence = 0.9f,
            state = TrackState.ACTIVE,
            occludedByTrackIds = emptySet()
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB, personC),
            selectedPersonIds = setOf(0, 2),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val eff = resolved.privacyMask!!

        // Person C is completely untouched
        for (y in 7..9) {
            for (x in 7..9) {
                assertEquals(255, getPixel(eff, x, y), "Target C must remain completely 255")
            }
        }
    }

    @Test
    fun testCase5_TwoUnselectedPersonsNeverProducePrivacyMask() {
        val maskA = createBinaryMask(10, 10, 1..3, 1..3)
        val personA = TrackedPerson(id = 0, bbox = FloatRect(10f, 10f, 30f, 30f), mask = maskA, confidence = 0.9f, state = TrackState.ACTIVE)

        val maskB = createBinaryMask(10, 10, 6..8, 6..8)
        val personB = TrackedPerson(id = 1, bbox = FloatRect(60f, 60f, 80f, 80f), mask = maskB, confidence = 0.9f, state = TrackState.ACTIVE)

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB),
            selectedPersonIds = emptySet()
        )

        assertFalse(resolved.hasPrivacy, "Empty selection must produce hasPrivacy=false")
        assertNull(resolved.privacyMask, "privacyMask must be null when nobody is selected")
    }
}
