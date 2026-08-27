package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerReacquireIdentityTest {

    private lateinit var tracker: TrackManager

    @BeforeTest
    fun setUp() {
        tracker = TrackManager(TrackingConfig(postOcclusionGraceFrames = 10))
    }

    private fun createMask(isPersonA: Boolean): NativeMask {
        val size = 64
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val value = if (isPersonA && x < size / 2) 255 else if (!isPersonA && x >= size / 2) 255 else 0
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
    fun testReacquireTransitionPreservesIdentityAfterTemporaryOcclusion() {
        // Frame 1: Person A at x=100, Person B approaching at x=140
        val f1DetA = PersonDetection(bbox = FloatRect(100f, 100f, 160f, 300f), confidence = 0.95f, mask = createMask(isPersonA = true))
        val f1DetB = PersonDetection(bbox = FloatRect(140f, 100f, 200f, 300f), confidence = 0.95f, mask = createMask(isPersonA = false))
        val init = tracker.initialize(listOf(f1DetA, f1DetB))
        val idA = init[0].id
        val idB = init[1].id

        // Frame 2: Person B moves over Person A (at x=105), Person A detection temporarily lost
        val f2DetB = PersonDetection(bbox = FloatRect(105f, 100f, 165f, 300f), confidence = 0.95f, mask = createMask(isPersonA = false))
        val f2Tracks = tracker.update(listOf(f2DetB), timestampUs = 33_333L)
        val trackA_f2 = f2Tracks.find { it.id == idA }
        assertTrue(trackA_f2 != null, "Track A must persist during occlusion")
        assertEquals(TrackState.OCCLUDED, trackA_f2.state)

        // Frame 3: Person B moves away (x=160), Person A not yet redetected -> transitions to REACQUIRING
        val f3DetB = PersonDetection(bbox = FloatRect(160f, 100f, 220f, 300f), confidence = 0.95f, mask = createMask(isPersonA = false))
        val f3Tracks = tracker.update(listOf(f3DetB), timestampUs = 66_666L)
        val trackA_f3 = f3Tracks.find { it.id == idA }
        assertTrue(trackA_f3 != null, "Track A must persist into REACQUIRING")
        assertEquals(TrackState.REACQUIRING, trackA_f3.state)

        // Frame 4: Person A reappears at x=105 -> successfully reacquired!
        val f4DetA = PersonDetection(bbox = FloatRect(105f, 100f, 165f, 300f), confidence = 0.95f, mask = createMask(isPersonA = true))
        val f4DetB = PersonDetection(bbox = FloatRect(220f, 100f, 280f, 300f), confidence = 0.95f, mask = createMask(isPersonA = false))
        val f4Tracks = tracker.update(listOf(f4DetA, f4DetB), timestampUs = 100_000L)

        val trackA_f4 = f4Tracks.find { it.id == idA }
        val trackB_f4 = f4Tracks.find { it.id == idB }

        assertTrue(trackA_f4 != null, "Track A must be reacquired")
        assertEquals(TrackState.ACTIVE, trackA_f4.state)
        assertEquals(0, trackA_f4.missedFrames)

        assertTrue(trackB_f4 != null, "Track B must remain active")
        assertEquals(TrackState.ACTIVE, trackB_f4.state)
    }
}
