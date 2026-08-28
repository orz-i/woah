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

class PrivacyActiveSelectedBehindForegroundTest {

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
    fun testActiveSelectedBehindForegroundIsLayeredUnderForeground() {
        // Selected person 0 is further back (footY = 300f)
        val selectedPerson = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 300f
        )

        // Unselected person 1 is in foreground (closer to camera, footY = 400f)
        val foregroundUnselected = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 400f),
            mask = createRectMask(64, 10..50, 10..50),
            confidence = 0.95f,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 400f
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selectedPerson, foregroundUnselected),
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
        // Foreground occluder core (1521 pixels) is subtracted from background target (1681 pixels), leaving safety border (160 pixels)
        assertEquals(160, nonZeroCount, "Foreground unselected person must carve privacy mask from background selected person")
    }
}
