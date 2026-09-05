package com.danceanon.native.diagnostics

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.PrivacySelectionClass
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossDeviceTrackingDiagnosticsTest {

    private fun solidMask(): NativeMask {
        val size = 64
        val buffer = ByteBuffer.allocateDirect(size * size)
        repeat(size * size) { buffer.put(255.toByte()) }
        buffer.rewind()
        return NativeMask(size, size, buffer, 640, 640)
    }

    @Test
    fun conservativeUnknownFaceEvidenceRequiresOneStrongDormantSelectedOwner() {
        val unknown = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 5,
            detection = PersonDetection(
                FloatRect(220f, 100f, 340f, 320f),
                0.95f,
                solidMask()
            ),
            residualTrackIds = emptySet(),
            conservativeUnknown = true
        )
        val tracks = listOf(
            TrackedPerson(
                id = 5,
                bbox = FloatRect(20f, 100f, 140f, 320f),
                mask = solidMask(),
                confidence = 0.95f,
                state = TrackState.ACTIVE,
                observedThisFrame = true
            ),
            TrackedPerson(
                id = 6,
                bbox = FloatRect(210f, 100f, 330f, 320f),
                mask = solidMask(),
                confidence = 0.95f,
                state = TrackState.LOST,
                observedThisFrame = false
            )
        )

        val resolved = CrossDeviceTrackingDiagnostics.resolveTemporalFacePrivacyClassEvidence(
            temporalEvidence = listOf(unknown),
            faceOnlyPersonIds = setOf(5, 6),
            cpuFullTracked = tracks
        )

        assertEquals(1, resolved.size)
        assertEquals(setOf(6), resolved.single().residualTrackIds)
        assertTrue(resolved.single().conservativeUnknown)
    }

    @Test
    fun conservativeUnknownFaceEvidenceRejectsFarOrMultiOwnerGeometry() {
        val unknown = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 5,
            detection = PersonDetection(
                FloatRect(220f, 100f, 340f, 320f),
                0.95f,
                solidMask()
            ),
            residualTrackIds = emptySet(),
            conservativeUnknown = true
        )
        val twoDormantOwners = listOf(
            TrackedPerson(5, FloatRect(205f, 100f, 325f, 320f), solidMask(), 0.95f,
                state = TrackState.LOST, observedThisFrame = false),
            TrackedPerson(6, FloatRect(225f, 100f, 345f, 320f), solidMask(), 0.95f,
                state = TrackState.LOST, observedThisFrame = false)
        )
        val farOwner = listOf(
            TrackedPerson(6, FloatRect(500f, 100f, 620f, 320f), solidMask(), 0.95f,
                state = TrackState.LOST, observedThisFrame = false)
        )

        assertTrue(
            CrossDeviceTrackingDiagnostics.resolveTemporalFacePrivacyClassEvidence(
                listOf(unknown), setOf(5, 6), twoDormantOwners
            ).isEmpty()
        )
        assertTrue(
            CrossDeviceTrackingDiagnostics.resolveTemporalFacePrivacyClassEvidence(
                listOf(unknown), setOf(6), farOwner
            ).isEmpty()
        )
    }

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
            TrackedPerson(3, previous[1].bbox, null, 0.8f, state = TrackState.LOST)
        )

        val decision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config = config,
            inferenceOrdinal = 1,
            lastCpuOrdinal = 0,
            gpuDetections = current,
            previousTracks = tracks,
            identityProtectedTrackIds = setOf(7)
        )

        assertFalse(decision.useCpu)
        assertEquals("SAFE_GPU_SCHEDULER_ONLY", decision.reason)
        assertEquals(0, decision.metrics.nonActiveProtectedTrackCount)
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
    fun adaptiveSchedulerForcesCpuOnGapLocalOverlapAndProtectedTrackState() {
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
            config, 3, 0, separated, activeTracks, setOf(7)
        )
        assertTrue(gapDecision.useCpu)
        assertEquals("MAX_GAP", gapDecision.reason)

        val overlapping = listOf(
            separated[0],
            separated[1].copy(bbox = FloatRect(80f, 0f, 180f, 200f))
        )
        val overlapDecision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config, 1, 0, overlapping, activeTracks, setOf(7)
        )
        assertTrue(overlapDecision.useCpu)
        assertEquals("PROTECTED_GPU_CANDIDATE_COUNT", overlapDecision.reason)

        val unstableTracks = activeTracks.toMutableList().apply {
            this[0] = this[0].copy(state = TrackState.OCCLUDED)
        }
        val stateDecision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config, 1, 0, separated, unstableTracks, setOf(7)
        )
        assertTrue(stateDecision.useCpu)
        assertEquals("PROTECTED_NON_ACTIVE", stateDecision.reason)
    }

    @Test
    fun adaptiveSchedulerIgnoresUnrelatedOverlapAwayFromProtectedIdentity() {
        val config = CrossDeviceTrackingDiagnostics.AdaptiveConfig(
            key = "test",
            maxGap = 4,
            maxMotionRatio = 0.20f,
            overlapTrigger = 0.10f
        )
        val protected = PersonDetection(FloatRect(0f, 0f, 100f, 200f), 0.9f)
        val unrelatedA = PersonDetection(FloatRect(300f, 0f, 400f, 200f), 0.9f)
        val unrelatedB = PersonDetection(FloatRect(350f, 0f, 450f, 200f), 0.9f)
        val tracks = listOf(
            TrackedPerson(7, protected.bbox, null, 0.9f, state = TrackState.ACTIVE),
            TrackedPerson(3, unrelatedA.bbox, null, 0.9f, state = TrackState.LOST)
        )

        val decision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config = config,
            inferenceOrdinal = 1,
            lastCpuOrdinal = 0,
            gpuDetections = listOf(
                protected.copy(bbox = FloatRect(4f, 1f, 104f, 201f)),
                unrelatedA,
                unrelatedB
            ),
            previousTracks = tracks,
            identityProtectedTrackIds = setOf(7)
        )

        assertFalse(decision.useCpu)
        assertEquals("SAFE_GPU_SCHEDULER_ONLY", decision.reason)
        assertEquals(1, decision.metrics.maxLocalCandidateCount)
    }

    @Test
    fun adaptiveSchedulerForcesCpuWhenProtectedGpuCandidateIsMissing() {
        val config = CrossDeviceTrackingDiagnostics.AdaptiveConfig(
            key = "test",
            maxGap = 4,
            maxMotionRatio = 0.20f,
            overlapTrigger = 0.10f
        )
        val track = TrackedPerson(
            7,
            FloatRect(0f, 0f, 100f, 200f),
            null,
            0.9f,
            state = TrackState.ACTIVE
        )

        val decision = CrossDeviceTrackingDiagnostics.decideCpuAnchor(
            config = config,
            inferenceOrdinal = 1,
            lastCpuOrdinal = 0,
            gpuDetections = listOf(
                PersonDetection(FloatRect(400f, 0f, 500f, 200f), 0.9f)
            ),
            previousTracks = listOf(track),
            identityProtectedTrackIds = setOf(7)
        )

        assertTrue(decision.useCpu)
        assertEquals("PROTECTED_GPU_CANDIDATE_COUNT", decision.reason)
        assertEquals(0, decision.metrics.minLocalCandidateCount)
    }

    @Test
    fun disablingAdaptiveMatrixPreservesCpuFullTrackingExactly() {
        val adaptiveOn = CrossDeviceTrackingDiagnostics(
            jobId = "adaptive-on",
            fullBodyPersonIds = setOf(7),
            faceOnlyPersonIds = emptySet(),
            identityProtectedTrackIds = setOf(7),
            enableAdaptiveShadowMatrix = true,
            emitStructuredDiagnostics = false
        )
        val adaptiveOff = CrossDeviceTrackingDiagnostics(
            jobId = "adaptive-off",
            fullBodyPersonIds = setOf(7),
            faceOnlyPersonIds = emptySet(),
            identityProtectedTrackIds = setOf(7),
            enableAdaptiveShadowMatrix = false,
            emitStructuredDiagnostics = false
        )

        repeat(12) { frame ->
            val shouldInfer = frame == 0 || frame % 3 != 2
            val shift = frame * 4f
            val detections = listOf(
                PersonDetection(FloatRect(shift, 0f, 100f + shift, 200f), 0.90f),
                PersonDetection(FloatRect(300f + shift, 0f, 400f + shift, 200f), 0.80f)
            )
            val productionDetections = if (shouldInfer) detections else null
            val cpuDetections = if (shouldInfer) detections else null
            val assignedIds = if (frame == 0) listOf(7, 3) else null

            val withAdaptive = adaptiveOn.recordFrame(
                ptsUs = frame * 33_366L,
                shouldInfer = shouldInfer,
                productionDetections = productionDetections,
                productionTracked = null,
                cpuMt4Detections = cpuDetections,
                initialAssignedIds = assignedIds
            )
            val withoutAdaptive = adaptiveOff.recordFrame(
                ptsUs = frame * 33_366L,
                shouldInfer = shouldInfer,
                productionDetections = productionDetections,
                productionTracked = null,
                cpuMt4Detections = cpuDetections,
                initialAssignedIds = assignedIds
            )

            assertEquals(
                CrossDeviceTrackingDiagnostics.trackSignature(requireNotNull(withAdaptive)),
                CrossDeviceTrackingDiagnostics.trackSignature(requireNotNull(withoutAdaptive)),
                "CPU-full tracking changed at frame $frame"
            )
        }

        assertTrue(adaptiveOn.isAdaptiveShadowMatrixEnabled())
        assertFalse(adaptiveOff.isAdaptiveShadowMatrixEnabled())
        assertEquals(12L * CrossDeviceTrackingDiagnostics.DEFAULT_ADAPTIVE_CONFIGS.size,
            adaptiveOn.getAdaptiveShadowTrackerSteps())
        assertEquals(0L, adaptiveOff.getAdaptiveShadowTrackerSteps())
    }
}
