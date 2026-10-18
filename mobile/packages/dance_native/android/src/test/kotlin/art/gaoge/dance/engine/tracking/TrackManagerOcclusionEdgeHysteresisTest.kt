package art.gaoge.dance.engine.tracking

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackManagerOcclusionEdgeHysteresisTest {
    @Test
    fun plk110ProtagonistGroupEdgeDoesNotDropAtOneFrameThresholdCrossing() {
        // Real PLK110 production group edge: target ID 2 and stale ID 8
        // were at 0.320 at 18.233s, then 0.293 at 18.267s. The target
        // was still observed at 18.233s, and YOLO still saw it at 18.267s.
        assertTrue(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = 0.320f,
                entryThreshold = 0.30f,
                activeGroupPair = false,
                recentlyObservedMember = true
            )
        )
        assertTrue(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = 0.293f,
                entryThreshold = 0.30f,
                activeGroupPair = true,
                recentlyObservedMember = true
            ),
            "a 0.007 overlap jitter must not drop a recently observed member before association"
        )
    }

    @Test
    fun noNewGroupOrStaleGroupCanEnterOnHysteresisAlone() {
        val ratio = 0.293f
        assertFalse(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = ratio,
                entryThreshold = 0.30f,
                activeGroupPair = false,
                recentlyObservedMember = true
            )
        )
        assertFalse(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = ratio,
                entryThreshold = 0.30f,
                activeGroupPair = true,
                recentlyObservedMember = false
            )
        )
        assertFalse(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = 0.249f,
                entryThreshold = 0.30f,
                activeGroupPair = true,
                recentlyObservedMember = true
            ),
            "a genuinely separating pair must exit even when it used to overlap"
        )
        assertFalse(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = 0f,
                entryThreshold = 0.30f,
                activeGroupPair = true,
                recentlyObservedMember = true
            )
        )
    }

    @Test
    fun invalidAndSmallThresholdsDoNotCreateAlwaysConnectedGroups() {
        assertFalse(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = Float.NaN,
                entryThreshold = 0.30f,
                activeGroupPair = true,
                recentlyObservedMember = true
            )
        )
        assertFalse(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = 0f,
                entryThreshold = 0.02f,
                activeGroupPair = true,
                recentlyObservedMember = true
            )
        )
        assertTrue(
            TrackManager.isOcclusionPairConnected(
                overlapRatio = 0.016f,
                entryThreshold = 0.02f,
                activeGroupPair = true,
                recentlyObservedMember = true
            )
        )
    }
}
