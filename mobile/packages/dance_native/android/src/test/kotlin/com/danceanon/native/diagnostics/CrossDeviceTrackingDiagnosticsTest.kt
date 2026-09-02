package com.danceanon.native.diagnostics

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossDeviceTrackingDiagnosticsTest {

    @Test
    fun adaptiveSchedulerSkipsStableSeparatedGpuFrameWithoutGivingGpuIdentityAuthority() {
        val config = CrossDeviceTrackingDiagnostics.AdaptiveConfig(
            key = "test",
            maxGap = 4,
            maxMotionRatio = 0.20f,
            overlapTrigger = 0.15f
        )
        val previous = listOf(
            PersonDetection(FloatRect(0f, 0f, 100f, 200f), 0.9f),
            PersonDetection(FloatRect(300f, 0f, 400f, 200f), 0.8f)
        )
        val current = listOf(
            previous[0].copy(bbox = FloatRect(4f, 1f, 104f, 201f)),
            previous[1].copy(bbox = FloatRect(304f, 2f, 404f, 202f))
        )
        val tracks = listOf(
            TrackedPerson(7, previous[0].bbox, null, 0.9f, state = TrackState.ACTIVE),
            TrackedPerson(3, previous[1].bbox, null, 0.8f, state = TrackState.ACTIVE)
        )

        val decision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config = config,
            inferenceOrdinal = 1,
            lastCpuOrdinal = 0,
            previousGpuDetections = previous,
            gpuDetections = current,
            previousTracks = tracks
        )

        assertFalse(decision.useCpu)
        assertEquals("SAFE_GPU_SCHEDULER_ONLY", decision.reason)
    }

    @Test
    fun productionTrackIdsAreMappedToCpuDetectionOrderByFirstFrameGeometry() {
        val prodDetections = listOf(
            PersonDetection(FloatRect(0f, 0f, 100f, 200f), 0.9f),
            PersonDetection(FloatRect(200f, 0f, 300f, 200f), 0.8f)
        )
        val prodTracks = listOf(
            TrackedPerson(7, prodDetections[0].bbox, null, 0.9f, state = TrackState.ACTIVE),
            TrackedPerson(3, prodDetections[1].bbox, null, 0.8f, state = TrackState.ACTIVE)
        )
        val cpuDetections = listOf(
            prodDetections[1].copy(bbox = FloatRect(200.1f, 0f, 300.1f, 200f)),
            prodDetections[0].copy(bbox = FloatRect(0.1f, 0f, 100.1f, 200f))
        )

        assertEquals(
            listOf(3, 7),
            CrossDeviceTrackingDiagnostics.mapProductionIdsToCpuDetections(
                prodDetections,
                prodTracks,
                cpuDetections
            )
        )
    }

    @Test
    fun adaptiveSchedulerForcesCpuOnGapOverlapAndUnstableTrackState() {
        val config = CrossDeviceTrackingDiagnostics.AdaptiveConfig(
            key = "test",
            maxGap = 3,
            maxMotionRatio = 0.20f,
            overlapTrigger = 0.10f
        )
        val separated = listOf(
            PersonDetection(FloatRect(0f, 0f, 100f, 200f), 0.9f),
            PersonDetection(FloatRect(300f, 0f, 400f, 200f), 0.8f)
        )
        val activeTracks = listOf(
            TrackedPerson(7, separated[0].bbox, null, 0.9f, state = TrackState.ACTIVE),
            TrackedPerson(3, separated[1].bbox, null, 0.8f, state = TrackState.ACTIVE)
        )

        val gapDecision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config, 3, 0, separated, separated, activeTracks
        )
        assertTrue(gapDecision.useCpu)
        assertEquals("MAX_GAP", gapDecision.reason)

        val overlapping = listOf(
            separated[0],
            separated[1].copy(bbox = FloatRect(80f, 0f, 180f, 200f))
        )
        val overlapDecision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config, 1, 0, separated, overlapping, activeTracks
        )
        assertTrue(overlapDecision.useCpu)
        assertEquals("PERSON_OVERLAP", overlapDecision.reason)

        val unstableTracks = activeTracks.toMutableList().apply {
            this[0] = this[0].copy(state = TrackState.OCCLUDED)
        }
        val stateDecision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config, 1, 0, separated, separated, unstableTracks
        )
        assertTrue(stateDecision.useCpu)
        assertEquals("NON_ACTIVE_TRACK", stateDecision.reason)
    }
}
