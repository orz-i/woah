package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class PrivacyOccludedStrongForegroundTest {

    private fun createRectMask(size: Int = 64, xRange: IntRange, yRange: IntRange, value: Int = 255): NativeMask {
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                buf.put(if (x in xRange && y in yRange) value.coerceIn(0, 255).toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(width = size, height = size, buffer = buf, originalWidth = 640, originalHeight = 640)
    }

    @Test
    fun testExplicitOccluderNeedsPixelEvidenceBeforeCarvingSelectedPrivacy() {
        // Selected person 0 is OCCLUDED by track 1
        val occludedTarget = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50, value = 140),
            confidence = 0.95f,
            state = TrackState.OCCLUDED,
            observedThisFrame = false,
            occludedByTrackIds = setOf(1),
            footY = 300f
        )

        // Unselected person 1 is the explicit occluder
        val explicitOccluder = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 350f),
            mask = createRectMask(64, 10..50, 10..50, value = 255),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 350f
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(occludedTarget, explicitOccluder),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            conservativeUnobservedOccluderPolicy = true
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        var nonZeroCount = 0
        for (i in 0 until pBuf.capacity()) {
            if ((pBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        // Subtracted eroded core (1521px) from 1681px -> 160px safety halo remains
        assertEquals(160, nonZeroCount)
    }

    @Test
    fun conservativeMixedPolicyRejectsUnconfirmedOccluderWhileTargetIsOccluded() {
        val occludedTarget = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50, value = 140),
            confidence = 0.95f,
            state = TrackState.OCCLUDED,
            observedThisFrame = false,
            occludedByTrackIds = emptySet(),
            footY = 300f
        )
        val nearbyFreshPerson = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 350f),
            mask = createRectMask(64, 10..50, 10..50, value = 255),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            age = 30,
            footY = 350f
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(occludedTarget, nearbyFreshPerson),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            conservativeUnobservedOccluderPolicy = true
        )

        val privacyMask = resolved.privacyMask
        assertNotNull(privacyMask)
        val pBuf = privacyMask!!.buffer
        var nonZeroCount = 0
        for (i in 0 until pBuf.capacity()) {
            if ((pBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertEquals(1681, nonZeroCount, "unconfirmed neighbor must not reshape mixed FULL_BODY privacy")
    }
}
