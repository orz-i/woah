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
        assertEquals(PrivacySelectionClass.SELECTED, tracker.getHardPrivacyClassByDetectionIndex()[0])
        assertEquals(null, tracker.getHardPrivacyClassByDetectionIndex()[1], "young unselected identity must not become a hard temporal seed")
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
        assertEquals(PrivacySelectionClass.SELECTED, tracker.getHardPrivacyClassByDetectionIndex()[0])
        assertEquals(null, tracker.getHardPrivacyClassByDetectionIndex()[1], "young residual unselected identity must not become a hard temporal seed")
    }

    @Test
    fun testStableUnselectedExactObservationBecomesHardPrivacyClassSeed() {
        tracker = TrackManager()
        tracker.setProtectedTrackIds(setOf(0))
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 180f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(400f, 100f, 480f, 300f), 0.95f, createDummyMask())
            ),
            listOf(0, 1)
        )

        tracker.update(
            listOf(
                PersonDetection(FloatRect(105f, 100f, 185f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(395f, 100f, 475f, 300f), 0.95f, createDummyMask())
            ),
            33_333L
        )
        assertEquals(null, tracker.getHardPrivacyClassByDetectionIndex()[1])

        tracker.update(
            listOf(
                PersonDetection(FloatRect(110f, 100f, 190f, 300f), 0.95f, createDummyMask()),
                PersonDetection(FloatRect(390f, 100f, 470f, 300f), 0.95f, createDummyMask())
            ),
            66_666L
        )
        assertEquals(PrivacySelectionClass.SELECTED, tracker.getHardPrivacyClassByDetectionIndex()[0])
        assertEquals(PrivacySelectionClass.UNSELECTED, tracker.getHardPrivacyClassByDetectionIndex()[1])
    }
}
