package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerOcclusionGroupFirstTest {

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
    fun testOcclusionGroupFirstPreventsIdSwapOnCrossing() {
        // Frame 1: Person A at x=100 (moving right), Person B at x=300 (moving left)
        val f1DetA = PersonDetection(bbox = FloatRect(100f, 100f, 160f, 300f), confidence = 0.95f, mask = createDummyMask())
        val f1DetB = PersonDetection(bbox = FloatRect(300f, 100f, 360f, 300f), confidence = 0.95f, mask = createDummyMask())
        val initTracks = tracker.initialize(listOf(f1DetA, f1DetB))
        assertEquals(2, initTracks.size)
        val idA = initTracks[0].id
        val idB = initTracks[1].id

        // Frame 2: Person A at x=150, Person B at x=250 (approaching)
        val f2DetA = PersonDetection(bbox = FloatRect(150f, 100f, 210f, 300f), confidence = 0.95f, mask = createDummyMask())
        val f2DetB = PersonDetection(bbox = FloatRect(250f, 100f, 310f, 300f), confidence = 0.95f, mask = createDummyMask())
        tracker.update(listOf(f2DetA, f2DetB), timestampUs = 33_333L)

        // Frame 3: Person A at x=200, Person B at x=205 (overlapping/crossing - OcclusionGroup active)
        val f3DetA = PersonDetection(bbox = FloatRect(200f, 100f, 260f, 300f), confidence = 0.95f, mask = createDummyMask())
        val f3DetB = PersonDetection(bbox = FloatRect(205f, 100f, 265f, 300f), confidence = 0.95f, mask = createDummyMask())
        tracker.update(listOf(f3DetA, f3DetB), timestampUs = 66_666L)

        // Frame 4: Post-crossing separation.
        // Person A moved to x=255 (right side). Person B moved to x=150 (left side).
        // Detector returns them in left-to-right order (Det 0 at x=150, Det 1 at x=255)
        val f4DetLeft = PersonDetection(bbox = FloatRect(150f, 100f, 210f, 300f), confidence = 0.95f, mask = createDummyMask())
        val f4DetRight = PersonDetection(bbox = FloatRect(255f, 100f, 315f, 300f), confidence = 0.95f, mask = createDummyMask())
        val f4Tracks = tracker.update(listOf(f4DetLeft, f4DetRight), timestampUs = 100_000L)

        assertEquals(2, f4Tracks.size)
        val trackA = f4Tracks.find { it.id == idA }
        val trackB = f4Tracks.find { it.id == idB }

        assertTrue(trackA != null, "Track A must exist")
        assertTrue(trackB != null, "Track B must exist")

        // CRITICAL CHECK: Person A (moving right) must be at x=255, Person B (moving left) must be at x=150
        val debugTracks = f4Tracks.map { "id=${it.id},state=${it.state},left=${it.bbox.left},observed=${it.observedThisFrame}" }
        assertTrue(
            trackA.bbox.left > 240f,
            "Person A must continue rightwards (>240), was ${trackA.bbox.left}; tracks=$debugTracks"
        )
        assertTrue(
            trackB.bbox.left < 170f,
            "Person B must continue leftwards (<170), was ${trackB.bbox.left}; tracks=$debugTracks"
        )
    }
}
