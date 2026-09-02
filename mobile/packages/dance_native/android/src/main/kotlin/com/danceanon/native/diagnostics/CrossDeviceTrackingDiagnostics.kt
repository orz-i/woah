package com.danceanon.native.diagnostics

import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Debug-only shadow tracking matrix for cross-device validation.
 *
 * The full baseline consumes deterministic CPU detections every inference frame. Adaptive
 * trackers use production detections only as a scheduler signal: motion, overlap, confidence, and
 * detection-count changes decide whether the deterministic CPU path must run on this frame. GPU
 * detections are never passed into TrackManager, so they cannot create, remove, swap, or commit an
 * identity. Multiple schedules are evaluated from one export to measure CPU savings and parity.
 */
internal class CrossDeviceTrackingDiagnostics(
    private val jobId: String,
    fullBodyPersonIds: Set<Int>,
    faceOnlyPersonIds: Set<Int>,
    private val adaptiveConfigs: List<AdaptiveConfig> = DEFAULT_ADAPTIVE_CONFIGS
) {
    internal data class AdaptiveConfig(
        val key: String,
        val maxGap: Int,
        val maxMotionRatio: Float,
        val overlapTrigger: Float
    )

    internal data class AdaptiveMetrics(
        val protectedTrackCount: Int,
        val missingProtectedTrackCount: Int,
        val nonActiveProtectedTrackCount: Int,
        val minLocalCandidateCount: Int,
        val maxLocalCandidateCount: Int,
        val duplicateCandidateOwnership: Boolean,
        val minMatchedConfidence: Float?,
        val maxMatchedMotionRatio: Float,
        val maxMatchedOverlapRatio: Float,
        val maxGapReached: Boolean
    ) {
        fun asFields(): Map<String, Any?> = mapOf(
            "protected_track_count" to protectedTrackCount,
            "missing_protected_track_count" to missingProtectedTrackCount,
            "non_active_protected_track_count" to nonActiveProtectedTrackCount,
            "min_local_candidate_count" to minLocalCandidateCount,
            "max_local_candidate_count" to maxLocalCandidateCount,
            "duplicate_candidate_ownership" to duplicateCandidateOwnership,
            "min_matched_confidence_q1e4" to minMatchedConfidence?.let { (it * 10_000f).roundToInt() },
            "max_matched_motion_ratio_q1e4" to (maxMatchedMotionRatio * 10_000f).roundToInt(),
            "max_matched_overlap_ratio_q1e4" to (maxMatchedOverlapRatio * 10_000f).roundToInt(),
            "max_gap_reached" to maxGapReached
        )
    }

    internal data class AdaptiveDecision(
        val useCpu: Boolean,
        val reason: String,
        val metrics: AdaptiveMetrics
    )

    private val cpuFullTracker = TrackManager(diagnosticsEnabled = false)
    private val identityProtectedTrackIds = (fullBodyPersonIds + faceOnlyPersonIds).toSortedSet()
    private val adaptiveTrackers = adaptiveConfigs.associateWith {
        TrackManager(diagnosticsEnabled = false)
    }
    private val lastCpuOrdinalByConfig = adaptiveConfigs.associateWith { 0 }.toMutableMap()
    private var initialized = false
    private var inferenceOrdinal = 0
    private var lastAdaptiveTracked: Map<AdaptiveConfig, List<TrackedPerson>> = emptyMap()
    private var disabledReason: String? = null

    init {
        require(adaptiveConfigs.isNotEmpty())
        configureTracker(cpuFullTracker, fullBodyPersonIds, faceOnlyPersonIds)
        adaptiveTrackers.values.forEach {
            configureTracker(it, fullBodyPersonIds, faceOnlyPersonIds)
        }
    }

    fun recordFrame(
        ptsUs: Long,
        shouldInfer: Boolean,
        productionDetections: List<PersonDetection>?,
        productionTracked: List<TrackedPerson>?,
        cpuMt4Detections: List<PersonDetection>?
    ) {
        if (!com.danceanon.dance_native.BuildConfig.DEBUG || disabledReason != null) return

        try {
            val cpuFullTracked: List<TrackedPerson>
            val adaptiveTracked: Map<AdaptiveConfig, List<TrackedPerson>>
            val adaptiveSources = linkedMapOf<String, String>()
            val adaptiveReasons = linkedMapOf<String, String>()
            val adaptiveMetrics = linkedMapOf<String, Map<String, Any?>>()
            if (!initialized) {
                val prodDetections = productionDetections ?: return disable("MISSING_PRODUCTION_FIRST_FRAME")
                val prodTracked = productionTracked ?: return disable("MISSING_PRODUCTION_TRACKS_FIRST_FRAME")
                val cpuDetections = cpuMt4Detections ?: return disable("MISSING_CPU_MT4_FIRST_FRAME")
                val assignedIds = mapProductionIdsToCpuDetections(
                    productionDetections = prodDetections,
                    productionTracked = prodTracked,
                    cpuDetections = cpuDetections
                ) ?: return disable("CPU_MT4_FIRST_FRAME_ID_MAP_FAILED")

                cpuFullTracked = cpuFullTracker.initializeWithAssignedIds(cpuDetections, assignedIds)
                adaptiveTracked = adaptiveTrackers.mapValues { (config, tracker) ->
                    adaptiveSources[config.key] = "CPU"
                    adaptiveReasons[config.key] = "INITIALIZE"
                    tracker.initializeWithAssignedIds(cpuDetections, assignedIds)
                }
                initialized = true
                inferenceOrdinal = 0
            } else if (!shouldInfer) {
                cpuFullTracked = cpuFullTracker.predictWithoutObservation(ptsUs)
                adaptiveTracked = adaptiveTrackers.mapValues { (config, tracker) ->
                    adaptiveSources[config.key] = "PREDICT"
                    adaptiveReasons[config.key] = "NO_INFERENCE_FRAME"
                    tracker.predictWithoutObservation(ptsUs)
                }
            } else {
                val cpuDetections = cpuMt4Detections ?: return disable("MISSING_CPU_MT4_INFERENCE_FRAME")
                val gpuDetections = productionDetections ?: return disable("MISSING_PRODUCTION_INFERENCE_FRAME")
                inferenceOrdinal++

                cpuFullTracked = if (cpuDetections.isEmpty()) {
                    cpuFullTracker.predict(ptsUs)
                } else {
                    cpuFullTracker.update(cpuDetections, ptsUs)
                }

                adaptiveTracked = adaptiveTrackers.mapValues { (config, tracker) ->
                    val decision = decideCpuAnchor(
                        config = config,
                        inferenceOrdinal = inferenceOrdinal,
                        lastCpuOrdinal = lastCpuOrdinalByConfig.getValue(config),
                        gpuDetections = gpuDetections,
                        previousTracks = lastAdaptiveTracked[config].orEmpty(),
                        identityProtectedTrackIds = identityProtectedTrackIds
                    )
                    adaptiveReasons[config.key] = decision.reason
                    adaptiveMetrics[config.key] = decision.metrics.asFields()
                    if (decision.useCpu) {
                        lastCpuOrdinalByConfig[config] = inferenceOrdinal
                        adaptiveSources[config.key] = "CPU"
                        if (cpuDetections.isEmpty()) {
                            tracker.predict(ptsUs)
                        } else {
                            tracker.update(cpuDetections, ptsUs)
                        }
                    } else {
                        adaptiveSources[config.key] = "PREDICT"
                        tracker.predictWithoutObservation(ptsUs)
                    }
                }
            }
            lastAdaptiveTracked = adaptiveTracked

            NativeDiagnostics.event(
                level = "INFO",
                component = "CrossDeviceTrackingDiagnostics",
                event = "YOLO_CPU_MT4_TRACK_SHADOW",
                fields = mapOf(
                    "job_id" to jobId,
                    "pts_us" to ptsUs,
                    "should_infer" to shouldInfer,
                    "cpu_inference_ordinal" to inferenceOrdinal,
                    "identity_protected_track_ids" to identityProtectedTrackIds.toList(),
                    "cpu_full_tracks" to trackSignature(cpuFullTracked),
                    "adaptive_tracks" to adaptiveTracked
                        .entries
                        .sortedBy { it.key.key }
                        .associate { (config, tracks) -> config.key to trackSignature(tracks) },
                    "adaptive_sources" to adaptiveSources,
                    "adaptive_reasons" to adaptiveReasons,
                    "adaptive_metrics" to adaptiveMetrics
                )
            )
        } catch (t: Throwable) {
            disable("${t.javaClass.simpleName}:${t.message ?: "unknown"}")
        }
    }

    private fun disable(reason: String) {
        if (disabledReason != null) return
        disabledReason = reason
        NativeDiagnostics.event(
            level = "WARN",
            component = "CrossDeviceTrackingDiagnostics",
            event = "YOLO_CPU_MT4_TRACK_SHADOW_DISABLED",
            fields = mapOf(
                "job_id" to jobId,
                "reason" to reason
            )
        )
    }

    companion object {
        internal val DEFAULT_ADAPTIVE_CONFIGS = listOf(
            AdaptiveConfig("dense", maxGap = 2, maxMotionRatio = 0.08f, overlapTrigger = 0.05f),
            AdaptiveConfig("safe", maxGap = 3, maxMotionRatio = 0.12f, overlapTrigger = 0.10f),
            AdaptiveConfig("balanced", maxGap = 4, maxMotionRatio = 0.18f, overlapTrigger = 0.15f),
            AdaptiveConfig("aggressive", maxGap = 6, maxMotionRatio = 0.25f, overlapTrigger = 0.20f),
            AdaptiveConfig("sparse", maxGap = 8, maxMotionRatio = 0.35f, overlapTrigger = 0.25f)
        )

        internal fun decideCpuAnchor(
            config: AdaptiveConfig,
            inferenceOrdinal: Int,
            lastCpuOrdinal: Int,
            gpuDetections: List<PersonDetection>,
            previousTracks: List<TrackedPerson>,
            identityProtectedTrackIds: Set<Int>
        ): AdaptiveDecision {
            val protectedTracks = previousTracks.filter { identityProtectedTrackIds.contains(it.id) }
            val missingProtectedTrackCount =
                (identityProtectedTrackIds.size - protectedTracks.map { it.id }.toSet().size).coerceAtLeast(0)
            val nonActiveProtectedTrackCount = protectedTracks.count {
                it.state != TrackState.ACTIVE && it.state != TrackState.NEW
            }

            val candidateIndicesByTrack = mutableListOf<List<Int>>()
            var minMatchedConfidence: Float? = null
            var maxMatchedMotionRatio = 0f
            var maxMatchedOverlapRatio = 0f
            for (track in protectedTracks) {
                val searchBox = expandBbox(track.bbox, LOCAL_GPU_SEARCH_EXPANSION_RATIO)
                val candidateIndices = gpuDetections.indices.filter { detectionIndex ->
                    bboxIntersectionArea(searchBox, gpuDetections[detectionIndex].bbox) > 0f
                }
                candidateIndicesByTrack.add(candidateIndices)
                if (candidateIndices.size != 1) continue

                val candidateIndex = candidateIndices.single()
                val candidate = gpuDetections[candidateIndex]
                minMatchedConfidence = minOf(minMatchedConfidence ?: candidate.confidence, candidate.confidence)
                val dx = candidate.bbox.centerX - track.bbox.centerX
                val dy = candidate.bbox.centerY - track.bbox.centerY
                val refDim = maxOf(track.bbox.width, track.bbox.height, 1f)
                maxMatchedMotionRatio = maxOf(
                    maxMatchedMotionRatio,
                    sqrt(dx * dx + dy * dy) / refDim
                )
                for (otherIndex in gpuDetections.indices) {
                    if (otherIndex == candidateIndex) continue
                    val other = gpuDetections[otherIndex]
                    val minArea = minOf(
                        candidate.bbox.width * candidate.bbox.height,
                        other.bbox.width * other.bbox.height
                    )
                    if (minArea <= 0f) continue
                    maxMatchedOverlapRatio = maxOf(
                        maxMatchedOverlapRatio,
                        bboxIntersectionArea(candidate.bbox, other.bbox) / minArea
                    )
                }
            }

            val localCandidateCounts = candidateIndicesByTrack.map { it.size }
            val minLocalCandidateCount = localCandidateCounts.minOrNull() ?: 0
            val maxLocalCandidateCount = localCandidateCounts.maxOrNull() ?: 0
            val uniquelyOwnedCandidateIndices = candidateIndicesByTrack
                .filter { it.size == 1 }
                .map { it.single() }
            val duplicateCandidateOwnership =
                uniquelyOwnedCandidateIndices.size != uniquelyOwnedCandidateIndices.toSet().size
            val maxGapReached = inferenceOrdinal - lastCpuOrdinal >= config.maxGap
            val metrics = AdaptiveMetrics(
                protectedTrackCount = protectedTracks.size,
                missingProtectedTrackCount = missingProtectedTrackCount,
                nonActiveProtectedTrackCount = nonActiveProtectedTrackCount,
                minLocalCandidateCount = minLocalCandidateCount,
                maxLocalCandidateCount = maxLocalCandidateCount,
                duplicateCandidateOwnership = duplicateCandidateOwnership,
                minMatchedConfidence = minMatchedConfidence,
                maxMatchedMotionRatio = maxMatchedMotionRatio,
                maxMatchedOverlapRatio = maxMatchedOverlapRatio,
                maxGapReached = maxGapReached
            )

            fun cpu(reason: String) = AdaptiveDecision(true, reason, metrics)
            if (maxGapReached) {
                return cpu("MAX_GAP")
            }
            if (identityProtectedTrackIds.isEmpty()) {
                return cpu("NO_IDENTITY_PROTECTED_TRACKS")
            }
            if (missingProtectedTrackCount > 0) {
                return cpu("PROTECTED_TRACK_MISSING")
            }
            if (nonActiveProtectedTrackCount > 0) {
                return cpu("PROTECTED_NON_ACTIVE")
            }
            if (localCandidateCounts.any { it != 1 }) {
                return cpu("PROTECTED_GPU_CANDIDATE_COUNT")
            }
            if (duplicateCandidateOwnership) {
                return cpu("PROTECTED_GPU_OWNERSHIP_AMBIGUOUS")
            }
            if ((minMatchedConfidence ?: 0f) < SAFE_GPU_CONFIDENCE) {
                return cpu("PROTECTED_LOW_GPU_CONFIDENCE")
            }
            if (maxMatchedOverlapRatio >= config.overlapTrigger) {
                return cpu("PROTECTED_PERSON_OVERLAP")
            }
            if (maxMatchedMotionRatio >= config.maxMotionRatio) {
                return cpu("PROTECTED_GPU_MOTION")
            }
            return AdaptiveDecision(false, "SAFE_GPU_SCHEDULER_ONLY", metrics)
        }

        private const val SAFE_GPU_CONFIDENCE = 0.35f
        private const val LOCAL_GPU_SEARCH_EXPANSION_RATIO = 0.30f

        private fun expandBbox(bbox: com.danceanon.native.inference.FloatRect, ratio: Float) =
            com.danceanon.native.inference.FloatRect(
                left = bbox.left - bbox.width * ratio,
                top = bbox.top - bbox.height * ratio,
                right = bbox.right + bbox.width * ratio,
                bottom = bbox.bottom + bbox.height * ratio
            )

        private fun bboxIntersectionArea(
            a: com.danceanon.native.inference.FloatRect,
            b: com.danceanon.native.inference.FloatRect
        ): Float = TrackManager.computeBBoxIntersectionArea(a, b)

        internal fun mapProductionIdsToCpuDetections(
            productionDetections: List<PersonDetection>,
            productionTracked: List<TrackedPerson>,
            cpuDetections: List<PersonDetection>
        ): List<Int>? {
            if (
                productionDetections.isEmpty() ||
                productionDetections.size != productionTracked.size ||
                productionDetections.size != cpuDetections.size
            ) {
                return null
            }

            val costs = Array(productionDetections.size) { productionIndex ->
                FloatArray(cpuDetections.size) { cpuIndex ->
                    1f - TrackManager.computeBBoxIoU(
                        productionDetections[productionIndex].bbox,
                        cpuDetections[cpuIndex].bbox
                    )
                }
            }
            val matches = HungarianSolver.match(costs, maxCostThreshold = 0.50f).matches
            if (matches.size != cpuDetections.size) return null

            val assigned = IntArray(cpuDetections.size) { Int.MIN_VALUE }
            for ((productionIndex, cpuIndex) in matches) {
                if (productionIndex !in productionTracked.indices || cpuIndex !in assigned.indices) return null
                assigned[cpuIndex] = productionTracked[productionIndex].id
            }
            return if (assigned.any { it == Int.MIN_VALUE }) null else assigned.toList()
        }

        internal fun trackSignature(tracks: List<TrackedPerson>): List<Map<String, Any?>> =
            tracks.sortedBy { it.id }.map { track ->
                mapOf(
                    "id" to track.id,
                    "state" to track.state.name,
                    "bbox_q0_0625px" to listOf(
                        (track.bbox.left * 16f).roundToInt(),
                        (track.bbox.top * 16f).roundToInt(),
                        (track.bbox.right * 16f).roundToInt(),
                        (track.bbox.bottom * 16f).roundToInt()
                    ),
                    "observed_this_frame" to track.observedThisFrame,
                    "frames_since_last_observation" to track.framesSinceLastObservation,
                    "missed_frames" to track.missedFrames,
                    "occluded_by_track_ids" to track.occludedByTrackIds.sorted()
                )
            }

        private fun configureTracker(
            tracker: TrackManager,
            fullBodyPersonIds: Set<Int>,
            faceOnlyPersonIds: Set<Int>
        ) {
            if (faceOnlyPersonIds.isEmpty()) {
                tracker.setProtectedTrackIds(fullBodyPersonIds)
            } else {
                tracker.setIdentityProtectedTrackIds(fullBodyPersonIds + faceOnlyPersonIds)
                tracker.setPrivacySelectedTrackIds(fullBodyPersonIds)
                tracker.setPrivacyOffscreenDormancyEnabled(fullBodyPersonIds.isNotEmpty())
            }
        }
    }
}
