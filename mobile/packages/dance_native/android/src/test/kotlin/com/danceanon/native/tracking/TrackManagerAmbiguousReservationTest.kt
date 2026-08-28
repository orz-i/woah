package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class TrackManagerAmbiguousReservationTest {

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
    fun testAmbiguousGroupDetectionDoesNotForciblyCreateNewTrackOrSwap() {
        // Frame 0: Two dancers in overlapping proximity
        val p1_f0 = PersonDetection(FloatRect(180f, 100f, 260f, 300f), 0.95f, createDummyMask())
        val p2_f0 = PersonDetection(FloatRect(200f, 100f, 280f, 300f), 0.95f, createDummyMask())
        val init = tracker.initialize(listOf(p1_f0, p2_f0))
        val id1 = init[0].id
        val id2 = init[1].id

        // Frame 1: Dense occlusion, only 1 detection returned
        val p_shared = PersonDetection(FloatRect(190f, 100f, 270f, 300f), 0.95f, createDummyMask())
        val tracks_f1 = tracker.update(listOf(p_shared), timestampUs = 33_333L)

        // One dancer matched to detection, other enters OCCLUDED or REACQUIRING without creating spurious new ID
        assertEquals(2, tracks_f1.size)
        val matchedCount = tracks_f1.count { it.state == TrackState.ACTIVE }
        val occludedOrReacquiring = tracks_f1.count { it.state == TrackState.OCCLUDED || it.state == TrackState.REACQUIRING }
        assertEquals(1, matchedCount)
        assertEquals(1, occludedOrReacquiring)

        // Frame 2: Dancers separate cleanly
        val p1_f2 = PersonDetection(FloatRect(100f, 100f, 180f, 300f), 0.95f, createDummyMask())
        val p2_f2 = PersonDetection(FloatRect(300f, 100f, 380f, 300f), 0.95f, createDummyMask())
        val tracks_f2 = tracker.update(listOf(p1_f2, p2_f2), timestampUs = 66_666L)

        assertEquals(2, tracks_f2.size)
        val t1 = tracks_f2.find { it.id == id1 }
        val t2 = tracks_f2.find { it.id == id2 }
        assertEquals(TrackState.ACTIVE, t1?.state)
        assertEquals(TrackState.ACTIVE, t2?.state)
    }
}
