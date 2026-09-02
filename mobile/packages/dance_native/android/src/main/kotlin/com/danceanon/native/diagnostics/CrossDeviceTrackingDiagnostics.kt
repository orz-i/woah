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

    internal data class AdaptiveDecision(val useCpu: Boolean, val reason: String)

    private val cpuFullTracker = TrackManager(diagnosticsEnabled = false)
    private val adaptiveTrackers = adaptiveConfigs.associateWith {
        TrackManager(diagnosticsEnabled = false)
    }
    private val lastCpuOrdinalByConfig = adaptiveConfigs.associateWith { 0 }.toMutableMap()
    private var initialized = false
    private var inferenceOrdinal = 0
    private var previousProductionDetections: List<PersonDetection>? = null
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
                previousProductionDetections = prodDetections
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
                        previousGpuDetections = previousProductionDetections,
                        gpuDetections = gpuDetections,
                        previousTracks = lastAdaptiveTracked[config].orEmpty()
                    )
                    adaptiveReasons[config.key] = decision.reason
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
                previousProductionDetections = gpuDetections
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
                    "cpu_full_tracks" to trackSignature(cpuFullTracked),
                    "adaptive_tracks" to adaptiveTracked
                        .entries
                        .sortedBy { it.key.key }
                        .associate { (config, tracks) -> config.key to trackSignature(tracks) },
                    "adaptive_sources" to adaptiveSources,
                    "adaptive_reasons" to adaptiveReasons
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
            previousGpuDetections: List<PersonDetection>?,
            gpuDetections: List<PersonDetection>,
            previousTracks: List<TrackedPerson>
        ): AdaptiveDecision {
            if (inferenceOrdinal - lastCpuOrdinal >= config.maxGap) {
                return AdaptiveDecision(true, "MAX_GAP")
            }
            val previous = previousGpuDetections ?: return AdaptiveDecision(true, "NO_PREVIOUS_GPU")
            if (previousTracks.any { it.state != TrackState.ACTIVE && it.state != TrackState.NEW }) {
                return AdaptiveDecision(true, "NON_ACTIVE_TRACK")
            }
            if (previous.size != gpuDetections.size) {
                return AdaptiveDecision(true, "DETECTION_COUNT_CHANGE")
            }
            if (previous.isEmpty() || gpuDetections.isEmpty()) {
                return AdaptiveDecision(true, "EMPTY_DETECTIONS")
            }
            if (gpuDetections.any { it.confidence < SAFE_GPU_CONFIDENCE }) {
                return AdaptiveDecision(true, "LOW_GPU_CONFIDENCE")
            }
            if (
                maxPairwiseOverlap(previous) >= config.overlapTrigger ||
                maxPairwiseOverlap(gpuDetections) >= config.overlapTrigger
            ) {
                return AdaptiveDecision(true, "PERSON_OVERLAP")
            }

            val costs = Array(previous.size) { previousIndex ->
                FloatArray(gpuDetections.size) { currentIndex ->
                    1f - TrackManager.computeBBoxIoU(
                        previous[previousIndex].bbox,
                        gpuDetections[currentIndex].bbox
                    )
                }
            }
            val matches = HungarianSolver.match(costs, maxCostThreshold = 0.95f).matches
            if (matches.size != gpuDetections.size) {
                return AdaptiveDecision(true, "GPU_GEOMETRY_UNSTABLE")
            }
            for ((previousIndex, currentIndex) in matches) {
                val before = previous[previousIndex].bbox
                val after = gpuDetections[currentIndex].bbox
                val dx = after.centerX - before.centerX
                val dy = after.centerY - before.centerY
                val refDim = maxOf(before.width, before.height, 1f)
                val motionRatio = sqrt(dx * dx + dy * dy) / refDim
                if (motionRatio >= config.maxMotionRatio) {
                    return AdaptiveDecision(true, "GPU_MOTION")
                }
            }
            return AdaptiveDecision(false, "SAFE_GPU_SCHEDULER_ONLY")
        }

        private const val SAFE_GPU_CONFIDENCE = 0.35f

        private fun maxPairwiseOverlap(detections: List<PersonDetection>): Float {
            var maxOverlap = 0f
            for (i in detections.indices) {
                val a = detections[i].bbox
                for (j in i + 1 until detections.size) {
                    val b = detections[j].bbox
                    val intersection = TrackManager.computeBBoxIntersectionArea(a, b)
                    val minArea = minOf(a.width * a.height, b.width * b.height)
                    if (minArea > 0f) {
                        maxOverlap = maxOf(maxOverlap, intersection / minArea)
                    }
                }
            }
            return maxOverlap
        }

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
