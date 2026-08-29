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
    fun framesSinceLastObservationTracksAbsoluteFreshnessAcrossStateCounters() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 10))
        val box = FloatRect(100f, 100f, 200f, 300f)
        val initial = tracker.initialize(listOf(PersonDetection(box, 0.95f, createTestMask())))
        assertEquals(0, initial.single().framesSinceLastObservation)

        val dtUs = 33333L
        val predicted1 = tracker.update(emptyList(), dtUs)
        assertEquals(1, predicted1.single().framesSinceLastObservation)

        val predicted2 = tracker.update(emptyList(), 2 * dtUs)
        assertEquals(2, predicted2.single().framesSinceLastObservation)

        val recovered = tracker.update(
            listOf(PersonDetection(FloatRect(102f, 100f, 202f, 300f), 0.96f, createTestMask())),
            3 * dtUs
        )
        assertEquals(0, recovered.single().framesSinceLastObservation)
        assertEquals(TrackState.ACTIVE, recovered.single().state)
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

    @Test
    fun testRemovedTrackDoesNotReviveInPredictOrUpdate() {
        val maxMissed = 3
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = maxMissed))

        val personA = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createTestMask())
        val init = tracker.initialize(listOf(personA))
        assertEquals(1, init.size)
        assertEquals(0, init[0].id)

        val dtUs = 33333L

        // Predict for maxMissed + 2 frames
        for (i in 1..(maxMissed + 2)) {
            val preds = tracker.predict(i * dtUs)
            if (i <= maxMissed) {
                assertEquals(1, preds.size, "Should be kept as LOST up to maxMissed")
                assertEquals(TrackState.LOST, preds[0].state)
            } else {
                assertTrue(preds.isEmpty(), "Beyond maxMissed, track must be filtered out as REMOVED")
            }
        }

        // Now a new detection appears at the exact same location
        val newPersonAtSameLocation = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createTestMask())
        val updated = tracker.update(listOf(newPersonAtSameLocation), (maxMissed + 3) * dtUs)

        assertEquals(1, updated.size)
        assertEquals(1, updated[0].id, "New detection must be assigned a new track ID (1), NOT reviving removed ID (0)")
        assertEquals(TrackState.ACTIVE, updated[0].state)
    }

    @Test
    fun testWarpMaskMultiAspectRatioLetterboxParity() {
        // Test 16:9 Landscape, 9:16 Portrait, and 1:1 Square
        val aspectRatios = listOf(
            Triple(1920, 1080, "16:9 Landscape"),
            Triple(1080, 1920, "9:16 Portrait"),
            Triple(1080, 1080, "1:1 Square")
        )

        for ((srcW, srcH, desc) in aspectRatios) {
            val mapper = com.danceanon.native.geometry.ModelCoordinateMapper(srcW, srcH, modelInputSize = 640, protoSize = 160)
            val protoW = 160
            val protoH = 160

            // Create a mask with a single mark at the bbox center
            val prevBbox = FloatRect(
                left = srcW * 0.4f,
                top = srcH * 0.4f,
                right = srcW * 0.6f,
                bottom = srcH * 0.6f
            )

            val maskBuf = ByteBuffer.allocateDirect(protoW * protoH)
            val prevProtoX = mapper.sourceToProtoX(prevBbox.centerX).toInt().coerceIn(0, 159)
            val prevProtoY = mapper.sourceToProtoY(prevBbox.centerY).toInt().coerceIn(0, 159)

            for (i in 0 until protoW * protoH) maskBuf.put(0.toByte())
            maskBuf.put(prevProtoY * protoW + prevProtoX, 255.toByte())
            maskBuf.rewind()

            val nativeMask = NativeMask(
                width = protoW,
                height = protoH,
                buffer = maskBuf,
                originalWidth = srcW,
                originalHeight = srcH,
                mapper = mapper
            )

            // Translate bbox by +10% in X and +10% in Y
            val predBbox = FloatRect(
                left = prevBbox.left + srcW * 0.1f,
                top = prevBbox.top + srcH * 0.1f,
                right = prevBbox.right + srcW * 0.1f,
                bottom = prevBbox.bottom + srcH * 0.1f
            )

            val warped = TrackManager.warpMask(
                sourceMask = nativeMask,
                prevBbox = prevBbox,
                predBbox = predBbox,
                missedFrames = 1
            )

            val expectedNewProtoX = mapper.sourceToProtoX(predBbox.centerX).toInt().coerceIn(0, 159)
            val expectedNewProtoY = mapper.sourceToProtoY(predBbox.centerY).toInt().coerceIn(0, 159)

            val warpedBuf = warped.buffer
            warpedBuf.rewind()

            // The marked pixel in the warped mask should have translated to around (expectedNewProtoX, expectedNewProtoY)
            val pixelVal = warpedBuf.get(expectedNewProtoY * protoW + expectedNewProtoX).toInt() and 0xFF
            assertEquals(255, pixelVal, "$desc: Warped mask center must align with translated bbox proto coordinates")
        }
    }

    @Test
    fun skippedInferenceDoesNotIncrementMissedFrames() {
        val tracker = TrackManager()
        val det = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createTestMask())
        tracker.initialize(listOf(det))

        // Stride skip for 5 frames
        for (i in 1..5) {
            val results = tracker.predictWithoutObservation(i * 33333L)
            assertEquals(1, results.size)
            assertEquals(0, results[0].missedFrames, "Skipped inference MUST NOT increment missedFrames")
            assertEquals(TrackState.ACTIVE, results[0].state, "Skipped inference MUST keep track in ACTIVE state")
        }
    }

    @Test
    fun skippedInferenceStillMovesPredictedBBoxAndPrivacyMask() {
        val tracker = TrackManager()
        val det0 = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createTestMask())
        tracker.initialize(listOf(det0))

        // Train velocity with one update
        val det1 = PersonDetection(FloatRect(120f, 100f, 220f, 300f), 0.95f, createTestMask())
        tracker.update(listOf(det1), 33333L)

        // Predict on skipped frame
        val results = tracker.predictWithoutObservation(66666L)
        assertEquals(1, results.size)
        assertTrue(results[0].bbox.centerX > 160f, "Predicted bbox should advance based on learned velocity")
        assertNotNull(results[0].mask, "Privacy mask must be present and warped during skipped frame")
    }

    @Test
    fun actualEmptyDetectionIncrementsMissedFramesAndEventuallyMarksTrackLost() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 3))
        val det = PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createTestMask())
        tracker.initialize(listOf(det))

        // Detector actual misses
        val res1 = tracker.predict(33333L)
        assertEquals(1, res1[0].missedFrames)
        assertEquals(TrackState.LOST, res1[0].state)

        val res2 = tracker.predict(66666L)
        assertEquals(2, res2[0].missedFrames)
        assertEquals(TrackState.LOST, res2[0].state)

        val res3 = tracker.predict(99999L)
        assertEquals(3, res3[0].missedFrames)
        assertEquals(TrackState.LOST, res3[0].state)

        // 4th miss exceeds maxMissedFrames=3 -> removed
        val res4 = tracker.predict(133332L)
        assertEquals(0, res4.size, "Track should be removed after exceeding maxMissedFrames")
    }
}


