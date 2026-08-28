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

class PrivacyAmbiguousDepthPrivacyWinsTest {

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
    fun testAmbiguousDepthPreservesPrivacyMask() {
        // Selected person 0 and unselected person 1 have virtually identical footY (ambiguous depth)
        val selectedPerson = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 300f
        )

        val ambiguousPerson = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 302f), // only 2px difference on 200px height (< 5% margin)
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 302f
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selectedPerson, ambiguousPerson),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        var nonZeroCount = 0
        for (i in 0 until pBuf.capacity()) {
            if ((pBuf.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        // When depth is ambiguous, privacy wins: target is NOT carved, all 1681 pixels preserved
        assertEquals(1681, nonZeroCount, "Ambiguous depth must favor privacy safety and not carve target mask")
    }
}
