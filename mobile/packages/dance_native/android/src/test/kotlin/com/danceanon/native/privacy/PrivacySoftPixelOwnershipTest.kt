package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class PrivacySoftPixelOwnershipTest {

    private fun createSoftRectMask(value: Int, size: Int = 16): NativeMask {
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = x in 3..12 && y in 3..12
                buf.put(if (inside) value.coerceIn(0, 255).toByte() else 0)
            }
        }
        buf.rewind()
        return NativeMask(size, size, buf, 160, 160)
    }

    private fun person(
        id: Int,
        maskValue: Int,
        confidence: Float = 0.95f,
        state: TrackState = TrackState.ACTIVE,
        observed: Boolean = true,
        footY: Float = 120f
    ): TrackedPerson = TrackedPerson(
        id = id,
        bbox = FloatRect(30f, 20f, 130f, 120f),
        mask = createSoftRectMask(maskValue),
        confidence = confidence,
        state = state,
        observedThisFrame = observed,
        footY = footY
    )

    @Test
    fun testSoftConfidenceCanEstablishUnselectedPixelOwnerWithoutFootYDepth() {
        val selected = person(id = 0, maskValue = 140, footY = 120f)
        val unselected = person(id = 1, maskValue = 245, footY = 120f)

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selected, unselected),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1
        )

        val mask = resolved.privacyMask!!
        val center = mask.buffer.get(8 * 16 + 8).toInt() and 0xFF
        val boundary = mask.buffer.get(3 * 16 + 3).toInt() and 0xFF
        assertEquals(0, center, "clear unselected ownership should preserve the foreground pixel")
        assertTrue(boundary > 0, "ownership subtraction must keep a privacy-safe boundary halo")
    }

    @Test
    fun testFootYAloneCannotOverrideAmbiguousSoftOwnership() {
        val selected = person(id = 0, maskValue = 220, footY = 100f)
        val unselected = person(id = 1, maskValue = 215, footY = 150f)

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selected, unselected),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1
        )

        val center = resolved.privacyMask!!.buffer.get(8 * 16 + 8).toInt() and 0xFF
        assertEquals(220, center, "footY is only a weak tie-breaker; ambiguous ownership must keep privacy")
    }

    @Test
    fun testFreshUnselectedCanOwnPixelsAgainstClearlyStaleSelectedMask() {
        val staleSelected = person(
            id = 0,
            maskValue = 180,
            state = TrackState.REACQUIRING,
            observed = false,
            footY = 120f
        )
        val freshUnselected = person(id = 1, maskValue = 245, footY = 120f)

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(staleSelected, freshUnselected),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1
        )

        val center = resolved.privacyMask!!.buffer.get(8 * 16 + 8).toInt() and 0xFF
        assertEquals(0, center, "fresh strong instance evidence should prevent an obviously stale privacy mask covering the foreground")
    }
}
