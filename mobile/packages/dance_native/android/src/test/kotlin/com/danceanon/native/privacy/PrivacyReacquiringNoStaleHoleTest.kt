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

class PrivacyReacquiringNoStaleHoleTest {

    private fun createSolidMask(): NativeMask {
        val size = 64
        val buf = ByteBuffer.allocateDirect(size * size)
        for (i in 0 until size * size) {
            buf.put(255.toByte())
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
    fun testReacquiringTargetNeverSubtractedByStaleOccluder() {
        val target = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createSolidMask(),
            confidence = 0.95f,
            state = TrackState.REACQUIRING,
            occludedByTrackIds = emptySet() // Stale occluders cleared on transition to REACQUIRING
        )

        val formerOccluder = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createSolidMask(),
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(target, formerOccluder),
            selectedPersonIds = setOf(0),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)
        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        for (i in 0 until pBuf.capacity()) {
            assertEquals(255, pBuf.get(i).toInt() and 0xFF, "REACQUIRING target must remain 100% solid without stale holes")
        }
    }
}
