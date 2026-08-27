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
    fun testCaseA_SelectedBackgroundAndUnselectedForegroundOverlap() {
        // Selected Person A: covers (x: 2..7, y: 2..7)
        val maskA = createBinaryMask(10, 10, 2..7, 2..7)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(20f, 20f, 70f, 70f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.ACTIVE
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
        assertTrue(resolved.hasOccluder)
        assertNotNull(resolved.privacyMask)
        assertNotNull(resolved.occluderMask)

        val effectiveMask = PrivacyOcclusionResolver.computeEffectivePrivacyMask(
            privacyMask = resolved.privacyMask,
            occluderMask = resolved.occluderMask
        )
        assertNotNull(effectiveMask)

        // 1. In A's non-overlapping region (x: 3, y: 4): effective privacy must be 255
        assertEquals(255, getPixel(effectiveMask, 3, 4), "Non-occluded region of selected person must retain full privacy")

        // 2. In overlap region (x: 6, y: 4): effective privacy must be 0 (occluder B protects foreground person!)
        assertEquals(0, getPixel(effectiveMask, 6, 4), "Occluded region must have 0 privacy to preserve foreground unselected person")

        // 3. Outside both persons (x: 0, y: 0): effective privacy must be 0
        assertEquals(0, getPixel(effectiveMask, 0, 0))
    }

    @Test
    fun testCaseB_SelectedForegroundPreservesFullPrivacy() {
        // Selected Person A (Foreground): covers (x: 2..5, y: 2..5)
        val maskA = createBinaryMask(10, 10, 2..5, 2..5)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(20f, 20f, 50f, 50f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        // Unselected Person B (separate region): covers (x: 7..9, y: 7..9)
        val maskB = createBinaryMask(10, 10, 7..9, 7..9)
        val personB = TrackedPerson(
            id = 1,
            bbox = FloatRect(70f, 70f, 90f, 90f),
            mask = maskB,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        val effectiveMask = PrivacyOcclusionResolver.computeEffectivePrivacyMask(
            privacyMask = resolved.privacyMask,
            occluderMask = resolved.occluderMask
        )
        assertNotNull(effectiveMask)

        for (y in 2..5) {
            for (x in 2..5) {
                assertEquals(255, getPixel(effectiveMask, x, y), "All pixels of selected person A must be 255")
            }
        }
    }

    @Test
    fun testCaseC_NoOverlapNoOcclusion() {
        val maskA = createBinaryMask(10, 10, 1..3, 1..3)
        val personA = TrackedPerson(
            id = 0,
            bbox = FloatRect(10f, 10f, 30f, 30f),
            mask = maskA,
            confidence = 0.9f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertFalse(resolved.hasOccluder)
        assertNull(resolved.occluderMask)

        val effectiveMask = PrivacyOcclusionResolver.computeEffectivePrivacyMask(
            privacyMask = resolved.privacyMask,
            occluderMask = resolved.occluderMask
        )
        assertNotNull(effectiveMask)
        assertEquals(255, getPixel(effectiveMask, 2, 2))
        assertEquals(0, getPixel(effectiveMask, 5, 5))
    }

    @Test
    fun testCaseD_TwoSelectedPersonsBothAnonymized() {
        val maskA = createBinaryMask(10, 10, 1..3, 1..3)
        val personA = TrackedPerson(id = 0, bbox = FloatRect(10f, 10f, 30f, 30f), mask = maskA, confidence = 0.9f, state = TrackState.ACTIVE)

        val maskB = createBinaryMask(10, 10, 6..8, 6..8)
        val personB = TrackedPerson(id = 1, bbox = FloatRect(60f, 60f, 80f, 80f), mask = maskB, confidence = 0.9f, state = TrackState.ACTIVE)

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(personA, personB),
            selectedPersonIds = setOf(0, 1),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertFalse(resolved.hasOccluder)

        val effectiveMask = PrivacyOcclusionResolver.computeEffectivePrivacyMask(
            privacyMask = resolved.privacyMask,
            occluderMask = resolved.occluderMask
        )
        assertNotNull(effectiveMask)
        assertEquals(255, getPixel(effectiveMask, 2, 2), "Person A must be masked")
        assertEquals(255, getPixel(effectiveMask, 7, 7), "Person B must be masked")
    }

    @Test
    fun testCaseE_TwoUnselectedPersonsNeverProducePrivacyMask() {
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
