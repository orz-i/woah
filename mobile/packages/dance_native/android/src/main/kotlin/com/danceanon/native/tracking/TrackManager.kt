package com.danceanon.native.tracking

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrackingConfig(
    val maxMissedFrames: Int = 15,
    val postOcclusionGraceFrames: Int = 10,
    val minMatchScore: Float = 0.15f,
    val bboxIouWeight: Float = 0.40f,
    val maskIouWeight: Float = 0.20f,
    val motionWeight: Float = 0.40f,
    val occlusionOverlapRatio: Float = 0.30f,
    val occlusionMaxDurationFrames: Int = 90,
    val associationAmbiguityMargin: Float = 0.05f
)

data class OcclusionGroup(
    val trackIds: MutableSet<Int>,
    val startedAtUs: Long,
    var lastActiveTimestampUs: Long = startedAtUs
)

/**
 * Internal tracking representation enforcing strict separation between:
 * 1. Canonical observed segmentation (lastObservedMask, lastObservedBbox)
 * 2. Predicted motion state (currentPredictedBbox)
 * 3. Rendered privacy fallback mask (currentRenderMask)
 * 4. Separate counters for lostFrames, occludedFrames, and reacquireFrames.
 */
class InternalTrack(
    val id: Int,
    var lastObservedBbox: FloatRect,
    var lastObservedMask: NativeMask?,
    var currentPredictedBbox: FloatRect = lastObservedBbox,
    var currentRenderMask: NativeMask? = lastObservedMask,
    var confidence: Float,
    val kalman: KalmanFilter = KalmanFilter(),
    var state: TrackState = TrackState.ACTIVE,
    var lostFrames: Int = 0,
    var occludedFrames: Int = 0,
    var reacquireFrames: Int = 0,
    val occludedByTrackIds: MutableSet<Int> = mutableSetOf(),
    var age: Int = 1
) {
    init {
        kalman.init(lastObservedBbox)
    }

    var missedFrames: Int
        get() = when (state) {
            TrackState.OCCLUDED -> occludedFrames
            TrackState.REACQUIRING -> reacquireFrames
            else -> lostFrames
        }
        set(value) {
            lostFrames = value
        }

    var bbox: FloatRect
        get() = currentPredictedBbox
        set(value) {
            currentPredictedBbox = value
        }

    var mask: NativeMask?
        get() = currentRenderMask
        set(value) {
            currentRenderMask = value
        }

    fun toTrackedPerson(): TrackedPerson {
        return TrackedPerson(
            id = id,
            bbox = currentPredictedBbox,
            mask = currentRenderMask,
            confidence = confidence,
            missedFrames = missedFrames,
            age = age,
            state = state,
            occludedByTrackIds = occludedByTrackIds.toSet()
        )
    }
}

class TrackManager(
    val config: TrackingConfig = TrackingConfig()
) : PersonTracker {

    private val tracks = mutableListOf<InternalTrack>()
    private val occlusionGroups = mutableListOf<OcclusionGroup>()
    private var nextTrackId = 0
    private var hasInitialized = false

    override fun initialize(detections: List<PersonDetection>): List<TrackedPerson> {
        val defaultIds = detections.indices.toList()
        return initializeWithAssignedIds(detections, defaultIds)
    }

    fun initializeWithAssignedIds(
        detections: List<PersonDetection>,
        assignedIds: List<Int>
    ): List<TrackedPerson> {
        tracks.clear()
        occlusionGroups.clear()
        nextTrackId = 0
        for ((index, det) in detections.withIndex()) {
            val trackId = if (index < assignedIds.size) assignedIds[index] else nextTrackId
            val track = InternalTrack(
                id = trackId,
                lastObservedBbox = det.bbox,
                lastObservedMask = det.mask,
                currentPredictedBbox = det.bbox,
                currentRenderMask = det.mask,
                confidence = det.confidence,
                state = TrackState.ACTIVE
            )
            tracks.add(track)
            nextTrackId = maxOf(nextTrackId, trackId + 1)
        }
        hasInitialized = true
        return tracks.map { it.toTrackedPerson() }
    }

    private fun updateOcclusionGroups(predictedTracks: List<InternalTrack>, timestampUs: Long) {
        for (i in predictedTracks.indices) {
            val trackA = predictedTracks[i]
            for (j in i + 1 until predictedTracks.size) {
                val trackB = predictedTracks[j]
                val interArea = computeBBoxIntersectionArea(trackA.currentPredictedBbox, trackB.currentPredictedBbox)
                val minArea = minOf(
                    trackA.currentPredictedBbox.width * trackA.currentPredictedBbox.height,
                    trackB.currentPredictedBbox.width * trackB.currentPredictedBbox.height
                )
                val overlapRatio = if (minArea > 0f) interArea / minArea else 0f

                if (overlapRatio >= config.occlusionOverlapRatio) {
                    val existingGroup = occlusionGroups.find {
                        it.trackIds.contains(trackA.id) || it.trackIds.contains(trackB.id)
                    }
                    if (existingGroup != null) {
                        existingGroup.trackIds.add(trackA.id)
                        existingGroup.trackIds.add(trackB.id)
                        existingGroup.lastActiveTimestampUs = timestampUs
                    } else {
                        occlusionGroups.add(
                            OcclusionGroup(
                                trackIds = mutableSetOf(trackA.id, trackB.id),
                                startedAtUs = timestampUs,
                                lastActiveTimestampUs = timestampUs
                            )
                        )
                    }
                }
            }
        }

        occlusionGroups.removeAll { group ->
            val groupTracks = predictedTracks.filter { group.trackIds.contains(it.id) }
            if (groupTracks.size < 2) {
                true
            } else {
                var anyPairOverlaps = false
                for (i in groupTracks.indices) {
                    for (j in i + 1 until groupTracks.size) {
                        val interArea = computeBBoxIntersectionArea(groupTracks[i].currentPredictedBbox, groupTracks[j].currentPredictedBbox)
                        val minArea = minOf(
                            groupTracks[i].currentPredictedBbox.width * groupTracks[i].currentPredictedBbox.height,
                            groupTracks[j].currentPredictedBbox.width * groupTracks[j].currentPredictedBbox.height
                        )
                        val ratio = if (minArea > 0f) interArea / minArea else 0f
                        if (ratio >= config.occlusionOverlapRatio * 0.5f) {
                            anyPairOverlaps = true
                            break
                        }
                    }
                    if (anyPairOverlaps) break
                }
                !anyPairOverlaps
            }
        }
    }

    override fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson> {
        if (!hasInitialized) {
            return initialize(detections)
        }

        if (detections.isEmpty()) {
            return predict(timestampUs)
        }

        if (tracks.isEmpty()) {
            for (det in detections) {
                val newTrack = InternalTrack(
                    id = nextTrackId++,
                    lastObservedBbox = det.bbox,
                    lastObservedMask = det.mask,
                    currentPredictedBbox = det.bbox,
                    currentRenderMask = det.mask,
                    confidence = det.confidence,
                    state = TrackState.ACTIVE
                )
                tracks.add(newTrack)
            }
            return tracks.map { it.toTrackedPerson() }
        }

        // 1. Predict all tracks with 8D Kalman Filter
        val predictedBoxes = tracks.map { track ->
            val pred = track.kalman.predict(timestampUs)
            track.age++
            track.currentPredictedBbox = pred
            pred
        }

        // 2. Update active occlusion groups based on spatial overlaps
        updateOcclusionGroups(tracks, timestampUs)

        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        // Cache predicted warped masks once per track to avoid redundant warping
        val predictedMaskCache = mutableMapOf<Int, NativeMask?>()
        fun getPredictedMask(track: InternalTrack): NativeMask? {
            return predictedMaskCache.getOrPut(track.id) {
                val src = track.lastObservedMask ?: return@getOrPut null
                warpMask(
                    sourceMask = src,
                    prevBbox = track.lastObservedBbox,
                    predBbox = track.currentPredictedBbox,
                    missedFrames = 0
                )
            }
        }

        // 3. Global Hungarian Matching on All Tracks & Detections
        if (tracks.isNotEmpty() && detections.isNotEmpty()) {
            val costMatrix = Array(tracks.size) { r ->
                val track = tracks[r]
                val predBox = predictedBoxes[r]
                val predMask = getPredictedMask(track)

                FloatArray(detections.size) { c ->
                    val det = detections[c]
                    val detBox = det.bbox

                    val bIoU = computeBBoxIoU(predBox, detBox)
                    val refDim = max(predBox.width, predBox.height)
                    val dx = predBox.centerX - detBox.centerX
                    val dy = predBox.centerY - detBox.centerY
                    val dist = sqrt(dx * dx + dy * dy)
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)

                    val isProximate = bIoU > 0f || dist < refDim * 1.2f || (absDx < refDim * 0.7f && absDy < refDim * 2.2f)
                    val mIoU = if (isProximate) computeMaskIoU(predMask, det.mask) else 0f

                    val motionScore = if (refDim > 0f) {
                        val weightedDist = sqrt(dx * dx * 2.5f + dy * dy * 0.6f)
                        (1.0f - (weightedDist / (refDim * 2.5f))).coerceIn(0f, 1f)
                    } else 0f

                    val matchScore = config.bboxIouWeight * bIoU +
                                     config.maskIouWeight * mIoU +
                                     config.motionWeight * motionScore

                    (1.0f - matchScore).coerceIn(0f, 1f)
                }
            }

            val maxCost = (1.0f - config.minMatchScore).coerceIn(0.1f, 0.90f)
            val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = maxCost)

            for (match in matchResult.matches) {
                val tIdx = match.first
                val dIdx = match.second
                val track = tracks[tIdx]
                val det = detections[dIdx]

                matchedTrackIndices.add(tIdx)
                matchedDetectionIndices.add(dIdx)

                val prevState = track.state
                track.lastObservedBbox = det.bbox
                track.lastObservedMask = det.mask ?: track.lastObservedMask
                track.currentPredictedBbox = det.bbox
                track.currentRenderMask = det.mask ?: track.lastObservedMask
                track.confidence = det.confidence
                track.lostFrames = 0
                track.occludedFrames = 0
                track.reacquireFrames = 0
                track.occludedByTrackIds.clear()
                track.state = TrackState.ACTIVE
                track.kalman.update(det.bbox, timestampUs)

                if (prevState == TrackState.OCCLUDED || prevState == TrackState.REACQUIRING) {
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = if (prevState == TrackState.REACQUIRING) "REACQUIRE_SUCCESS" else "OCCLUSION_END",
                        fields = mapOf(
                            "track_id" to track.id,
                            "prev_state" to prevState.name
                        )
                    )
                }
            }
        }

        // 4. Handle Unmatched Tracks (State Transitions)
        for (i in tracks.indices) {
            if (!matchedTrackIndices.contains(i)) {
                val track = tracks[i]
                val predBox = predictedBoxes[i]
                track.currentPredictedBbox = predBox

                // Check overlap with any active or matched other track
                val overlappingOtherTracks = tracks.filter { other ->
                    other.id != track.id && (other.state == TrackState.ACTIVE || matchedTrackIndices.contains(tracks.indexOf(other))) &&
                    run {
                        val interArea = computeBBoxIntersectionArea(predBox, other.currentPredictedBbox)
                        val minArea = minOf(
                            predBox.width * predBox.height,
                            other.currentPredictedBbox.width * other.currentPredictedBbox.height
                        )
                        val ratio = if (minArea > 0f) interArea / minArea else 0f
                        ratio >= config.occlusionOverlapRatio
                    }
                }

                val isOccluded = overlappingOtherTracks.isNotEmpty()

                if (isOccluded) {
                    track.occludedFrames++
                    track.state = TrackState.OCCLUDED
                    track.occludedByTrackIds.clear()
                    for (other in overlappingOtherTracks) {
                        track.occludedByTrackIds.add(other.id)
                    }
                    track.kalman.dampenVelocity(0.70f)

                    // STRICT REQUIREMENT: OCCLUDED state MUST NEVER generate rectangle fallback!
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = warpMask(
                            sourceMask = track.lastObservedMask!!,
                            prevBbox = track.lastObservedBbox,
                            predBbox = predBox,
                            missedFrames = 0
                        )
                    }
                } else {
                    // Occlusion group cleared / overlap ended
                    when (track.state) {
                        TrackState.OCCLUDED -> {
                            // Transition to REACQUIRING
                            track.state = TrackState.REACQUIRING
                            track.lostFrames = 0
                            track.reacquireFrames = 1
                            if (track.lastObservedMask != null) {
                                track.currentRenderMask = warpMask(
                                    sourceMask = track.lastObservedMask!!,
                                    prevBbox = track.lastObservedBbox,
                                    predBbox = predBox,
                                    missedFrames = 0
                                )
                            }
                            NativeDiagnostics.event(
                                level = "INFO",
                                component = "TrackManager",
                                event = "REACQUIRE_START",
                                fields = mapOf("track_id" to track.id)
                            )
                        }
                        TrackState.REACQUIRING -> {
                            track.reacquireFrames++
                            if (track.reacquireFrames <= config.postOcclusionGraceFrames) {
                                // Continue REACQUIRING grace period
                                if (track.lastObservedMask != null) {
                                    track.currentRenderMask = warpMask(
                                        sourceMask = track.lastObservedMask!!,
                                        prevBbox = track.lastObservedBbox,
                                        predBbox = predBox,
                                        missedFrames = 0
                                    )
                                }
                            } else {
                                // Reacquire grace period exhausted -> transition to LOST
                                track.state = TrackState.LOST
                                track.lostFrames = 1
                                track.occludedByTrackIds.clear()
                                if (track.lastObservedMask != null) {
                                    track.currentRenderMask = updateLostMask(
                                        canonicalMask = track.lastObservedMask!!,
                                        observedBbox = track.lastObservedBbox,
                                        predBbox = predBox,
                                        missedFrames = track.lostFrames
                                    )
                                }
                                NativeDiagnostics.event(
                                    level = "WARN",
                                    component = "TrackManager",
                                    event = "REACQUIRE_TIMEOUT",
                                    fields = mapOf(
                                        "track_id" to track.id,
                                        "grace_frames" to config.postOcclusionGraceFrames
                                    )
                                )
                            }
                        }
                        TrackState.ACTIVE, TrackState.NEW -> {
                            track.state = TrackState.LOST
                            track.lostFrames = 1
                            track.occludedByTrackIds.clear()
                            if (track.lastObservedMask != null) {
                                track.currentRenderMask = updateLostMask(
                                    canonicalMask = track.lastObservedMask!!,
                                    observedBbox = track.lastObservedBbox,
                                    predBbox = predBox,
                                    missedFrames = track.lostFrames
                                )
                            }
                        }
                        TrackState.LOST -> {
                            track.lostFrames++
                            if (track.lostFrames > config.maxMissedFrames) {
                                track.state = TrackState.REMOVED
                                NativeDiagnostics.event(
                                    level = "WARN",
                                    component = "TrackManager",
                                    event = "TRACK_REMOVED",
                                    fields = mapOf(
                                        "track_id" to track.id,
                                        "lost_frames" to track.lostFrames,
                                        "max_missed" to config.maxMissedFrames
                                    )
                                )
                            } else {
                                if (track.lastObservedMask != null) {
                                    track.currentRenderMask = updateLostMask(
                                        canonicalMask = track.lastObservedMask!!,
                                        observedBbox = track.lastObservedBbox,
                                        predBbox = predBox,
                                        missedFrames = track.lostFrames
                                    )
                                }
                            }
                        }
                        TrackState.REMOVED -> {}
                    }
                }
            }
        }

        // 5. Recovery association on remaining unassigned detections
        val unassignedDetections = mutableListOf<PersonDetection>()
        for (c in detections.indices) {
            if (!matchedDetectionIndices.contains(c)) {
                unassignedDetections.add(detections[c])
            }
        }

        val recoverableTracks = tracks.filter {
            (it.state == TrackState.LOST || it.state == TrackState.REACQUIRING) && !matchedTrackIndices.contains(tracks.indexOf(it))
        }
        val reclaimedTrackIds = mutableSetOf<Int>()

        for (det in unassignedDetections) {
            var bestTrack: InternalTrack? = null
            var bestDist = Float.MAX_VALUE

            for (candTrack in recoverableTracks) {
                if (reclaimedTrackIds.contains(candTrack.id)) continue
                val dx = candTrack.currentPredictedBbox.centerX - det.bbox.centerX
                val dy = candTrack.currentPredictedBbox.centerY - det.bbox.centerY
                val dist = sqrt(dx * dx + dy * dy)
                val bIoU = computeBBoxIoU(candTrack.currentPredictedBbox, det.bbox)
                val refDim = max(candTrack.currentPredictedBbox.width, candTrack.currentPredictedBbox.height)
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                val isNearby = bIoU > 0.05f || dist < refDim * 0.9f || (absDx < refDim * 0.5f && absDy < refDim * 2.5f)

                if (isNearby && dist < bestDist) {
                    bestDist = dist
                    bestTrack = candTrack
                }
            }

            if (bestTrack != null) {
                bestTrack.lastObservedBbox = det.bbox
                bestTrack.lastObservedMask = det.mask ?: bestTrack.lastObservedMask
                bestTrack.currentPredictedBbox = det.bbox
                bestTrack.currentRenderMask = det.mask ?: bestTrack.lastObservedMask
                bestTrack.confidence = det.confidence
                bestTrack.lostFrames = 0
                bestTrack.occludedFrames = 0
                bestTrack.reacquireFrames = 0
                bestTrack.occludedByTrackIds.clear()
                bestTrack.state = TrackState.ACTIVE
                bestTrack.kalman.update(det.bbox, timestampUs)
                reclaimedTrackIds.add(bestTrack.id)
            } else {
                val newTrack = InternalTrack(
                    id = nextTrackId++,
                    lastObservedBbox = det.bbox,
                    lastObservedMask = det.mask,
                    currentPredictedBbox = det.bbox,
                    currentRenderMask = det.mask,
                    confidence = det.confidence,
                    state = TrackState.ACTIVE
                )
                tracks.add(newTrack)
            }
        }

        // 6. Filter out REMOVED tracks
        tracks.removeAll { it.state == TrackState.REMOVED }

        return tracks.map { it.toTrackedPerson() }
    }

    fun predictWithoutObservation(timestampUs: Long): List<TrackedPerson> {
        return predictInternal(timestampUs, countAsDetectionMiss = false)
    }

    override fun predict(timestampUs: Long): List<TrackedPerson> {
        return predictInternal(timestampUs, countAsDetectionMiss = true)
    }

    private fun predictInternal(timestampUs: Long, countAsDetectionMiss: Boolean): List<TrackedPerson> {
        val activeOrLost = tracks.map { track ->
            val predBox = track.kalman.predict(timestampUs)
            track.currentPredictedBbox = predBox

            if (countAsDetectionMiss) {
                val inOcclusionGroup = occlusionGroups.any { it.trackIds.contains(track.id) }
                val isOccluded = inOcclusionGroup || track.state == TrackState.OCCLUDED

                if (isOccluded) {
                    track.occludedFrames++
                    track.state = TrackState.OCCLUDED
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = warpMask(
                            sourceMask = track.lastObservedMask!!,
                            prevBbox = track.lastObservedBbox,
                            predBbox = predBox,
                            missedFrames = 0
                        )
                    }
                } else if (track.state == TrackState.REACQUIRING) {
                    track.reacquireFrames++
                    if (track.reacquireFrames <= config.postOcclusionGraceFrames) {
                        if (track.lastObservedMask != null) {
                            track.currentRenderMask = warpMask(
                                sourceMask = track.lastObservedMask!!,
                                prevBbox = track.lastObservedBbox,
                                predBbox = predBox,
                                missedFrames = 0
                            )
                        }
                    } else {
                        track.state = TrackState.LOST
                        track.lostFrames = 1
                        track.occludedByTrackIds.clear()
                        if (track.lastObservedMask != null) {
                            track.currentRenderMask = updateLostMask(
                                canonicalMask = track.lastObservedMask!!,
                                observedBbox = track.lastObservedBbox,
                                predBbox = predBox,
                                missedFrames = track.lostFrames
                            )
                        }
                    }
                } else {
                    track.state = TrackState.LOST
                    track.lostFrames++
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = updateLostMask(
                            canonicalMask = track.lastObservedMask!!,
                            observedBbox = track.lastObservedBbox,
                            predBbox = predBox,
                            missedFrames = track.lostFrames
                        )
                    }
                    if (track.lostFrames > config.maxMissedFrames) {
                        track.state = TrackState.REMOVED
                    }
                }
            } else {
                // Prediction during skipped inference cadence (stride):
                if (track.lastObservedMask != null) {
                    track.currentRenderMask = warpMask(
                        sourceMask = track.lastObservedMask!!,
                        prevBbox = track.lastObservedBbox,
                        predBbox = predBox,
                        missedFrames = 0
                    )
                }
            }

            track.toTrackedPerson()
        }.filter { it.state != TrackState.REMOVED }

        tracks.removeAll { it.state == TrackState.REMOVED }

        return activeOrLost
    }

    override fun reset() {
        tracks.clear()
        occlusionGroups.clear()
        nextTrackId = 0
        hasInitialized = false
    }

    companion object {
        const val LOST_WARP_MAX_FRAMES = 3
        const val LOST_MARGIN_TIER1_RATIO = 0.15f // 15% margin for frames 4..10
        const val LOST_MARGIN_TIER2_RATIO = 0.25f // 25% margin for frames > 10

        fun computeBBoxIntersectionArea(boxA: FloatRect, boxB: FloatRect): Float {
            val interX1 = max(boxA.left, boxB.left)
            val interY1 = max(boxA.top, boxB.top)
            val interX2 = min(boxA.right, boxB.right)
            val interY2 = min(boxA.bottom, boxB.bottom)

            val interW = max(0f, interX2 - interX1)
            val interH = max(0f, interY2 - interY1)
            return interW * interH
        }

        fun computeBBoxIoU(boxA: FloatRect, boxB: FloatRect): Float {
            val interX1 = max(boxA.left, boxB.left)
            val interY1 = max(boxA.top, boxB.top)
            val interX2 = min(boxA.right, boxB.right)
            val interY2 = min(boxA.bottom, boxB.bottom)

            val interW = max(0f, interX2 - interX1)
            val interH = max(0f, interY2 - interY1)
            val interArea = interW * interH

            val areaA = boxA.width * boxA.height
            val areaB = boxB.width * boxB.height
            val unionArea = areaA + areaB - interArea

            return if (unionArea <= 0f) 0f else interArea / unionArea
        }

        fun computeMaskIoU(maskA: NativeMask?, maskB: NativeMask?): Float {
            if (maskA == null || maskB == null) return 0f
            if (maskA.width != maskB.width || maskA.height != maskB.height) return 0f

            val total = maskA.width * maskA.height
            val bufA = maskA.buffer
            val bufB = maskB.buffer
            bufA.rewind()
            bufB.rewind()

            var intersection = 0
            var union = 0
            for (i in 0 until total) {
                val a = (bufA.get(i).toInt() and 0xFF) > 128
                val b = (bufB.get(i).toInt() and 0xFF) > 128
                if (a && b) intersection++
                if (a || b) union++
            }

            return if (union == 0) 1.0f else intersection.toFloat() / union.toFloat()
        }

        fun updateLostMask(
            canonicalMask: NativeMask,
            observedBbox: FloatRect,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            if (missedFrames <= LOST_WARP_MAX_FRAMES) {
                return warpMask(
                    sourceMask = canonicalMask,
                    prevBbox = observedBbox,
                    predBbox = predBbox,
                    missedFrames = missedFrames
                )
            }

            return generateConservativeFallbackMask(
                sourceMask = canonicalMask,
                predBbox = predBbox,
                missedFrames = missedFrames
            )
        }

        fun generateConservativeFallbackMask(
            sourceMask: NativeMask,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            val w = sourceMask.width
            val h = sourceMask.height
            val mapper = sourceMask.mapper ?: com.danceanon.native.geometry.ModelCoordinateMapper(
                srcWidth = max(1, sourceMask.originalWidth),
                srcHeight = max(1, sourceMask.originalHeight),
                modelInputSize = 640,
                protoSize = w
            )

            val marginRatio = if (missedFrames <= 10) LOST_MARGIN_TIER1_RATIO else LOST_MARGIN_TIER2_RATIO
            val marginX = predBbox.width * marginRatio
            val marginY = predBbox.height * marginRatio

            val expandedBox = FloatRect(
                left = predBbox.left - marginX,
                top = predBbox.top - marginY,
                right = predBbox.right + marginX,
                bottom = predBbox.bottom + marginY
            )

            val pX1 = mapper.sourceToProtoX(expandedBox.left).roundToInt().coerceIn(0, w)
            val pY1 = mapper.sourceToProtoY(expandedBox.top).roundToInt().coerceIn(0, h)
            val pX2 = mapper.sourceToProtoX(expandedBox.right).roundToInt().coerceIn(0, w)
            val pY2 = mapper.sourceToProtoY(expandedBox.bottom).roundToInt().coerceIn(0, h)

            val minX = min(pX1, pX2)
            val maxX = max(pX1, pX2)
            val minY = min(pY1, pY2)
            val maxY = max(pY1, pY2)

            val dstBuf = ByteBuffer.allocateDirect(w * h)
            val tempArr = ByteArray(w * h)

            for (y in minY until maxY) {
                val rowOffset = y * w
                for (x in minX until maxX) {
                    tempArr[rowOffset + x] = 255.toByte()
                }
            }

            dstBuf.put(tempArr)
            dstBuf.rewind()

            return NativeMask(
                width = w,
                height = h,
                buffer = dstBuf,
                originalWidth = sourceMask.originalWidth,
                originalHeight = sourceMask.originalHeight,
                mapper = mapper
            )
        }

        fun warpMask(
            sourceMask: NativeMask,
            prevBbox: FloatRect,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            val w = sourceMask.width
            val h = sourceMask.height
            val srcBuf = sourceMask.buffer
            srcBuf.rewind()

            val dstBuf = ByteBuffer.allocateDirect(w * h)

            val prevW = max(1f, prevBbox.width)
            val prevH = max(1f, prevBbox.height)
            val predW = max(1f, predBbox.width)
            val predH = max(1f, predBbox.height)

            val scaleX = predW / prevW
            val scaleY = predH / prevH

            val mapper = sourceMask.mapper ?: com.danceanon.native.geometry.ModelCoordinateMapper(
                srcWidth = max(1, sourceMask.originalWidth),
                srcHeight = max(1, sourceMask.originalHeight),
                modelInputSize = 640,
                protoSize = w
            )

            val prevNormCenterX = mapper.sourceToProtoX(prevBbox.centerX)
            val prevNormCenterY = mapper.sourceToProtoY(prevBbox.centerY)
            val predNormCenterX = mapper.sourceToProtoX(predBbox.centerX)
            val predNormCenterY = mapper.sourceToProtoY(predBbox.centerY)

            val dilation = if (missedFrames in 1..3) 1 else 0

            val tempArr = ByteArray(w * h)

            for (y in 0 until h) {
                val srcY = ((y - predNormCenterY) / scaleY + prevNormCenterY).roundToInt()
                for (x in 0 until w) {
                    val srcX = ((x - predNormCenterX) / scaleX + prevNormCenterX).roundToInt()
                    if (srcX in 0 until w && srcY in 0 until h) {
                        tempArr[y * w + x] = srcBuf.get(srcY * w + srcX)
                    } else {
                        tempArr[y * w + x] = 0
                    }
                }
            }

            // Apply slight dilation for LOST frames to prevent under-anonymization
            if (dilation > 0) {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var maxVal: Byte = tempArr[y * w + x]
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val ny = y + dy
                                val nx = x + dx
                                if (nx in 0 until w && ny in 0 until h) {
                                    val b = tempArr[ny * w + nx]
                                    if ((b.toInt() and 0xFF) > (maxVal.toInt() and 0xFF)) {
                                        maxVal = b
                                    }
                                }
                            }
                        }
                        dstBuf.put(maxVal)
                    }
                }
            } else {
                dstBuf.put(tempArr)
            }

            dstBuf.rewind()
            return NativeMask(
                width = w,
                height = h,
                buffer = dstBuf,
                originalWidth = sourceMask.originalWidth,
                originalHeight = sourceMask.originalHeight,
                mapper = mapper
            )
        }
    }
}
