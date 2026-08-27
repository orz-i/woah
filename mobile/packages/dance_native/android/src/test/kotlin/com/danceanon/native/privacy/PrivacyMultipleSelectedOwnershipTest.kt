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

class PrivacyMultipleSelectedOwnershipTest {

    private fun createHalfMask(isLeft: Boolean): NativeMask {
        val size = 64
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val value = if (isLeft && x < size / 2) 255 else if (!isLeft && x >= size / 2) 255 else 0
                buf.put(value.toByte())
            }
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
    fun testMultiSelectedUnionCombinesWithoutInterference() {
        val targetA = TrackedPerson(
            id = 0,
            bbox = FloatRect(100f, 100f, 200f, 300f),
            mask = createHalfMask(isLeft = true),
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val targetB = TrackedPerson(
            id = 1,
            bbox = FloatRect(150f, 100f, 250f, 300f),
            mask = createHalfMask(isLeft = false),
            confidence = 0.95f,
            state = TrackState.ACTIVE
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(targetA, targetB),
            selectedPersonIds = setOf(0, 1),
            applyDilationToPrivacyTargets = false
        )

        assertTrue(resolved.hasPrivacy)
        assertNotNull(resolved.privacyMask)

        val pBuf = resolved.privacyMask!!.buffer
        pBuf.rewind()
        for (i in 0 until pBuf.capacity()) {
            assertEquals(255, pBuf.get(i).toInt() and 0xFF, "Union of left half and right half must cover 100% of buffer")
        }
    }
}
