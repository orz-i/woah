package com.danceanon.native.diagnostics

import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.ProtectedTrackMotionEvidence
import com.danceanon.native.tracking.TrackedPerson

/**
 * Release stub for the deterministic cross-device diagnostic reference lane.
 * The production export path never instantiates this class in release builds.
 */
internal class CrossDeviceTrackingDiagnostics(
    jobId: String,
    fullBodyPersonIds: Set<Int>,
    faceOnlyPersonIds: Set<Int>,
    identityProtectedTrackIds: Set<Int> = fullBodyPersonIds + faceOnlyPersonIds,
    adaptiveConfigs: List<AdaptiveConfig> = DEFAULT_ADAPTIVE_CONFIGS,
    enableAdaptiveShadowMatrix: Boolean = false,
    emitStructuredDiagnostics: Boolean = true
) {
    internal data class AdaptiveConfig(
        val key: String,
        val maxGap: Int,
        val maxMotionRatio: Float,
        val overlapTrigger: Float
    )

    fun recordFrame(
        ptsUs: Long,
        shouldInfer: Boolean,
        productionDetections: List<PersonDetection>?,
        productionTracked: List<TrackedPerson>?,
        cpuMt4Detections: List<PersonDetection>?,
        initialAssignedIds: List<Int>? = null,
        allowProductionFallbackForCpuFull: Boolean = false
    ): List<TrackedPerson>? = null

    fun getCpuFullProtectedTrackMotionEvidence(): List<ProtectedTrackMotionEvidence> = emptyList()
    fun isAdaptiveShadowMatrixEnabled(): Boolean = false
    fun getAdaptiveShadowTrackerSteps(): Long = 0L
    fun getCpuFullTemporalFacePrivacyClassEvidence(): List<FreshPrivacyClassEvidence> = emptyList()
    fun getCpuFullFreshFacePrivacyClassEvidence(): List<FreshPrivacyClassEvidence> = emptyList()

    companion object {
        internal val DEFAULT_ADAPTIVE_CONFIGS = listOf(
            AdaptiveConfig("dense", 2, 0.08f, 0.05f),
            AdaptiveConfig("safe", 3, 0.12f, 0.10f),
            AdaptiveConfig("balanced", 4, 0.18f, 0.15f),
            AdaptiveConfig("aggressive", 6, 0.25f, 0.20f),
            AdaptiveConfig("sparse", 8, 0.35f, 0.25f)
        )

        internal fun trackSignature(tracks: List<TrackedPerson>): List<Map<String, Any?>> = emptyList()
    }
}
