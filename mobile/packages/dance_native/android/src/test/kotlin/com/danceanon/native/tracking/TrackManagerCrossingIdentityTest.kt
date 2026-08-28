package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class TrackManagerCrossingIdentityTest {

    private lateinit var tracker: TrackManager

    @BeforeEach
    fun setUp() {
        tracker = TrackManager(TrackingConfig())
    }

    private fun createDummyMask(): NativeMask {
        val buf = ByteBuffer.allocateDirect(64 * 64)
        for (i in 0 until 64 * 64) buf.put(255.toByte())
        buf.rewind()
        return NativeMask(64, 64, buf, 640, 640)
    }

    @Test
    fun testTwoDancersCrossingAtSpeedMaintainsIdentities() {
        // Track 1 moves Right: x = 100 -> 150 -> 200 -> 250 -> 300
        // Track 2 moves Left:  x = 300 -> 250 -> 200 -> 150 -> 100
        val p1_f0 = PersonDetection(FloatRect(100f, 100f, 160f, 300f), 0.95f, createDummyMask())
        val p2_f0 = PersonDetection(FloatRect(300f, 100f, 360f, 300f), 0.95f, createDummyMask())
        val init = tracker.initialize(listOf(p1_f0, p2_f0))
        val id1 = init[0].id // moves right
        val id2 = init[1].id // moves left

        // Frame 1: Approach
        val p1_f1 = PersonDetection(FloatRect(150f, 100f, 210f, 300f), 0.95f, createDummyMask())
        val p2_f1 = PersonDetection(FloatRect(250f, 100f, 310f, 300f), 0.95f, createDummyMask())
        tracker.update(listOf(p1_f1, p2_f1), timestampUs = 33_333L)

        // Frame 2: Maximum Overlap / Crossing Center
        val p1_f2 = PersonDetection(FloatRect(190f, 100f, 250f, 300f), 0.95f, createDummyMask())
        val p2_f2 = PersonDetection(FloatRect(210f, 100f, 270f, 300f), 0.95f, createDummyMask())
        tracker.update(listOf(p1_f2, p2_f2), timestampUs = 66_666L)

        // Frame 3: Separation on Opposite Sides
        val p1_f3 = PersonDetection(FloatRect(260f, 100f, 320f, 300f), 0.95f, createDummyMask())
        val p2_f3 = PersonDetection(FloatRect(140f, 100f, 200f, 300f), 0.95f, createDummyMask())
        val tracks_f3 = tracker.update(listOf(p1_f3, p2_f3), timestampUs = 100_000L)

        val t1 = tracks_f3.find { it.id == id1 }
        val t2 = tracks_f3.find { it.id == id2 }

        // Track 1 (moving right) is at x >= 260f
        assertEquals(260f, t1?.bbox?.left)
        // Track 2 (moving left) is at x <= 140f
        assertEquals(140f, t2?.bbox?.left)
    }
}
