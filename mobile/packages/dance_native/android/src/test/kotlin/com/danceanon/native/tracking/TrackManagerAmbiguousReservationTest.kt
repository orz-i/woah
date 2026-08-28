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

    @Test
    fun testAmbiguousGroupAssignmentsDoNotLeakIntoGlobalHungarian() {
        val bboxOnlyConfig = TrackingConfig(
            minMatchScore = 0.20f,
            bboxIouWeight = 1.0f,
            maskIouWeight = 0.0f,
            motionWeight = 0.0f,
            directionWeight = 0.0f,
            associationAmbiguityMargin = 0.05f
        )
        tracker = TrackManager(bboxOnlyConfig)

        // The initial tracks overlap enough to form one occlusion group.
        val a0 = PersonDetection(FloatRect(200f, 100f, 300f, 300f), 0.95f)
        val b0 = PersonDetection(FloatRect(220f, 100f, 320f, 300f), 0.95f)
        val initial = tracker.initialize(listOf(a0, b0))
        val idA = initial[0].id
        val idB = initial[1].id

        // BBox-only score geometry intentionally makes the Hungarian optimum use
        // assignments that are not reciprocal best:
        //   A -> leftCandidate is far below A's row-best score.
        //   B -> centerCandidate is below that detection's column-best score.
        // Group association must therefore defer both identities instead of
        // allowing Global Hungarian to immediately commit the same assignments.
        val centerCandidate = PersonDetection(FloatRect(206f, 100f, 306f, 300f), 0.95f)
        val leftCandidate = PersonDetection(FloatRect(155f, 100f, 255f, 300f), 0.95f)

        val tracks = tracker.update(
            detections = listOf(centerCandidate, leftCandidate),
            timestampUs = 33_333L
        )

        val trackA = tracks.single { it.id == idA }
        val trackB = tracks.single { it.id == idB }

        val unresolved = listOf(trackA, trackB).filter {
            it.state == TrackState.REACQUIRING || it.state == TrackState.OCCLUDED
        }
        assertTrue(unresolved.isNotEmpty(), "at least one ambiguous group identity must remain unresolved")
        assertTrue(unresolved.all { !it.observedThisFrame })
        assertTrue(
            listOf(trackA, trackB).count { it.observedThisFrame } < 2,
            "Global Hungarian must not consume every identity that group association left ambiguous"
        )
    }
}
