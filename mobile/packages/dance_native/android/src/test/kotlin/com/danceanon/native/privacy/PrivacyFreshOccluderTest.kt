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
    fun testExplicitOccluderRelationAloneCannotCarveAmbiguousSelectedPrivacy() {
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
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        // Identical per-pixel evidence is ambiguous. The tracker relation alone
        // must not create foreground ownership or punch a hole in privacy.
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        var nonZeroCount = 0
        for (i in 0 until pBuf.capacity()) {
            if ((pBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertTrue(nonZeroCount > 0, "ambiguous identical masks must preserve selected privacy")
    }

    private fun createRectMask(size: Int = 64, xRange: IntRange, yRange: IntRange): NativeMask {
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                buf.put(if (x in xRange && y in yRange) 255.toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(width = size, height = size, buffer = buf, originalWidth = 640, originalHeight = 640)
    }

    @Test
    fun testOccludedTargetPreservesSafetyBoundaryWhenEroded() {
        val target = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.OCCLUDED,
            occludedByTrackIds = setOf(1)
        )

        val explicitOccluder = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(target, explicitOccluder),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        var nonZeroCount = 0
        for (i in 0 until pBuf.capacity()) {
            if ((pBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        // Identical binary masks provide no per-pixel ownership advantage.
        // An explicit tracker relation alone must not carve selected privacy.
        assertEquals(1681, nonZeroCount, "ambiguous explicit overlap must preserve the full selected mask")
    }
}
