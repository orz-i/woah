package com.danceanon.native.tracking

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackManagerOcclusionEdgeHysteresisTest {

    @Test
    fun `existing direct edge survives measured cross-device threshold jitter`() {
        val enterThreshold = 0.30f

        // Same pts_us=416944 frame from the three-device diagnostic set:
        // PLK110 fell just below the old hard threshold while the other two
        // devices remained above it.  A direct edge that existed on the prior
        // frame must not split solely because of this narrow numeric spread.
        assertTrue(
            TrackManager.shouldConnectOcclusionPair(
                overlapRatio = 0.29133296f,
                enterThreshold = enterThreshold,
                previouslyDirectOverlapEdge = true
            )
        )
        assertTrue(
            TrackManager.shouldConnectOcclusionPair(
                overlapRatio = 0.30298156f,
                enterThreshold = enterThreshold,
                previouslyDirectOverlapEdge = true
            )
        )
        assertTrue(
            TrackManager.shouldConnectOcclusionPair(
                overlapRatio = 0.3060077f,
                enterThreshold = enterThreshold,
                previouslyDirectOverlapEdge = true
            )
        )
    }

    @Test
    fun `hysteresis does not lower the entry threshold for new edges`() {
        assertFalse(
            TrackManager.shouldConnectOcclusionPair(
                overlapRatio = 0.29133296f,
                enterThreshold = 0.30f,
                previouslyDirectOverlapEdge = false
            )
        )
    }

    @Test
    fun `existing edge exits once separation is clearly below the narrow band`() {
        // On the very next common frame the measured ID3-ID4 ratios were all
        // <= 0.26345, so every device should split instead of extending the
        // occlusion group indefinitely.
        assertFalse(
            TrackManager.shouldConnectOcclusionPair(
                overlapRatio = 0.2634502f,
                enterThreshold = 0.30f,
                previouslyDirectOverlapEdge = true
            )
        )
        assertFalse(
            TrackManager.shouldConnectOcclusionPair(
                overlapRatio = 0.2457189f,
                enterThreshold = 0.30f,
                previouslyDirectOverlapEdge = true
            )
        )
    }
}
