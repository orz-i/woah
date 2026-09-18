package com.danceanon.native.pipeline

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
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
