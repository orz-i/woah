package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackManagerTest {

    private fun createTestMask(width: Int = 160, height: Int = 160, fillByte: Byte = 255.toByte()): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * height)
        for (i in 0 until width * height) {
            buf.put(fillByte)
        }
        buf.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buf,
            originalWidth = 640,
            originalHeight = 640
        )
    }

    @Test
    fun testTwoPeopleCrossingMaintainsStableIds() {
        val tracker = TrackManager(TrackingConfig(minMatchScore = 0.30f))

        // Initial positions: Person A at x=100 (moving right), Person B at x=500 (moving left)
        val detA0 = PersonDetection(FloatRect(80f, 100f, 120f, 300f), 0.95f, createTestMask())
        val detB0 = PersonDetection(FloatRect(480f, 100f, 520f, 300f), 0.95f, createTestMask())
        val tracks0 = tracker.initialize(listOf(detA0, detB0))
        assertEquals(0, tracks0[0].id)
        assertEquals(1, tracks0[1].id)

        val dtUs = 33333L
        // Simulate 20 frames approaching and crossing
        for (i in 1..20) {
            val pts = i * dtUs
            val xA = 100f + i * 15f // x: 100 -> 400
            val xB = 500f - i * 15f // x: 500 -> 200

            val detA = PersonDetection(FloatRect(xA - 20f, 100f, xA + 20f, 300f), 0.95f, createTestMask())
            val detB = PersonDetection(FloatRect(xB - 20f, 100f, xB + 20f, 300f), 0.95f, createTestMask())

            val updated = tracker.update(listOf(detA, detB), pts)
            assertEquals(2, updated.size)

            val track0 = updated.first { it.id == 0 }
            val track1 = updated.first { it.id == 1 }

            // Track 0 should be moving right, Track 1 should be moving left
            assertEquals(xA, track0.bbox.centerX, 15f)
            assertEquals(xB, track1.bbox.centerX, 15f)
        }
    }

    @Test
    fun testTemporarilyLostTrackWarpsMaskAndRecovers() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 10))

        val startBox = FloatRect(100f, 100f, 200f, 300f)
        val initialMask = createTestMask()
        val initTracks = tracker.initialize(listOf(PersonDetection(startBox, 0.90f, initialMask)))
        assertEquals(1, initTracks.size)
        assertEquals(0, initTracks[0].id)

        val dtUs = 33333L
        // Track actively for 5 frames
        for (i in 1..5) {
            val pts = i * dtUs
            val box = FloatRect(100f + i * 10f, 100f, 200f + i * 10f, 300f)
            tracker.update(listOf(PersonDetection(box, 0.90f, createTestMask())), pts)
        }

        // Lost for 3 frames (detections empty)
        var lostTrack: TrackedPerson? = null
        for (i in 6..8) {
            val pts = i * dtUs
            val preds = tracker.update(emptyList(), pts)
            assertEquals(1, preds.size)
            lostTrack = preds[0]
            assertEquals(TrackState.LOST, lostTrack.state)
            assertNotNull(lostTrack.mask, "Mask must still exist and be warped during LOST frames")
            assertTrue(lostTrack.bbox.centerX > 150f, "Predicted bbox should continue rightward")
        }

        // Recovery on frame 9
        val recoverBox = FloatRect(190f, 100f, 290f, 300f)
        val recoveredTracks = tracker.update(listOf(PersonDetection(recoverBox, 0.92f, createTestMask())), 9 * dtUs)
        assertEquals(1, recoveredTracks.size)
        assertEquals(0, recoveredTracks[0].id, "Track ID must remain 0 after recovery")
        assertEquals(TrackState.ACTIVE, recoveredTracks[0].state)
    }

    @Test
    fun testNewPersonEntersAndOldLeaves() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 5))

        val personA = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.90f, createTestMask())
        val personB = PersonDetection(FloatRect(400f, 100f, 500f, 300f), 0.90f, createTestMask())
        tracker.initialize(listOf(personA, personB))

        val dtUs = 33333L
        // Frame 1: Person B vanishes, Person C enters at x=700
        val personC = PersonDetection(FloatRect(650f, 100f, 750f, 300f), 0.90f, createTestMask())
        val updated1 = tracker.update(listOf(personA, personC), 1 * dtUs)

        // Person A (id=0), Person B (id=1, lost), Person C (id=2, new)
        val trackA = updated1.firstOrNull { it.id == 0 }
        val trackB = updated1.firstOrNull { it.id == 1 }
        val trackC = updated1.firstOrNull { it.id == 2 }

        assertNotNull(trackA)
        assertNotNull(trackB)
        assertNotNull(trackC)
        assertEquals(TrackState.ACTIVE, trackA.state)
        assertEquals(TrackState.LOST, trackB.state)
        assertEquals(TrackState.ACTIVE, trackC.state)

        // Keep running for 6 frames with only A and C present -> B should exceed maxMissedFrames and be removed
        for (i in 2..8) {
            tracker.update(listOf(personA, personC), i * dtUs)
        }

        val finalTracks = tracker.update(listOf(personA, personC), 9 * dtUs)
        assertEquals(2, finalTracks.size)
        val ids = finalTracks.map { it.id }.toSet()
        assertEquals(setOf(0, 2), ids)
    }
}
