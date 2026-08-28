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

class PrivacyActiveSelectedForegroundTest {

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
    fun testActiveSelectedInForegroundNeverSubtractedByBackgroundUnselected() {
        // Selected person 0 is in foreground (footY = 400f, closer to camera)
        val selectedForeground = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 400f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 400f
        )

        // Unselected person 1 is in background (further away, footY = 300f)
        val backgroundUnselected = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 300f
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selectedForeground, backgroundUnselected),
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
        // Background unselected person MUST NOT carve foreground selected person: all 1681 pixels preserved
        assertEquals(1681, nonZeroCount, "Background unselected person must never carve privacy from foreground selected person")
    }
}
