package com.danceanon.native.diagnostics

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrossDeviceTrackingDiagnosticsTest {

    @Test
    fun quarterPixelStabilizationQuantizesBboxAndFootYWithoutChangingIdentityEvidence() {
        val detection = PersonDetection(
            bbox = FloatRect(10.12f, 20.13f, 30.36f, 40.37f),
            confidence = 0.8123f,
            mask = null,
            footY = 40.37f
        )

        val stable = CrossDeviceTrackingDiagnostics.stabilizeDetections(listOf(detection)).single()

        assertEquals(FloatRect(10.0f, 20.25f, 30.25f, 40.25f), stable.bbox)
        assertEquals(40.25f, stable.footY)
        assertEquals(detection.confidence, stable.confidence)
        assertNull(stable.mask)
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
}
