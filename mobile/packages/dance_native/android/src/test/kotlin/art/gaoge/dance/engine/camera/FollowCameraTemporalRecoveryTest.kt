package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Geometry-only, camera-specific tests. Tracker identity and privacy IDs stay separate. */
class FollowCameraTemporalRecoveryTest {
    private val anchor = FloatRect(573.9375f, 397.8125f, 702.9375f, 622.1875f)
    private val lastObservedUs = 18_233_333L

    private fun track(
        id: Int,
        bbox: FloatRect,
        observed: Boolean = true,
        confidence: Float = 0.85f,
        state: TrackState = TrackState.ACTIVE
    ) = TrackedPerson(
        id = id, bbox = bbox, mask = null, confidence = confidence,
        state = state, observedThisFrame = observed,
        framesSinceLastObservation = if (observed) 0 else 8
    )

    private fun sceneCandidate(id: Int = 8, timeUs: Long) = track(
        id,
        when (timeUs) {
            19_500_000L -> FloatRect(566f, 260f, 758f, 666f)
            20_100_000L -> FloatRect(531f, 306f, 704f, 646f)
            else -> FloatRect(557f, 310f, 729f, 646f)
        }
    )

    @Test fun diagnosticLikeReappearanceNeedsRepeatedUniqueObservations() {
        val recovery = FollowCameraTemporalRecovery()
        val selectedPrivacy = setOf(0, 1, 3, 4)
        val distractor = track(11, FloatRect(710f, 340f, 824f, 600f))
        val times = listOf(19_500_000L, 20_100_000L, 20_200_000L)
        val results = times.map { now ->
            recovery.observe(
                anchor = anchor, rootTrackId = 2, lastTargetPtsUs = lastObservedUs,
                ptsUs = now,
                tracks = listOf(sceneCandidate(timeUs = now), distractor),
                excludedTrackIds = selectedPrivacy
            )
        }
        assertNull(results[0])
        assertNull(results[1])
        val confirmed = assertNotNull(results[2])
        assertEquals(8, confirmed.track.id)
        assertEquals(3, confirmed.observations)
        assertEquals(700_000L, confirmed.observationSpanUs)
        assertEquals(1_966_667L, confirmed.ageUs)
        assertNull(recovery.pendingTrackId)
    }

    @Test fun aSingleHighlyOverlappingUnrelatedPersonNeverGetsControl() {
        val recovery = FollowCameraTemporalRecovery()
        val perfectMatch = track(8, anchor)
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 19_500_000L, listOf(perfectMatch), emptySet()))
        assertEquals(1, recovery.pendingObservations)
    }

    @Test fun privacyProtectedAndKnownDistinctPeopleAreExcluded() {
        val recovery = FollowCameraTemporalRecovery()
        val perfectMatch = track(3, anchor)
        for (time in listOf(19_500_000L, 20_100_000L, 20_200_000L)) {
            assertNull(recovery.observe(anchor, 2, lastObservedUs, time, listOf(perfectMatch), setOf(3)))
        }
        assertNull(recovery.pendingTrackId)
        // A co-observed separate identity (ID=8 here) uses exactly the same
        // exclusion mechanism; the list is derived when root ID=2 was visible.
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 20_200_000L, listOf(track(8, anchor)), setOf(8)))
    }

    @Test fun similarlyStrongCrossingCandidatesForceHoldAndClearPendingVotes() {
        val recovery = FollowCameraTemporalRecovery()
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 19_500_000L, listOf(track(8, anchor)), emptySet()))
        val almostSame = FloatRect(anchor.left + 3, anchor.top + 4, anchor.right + 3, anchor.bottom + 4)
        assertNull(recovery.observe(
            anchor, 2, lastObservedUs, 19_700_000L,
            listOf(track(8, anchor), track(9, almostSame)), emptySet()
        ))
        assertNull(recovery.pendingTrackId)
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 19_900_000L, listOf(track(8, anchor)), emptySet()))
        assertEquals(1, recovery.pendingObservations)
    }

    @Test fun aGapLargerThanVoteWindowRestartsEvidence() {
        val recovery = FollowCameraTemporalRecovery()
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 19_500_000L, listOf(track(8, anchor)), emptySet()))
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 20_400_000L, listOf(track(8, anchor)), emptySet()))
        assertEquals(1, recovery.pendingObservations)
    }

    @Test fun noRecoveryAfterDeadlineEvenIfTrackMatchesPerfectly() {
        val recovery = FollowCameraTemporalRecovery()
        for (time in listOf(20_900_000L, 21_000_000L, 21_200_000L)) {
            assertNull(recovery.observe(anchor, 2, lastObservedUs, time, listOf(track(8, anchor)), emptySet()))
        }
        assertNull(recovery.pendingTrackId)
    }

    @Test fun noLateHandoffOnWeakOrUnobservedDetection() {
        val recovery = FollowCameraTemporalRecovery()
        val distant = track(8, FloatRect(830f, 350f, 1020f, 700f))
        val tiny = track(9, FloatRect(600f, 450f, 645f, 525f))
        val unobserved = track(10, anchor, observed = false, state = TrackState.REACQUIRING)
        for (time in listOf(19_500_000L, 20_100_000L, 20_200_000L)) {
            assertNull(recovery.observe(
                anchor, 2, lastObservedUs, time,
                listOf(distant, tiny, unobserved, track(11, anchor, confidence = .3f)), emptySet()
            ))
        }
        assertNull(recovery.pendingTrackId)
    }

    @Test fun rootRecoveryResetsPendingCandidateVotes() {
        val recovery = FollowCameraTemporalRecovery()
        assertNull(recovery.observe(anchor, 2, lastObservedUs, 19_500_000L, listOf(track(8, anchor)), emptySet()))
        recovery.reset() // invoked when the original selected root is observed again
        assertNull(recovery.pendingTrackId)
        assertEquals(0, recovery.pendingObservations)
    }
}
