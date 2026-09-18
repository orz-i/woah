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
        bbox: FloatRect = FloatRect(100f, 200f, 300f, 800f)
    ) = TrackedPerson(
        id = 7,
        bbox = bbox,
        mask = null,
        confidence = 0.9f,
        framesSinceLastObservation = framesSinceLastObservation,
        state = state,
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
