package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerFastLateralMotionTest {

    private lateinit var tracker: TrackManager

    @BeforeTest
    fun setUp() {
        tracker = TrackManager(TrackingConfig())
    }

    private fun createDummyMask(): NativeMask {
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
    fun testFastLateralDisplacementTrackPreservation() {
        // Frame 1: Person at x=100
        val det1 = PersonDetection(bbox = FloatRect(100f, 100f, 160f, 300f), confidence = 0.95f, mask = createDummyMask())
        val init = tracker.initialize(listOf(det1))
        val targetId = init[0].id

        // Frame 2: Person shifts +50px (fast lateral jump)
        val det2 = PersonDetection(bbox = FloatRect(150f, 100f, 210f, 300f), confidence = 0.95f, mask = createDummyMask())
        val tracks2 = tracker.update(listOf(det2), timestampUs = 33_333L)
        assertEquals(1, tracks2.size)
        assertEquals(targetId, tracks2[0].id)
        assertEquals(TrackState.ACTIVE, tracks2[0].state)

        // Frame 3: Person shifts another +55px (continued fast lateral motion)
        val det3 = PersonDetection(bbox = FloatRect(205f, 100f, 265f, 300f), confidence = 0.95f, mask = createDummyMask())
        val tracks3 = tracker.update(listOf(det3), timestampUs = 66_666L)
        assertEquals(1, tracks3.size)
        assertEquals(targetId, tracks3[0].id)
        assertEquals(TrackState.ACTIVE, tracks3[0].state)
        assertTrue(tracks3[0].bbox.left > 190f)
    }
}
