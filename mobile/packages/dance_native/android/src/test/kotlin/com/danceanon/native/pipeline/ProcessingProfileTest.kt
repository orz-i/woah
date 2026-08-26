package com.danceanon.native.pipeline

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackState
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessingProfileTest {

    @Test
    fun testQualityProfileUsesStride1() {
        val profile = ProcessingProfile.fromName("quality")
        assertEquals("quality", profile.name)
        assertEquals(1, profile.inferenceStride)
        assertEquals(640, profile.inputSize)
    }

    @Test
    fun testBalancedProfileUsesStride2() {
        val profile = ProcessingProfile.fromName("balanced")
        assertEquals("balanced", profile.name)
        assertEquals(2, profile.inferenceStride)
        assertEquals(640, profile.inputSize)
    }

    @Test
    fun testSpeedProfileUsesStride3() {
        val profile = ProcessingProfile.fromName("speed")
        assertEquals("speed", profile.name)
        assertEquals(3, profile.inferenceStride)
        assertEquals(640, profile.inputSize)
    }

    @Test
    fun testSam2ProfileUsesDynamicImageSizeAndStride1() {
        val profile = ProcessingProfile.fromName("sam2")
        assertEquals("sam2", profile.name)
        assertEquals(1, profile.inferenceStride)
        assertEquals(com.danceanon.native.sam2.Sam2TensorContract.IMAGE_SIZE, profile.inputSize)
        assertTrue(profile.useSam2)
    }

    @Test
    fun testDefaultFallbackToQuality() {
        val nullProfile = ProcessingProfile.fromName(null)
        assertEquals(ProcessingProfile.QUALITY, nullProfile)

        val unknownProfile = ProcessingProfile.fromName("ultra_fast")
        assertEquals(ProcessingProfile.QUALITY, unknownProfile)
    }


    @Test
    fun testInferenceCadenceWithStride3MaintainsTracking() {
        val tracker = TrackManager()
        val dtUs = 33_333L
        val stride = ProcessingProfile.SPEED.inferenceStride
        assertEquals(3, stride)

        // Frame 1: YOLO inference and init
        val b0 = FloatRect(100f, 100f, 200f, 400f)
        val d0 = PersonDetection(bbox = b0, confidence = 0.95f, mask = NativeMask(160, 160, ByteBuffer.allocateDirect(160 * 160), 1920, 1080))
        val initTracks = tracker.initialize(listOf(d0))
        assertEquals(1, initTracks.size)
        assertEquals(0, initTracks[0].id)

        var inferenceCount = 1
        var predictCount = 0

        // Simulate 30 video frames
        for (f in 2..30) {
            val shouldInfer = (f == 1) || (f % stride == 0)
            val ptsUs = f * dtUs

            val currentTracks = if (shouldInfer) {
                inferenceCount++
                val curX = 100f + f * 10f
                val curBox = FloatRect(curX, 100f, curX + 100f, 400f)
                val curDet = PersonDetection(bbox = curBox, confidence = 0.95f, mask = NativeMask(160, 160, ByteBuffer.allocateDirect(160 * 160), 1920, 1080))
                tracker.update(listOf(curDet), ptsUs)
            } else {
                predictCount++
                tracker.predict(ptsUs)
            }

            assertEquals(1, currentTracks.size, "Frame  must produce exactly 1 tracked person")
            assertEquals(0, currentTracks[0].id, "Track ID must remain stable across skipped inference frames")
            assertTrue(currentTracks[0].state == TrackState.ACTIVE || currentTracks[0].state == TrackState.LOST)
        }

        assertEquals(11, inferenceCount, "30 frames with stride=3 should result in 11 YOLO inferences (frame 1, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30)")
        assertEquals(19, predictCount, "30 frames with stride=3 should result in 19 predicted frames")
    }
}
