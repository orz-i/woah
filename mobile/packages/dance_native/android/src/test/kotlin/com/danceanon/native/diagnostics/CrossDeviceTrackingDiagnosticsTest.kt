package com.danceanon.native.diagnostics

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.MotionBridgeMeasurementMode
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossDeviceTrackingDiagnosticsTest {

    @Test
    fun cadenceObservationScheduleKeepsFirstAnchorAndRequestedStride() {
        assertTrue(CrossDeviceTrackingDiagnostics.shouldObserve(0, 6))
        assertTrue(CrossDeviceTrackingDiagnostics.shouldObserve(2, 2))
        assertTrue(CrossDeviceTrackingDiagnostics.shouldObserve(3, 3))
        assertFalse(CrossDeviceTrackingDiagnostics.shouldObserve(3, 2))
        assertTrue(CrossDeviceTrackingDiagnostics.shouldObserve(6, 6))
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
    fun motionBridgeMovesPredictionWithoutCommittingIdentityObservation() {
        val tracker = TrackManager(diagnosticsEnabled = false)
        val initial = listOf(
            PersonDetection(FloatRect(0f, 0f, 100f, 200f), 0.9f),
            PersonDetection(FloatRect(300f, 0f, 400f, 200f), 0.8f)
        )
        tracker.initializeWithAssignedIds(initial, listOf(7, 3))

        val bridged = tracker.bridgeMotionOnly(
            detections = listOf(
                initial[0].copy(bbox = FloatRect(12f, 4f, 112f, 204f)),
                initial[1].copy(bbox = FloatRect(310f, 6f, 410f, 206f))
            ),
            timestampUs = 16_677L,
            measurementMode = MotionBridgeMeasurementMode.FULL_BBOX
        ).associateBy { it.id }

        assertEquals(setOf(3, 7), bridged.keys)
        assertFalse(bridged.getValue(7).observedThisFrame)
        assertEquals(1, bridged.getValue(7).framesSinceLastObservation)
        assertEquals(FloatRect(12f, 4f, 112f, 204f), bridged.getValue(7).bbox)
    }

    @Test
    fun centerTranslationBridgePreservesPredictedBoxSize() {
        val tracker = TrackManager(diagnosticsEnabled = false)
        val initial = PersonDetection(FloatRect(0f, 0f, 100f, 200f), 0.9f)
        tracker.initializeWithAssignedIds(listOf(initial), listOf(5))

        val bridged = tracker.bridgeMotionOnly(
            detections = listOf(
                initial.copy(bbox = FloatRect(20f, 10f, 140f, 250f))
            ),
            timestampUs = 16_677L,
            measurementMode = MotionBridgeMeasurementMode.CENTER_TRANSLATION
        ).single()

        assertEquals(100f, bridged.bbox.width)
        assertEquals(200f, bridged.bbox.height)
        assertEquals(80f, bridged.bbox.centerX)
        assertEquals(130f, bridged.bbox.centerY)
        assertEquals(5, bridged.id)
        assertFalse(bridged.observedThisFrame)
    }
}
