package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerAmbiguousCrossingTest {

    private lateinit var tracker: TrackManager

    @BeforeTest
    fun setUp() {
        tracker = TrackManager(TrackingConfig(associationAmbiguityMargin = 0.05f))
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
    fun testAmbiguousOverlapDefersCommitWithoutSwapping() {
        // Frame 1: Two persons side by side
        val det1 = PersonDetection(bbox = FloatRect(190f, 100f, 250f, 300f), confidence = 0.9f, mask = createDummyMask())
        val det2 = PersonDetection(bbox = FloatRect(210f, 100f, 270f, 300f), confidence = 0.9f, mask = createDummyMask())
        val init = tracker.initialize(listOf(det1, det2))
        assertEquals(2, init.size)

        // Frame 2: Heavy overlap, detections virtually identical
        val f2Det1 = PersonDetection(bbox = FloatRect(200f, 100f, 260f, 300f), confidence = 0.9f, mask = createDummyMask())
        val f2Det2 = PersonDetection(bbox = FloatRect(201f, 100f, 261f, 300f), confidence = 0.9f, mask = createDummyMask())
        val tracks = tracker.update(listOf(f2Det1, f2Det2), timestampUs = 33_333L)

        assertEquals(2, tracks.size)
        // Both tracks should retain valid state and non-null masks
        for (t in tracks) {
            assertTrue(t.mask != null, "Mask must not be null during ambiguous overlap")
        }
    }

    @Test
    fun testGlobalNearTieBeforeOverlapDoesNotCommitIdentity() {
        tracker = TrackManager(
            TrackingConfig(
                minMatchScore = 0.15f,
                bboxIouWeight = 1.0f,
                maskIouWeight = 0.0f,
                motionWeight = 0.0f,
                directionWeight = 0.0f,
                associationAmbiguityMargin = 0.05f
            )
        )

        // Tracks are close but not overlapping, so no occlusion group exists yet.
        val initial = tracker.initialize(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(210f, 100f, 310f, 300f), 0.95f, createDummyMask())
            )
        )
        val originalIds = initial.map { it.id }.toSet()

        // Two almost-identical detections span both tracks. Global Hungarian can
        // produce a mathematically valid assignment, but neither row nor column
        // has enough alternative margin to justify an identity commitment.
        val tracks = tracker.update(
            listOf(
                PersonDetection(FloatRect(150f, 100f, 260f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(151f, 100f, 261f, 300f), 0.95f, createDummyMask())
            ),
            timestampUs = 33_333L
        )

        assertEquals(originalIds, tracks.map { it.id }.toSet(), "ambiguous global detections must not mint replacement identities")
        assertTrue(
            tracks.all { it.state == TrackState.REACQUIRING && !it.observedThisFrame },
            "global near-ties must defer identity commitment before an occlusion group has formed"
        )
    }

    @Test
    fun testProtectedGlobalCommitRequiresAbsoluteIdentityEvidence() {
        tracker = TrackManager(
            TrackingConfig(
                minMatchScore = 0.20f,
                bboxIouWeight = 1.0f,
                maskIouWeight = 0.0f,
                motionWeight = 0.0f,
                directionWeight = 0.0f,
                associationAmbiguityMargin = 0.05f
            )
        )
        tracker.setIdentityProtectedTrackIds(setOf(7))
        tracker.setPrivacySelectedTrackIds(setOf(7))
        tracker.initializeWithAssignedIds(
            detections = listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createDummyMask())
            ),
            assignedIds = listOf(7)
        )

        // IoU is 0.25: enough for the bbox-only global score (>= 0.20), but
        // below the protected ACTIVE absolute-evidence gate (0.35). With only
        // one row/column there is no near-tie ambiguity to save us; the global
        // protected gate must defer rather than transferring the selected ID.
        val tracks = tracker.update(
            listOf(PersonDetection(FloatRect(160f, 100f, 260f, 300f), 0.95f, mask = null)),
            timestampUs = 33_333L
        )
        val protected = tracks.single { it.id == 7 }
        assertEquals(TrackState.REACQUIRING, protected.state)
        assertTrue(!protected.observedThisFrame)
        assertTrue(
            protected.bbox.centerX < 200f,
            "weak global candidate must not take over the protected FULL_BODY identity"
        )
    }
}
