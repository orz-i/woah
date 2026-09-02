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

        // One shared detection cannot establish which identity owns it. Both
        // tracks must remain unresolved rather than arbitrarily committing one.
        assertEquals(2, tracks_f1.size)
        val matchedCount = tracks_f1.count { it.state == TrackState.ACTIVE }
        val occludedOrReacquiring = tracks_f1.count { it.state == TrackState.OCCLUDED || it.state == TrackState.REACQUIRING }
        assertEquals(0, matchedCount)
        assertEquals(2, occludedOrReacquiring)
        assertTrue(tracks_f1.none { it.observedThisFrame })
        assertTrue(
            tracker.getFreshPrivacyClassEvidence().isEmpty(),
            "merged/missing detection cardinality must not infer a privacy class"
        )

        // Frame 2: Dancers separate cleanly
        val p1_f2 = PersonDetection(FloatRect(100f, 100f, 180f, 300f), 0.95f, createDummyMask())
        val p2_f2 = PersonDetection(FloatRect(300f, 100f, 380f, 300f), 0.95f, createDummyMask())
        val tracks_f2 = tracker.update(listOf(p1_f2, p2_f2), timestampUs = 66_666L)

        assertEquals(2, tracks_f2.size)
        val t1 = tracks_f2.find { it.id == id1 }
        val t2 = tracks_f2.find { it.id == id2 }
        assertTrue(t1 != null && t2 != null)
        assertTrue(tracks_f2.all { it.id == id1 || it.id == id2 }, "separation must not create a replacement identity")

        // A second separated observation provides confirmation for any identity
        // that was intentionally deferred on the first separation frame.
        val p1_f3 = PersonDetection(FloatRect(90f, 100f, 170f, 300f), 0.95f, createDummyMask())
        val p2_f3 = PersonDetection(FloatRect(310f, 100f, 390f, 300f), 0.95f, createDummyMask())
        val tracks_f3 = tracker.update(listOf(p1_f3, p2_f3), timestampUs = 99_999L)
        assertEquals(2, tracks_f3.size)
        assertTrue(tracks_f3.any { it.id == id1 && it.state != TrackState.REMOVED })
        assertTrue(tracks_f3.any { it.id == id2 && it.state != TrackState.REMOVED })
        assertTrue(tracks_f3.all { it.id == id1 || it.id == id2 }, "confirmation frames must not replace either identity")
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

    @Test
    fun testNearTieGroupAssignmentsRemainAmbiguousWithoutSecondBestMargin() {
        val bboxOnlyConfig = TrackingConfig(
            minMatchScore = 0.20f,
            bboxIouWeight = 1.0f,
            maskIouWeight = 0.0f,
            motionWeight = 0.0f,
            directionWeight = 0.0f,
            associationAmbiguityMargin = 0.05f
        )
        tracker = TrackManager(bboxOnlyConfig)

        val a0 = PersonDetection(FloatRect(200f, 100f, 300f, 300f), 0.95f)
        val b0 = PersonDetection(FloatRect(204f, 100f, 304f, 300f), 0.95f)
        val initial = tracker.initialize(listOf(a0, b0))

        // Both detections are plausible for both tracks and differ only slightly.
        // A high privacy-cost identity tracker must not commit merely because an
        // assigned edge is within 0.05 of the row/column best. The winning edge
        // needs an explicit separation from its alternatives.
        val d0 = PersonDetection(FloatRect(201f, 100f, 301f, 300f), 0.95f)
        val d1 = PersonDetection(FloatRect(203f, 100f, 303f, 300f), 0.95f)

        val tracks = tracker.update(listOf(d0, d1), timestampUs = 33_333L)
        val trackedInitialIds = tracks.filter { t -> initial.any { it.id == t.id } }

        assertEquals(2, trackedInitialIds.size)
        assertTrue(
            trackedInitialIds.all { it.state == TrackState.REACQUIRING || it.state == TrackState.OCCLUDED },
            "near-tie group associations must defer identity commitment"
        )
        assertTrue(trackedInitialIds.none { it.observedThisFrame })
    }

    @Test
    fun testExtraDetectionInsideActiveOcclusionGroupDoesNotCreateDuplicateId() {
        val tracker = TrackManager(
            TrackingConfig(
                minMatchScore = 0.20f,
                bboxIouWeight = 1.0f,
                maskIouWeight = 0.0f,
                motionWeight = 0.0f,
                directionWeight = 0.0f
            )
        )

        val initial = tracker.initialize(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f),
                PersonDetection(FloatRect(140f, 100f, 240f, 300f), 0.95f)
            )
        )
        val originalIds = initial.map { it.id }.toSet()

        // The third detection is a transient fragment/duplicate inside the same
        // overlap group. During active group ownership it must not mint ID 2.
        val tracks = tracker.update(
            detections = listOf(
                PersonDetection(FloatRect(104f, 100f, 204f, 300f), 0.95f),
                PersonDetection(FloatRect(144f, 100f, 244f, 300f), 0.95f),
                PersonDetection(FloatRect(122f, 110f, 218f, 292f), 0.80f)
            ),
            timestampUs = 33_333L
        )

        assertEquals(originalIds, tracks.map { it.id }.toSet())
        assertEquals(2, tracks.size, "overlap-group fragment must not create a duplicate identity")
        assertTrue(
            tracker.getFreshPrivacyClassEvidence().isEmpty(),
            "extra fragment/duplicate cardinality must not infer a privacy class"
        )
    }

    @Test
    fun testSubpixelGroupBoundaryGrazeDoesNotStealIndependentDetection() {
        val tracker = TrackManager(
            TrackingConfig(
                minMatchScore = 0.20f,
                bboxIouWeight = 1.0f,
                maskIouWeight = 0.0f,
                motionWeight = 0.0f,
                directionWeight = 0.0f
            )
        )

        val initial = tracker.initializeWithAssignedIds(
            listOf(
                // Independent identity immediately to the left of an overlap group.
                PersonDetection(FloatRect(0f, 100f, 100f, 300f), 0.95f),
                PersonDetection(FloatRect(100.5f, 100f, 200.5f, 300f), 0.95f),
                PersonDetection(FloatRect(140.5f, 100f, 240.5f, 300f), 0.95f)
            ),
            listOf(2, 3, 4)
        )
        assertEquals(setOf(2, 3, 4), initial.map { it.id }.toSet())

        val independentDetection = PersonDetection(
            // Only a 0.75 px horizontal edge graze against track 3. This is
            // intentionally below the group-ownership tolerance and models the
            // cross-device first-fork geometry seen at pts_us=16677.
            FloatRect(0f, 100f, 101.25f, 300f),
            0.95f
        )
        val tracks = tracker.update(
            detections = listOf(
                independentDetection,
                PersonDetection(FloatRect(100.5f, 100f, 200.5f, 300f), 0.95f),
                PersonDetection(FloatRect(140.5f, 100f, 240.5f, 300f), 0.95f)
            ),
            timestampUs = 16_677L
        )

        val independent = tracks.single { it.id == 2 }
        assertTrue(independent.observedThisFrame, "edge graze must not reserve the independent detection")
        assertEquals(TrackState.ACTIVE, independent.state)
        assertEquals(independentDetection.bbox, independent.bbox)
        assertEquals(setOf(2, 3, 4), tracks.map { it.id }.toSet())
    }

    @Test
    fun ambiguousHungarianDetectionSupportsNeighborReservationWithoutIdentityCommit() {
        val tracker = TrackManager(
            TrackingConfig(
                minMatchScore = 0.20f,
                bboxIouWeight = 1.0f,
                maskIouWeight = 0.0f,
                motionWeight = 0.0f,
                directionWeight = 0.0f,
                associationAmbiguityMargin = 0.05f
            )
        )

        val initial = tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f),
                PersonDetection(FloatRect(120f, 100f, 220f, 300f), 0.95f)
            ),
            listOf(3, 4)
        )
        assertEquals(setOf(3, 4), initial.map { it.id }.toSet())

        // det0 is an equal-score Hungarian candidate for both group tracks, so
        // identity commitment must remain ambiguous. det1 only grazes the old
        // group boundary by 0.5 px, but overlaps det0 by 10.5 px. The current
        // ambiguous detection therefore proves that det1 is still inside this
        // frame's group ownership support even though no identity was committed.
        val tracks = tracker.update(
            detections = listOf(
                PersonDetection(FloatRect(90f, 100f, 230f, 300f), 0.95f),
                PersonDetection(FloatRect(219.5f, 100f, 319.5f, 300f), 0.80f)
            ),
            timestampUs = 33_333L
        )

        assertEquals(
            setOf(3, 4),
            tracks.map { it.id }.toSet(),
            "a detection overlapping ambiguous current group support must remain quarantined instead of minting a new identity"
        )
        assertTrue(tracks.none { it.observedThisFrame }, "ambiguous group evidence must not force an identity commit")
    }

    @Test
    fun committedGroupMatchDoesNotEraseAssociationTimeReservationGeometry() {
        val tracker = TrackManager(
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

        val initial = tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f),
                PersonDetection(FloatRect(170f, 100f, 270f, 300f), 0.95f)
            ),
            listOf(3, 4)
        )
        assertEquals(setOf(3, 4), initial.map { it.id }.toSet())

        // id=4 has a valid reciprocal-best move to det0. That successful commit
        // moves its live bbox to the right. det1 overlaps id=4's association-time
        // geometry by 15 px, but no longer overlaps the committed det0 or either
        // track after mutation. Reservation must still use the immutable geometry
        // that entered group association so det1 cannot leak out and mint a new ID.
        val tracks = tracker.update(
            detections = listOf(
                PersonDetection(FloatRect(225f, 100f, 325f, 300f), 0.95f),
                PersonDetection(FloatRect(205f, 100f, 220f, 300f), 0.80f)
            ),
            timestampUs = 33_333L
        )

        assertEquals(
            setOf(3, 4),
            tracks.map { it.id }.toSet(),
            "same-frame group commit must not erase reservation ownership from association-time geometry"
        )
        val moved = tracks.single { it.id == 4 }
        assertTrue(moved.observedThisFrame, "the valid group winner must still commit normally")
        assertEquals(FloatRect(225f, 100f, 325f, 300f), moved.bbox)
    }

    @Test
    fun testStrictFaceOnlyIdentityDoesNotEscapeSingleNeighborGroupReservation() {
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
        tracker.setIdentityProtectedTrackIds(setOf(2))
        tracker.setPrivacySelectedTrackIds(emptySet())
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(0f, 100f, 100f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(80f, 100f, 180f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(120f, 100f, 220f, 300f), 0.95f, createDummyMask())
            ),
            listOf(2, 3, 4)
        )

        val targetDetection = PersonDetection(FloatRect(5f, 100f, 105f, 300f), 0.95f, createDummyMask())
        val tracks = tracker.update(
            detections = listOf(
                targetDetection,
                PersonDetection(FloatRect(85f, 100f, 185f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(125f, 100f, 225f, 300f), 0.95f, createDummyMask())
            ),
            timestampUs = 33_333L
        )

        val target = tracks.single { it.id == 2 }
        assertTrue(!target.observedThisFrame, "a single neighboring group must retain the original reservation isolation")
        assertTrue(target.state != TrackState.ACTIVE)
        assertEquals(setOf(2, 3, 4), tracks.map { it.id }.toSet())

        val strictPrivacyEvidence = tracker.getFreshStrictUnselectedPrivacyEvidence()
        assertEquals(1, strictPrivacyEvidence.size)
        assertEquals(PrivacySelectionClass.UNSELECTED, strictPrivacyEvidence.single().selectionClass)
        assertEquals(setOf(2), strictPrivacyEvidence.single().residualTrackIds)
        assertEquals(targetDetection.bbox, strictPrivacyEvidence.single().detection.bbox)
    }

    @Test
    fun testStrictFaceOnlyReservationEscapeRequiresMultipleOwnerGroups() {
        assertTrue(
            TrackManager.isStrictFaceOnlyReservationRescueEligible(
                wouldStrictGlobalCommit = true,
                faceOnlyIdentityProtected = true,
                reservationOwnerGroupCount = 2
            )
        )
        assertTrue(
            !TrackManager.isStrictFaceOnlyReservationRescueEligible(
                wouldStrictGlobalCommit = true,
                faceOnlyIdentityProtected = true,
                reservationOwnerGroupCount = 1
            )
        )
        assertTrue(
            !TrackManager.isStrictFaceOnlyReservationRescueEligible(
                wouldStrictGlobalCommit = false,
                faceOnlyIdentityProtected = true,
                reservationOwnerGroupCount = 2
            )
        )
        assertTrue(
            !TrackManager.isStrictFaceOnlyReservationRescueEligible(
                wouldStrictGlobalCommit = true,
                faceOnlyIdentityProtected = false,
                reservationOwnerGroupCount = 2
            )
        )
    }

    @Test
    fun testStrictFaceOnlyIdentityCanEscapeTwoIndependentGroupReservations() {
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
        tracker.setIdentityProtectedTrackIds(setOf(2))
        tracker.setPrivacySelectedTrackIds(emptySet())
        tracker.initializeWithAssignedIds(
            listOf(
                // Left overlap group.
                PersonDetection(FloatRect(0f, 100f, 100f, 300f), 0.95f),
                PersonDetection(FloatRect(20f, 100f, 120f, 300f), 0.95f),
                // FACE_ONLY target sits between the two groups but does not
                // overlap either enough to join their topology.
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f),
                // Right overlap group.
                PersonDetection(FloatRect(180f, 100f, 280f, 300f), 0.95f),
                PersonDetection(FloatRect(200f, 100f, 300f, 300f), 0.95f)
            ),
            listOf(0, 1, 2, 3, 4)
        )

        val targetDetection = PersonDetection(FloatRect(80f, 100f, 220f, 300f), 0.95f)
        val tracks = tracker.update(
            detections = listOf(
                PersonDetection(FloatRect(0f, 100f, 100f, 300f), 0.95f),
                PersonDetection(FloatRect(20f, 100f, 120f, 300f), 0.95f),
                targetDetection,
                PersonDetection(FloatRect(180f, 100f, 280f, 300f), 0.95f),
                PersonDetection(FloatRect(200f, 100f, 300f, 300f), 0.95f)
            ),
            timestampUs = 33_333L
        )

        val target = tracks.single { it.id == 2 }
        assertTrue(target.observedThisFrame, "strict FACE_ONLY target must escape simultaneous quarantine by two independent groups")
        assertEquals(TrackState.ACTIVE, target.state)
        assertEquals(targetDetection.bbox, target.bbox)
        assertEquals(setOf(0, 1, 2, 3, 4), tracks.map { it.id }.toSet())
    }

    @Test
    fun testFullBodyIdentityDoesNotUseFaceOnlyReservationEscape() {
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
        tracker.setIdentityProtectedTrackIds(setOf(2))
        tracker.setPrivacySelectedTrackIds(setOf(2))
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(0f, 100f, 100f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(80f, 100f, 180f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(120f, 100f, 220f, 300f), 0.95f, createDummyMask())
            ),
            listOf(2, 3, 4)
        )

        val tracks = tracker.update(
            detections = listOf(
                PersonDetection(FloatRect(5f, 100f, 105f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(85f, 100f, 185f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(125f, 100f, 225f, 300f), 0.95f, createDummyMask())
            ),
            timestampUs = 33_333L
        )

        val target = tracks.single { it.id == 2 }
        assertTrue(!target.observedThisFrame, "FULL_BODY identity must retain the existing group-reservation isolation behavior")
        assertTrue(target.state != TrackState.ACTIVE)
        assertEquals(setOf(2, 3, 4), tracks.map { it.id }.toSet())
        assertTrue(
            tracker.getFreshStrictUnselectedPrivacyEvidence().isEmpty(),
            "FULL_BODY identities must never be exported as fresh UNSELECTED compositor evidence"
        )
    }

    @Test
    fun testBalancedResidualSelectedDetectionBecomesFreshPrivacyEvidenceWithoutIdentityCommit() {
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
        tracker.setProtectedTrackIds(setOf(0))
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(140f, 100f, 240f, 300f), 0.95f, createDummyMask())
            ),
            listOf(0, 1)
        )

        val tracks = tracker.update(
            listOf(
                // Equally plausible for both tracks -> exact selected identity must defer.
                PersonDetection(FloatRect(120f, 100f, 220f, 300f), 0.95f, createDummyMask()),
                // Clear unselected observation -> commits track 1.
                PersonDetection(FloatRect(150f, 100f, 250f, 300f), 0.95f, createDummyMask())
            ),
            33_333L
        )

        val selected = tracks.single { it.id == 0 }
        val unselected = tracks.single { it.id == 1 }
        assertTrue(!selected.observedThisFrame, "selected identity must remain uncommitted")
        assertTrue(unselected.observedThisFrame, "unselected identity should consume its clear detection")

        val evidence = tracker.getFreshPrivacyClassEvidence()
        assertEquals(1, evidence.size)
        assertEquals(PrivacySelectionClass.SELECTED, evidence.single().selectionClass)
        assertEquals(setOf(0), evidence.single().residualTrackIds)
        assertEquals(FloatRect(120f, 100f, 220f, 300f), evidence.single().detection.bbox)
        assertEquals(setOf(0), tracker.getPrivacySuppressedSelectedTrackIds())
        assertTrue(tracker.getHardPrivacyClassByDetectionIndex().isEmpty(), "runtime identities must not become privacy-class roots")
    }

    @Test
    fun testBalancedResidualUnselectedDetectionBecomesFreshPrivacyEvidenceWithoutIdentityCommit() {
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
        tracker.setProtectedTrackIds(setOf(0))
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(140f, 100f, 240f, 300f), 0.95f, createDummyMask())
            ),
            listOf(0, 1)
        )

        val tracks = tracker.update(
            listOf(
                // Clear selected observation -> commits track 0.
                PersonDetection(FloatRect(90f, 100f, 190f, 300f), 0.95f, createDummyMask()),
                // Equally plausible for selected/unselected -> exact track 1 identity defers.
                PersonDetection(FloatRect(120f, 100f, 220f, 300f), 0.95f, createDummyMask())
            ),
            33_333L
        )

        val selected = tracks.single { it.id == 0 }
        val unselected = tracks.single { it.id == 1 }
        assertTrue(selected.observedThisFrame)
        assertTrue(!unselected.observedThisFrame, "unselected identity must remain uncommitted")

        val evidence = tracker.getFreshPrivacyClassEvidence()
        assertEquals(1, evidence.size)
        assertEquals(PrivacySelectionClass.UNSELECTED, evidence.single().selectionClass)
        assertEquals(setOf(1), evidence.single().residualTrackIds)
        assertEquals(FloatRect(120f, 100f, 220f, 300f), evidence.single().detection.bbox)
        assertTrue(tracker.getPrivacySuppressedSelectedTrackIds().isEmpty())
        assertTrue(tracker.getHardPrivacyClassByDetectionIndex().isEmpty(), "residual/runtime identities must not become privacy-class roots")
    }

    @Test
    fun testOnlyInitializationExposesHardPrivacyClassRoots() {
        tracker = TrackManager()
        tracker.setProtectedTrackIds(setOf(0))
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 180f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(400f, 100f, 480f, 300f), 0.95f, createDummyMask())
            ),
            listOf(0, 1)
        )
        assertEquals(PrivacySelectionClass.SELECTED, tracker.getHardPrivacyClassByDetectionIndex()[0])
        assertEquals(PrivacySelectionClass.UNSELECTED, tracker.getHardPrivacyClassByDetectionIndex()[1])

        tracker.update(
            listOf(
                PersonDetection(FloatRect(105f, 100f, 185f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(395f, 100f, 475f, 300f), 0.95f, createDummyMask())
            ),
            33_333L
        )
        assertTrue(tracker.getHardPrivacyClassByDetectionIndex().isEmpty())

        tracker.update(
            listOf(
                PersonDetection(FloatRect(110f, 100f, 190f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(390f, 100f, 470f, 300f), 0.95f, createDummyMask())
            ),
            66_666L
        )
        assertTrue(tracker.getHardPrivacyClassByDetectionIndex().isEmpty())
    }
}
