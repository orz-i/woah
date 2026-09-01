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
        tracker.setIdentityProtectedTrackIds(setOf(0, 1))
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
        val motionEvidence = tracker.getFreshProtectedTrackMotionEvidence()
        assertTrue(
            motionEvidence.isNotEmpty(),
            "reciprocal-best protected ambiguity should expose motion-only evidence"
        )
        assertTrue(
            motionEvidence.all { it.trackId in setOf(0, 1) && it.detection.mask != null },
            "motion-only evidence must remain attached to protected FACE_ONLY candidates"
        )
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

    @Test
    fun `motion evidence gate is looser than protected identity commit gate`() {
        assertTrue(
            TrackManager.isProtectedMotionEvidenceSufficient(
                bboxIoU = 0.24f,
                maskIoU = 0.07f
            )
        )
        assertTrue(
            !TrackManager.isProtectedGroupIdentityEvidenceSufficient(
                state = TrackState.REACQUIRING,
                bboxIoU = 0.24f,
                maskIoU = 0.07f
            )
        )
    }

    @Test
    fun `motion evidence still rejects spatially unrelated candidate`() {
        assertTrue(
            !TrackManager.isProtectedMotionEvidenceSufficient(
                bboxIoU = 0.10f,
                maskIoU = 0.04f
            )
        )
    }

    @Test
    fun `face only protected occlusion hold requires an existing occluded near tie`() {
        assertTrue(
            TrackManager.isProtectedFaceOnlyNearTieOcclusionHoldEligible(
                frameStartState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.6005f,
                winningScore = 0.6119f,
                protectedIdentityEvidenceOk = true,
                heldOnPreviousFrame = false,
                minMatchScore = 0.20f,
                ambiguityMargin = 0.05f
            )
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyNearTieOcclusionHoldEligible(
                frameStartState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.60f,
                winningScore = 0.67f,
                protectedIdentityEvidenceOk = true,
                heldOnPreviousFrame = false,
                minMatchScore = 0.20f,
                ambiguityMargin = 0.05f
            ),
            "a clearly separated winner is not an identity near tie"
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyNearTieOcclusionHoldEligible(
                frameStartState = TrackState.REACQUIRING,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.60f,
                winningScore = 0.61f,
                protectedIdentityEvidenceOk = true,
                heldOnPreviousFrame = false,
                minMatchScore = 0.20f,
                ambiguityMargin = 0.05f
            ),
            "the hold must never pull REACQUIRING back into OCCLUDED"
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyNearTieOcclusionHoldEligible(
                frameStartState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = true,
                bestScore = 0.60f,
                winningScore = 0.61f,
                protectedIdentityEvidenceOk = true,
                heldOnPreviousFrame = false,
                minMatchScore = 0.20f,
                ambiguityMargin = 0.05f
            ),
            "FULL_BODY identities must remain outside the FACE_ONLY state hold"
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyNearTieOcclusionHoldEligible(
                frameStartState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.60f,
                winningScore = 0.61f,
                protectedIdentityEvidenceOk = false,
                heldOnPreviousFrame = false,
                minMatchScore = 0.20f,
                ambiguityMargin = 0.05f
            )
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyNearTieOcclusionHoldEligible(
                frameStartState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.60f,
                winningScore = 0.61f,
                protectedIdentityEvidenceOk = true,
                heldOnPreviousFrame = true,
                minMatchScore = 0.20f,
                ambiguityMargin = 0.05f
            ),
            "a near-tie hold may bridge only one consecutive frame"
        )
    }

    @Test
    fun `protected face occluded track holds state when group winner is a near tie`() {
        tracker = TrackManager(
            TrackingConfig(
                minMatchScore = 0.20f,
                bboxIouWeight = 1.0f,
                maskIouWeight = 0.0f,
                motionWeight = 0.0f,
                directionWeight = 0.0f,
                associationAmbiguityMargin = 0.05f,
                occlusionOverlapRatio = 0.30f
            )
        )
        tracker.setIdentityProtectedTrackIds(setOf(0))
        tracker.setPrivacySelectedTrackIds(emptySet())

        tracker.initializeWithAssignedIds(
            detections = listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(160f, 100f, 260f, 300f), 0.95f, createDummyMask())
            ),
            assignedIds = listOf(0, 1)
        )

        // First make protected id=0 genuinely OCCLUDED by a fresh committed id=1.
        val occludedFrame = tracker.update(
            listOf(PersonDetection(FloatRect(160f, 100f, 260f, 300f), 0.95f, createDummyMask())),
            timestampUs = 33_333L
        )
        val protectedOccluded = occludedFrame.single { it.id == 0 }
        assertEquals(TrackState.OCCLUDED, protectedOccluded.state)
        assertTrue(!protectedOccluded.observedThisFrame)

        // This single detection is slightly better for id=1, but id=0 is within
        // the existing 0.05 ambiguity margin and still has strong absolute bbox
        // evidence. Hungarian selects id=1, then reciprocal-best rejects that
        // identity commit because the protected row is a real column competitor.
        val nearTieFrame = tracker.update(
            listOf(PersonDetection(FloatRect(132f, 100f, 232f, 300f), 0.95f, createDummyMask())),
            timestampUs = 66_666L
        )
        val protectedHeld = nearTieFrame.single { it.id == 0 }
        assertEquals(
            TrackState.OCCLUDED,
            protectedHeld.state,
            "ambiguous group ownership should preserve the existing FACE_ONLY occlusion state for one frame"
        )
        assertTrue(!protectedHeld.observedThisFrame, "state hold must not become an identity observation")
        assertTrue(
            protectedHeld.occludedByTrackIds.isEmpty(),
            "an uncommitted near-tie winner must not be named as an explicit occluder"
        )
        assertTrue(
            nearTieFrame.none { it.observedThisFrame },
            "near-tie state hold must not force either group identity to commit"
        )

        // Repeating the same ambiguity on the next frame must not pin the track
        // in OCCLUDED indefinitely. After the one-frame bridge, normal group
        // ambiguity semantics resume and transition it to REACQUIRING.
        val repeatedNearTieFrame = tracker.update(
            listOf(PersonDetection(FloatRect(132f, 100f, 232f, 300f), 0.95f, createDummyMask())),
            timestampUs = 99_999L
        )
        val protectedAfterBridge = repeatedNearTieFrame.single { it.id == 0 }
        assertEquals(
            TrackState.REACQUIRING,
            protectedAfterBridge.state,
            "the bridge must not repeat on consecutive ambiguous frames"
        )
        assertTrue(!protectedAfterBridge.observedThisFrame)
    }
}
