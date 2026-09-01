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
    fun `two track uncertain occluder requires face only protected mask ownership`() {
        assertTrue(
            TrackManager.isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                groupTrackCount = 2,
                trackState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.61f,
                minMatchScore = 0.20f,
                protectedIdentityEvidenceOk = true,
                protectedMaskIoU = 0.52f,
                winnerIdentityProtected = false,
                winnerMaskIoU = 0.49f
            )
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                groupTrackCount = 3,
                trackState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.61f,
                minMatchScore = 0.20f,
                protectedIdentityEvidenceOk = true,
                protectedMaskIoU = 0.52f,
                winnerIdentityProtected = false,
                winnerMaskIoU = 0.49f
            ),
            "larger groups keep their existing group-first semantics"
        )
        assertTrue(
            TrackManager.isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                groupTrackCount = 2,
                trackState = TrackState.REACQUIRING,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.61f,
                minMatchScore = 0.20f,
                protectedIdentityEvidenceOk = true,
                protectedMaskIoU = 0.52f,
                winnerIdentityProtected = false,
                winnerMaskIoU = 0.49f
            ),
            "REACQUIRING must also reject an ordinary winner whose current mask ownership is weaker"
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                groupTrackCount = 2,
                trackState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = true,
                bestScore = 0.61f,
                minMatchScore = 0.20f,
                protectedIdentityEvidenceOk = true,
                protectedMaskIoU = 0.52f,
                winnerIdentityProtected = false,
                winnerMaskIoU = 0.49f
            ),
            "FULL_BODY identities are excluded"
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                groupTrackCount = 2,
                trackState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.61f,
                minMatchScore = 0.20f,
                protectedIdentityEvidenceOk = true,
                protectedMaskIoU = 0.40f,
                winnerIdentityProtected = false,
                winnerMaskIoU = 0.49f
            ),
            "a winner with stronger current mask ownership remains a valid occluder"
        )
        assertTrue(
            !TrackManager.isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                groupTrackCount = 2,
                trackState = TrackState.OCCLUDED,
                identityProtected = true,
                privacySelected = false,
                bestScore = 0.61f,
                minMatchScore = 0.20f,
                protectedIdentityEvidenceOk = true,
                protectedMaskIoU = 0.52f,
                winnerIdentityProtected = true,
                winnerMaskIoU = 0.49f
            ),
            "protected-vs-protected ambiguity is not treated as an ordinary occluder conflict"
        )
    }

    @Test
    fun `unprotected two track winner does not become definitive occluder when protected mask ownership is equal`() {
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

        // The ordinary identity is freshly observed while the protected identity
        // is hidden behind the same two-track overlap group.
        val occluded = tracker.update(
            listOf(PersonDetection(FloatRect(160f, 100f, 260f, 300f), 0.95f, createDummyMask())),
            timestampUs = 33_333L
        )
        val protectedOccluded = occluded.single { it.id == 0 }
        assertEquals(TrackState.OCCLUDED, protectedOccluded.state)
        assertTrue(!protectedOccluded.observedThisFrame)

        // The next detection is still a much better bbox match for id=1, so id=1
        // must remain free to commit. But both tracks retain the same current mask
        // ownership evidence; that detection therefore cannot simultaneously prove
        // that id=1 is a *different* person occluding protected id=0.
        val conflicted = tracker.update(
            listOf(PersonDetection(FloatRect(150f, 100f, 250f, 300f), 0.95f, createDummyMask())),
            timestampUs = 66_666L
        )
        val protected = conflicted.single { it.id == 0 }
        val ordinary = conflicted.single { it.id == 1 }
        assertEquals(TrackState.REACQUIRING, protected.state)
        assertTrue(!protected.observedThisFrame)
        assertTrue(protected.occludedByTrackIds.isEmpty())
        assertEquals(TrackState.ACTIVE, ordinary.state)
        assertTrue(ordinary.observedThisFrame, "the ordinary winner's identity commit must remain unchanged")

        // Repeat the same conflict after the protected identity has already moved
        // to REACQUIRING. The ordinary identity may still commit, but it still
        // cannot become definitive occlusion evidence for the protected identity.
        val reacquiringConflict = tracker.update(
            listOf(PersonDetection(FloatRect(150f, 100f, 250f, 300f), 0.95f, createDummyMask())),
            timestampUs = 99_999L
        )
        val protectedStillReacquiring = reacquiringConflict.single { it.id == 0 }
        val ordinaryStillActive = reacquiringConflict.single { it.id == 1 }
        assertEquals(TrackState.REACQUIRING, protectedStillReacquiring.state)
        assertTrue(!protectedStillReacquiring.observedThisFrame)
        assertTrue(protectedStillReacquiring.occludedByTrackIds.isEmpty())
        assertEquals(TrackState.ACTIVE, ordinaryStillActive.state)
        assertTrue(ordinaryStillActive.observedThisFrame)
    }
}
