package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackManagerOcclusionMotionConsensusTest {

    @Test
    fun freshOccluderMotionIsUsedBeforeMatchedObservationStateIsOverwritten() {
        val config = TrackingConfig(
            minMatchScore = 0.20f,
            bboxIouWeight = 1.0f,
            maskIouWeight = 0.0f,
            motionWeight = 0.0f,
            directionWeight = 0.0f,
            associationAmbiguityMargin = 0.05f,
            occlusionOverlapRatio = 0.05f,
            enableSceneMotionCompensation = false
        )

        // Reference tracker gives the selected identity's own Kalman prediction
        // for frame 2 without any occlusion-group blending.
        val reference = TrackManager(config)
        reference.initialize(
            listOf(PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f))
        )
        reference.update(
            listOf(PersonDetection(FloatRect(140f, 100f, 240f, 300f), 0.95f)),
            timestampUs = 33_333L
        )
        val ownPrediction = reference.predictWithoutObservation(66_666L).single().bbox

        // Two identities become overlapping after frame 1 while both still have
        // fresh observations. On frame 2 the selected target (id=0) disappears,
        // while id=1 remains freshly detected and moves +20 px to the right.
        val tracker = TrackManager(config)
        tracker.initializeWithAssignedIds(
            listOf(
                PersonDetection(FloatRect(100f, 100f, 200f, 300f), 0.95f),
                PersonDetection(FloatRect(196f, 100f, 296f, 300f), 0.95f)
            ),
            listOf(0, 1)
        )
        tracker.setProtectedTrackIds(setOf(0))
        tracker.update(
            listOf(
                PersonDetection(FloatRect(140f, 100f, 240f, 300f), 0.95f),
                PersonDetection(FloatRect(200f, 100f, 300f, 300f), 0.95f)
            ),
            timestampUs = 33_333L
        )

        val frame2 = tracker.update(
            listOf(PersonDetection(FloatRect(220f, 100f, 320f, 300f), 0.95f)),
            timestampUs = 66_666L
        )
        val selected = frame2.find { it.id == 0 }
        assertNotNull(selected)
        assertEquals(TrackState.OCCLUDED, selected.state)

        // id=1's actual fresh observed delta is +20 px (center 250 -> 270).
        // With the 0.5 group blend, the selected prediction should therefore be
        // halfway between its own Kalman prediction and center 210, not halfway
        // back toward its stale center 190. The old implementation computed
        // currentPredictedBbox-lastObservedBbox after id=1 had already been
        // overwritten by the current observation, yielding a false zero delta.
        val expectedCenterX = (ownPrediction.centerX + 210f) * 0.5f
        assertTrue(
            abs(selected.bbox.centerX - expectedCenterX) < 2.0f,
            "fresh occluder +20px motion must contribute to group consensus; " +
                "selected=${selected.bbox.centerX}, own=${ownPrediction.centerX}, expected=$expectedCenterX"
        )

        val frame3 = tracker.update(
            listOf(PersonDetection(FloatRect(230f, 100f, 330f, 300f), 0.95f)),
            timestampUs = 99_999L
        )
        val selected3 = frame3.find { it.id == 0 }
        assertNotNull(selected3)
        assertEquals(TrackState.OCCLUDED, selected3.state)
        assertTrue(
            selected3.bbox.centerX > selected.bbox.centerX + 3f,
            "group motion must accumulate across occluded frames instead of re-basing on stale lastObserved; " +
                "frame2=${selected.bbox.centerX}, frame3=${selected3.bbox.centerX}"
        )
    }
}
