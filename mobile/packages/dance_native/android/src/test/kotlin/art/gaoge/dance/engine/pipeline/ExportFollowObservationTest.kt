package art.gaoge.dance.engine.pipeline

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ExportFollowObservationTest {

    private fun track(
        observed: Boolean,
        framesSinceLastObservation: Int,
        state: TrackState,
        bbox: FloatRect = FloatRect(100f, 200f, 300f, 800f),
        id: Int = 7,
        occludedByTrackIds: Set<Int> = emptySet()
    ) = TrackedPerson(
        id = id,
        bbox = bbox,
        mask = null,
        confidence = 0.9f,
        framesSinceLastObservation = framesSinceLastObservation,
        state = state,
        occludedByTrackIds = occludedByTrackIds,
        observedThisFrame = observed
    )

    @Test
    fun observedFollowTargetUsesCurrentBox() {
        val resolved = ExportPipeline.resolveFollowCameraObservation(
            track = track(true, 0, TrackState.ACTIVE),
            trackingWidth = 1000,
            trackingHeight = 1000
        )
        assertNotNull(resolved)
        assertEquals(0.1f, resolved.left, 0.00001f)
        assertEquals(0.3f, resolved.right, 0.00001f)
    }

    @Test
    fun shortReacquireGapUsesPredictedBox() {
        val resolved = ExportPipeline.resolveFollowCameraObservation(
            track = track(false, 3, TrackState.REACQUIRING),
            trackingWidth = 1000,
            trackingHeight = 1000
        )
        assertNotNull(resolved)
        assertEquals(0.2f, resolved.top, 0.00001f)
        assertEquals(0.8f, resolved.bottom, 0.00001f)
    }

    @Test
    fun longGapCanBorrowExplicitObservedOccluderWithoutChangingIdentity() {
        val target = track(
            observed = false,
            framesSinceLastObservation = 12,
            state = TrackState.OCCLUDED,
            bbox = FloatRect(100f, 100f, 400f, 900f),
            id = 7,
            occludedByTrackIds = setOf(11)
        )
        val proxy = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(115f, 110f, 405f, 890f),
            id = 11
        )
        val resolved = ExportPipeline.resolveFollowCameraOcclusionProxy(
            target = target,
            tracks = listOf(target, proxy)
        )
        assertEquals(11, resolved?.id)
    }

    @Test
    fun reacquiringDuplicateCanBeProxyOnlyWhenGeometryIsVeryClose() {
        val target = track(
            observed = false,
            framesSinceLastObservation = 20,
            state = TrackState.REACQUIRING,
            bbox = FloatRect(100f, 100f, 400f, 900f),
            id = 7
        )
        val duplicate = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(120f, 110f, 410f, 890f),
            id = 11
        )
        val unrelated = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(430f, 100f, 730f, 900f),
            id = 12
        )
        val resolved = ExportPipeline.resolveFollowCameraOcclusionProxy(
            target = target,
            tracks = listOf(target, unrelated, duplicate)
        )
        assertEquals(11, resolved?.id)

        val scaleMismatch = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(150f, 300f, 350f, 700f),
            id = 13
        )
        assertNull(
            ExportPipeline.resolveFollowCameraOcclusionProxy(
                target = target,
                tracks = listOf(target, scaleMismatch)
            )
        )
    }

    @Test
    fun lostTargetCanHandOffToUniqueGeometricContinuation() {
        // Last trusted id=2 observation from the 2026-09-26 diagnostic clip.
        // The tracker removes id=2 before id=8 appears, so camera handoff must
        // bridge from this remembered box rather than a still-live target slot.
        val anchor = FloatRect(1578.75f, 695.1875f, 1961.25f, 1742.375f)
        val continuation = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(1347.875f, 838.125f, 1897.75f, 1760.625f),
            id = 8
        )
        val unrelated = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(1903.9375f, 1002.875f, 2247.3125f, 1637.125f),
            id = 3
        )

        val resolved = ExportPipeline.resolveFollowCameraLostHandoffProxy(
            anchor = anchor,
            targetId = 2,
            tracks = listOf(unrelated, continuation),
            handoffAgeUs = 333_667L
        )

        assertEquals(8, resolved?.id)
    }

    @Test
    fun lateStrongContinuationCanChainToNextCameraIdentity() {
        val anchor = FloatRect(1593.75f, 902.8125f, 2126.25f, 1785.9375f)
        val continuation = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(1728.3125f, 808.125f, 2098.625f, 1805.625f),
            id = 10
        )

        val resolved = ExportPipeline.resolveFollowCameraLostHandoffProxy(
            anchor = anchor,
            targetId = 8,
            tracks = listOf(continuation),
            handoffAgeUs = 934_266L
        )

        assertEquals(10, resolved?.id)
    }

    @Test
    fun lateWeakContinuationDoesNotUseRelaxedEarlyThresholds() {
        val anchor = FloatRect(1578.75f, 695.1875f, 1961.25f, 1742.375f)
        val weakContinuation = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(1347.875f, 838.125f, 1897.75f, 1760.625f),
            id = 8
        )

        assertNull(
            ExportPipeline.resolveFollowCameraLostHandoffProxy(
                anchor = anchor,
                targetId = 2,
                tracks = listOf(weakContinuation),
                handoffAgeUs = 934_266L
            )
        )
    }

    @Test
    fun handoffStopsAfterMaximumContinuityWindow() {
        val anchor = FloatRect(100f, 100f, 400f, 900f)
        val exactContinuation = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = anchor,
            id = 8
        )

        assertNull(
            ExportPipeline.resolveFollowCameraLostHandoffProxy(
                anchor = anchor,
                targetId = 2,
                tracks = listOf(exactContinuation),
                handoffAgeUs = ExportPipeline.FOLLOW_CAMERA_ID_HANDOFF_WINDOW_US + 1L
            )
        )
    }

    @Test
    fun lostTargetHandoffRefusesAmbiguousNearbyCandidates() {
        val anchor = FloatRect(100f, 100f, 400f, 900f)
        val first = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(90f, 110f, 410f, 890f),
            id = 8
        )
        val second = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(95f, 105f, 405f, 895f),
            id = 9
        )

        assertNull(
            ExportPipeline.resolveFollowCameraLostHandoffProxy(
                anchor = anchor,
                targetId = 2,
                tracks = listOf(first, second),
                handoffAgeUs = 300_000L
            )
        )
    }

    @Test
    fun lostTargetHandoffRejectsDistantOrScaleMismatchedTrack() {
        val anchor = FloatRect(100f, 100f, 400f, 900f)
        val distant = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(450f, 100f, 750f, 900f),
            id = 8
        )
        val scaleMismatch = track(
            observed = true,
            framesSinceLastObservation = 0,
            state = TrackState.ACTIVE,
            bbox = FloatRect(120f, 320f, 380f, 680f),
            id = 9
        )

        assertNull(
            ExportPipeline.resolveFollowCameraLostHandoffProxy(
                anchor = anchor,
                targetId = 2,
                tracks = listOf(distant, scaleMismatch),
                handoffAgeUs = 300_000L
            )
        )
    }

    @Test
    fun longOrLostGapHoldsLastCameraInsteadOfFollowingPredictionForever() {
        assertNull(
            ExportPipeline.resolveFollowCameraObservation(
                track = track(
                    false,
                    ExportPipeline.FOLLOW_CAMERA_PREDICTION_GRACE_FRAMES + 1,
                    TrackState.REACQUIRING
                ),
                trackingWidth = 1000,
                trackingHeight = 1000
            )
        )
        assertNull(
            ExportPipeline.resolveFollowCameraObservation(
                track = track(false, 2, TrackState.LOST),
                trackingWidth = 1000,
                trackingHeight = 1000
            )
        )
    }
}
