package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivacyFreshOccluderTest {

    private fun createMaskWithPattern(fillValue: Int): NativeMask {
        val size = 64
        val buf = ByteBuffer.allocateDirect(size * size)
        for (i in 0 until size * size) {
            buf.put(fillValue.toByte())
        }
        buf.rewind()
        return NativeMask(
            width = size,
            height = size,
            buffer = buf,
            originalWidth = 640,
            originalHeight = 640
        )
    }

    @Test
    fun testActiveTargetNeverSubtractedByUnselectedPerson() {
        val target = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createMaskWithPattern(255),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            occludedByTrackIds = setOf(1) // Even if explicitly tagged, ACTIVE state rejects subtraction
        )

        val unselected = TrackedPerson(
            id = 1,
            bbox = FloatRect(150f, 100f, 250f, 300f),
            mask = createMaskWithPattern(255),
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(target, unselected),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        // Verify no pixels carved (all 255)
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        for (i in 0 until pBuf.capacity()) {
            assertEquals(255, pBuf.get(i).toInt() and 0xFF)
        }
    }

    @Test
    fun testOccludedTargetSubtractsOnlyExplicitForegroundOccluder() {
        val target = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createMaskWithPattern(255),
            confidence = 0.95f,
            state = TrackState.OCCLUDED,
            occludedByTrackIds = setOf(1) // Explicit occluder ID 1
        )

        val explicitOccluder = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createMaskWithPattern(255), // Foreground mask
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val unrelatedOther = TrackedPerson(
            id = 2,
            bbox = FloatRect(300f, 100f, 400f, 300f),
            mask = createMaskWithPattern(255),
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(target, explicitOccluder, unrelatedOther),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        // Explicit occluder subtracted solid foreground pixels
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        var nonZeroCount = 0
        for (i in 0 until pBuf.capacity()) {
            if ((pBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertEquals(0, nonZeroCount, "Explicit foreground occluder subtracted all overlapping solid pixels")
    }
}
