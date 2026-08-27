package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackManagerLongOcclusionReacquisitionTest {

    private fun createMovingMask(
        width: Int = 160,
        height: Int = 160,
        centerX: Float,
        centerY: Float,
        radius: Float = 15f
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val inCircle = (dx * dx + dy * dy) <= (radius * radius)
                buf.put(if (inCircle) 255.toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buf,
            originalWidth = 1920,
            originalHeight = 1080,
            mapper = null
        )
    }

    @Test
    fun testLongOcclusion25FramesDoesNotInstantRemoveTargetAndReacquiresId() {
        val config = TrackingConfig(
            maxMissedFrames = 15,
            postOcclusionGraceFrames = 10,
            occlusionOverlapRatio = 0.30f
        )
        val tracker = TrackManager(config)

        val fps = 30.0
        val frameDurationUs = (1_000_000.0 / fps).toLong()
        var currentPtsUs = 100_000L

        // Frame 0: Person A at 400 (yCenter = 30), Person B at 600 (yCenter = 70)
        val initialA = PersonDetection(
            bbox = FloatRect(350f, 200f, 450f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 40f, 30f)
        )
        val initialB = PersonDetection(
            bbox = FloatRect(550f, 200f, 650f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 60f, 70f)
        )

        val initTracks = tracker.initializeWithAssignedIds(listOf(initialA, initialB), listOf(0, 1))
        assertEquals(2, initTracks.size)
        assertEquals(0, initTracks[0].id)
        assertEquals(1, initTracks[1].id)

        // Frames 1..5: Both approach center x = 500 and hold position
        val approachA = listOf(420f, 450f, 480f, 500f, 500f)
        val approachB = listOf(580f, 550f, 520f, 500f, 500f)
        for (f in 0 until 5) {
            currentPtsUs += frameDurationUs
            val xA = approachA[f]
            val xB = approachB[f]
            val detA = PersonDetection(
                bbox = FloatRect(xA - 50f, 200f, xA + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xA / 10f, 30f)
            )
            val detB = PersonDetection(
                bbox = FloatRect(xB - 50f, 200f, xB + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xB / 10f, 70f)
            )
            val tracks = tracker.update(listOf(detA, detB), currentPtsUs)
            assertEquals(2, tracks.size)
        }

        // Frames 6..30: Long Occlusion period (25 frames, > maxMissedFrames=15).
        // Person A is occluded behind Person B centered at x = 500. Only detection for B is returned!
        for (f in 6..30) {
            currentPtsUs += frameDurationUs
            val xB = 500f
            val detB = PersonDetection(
                bbox = FloatRect(xB - 50f, 200f, xB + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xB / 10f, 70f)
            )
            val tracks = tracker.update(listOf(detB), currentPtsUs)

            // CRITICAL ASSERTION: Track 0 (selected A) must NEVER be removed during 25-frame occlusion!
            val track0 = tracks.find { it.id == 0 }
            assertNotNull(track0, "Track 0 must NOT be removed at frame $f (during long occlusion)")
            assertTrue(
                track0.state == TrackState.OCCLUDED || track0.state == TrackState.REACQUIRING,
                "Track 0 state must be OCCLUDED or REACQUIRING at frame $f (was ${track0.state})"
            )
        }

        // Frame 31 (First separation frame): B moves left to 400, A continues right to 600 (not detected yet)
        currentPtsUs += frameDurationUs
        val detB31 = PersonDetection(
            bbox = FloatRect(350f, 200f, 450f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 40f, 70f)
        )
        val tracks31 = tracker.update(listOf(detB31), currentPtsUs)
        val track0_31 = tracks31.find { it.id == 0 }
        assertNotNull(track0_31, "Track 0 must NOT be removed on first separation frame")
        assertEquals(TrackState.REACQUIRING, track0_31.state, "Track 0 must be in REACQUIRING state")

        // Frame 32 (Second separation frame): Detection for A returns at 650!
        currentPtsUs += frameDurationUs
        val detA32 = PersonDetection(
            bbox = FloatRect(600f, 200f, 700f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 65f, 30f)
        )
        val detB32 = PersonDetection(
            bbox = FloatRect(300f, 200f, 400f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 35f, 70f)
        )
        val tracks32 = tracker.update(listOf(detA32, detB32), currentPtsUs)

        // Verify successful reacquisition of original ID
        val reacquiredA = tracks32.find { it.id == 0 }
        assertNotNull(reacquiredA, "Track 0 must be successfully recovered")
        assertEquals(TrackState.ACTIVE, reacquiredA.state)
        assertEquals(0, reacquiredA.id, "ID 0 must be preserved for person A")
    }
}
