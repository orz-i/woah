package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowCameraIdentityHistoryTest {
    private fun track(id: Int, observed: Boolean, bbox: FloatRect) = TrackedPerson(
        id = id,
        bbox = bbox,
        mask = null,
        confidence = .85f,
        state = TrackState.ACTIVE,
        observedThisFrame = observed
    )

    @Test fun historicallyCoObservedTrackCannotInheritProtagonistCameraAfterLongGap() {
        val history = FollowCameraIdentityHistory()
        // Mirrors the PLK110 diagnostic: selected ID 2 and ID 8 were both
        // observed at 5.8s and 13.4s, long before ID 2 was lost at 18.2s.
        val protagonist = track(2, true, FloatRect(572f, 271f, 766f, 612f))
        val other = track(8, true, FloatRect(565f, 289f, 621f, 507f))
        history.recordSelectedObservation(2, true, listOf(protagonist, other))
        history.recordSelectedObservation(2, true, listOf(
            track(2, true, FloatRect(546f, 305f, 700f, 608f)),
            track(8, true, FloatRect(543f, 343f, 604f, 519f))
        ))
        assertEquals(setOf(8), history.excludedIds())
        // A long interval must never expire identity-negative evidence.
        history.recordSelectedObservation(2, false, listOf(
            track(8, true, FloatRect(557f, 310f, 729f, 646f))
        ))
        assertTrue(8 in history.excludedIds())

        val temporal = FollowCameraTemporalRecovery()
        val anchor = FloatRect(574f, 398f, 703f, 622f)
        for (ptsUs in listOf(19_500_000L, 20_100_000L, 20_200_000L)) {
            assertNull(temporal.observe(
                anchor = anchor,
                rootTrackId = 2,
                lastTargetPtsUs = 18_233_333L,
                ptsUs = ptsUs,
                tracks = listOf(track(8, true, FloatRect(557f, 310f, 729f, 646f))),
                excludedTrackIds = history.excludedIds()
            ))
        }
        assertNull(temporal.pendingTrackId)
    }

    @Test fun identicalCoObservedBoxesAreStillAmbiguousAndExcluded() {
        val history = FollowCameraIdentityHistory()
        val box = FloatRect(100f, 50f, 300f, 800f)
        history.recordSelectedObservation(2, true, listOf(track(2, true, box), track(8, true, box)))
        assertTrue(8 in history.excludedIds())
    }

    @Test fun unseenTracksAndPredictedRootDoNotCreateFalseNegativeEvidence() {
        val history = FollowCameraIdentityHistory()
        val box = FloatRect(100f, 50f, 300f, 800f)
        history.recordSelectedObservation(2, true, listOf(track(2, true, box), track(9, false, box)))
        history.recordSelectedObservation(2, false, listOf(track(2, false, box), track(10, true, box)))
        assertFalse(9 in history.excludedIds())
        assertFalse(10 in history.excludedIds())
        history.recordSelectedObservation(2, true, listOf(track(2, true, box), track(11, true, box)))
        assertEquals(setOf(11), history.excludedIds())
    }

    @Test fun strictShortWindowHandoffAlsoHonorsHistoricalExclusion() {
        val history = FollowCameraIdentityHistory()
        val box = FloatRect(100f, 100f, 400f, 900f)
        history.recordSelectedObservation(2, true, listOf(track(2, true, box), track(8, true, box)))
        val candidate = track(8, true, box)
        // Same exclusion is passed to both early and temporal handoff paths.
        assertNull(art.gaoge.dance.engine.pipeline.ExportPipeline.resolveFollowCameraLostHandoffProxy(
            anchor = box,
            targetId = 2,
            tracks = listOf(candidate),
            handoffAgeUs = 400_000L,
            excludedTrackIds = history.excludedIds()
        ))
    }
}
