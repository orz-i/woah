package com.danceanon.native.diagnostics

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackedPerson
import kotlin.math.roundToInt

/**
 * Debug-only shadow tracking matrix for cross-device validation.
 *
 * Both trackers consume the deterministic CPU-MT detector output and never feed rendering,
 * privacy, selection, or production TrackManager state. The raw tracker answers whether the
 * tiny remaining CPU geometry differences actually amplify into identity topology changes. The
 * stabilized tracker answers the same question after a deterministic 0.25px bbox/foot-Y grid.
 */
internal class CrossDeviceTrackingDiagnostics(
    private val jobId: String,
    fullBodyPersonIds: Set<Int>,
    faceOnlyPersonIds: Set<Int>,
    private val bboxGridPx: Float = DEFAULT_BBOX_GRID_PX
) {
    private val rawTracker = TrackManager(diagnosticsEnabled = false)
    private val stabilizedTracker = TrackManager(diagnosticsEnabled = false)
    private var initialized = false
    private var disabledReason: String? = null

    init {
        configureTracker(rawTracker, fullBodyPersonIds, faceOnlyPersonIds)
        configureTracker(stabilizedTracker, fullBodyPersonIds, faceOnlyPersonIds)
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
            val rawTracked: List<TrackedPerson>
            val stabilizedTracked: List<TrackedPerson>
            if (!initialized) {
                val prodDetections = productionDetections ?: return disable("MISSING_PRODUCTION_FIRST_FRAME")
                val prodTracked = productionTracked ?: return disable("MISSING_PRODUCTION_TRACKS_FIRST_FRAME")
                val cpuDetections = cpuMt4Detections ?: return disable("MISSING_CPU_MT4_FIRST_FRAME")
                val assignedIds = mapProductionIdsToCpuDetections(
                    productionDetections = prodDetections,
                    productionTracked = prodTracked,
                    cpuDetections = cpuDetections
                ) ?: return disable("CPU_MT4_FIRST_FRAME_ID_MAP_FAILED")

                rawTracked = rawTracker.initializeWithAssignedIds(cpuDetections, assignedIds)
                stabilizedTracked = stabilizedTracker.initializeWithAssignedIds(
                    stabilizeDetections(cpuDetections, bboxGridPx),
                    assignedIds
                )
                initialized = true
            } else if (!shouldInfer) {
                rawTracked = rawTracker.predictWithoutObservation(ptsUs)
                stabilizedTracked = stabilizedTracker.predictWithoutObservation(ptsUs)
            } else {
                val cpuDetections = cpuMt4Detections ?: return disable("MISSING_CPU_MT4_INFERENCE_FRAME")
                if (cpuDetections.isEmpty()) {
                    rawTracked = rawTracker.predict(ptsUs)
                    stabilizedTracked = stabilizedTracker.predict(ptsUs)
                } else {
                    rawTracked = rawTracker.update(cpuDetections, ptsUs)
                    stabilizedTracked = stabilizedTracker.update(
                        stabilizeDetections(cpuDetections, bboxGridPx),
                        ptsUs
                    )
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
                    "bbox_grid_px" to bboxGridPx,
                    "raw_tracks" to trackSignature(rawTracked),
                    "stabilized_tracks" to trackSignature(stabilizedTracked)
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
        internal const val DEFAULT_BBOX_GRID_PX = 0.25f

        internal fun stabilizeDetections(
            detections: List<PersonDetection>,
            gridPx: Float = DEFAULT_BBOX_GRID_PX
        ): List<PersonDetection> {
            require(gridPx > 0f)
            return detections.map { detection ->
                val bbox = detection.bbox
                val stableBbox = FloatRect(
                    left = quantize(bbox.left, gridPx),
                    top = quantize(bbox.top, gridPx),
                    right = quantize(bbox.right, gridPx),
                    bottom = quantize(bbox.bottom, gridPx)
                )
                detection.copy(
                    bbox = stableBbox,
                    footY = quantize(detection.footY, gridPx)
                )
            }
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

        private fun quantize(value: Float, gridPx: Float): Float =
            (value / gridPx).roundToInt() * gridPx
    }
}
