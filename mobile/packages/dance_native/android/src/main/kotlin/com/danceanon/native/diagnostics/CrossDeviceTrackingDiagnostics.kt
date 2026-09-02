package com.danceanon.native.diagnostics

import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackedPerson
import kotlin.math.roundToInt

/**
 * Debug-only shadow tracking matrix for cross-device validation.
 *
 * All shadow trackers consume the same deterministic CPU-MT detector output and never feed
 * rendering, privacy, selection, or production TrackManager state. They differ only in how often
 * detections are committed (cadence 1/2/3/4/6), so one export can determine the cheapest identity
 * anchor cadence that preserves the full-cadence tracking topology.
 */
internal class CrossDeviceTrackingDiagnostics(
    private val jobId: String,
    fullBodyPersonIds: Set<Int>,
    faceOnlyPersonIds: Set<Int>,
    private val cadences: IntArray = DEFAULT_CADENCES
) {
    private val trackersByCadence = cadences.associateWith {
        TrackManager(diagnosticsEnabled = false)
    }
    private var initialized = false
    private var inferenceOrdinal = 0
    private var disabledReason: String? = null

    init {
        require(cadences.isNotEmpty() && cadences.all { it > 0 })
        trackersByCadence.values.forEach {
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
            val trackedByCadence: Map<Int, List<TrackedPerson>>
            if (!initialized) {
                val prodDetections = productionDetections ?: return disable("MISSING_PRODUCTION_FIRST_FRAME")
                val prodTracked = productionTracked ?: return disable("MISSING_PRODUCTION_TRACKS_FIRST_FRAME")
                val cpuDetections = cpuMt4Detections ?: return disable("MISSING_CPU_MT4_FIRST_FRAME")
                val assignedIds = mapProductionIdsToCpuDetections(
                    productionDetections = prodDetections,
                    productionTracked = prodTracked,
                    cpuDetections = cpuDetections
                ) ?: return disable("CPU_MT4_FIRST_FRAME_ID_MAP_FAILED")

                trackedByCadence = trackersByCadence.mapValues { (_, tracker) ->
                    tracker.initializeWithAssignedIds(cpuDetections, assignedIds)
                }
                initialized = true
                inferenceOrdinal = 0
            } else if (!shouldInfer) {
                trackedByCadence = trackersByCadence.mapValues { (_, tracker) ->
                    tracker.predictWithoutObservation(ptsUs)
                }
            } else {
                val cpuDetections = cpuMt4Detections ?: return disable("MISSING_CPU_MT4_INFERENCE_FRAME")
                inferenceOrdinal++
                trackedByCadence = trackersByCadence.mapValues { (cadence, tracker) ->
                    if (!shouldObserve(inferenceOrdinal, cadence)) {
                        tracker.predictWithoutObservation(ptsUs)
                    } else if (cpuDetections.isEmpty()) {
                        tracker.predict(ptsUs)
                    } else {
                        tracker.update(cpuDetections, ptsUs)
                    }
                }
            }

            NativeDiagnostics.event(
                level = "INFO",
                component = "CrossDeviceTrackingDiagnostics",
                event = "YOLO_CPU_MT4_TRACK_SHADOW",
                fields = mapOf(
                    "job_id" to jobId,
                    "pts_us" to ptsUs,
                    "should_infer" to shouldInfer,
                    "cpu_inference_ordinal" to inferenceOrdinal,
                    "tracks_by_cadence" to trackedByCadence
                        .toSortedMap()
                        .mapKeys { it.key.toString() }
                        .mapValues { (_, tracks) -> trackSignature(tracks) }
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
        internal val DEFAULT_CADENCES = intArrayOf(1, 2, 3, 4, 6)

        internal fun shouldObserve(inferenceOrdinal: Int, cadence: Int): Boolean {
            require(inferenceOrdinal >= 0)
            require(cadence > 0)
            return inferenceOrdinal % cadence == 0
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
