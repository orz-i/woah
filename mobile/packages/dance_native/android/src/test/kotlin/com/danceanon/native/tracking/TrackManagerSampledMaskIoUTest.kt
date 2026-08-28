package com.danceanon.native.tracking

import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerSampledMaskIoUTest {

    private fun rectMask(
        size: Int = 160,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = x in left until right && y in top until bottom
                buf.put(if (inside) 255.toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(size, size, buf, 1920, 1080)
    }

    @Test
    fun sampledAssociationIoUPreservesCandidateOrdering() {
        val base = rectMask(left = 32, top = 24, right = 112, bottom = 144)
        val identical = rectMask(left = 32, top = 24, right = 112, bottom = 144)
        val shifted = rectMask(left = 52, top = 24, right = 132, bottom = 144)
        val disjoint = rectMask(left = 120, top = 24, right = 156, bottom = 144)

        val exactIdentical = TrackManager.computeMaskIoU(base, identical)
        val exactShifted = TrackManager.computeMaskIoU(base, shifted)
        val exactDisjoint = TrackManager.computeMaskIoU(base, disjoint)

        val sampledIdentical = TrackManager.computeMaskIoU(base, identical, sampleStride = 4)
        val sampledShifted = TrackManager.computeMaskIoU(base, shifted, sampleStride = 4)
        val sampledDisjoint = TrackManager.computeMaskIoU(base, disjoint, sampleStride = 4)

        assertEquals(1.0f, sampledIdentical)
        assertTrue(exactIdentical > exactShifted && exactShifted > exactDisjoint)
        assertTrue(sampledIdentical > sampledShifted && sampledShifted > sampledDisjoint)
        assertTrue(kotlin.math.abs(sampledShifted - exactShifted) < 0.08f)
    }
}
