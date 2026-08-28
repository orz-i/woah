package com.danceanon.native.tracking

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrackingConfig(
    val maxMissedFrames: Int = 15,
    val postOcclusionGraceFrames: Int = 10,
    val minMatchScore: Float = 0.20f,
    val bboxIouWeight: Float = 0.35f,
    val maskIouWeight: Float = 0.25f,
    val motionWeight: Float = 0.25f,
    val directionWeight: Float = 0.15f,
    val occlusionOverlapRatio: Float = 0.30f,
    val occlusionMaxDurationFrames: Int = 90,
    val associationAmbiguityMargin: Float = 0.05f,
    val kalmanGatingThreshold: Float = 16.0f,
    val minSceneMotionInliers: Int = 2,
    val sceneMotionTolerance: Float = 25.0f,
    val maxGlobalShift: Float = 300.0f,
    val enableSceneMotionCompensation: Boolean = false,
    val foregroundFootYMarginRatio: Float = 0.05f,
    val privacyOccluderErosionRadius: Int = 1
)

enum class OcclusionGroupState {
    ACTIVE_OVERLAP,
    REACQUIRING
}

data class OcclusionGroup(
    val trackIds: MutableSet<Int>,
    val startedAtUs: Long,
    var lastOverlapTimestampUs: Long = startedAtUs,
    var state: OcclusionGroupState = OcclusionGroupState.ACTIVE_OVERLAP,
    var reacquireFrames: Int = 0
)

/**
 * Internal tracking representation enforcing strict separation between:
 * 1. Canonical observed segmentation (lastObservedMask, lastObservedBbox)
 * 2. Predicted motion state (currentPredictedBbox)
 * 3. Rendered privacy fallback mask (currentRenderMask)
 * 4. Separate counters for lostFrames, occludedFrames, and reacquireFrames.
 * 5. Current-frame fresh observation metadata (observedThisFrame, lastObservedFootY, currentObservedFootY).
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
    var age: Int = 1,
    var observedThisFrame: Boolean = false,
    var lastObservedFootY: Float = lastObservedBbox.bottom,
    var currentObservedFootY: Float? = null
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
            occludedByTrackIds = occludedByTrackIds.toSet(),
            observedThisFrame = observedThisFrame,
            footY = currentObservedFootY ?: lastObservedFootY
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
                state = TrackState.ACTIVE,
                observedThisFrame = true,
                lastObservedFootY = det.footY,
                currentObservedFootY = det.footY
            )
            tracks.add(track)
            nextTrackId = maxOf(nextTrackId, trackId + 1)
        }
        hasInitialized = true
        return tracks.map { it.toTrackedPerson() }
    }

    private fun updateOcclusionGroups(predictedTracks: List<InternalTrack>, timestampUs: Long) {
        // 1. Build adjacency graph of current spatial overlaps based on predicted boxes
        val overlapAdj = mutableMapOf<Int, MutableSet<Int>>()
        val validTracks = predictedTracks.filter { it.state != TrackState.REMOVED }
        for (t in validTracks) {
            overlapAdj[t.id] = mutableSetOf()
        }

        for (i in validTracks.indices) {
            val trackA = validTracks[i]
            for (j in i + 1 until validTracks.size) {
                val trackB = validTracks[j]
                val interArea = computeBBoxIntersectionArea(trackA.currentPredictedBbox, trackB.currentPredictedBbox)
                val minArea = minOf(
                    trackA.currentPredictedBbox.width * trackA.currentPredictedBbox.height,
                    trackB.currentPredictedBbox.width * trackB.currentPredictedBbox.height
                )
                val overlapRatio = if (minArea > 0f) interArea / minArea else 0f
                if (overlapRatio >= config.occlusionOverlapRatio) {
                    overlapAdj[trackA.id]?.add(trackB.id)
                    overlapAdj[trackB.id]?.add(trackA.id)
                }
            }
        }

        // 2. Find connected components on overlap graph (size >= 2)
        val visited = mutableSetOf<Int>()
        val activeComponents = mutableListOf<Set<Int>>()
        for (tId in overlapAdj.keys) {
            if (!visited.contains(tId)) {
                val component = mutableSetOf<Int>()
                val queue = ArrayDeque<Int>()
                queue.add(tId)
                visited.add(tId)
                while (queue.isNotEmpty()) {
                    val curr = queue.removeFirst()
                    component.add(curr)
                    for (neighbor in overlapAdj[curr].orEmpty()) {
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
                if (component.size >= 2) {
                    activeComponents.add(component)
                }
            }
        }

        // 3. Match active components to existing occlusion groups or create new ones
        val matchedGroupIndices = mutableSetOf<Int>()
        for (comp in activeComponents) {
            val existingIdx = occlusionGroups.indices.find { idx ->
                !matchedGroupIndices.contains(idx) && occlusionGroups[idx].trackIds.any { comp.contains(it) }
            }
            if (existingIdx != null) {
                val existing = occlusionGroups[existingIdx]
                existing.trackIds.clear()
                existing.trackIds.addAll(comp)
                existing.lastOverlapTimestampUs = timestampUs
                existing.state = OcclusionGroupState.ACTIVE_OVERLAP
                existing.reacquireFrames = 0
                matchedGroupIndices.add(existingIdx)
            } else {
                val newGroup = OcclusionGroup(
                    trackIds = comp.toMutableSet(),
                    startedAtUs = timestampUs,
                    lastOverlapTimestampUs = timestampUs,
                    state = OcclusionGroupState.ACTIVE_OVERLAP,
                    reacquireFrames = 0
                )
                occlusionGroups.add(newGroup)
                matchedGroupIndices.add(occlusionGroups.size - 1)
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "TrackManager",
                    event = "OCCLUSION_GROUP_CREATE",
                    fields = mapOf(
                        "track_ids" to newGroup.trackIds.toList(),
                        "started_at_us" to newGroup.startedAtUs,
                        "pts_us" to timestampUs
                    )
                )
            }
        }

        // 4. For existing groups not in active overlap, transition to REACQUIRING immediately and clear stale occluders
        for (idx in occlusionGroups.indices) {
            if (!matchedGroupIndices.contains(idx)) {
                val group = occlusionGroups[idx]
                if (group.state == OcclusionGroupState.ACTIVE_OVERLAP) {
                    group.state = OcclusionGroupState.REACQUIRING
                    group.reacquireFrames = 1
                    for (tId in group.trackIds) {
                        predictedTracks.find { it.id == tId }?.occludedByTrackIds?.clear()
                    }
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "OCCLUSION_GROUP_REACQUIRE_START",
                        fields = mapOf(
                            "track_ids" to group.trackIds.toList(),
                            "started_at_us" to group.startedAtUs,
                            "last_overlap_us" to group.lastOverlapTimestampUs,
                            "pts_us" to timestampUs
                        )
                    )
                } else {
                    group.reacquireFrames++
                }
            }
        }

        // 5. Purge exhausted or dead groups
        val groupsToRemove = occlusionGroups.filter { group ->
            val aliveTracks = predictedTracks.filter { group.trackIds.contains(it.id) && it.state != TrackState.REMOVED }
            val isExhausted = (group.state == OcclusionGroupState.REACQUIRING && group.reacquireFrames > config.postOcclusionGraceFrames)
            aliveTracks.size < 2 || isExhausted
        }
        for (group in groupsToRemove) {
            NativeDiagnostics.event(
                level = "INFO",
                component = "TrackManager",
                event = "OCCLUSION_GROUP_END",
                fields = mapOf(
                    "track_ids" to group.trackIds.toList(),
                    "state" to group.state.name,
                    "reacquire_frames" to group.reacquireFrames,
                    "duration_us" to (timestampUs - group.startedAtUs).coerceAtLeast(0L),
                    "pts_us" to timestampUs
                )
            )
        }
        occlusionGroups.removeAll(groupsToRemove.toSet())
    }

    override fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson> {
        if (!hasInitialized) {
            return initialize(detections)
        }

        if (detections.isEmpty()) {
            return predict(timestampUs)
        }

        // Reset per-frame observation state
        for (t in tracks) {
            t.observedThisFrame = false
            t.currentObservedFootY = null
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
                    state = TrackState.ACTIVE,
                    observedThisFrame = true,
                    lastObservedFootY = det.footY,
                    currentObservedFootY = det.footY
                )
                tracks.add(newTrack)
            }
            return tracks.map { it.toTrackedPerson() }
        }

        // 1. Predict all tracks with 8D Kalman Filter & Scene Motion Compensation
        val rawPredictedBoxes = tracks.map { track ->
            val pred = track.kalman.predict(timestampUs)
            track.age++
            track.currentPredictedBbox = pred
            pred
        }

        val sceneMotion = SceneMotionEstimator.estimateSceneMotion(tracks, detections, config)
        if (sceneMotion.inlierCount > 0 || sceneMotion.confidence > 0f) {
            NativeDiagnostics.event(
                level = "INFO",
                component = "TrackManager",
                event = "SCENE_MOTION_ESTIMATED",
                fields = mapOf(
                    "dx" to sceneMotion.dx,
                    "dy" to sceneMotion.dy,
                    "inliers" to sceneMotion.inlierCount,
                    "confidence" to sceneMotion.confidence,
                    "applied" to config.enableSceneMotionCompensation,
                    "pts_us" to timestampUs
                )
            )
        }

        if (config.enableSceneMotionCompensation && sceneMotion.confidence > 0f && (abs(sceneMotion.dx) > 0.5f || abs(sceneMotion.dy) > 0.5f)) {
            for (i in tracks.indices) {
                tracks[i].currentPredictedBbox = rawPredictedBoxes[i].offset(sceneMotion.dx, sceneMotion.dy)
            }
        }

        // 2. Update active/reacquiring occlusion groups
        updateOcclusionGroups(tracks, timestampUs)

        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        // Cache predicted warped masks once per track
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

        fun computeMatchScore(track: InternalTrack, det: PersonDetection): Float {
            val predBox = track.currentPredictedBbox
            val detBox = det.bbox
            val bIoU = computeBBoxIoU(predBox, detBox)
            val gateDist = track.kalman.gatingDistance(det.bbox)

            val dxPred = detBox.centerX - predBox.centerX
            val dyPred = detBox.centerY - predBox.centerY
            val distToPred = sqrt(dxPred * dxPred + dyPred * dyPred)

            val dxObs = detBox.centerX - track.lastObservedBbox.centerX
            val dyObs = detBox.centerY - track.lastObservedBbox.centerY
            val distToObs = sqrt(dxObs * dxObs + dyObs * dyObs)

            val dist = if (track.state == TrackState.REACQUIRING || track.state == TrackState.LOST) minOf(distToPred, distToObs) else distToPred
            val inGroup = occlusionGroups.any { it.trackIds.contains(track.id) }
            val isFlexibleGate = track.state == TrackState.REACQUIRING || track.state == TrackState.LOST || inGroup
            val maxAllowedDist = if (isFlexibleGate) {
                max(predBox.width * 2.5f, predBox.height * 2.0f)
            } else {
                max(predBox.width * 1.5f, predBox.height * 0.8f)
            }

            // Physical Gate: Reject candidates with no spatial overlap that exceed physical displacement limit
            if (bIoU <= 0f && dist > maxAllowedDist) {
                return 0f
            }

            val predMask = if (track.state == TrackState.REACQUIRING && distToObs < distToPred) {
                track.lastObservedMask
            } else {
                getPredictedMask(track)
            }
            val mIoU = computeMaskIoU(predMask, det.mask)
            val distScore = (1.0f - (dist / maxAllowedDist)).coerceIn(0f, 1f)
            val motionScore = maxOf(distScore, (1.0f - (gateDist / (config.kalmanGatingThreshold * 2f))).coerceIn(0f, 1f))

            val vx = track.kalman.state[4]
            val vy = track.kalman.state[5]
            val motionDx = detBox.centerX - track.lastObservedBbox.centerX
            val motionDy = detBox.centerY - track.lastObservedBbox.centerY
            val dirXMatch = if (abs(vx) > 30f && abs(motionDx) > 10f) (vx * motionDx > 0) else null
            val dirYMatch = if (abs(vy) > 30f && abs(motionDy) > 10f) (vy * motionDy > 0) else null
            val directionScore = when {
                dirXMatch == true && dirYMatch == true -> 1.0f
                dirXMatch == true && dirYMatch == null -> 1.0f
                dirXMatch == null && dirYMatch == true -> 1.0f
                dirXMatch == false && dirYMatch == false -> 0.0f
                dirXMatch == false || dirYMatch == false -> 0.0f
                else -> 0.5f
            }

            val rawScore = config.bboxIouWeight * bIoU +
                           config.maskIouWeight * mIoU +
                           config.motionWeight * motionScore +
                           config.directionWeight * directionScore

            // Penalize candidate if both track and detection have non-null masks with negligible spatial overlap
            val maskPenalty = if (predMask != null && det.mask != null && mIoU < 0.05f) 0.4f else 1.0f

            return (rawScore * maskPenalty).coerceIn(0f, 1f)
        }

        // 3. PHASE B: OCCLUSION / REACQUIRING GROUP-FIRST ASSOCIATION WITH RESERVATION ISOLATION
        val reservedGroupTrackIndices = mutableSetOf<Int>()
        val reservedGroupDetectionIndices = mutableSetOf<Int>()

        for (group in occlusionGroups) {
            val groupTrackIndices = tracks.indices.filter { idx ->
                val t = tracks[idx]
                group.trackIds.contains(t.id) && !matchedTrackIndices.contains(idx) && t.state != TrackState.REMOVED
            }
            if (groupTrackIndices.isEmpty()) continue

            // Determine group bounding envelope encompassing predicted and last observed boxes with margin
            var envLeft = Float.MAX_VALUE
            var envTop = Float.MAX_VALUE
            var envRight = Float.MIN_VALUE
            var envBottom = Float.MIN_VALUE
            for (idx in groupTrackIndices) {
                val t = tracks[idx]
                val pBox = t.currentPredictedBbox
                val oBox = t.lastObservedBbox
                envLeft = minOf(envLeft, pBox.left, oBox.left)
                envTop = minOf(envTop, pBox.top, oBox.top)
                envRight = maxOf(envRight, pBox.right, oBox.right)
                envBottom = maxOf(envBottom, pBox.bottom, oBox.bottom)
            }
            val marginX = max((envRight - envLeft) * 0.50f, 100f)
            val marginY = max((envBottom - envTop) * 0.50f, 100f)
            val groupEnvelope = FloatRect(envLeft - marginX, envTop - marginY, envRight + marginX, envBottom + marginY)

            val candidateDetectionIndices = detections.indices.filter { dIdx ->
                !matchedDetectionIndices.contains(dIdx) &&
                computeBBoxIntersectionArea(groupEnvelope, detections[dIdx].bbox) > 0f
            }

            // Reserve group tracks to isolate from Global Hungarian unless successfully matched
            for (tIdx in groupTrackIndices) {
                reservedGroupTrackIndices.add(tIdx)
            }

            if (candidateDetectionIndices.isEmpty()) continue

            // Score matrix for group
            val scoreMatrix = Array(groupTrackIndices.size) { r ->
                val track = tracks[groupTrackIndices[r]]
                FloatArray(candidateDetectionIndices.size) { c ->
                    computeMatchScore(track, detections[candidateDetectionIndices[c]])
                }
            }

            val groupCostMatrix = Array(groupTrackIndices.size) { r ->
                FloatArray(candidateDetectionIndices.size) { c ->
                    (1.0f - scoreMatrix[r][c]).coerceIn(0f, 1f)
                }
            }

            val maxCost = (1.0f - config.minMatchScore).coerceIn(0.1f, 0.90f)
            val matchResult = HungarianSolver.match(groupCostMatrix, maxCostThreshold = maxCost)

            val matchedGroupRows = mutableSetOf<Int>()
            val matchedGroupCols = mutableSetOf<Int>()

            for (match in matchResult.matches) {
                val r = match.first
                val c = match.second
                val tIdx = groupTrackIndices[r]
                val dIdx = candidateDetectionIndices[c]
                val track = tracks[tIdx]
                val det = detections[dIdx]

                val assignedScore = scoreMatrix[r][c]

                // Mutual / Reciprocal-Best Commitment Check.
                // A privacy-sensitive identity commit must be the actual best edge
                // in both directions and must be separated from the next-best
                // alternative. "Within ambiguity margin of best" is still ambiguous.
                val rowBest = (0 until candidateDetectionIndices.size).map { scoreMatrix[r][it] }.maxOrNull() ?: 0f
                val colBest = (0 until groupTrackIndices.size).map { scoreMatrix[it][c] }.maxOrNull() ?: 0f
                val rowSecondBest = (0 until candidateDetectionIndices.size)
                    .filter { it != c }
                    .map { scoreMatrix[r][it] }
                    .maxOrNull()
                val colSecondBest = (0 until groupTrackIndices.size)
                    .filter { it != r }
                    .map { scoreMatrix[it][c] }
                    .maxOrNull()

                val epsilon = 1e-6f
                val isRowTop = assignedScore >= (rowBest - epsilon)
                val isColTop = assignedScore >= (colBest - epsilon)
                val rowMargin = rowSecondBest?.let { assignedScore - it } ?: Float.POSITIVE_INFINITY
                val colMargin = colSecondBest?.let { assignedScore - it } ?: Float.POSITIVE_INFINITY
                val hasRowSeparation = rowMargin >= config.associationAmbiguityMargin
                val hasColSeparation = colMargin >= config.associationAmbiguityMargin
                val isScoreValid = assignedScore >= config.minMatchScore

                val isReciprocalBest = isScoreValid && isRowTop && isColTop && hasRowSeparation && hasColSeparation

                if (!isReciprocalBest) {
                    NativeDiagnostics.event(
                        level = "WARN",
                        component = "TrackManager",
                        event = "ASSOCIATION_AMBIGUOUS",
                        fields = mapOf(
                            "group_id" to group.trackIds.toList(),
                            "track_id" to track.id,
                            "det_index" to dIdx,
                            "assigned_score" to assignedScore,
                            "row_best" to rowBest,
                            "col_best" to colBest,
                            "row_second_best" to rowSecondBest,
                            "col_second_best" to colSecondBest,
                            "row_margin" to rowMargin,
                            "col_margin" to colMargin,
                            "required_margin" to config.associationAmbiguityMargin,
                            "min_score" to config.minMatchScore
                        )
                    )
                    // Defer identity commitment: keep track in REACQUIRING / OCCLUDED, reserve detection
                    if (track.state == TrackState.OCCLUDED || track.state == TrackState.ACTIVE) {
                        track.state = TrackState.REACQUIRING
                    }
                    reservedGroupTrackIndices.add(tIdx)
                    reservedGroupDetectionIndices.add(dIdx)
                    continue
                }

                // High confidence reciprocal-best commit
                matchedTrackIndices.add(tIdx)
                matchedDetectionIndices.add(dIdx)
                matchedGroupRows.add(r)
                matchedGroupCols.add(c)
                reservedGroupTrackIndices.remove(tIdx)

                val prevState = track.state
                val associationPredictedBbox = track.currentPredictedBbox
                val associationLastObservedBbox = track.lastObservedBbox
                val associationBBoxIoU = computeBBoxIoU(associationPredictedBbox, det.bbox)
                val associationMaskIoU = computeMaskIoU(getPredictedMask(track), det.mask)
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
                track.observedThisFrame = true
                track.currentObservedFootY = det.footY
                track.lastObservedFootY = det.footY
                track.kalman.update(det.bbox, timestampUs)

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "TrackManager",
                    event = "GROUP_ASSIGNMENT_COMMIT",
                    fields = mapOf(
                        "group_id" to group.trackIds.toList(),
                        "track_id" to track.id,
                        "det_index" to dIdx,
                        "assigned_score" to assignedScore,
                        "bbox_iou" to associationBBoxIoU,
                        "mask_iou" to associationMaskIoU,
                        "row_margin" to rowMargin,
                        "col_margin" to colMargin,
                        "prev_state" to prevState.name,
                        "predicted_bbox" to listOf(
                            associationPredictedBbox.left,
                            associationPredictedBbox.top,
                            associationPredictedBbox.right,
                            associationPredictedBbox.bottom
                        ),
                        "last_observed_bbox" to listOf(
                            associationLastObservedBbox.left,
                            associationLastObservedBbox.top,
                            associationLastObservedBbox.right,
                            associationLastObservedBbox.bottom
                        ),
                        "detection_bbox" to listOf(det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom),
                        "pts_us" to timestampUs
                    )
                )

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

            // Keep every uncommitted detection that still overlaps this active /
            // reacquiring group under group ownership for the current frame.
            // Otherwise a transient fragment or duplicate detection can fall
            // through to ordinary recovery and immediately mint a new identity
            // while the people are still overlapping. A genuinely independent
            // person becomes eligible again as soon as it no longer overlaps the
            // group's current/last-observed ownership region.
            for (c in candidateDetectionIndices.indices) {
                if (!matchedGroupCols.contains(c)) {
                    val dIdx = candidateDetectionIndices[c]
                    val detBox = detections[dIdx].bbox
                    val overlapsGroupMember = groupTrackIndices.any { tIdx ->
                        val groupTrack = tracks[tIdx]
                        computeBBoxIntersectionArea(groupTrack.currentPredictedBbox, detBox) > 0f ||
                            computeBBoxIntersectionArea(groupTrack.lastObservedBbox, detBox) > 0f
                    }
                    if (overlapsGroupMember) {
                        reservedGroupDetectionIndices.add(dIdx)
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "GROUP_DETECTION_RESERVED",
                            fields = mapOf(
                                "group_id" to group.trackIds.toList(),
                                "det_index" to dIdx,
                                "reason" to "UNCOMMITTED_OVERLAP",
                                "pts_us" to timestampUs
                            )
                        )
                    }
                }
            }
        }

        // 4. Global Hungarian Matching on Remaining Non-Group Unmatched Tracks & Detections
        val remainingTrackIndices = tracks.indices.filter {
            !matchedTrackIndices.contains(it) &&
                !reservedGroupTrackIndices.contains(it) &&
                (tracks[it].state == TrackState.ACTIVE || tracks[it].state == TrackState.NEW)
        }
        val remainingDetectionIndices = detections.indices.filter {
            !matchedDetectionIndices.contains(it) && !reservedGroupDetectionIndices.contains(it)
        }

        if (remainingTrackIndices.isNotEmpty() && remainingDetectionIndices.isNotEmpty()) {
            val scoreMatrix = Array(remainingTrackIndices.size) { r ->
                val track = tracks[remainingTrackIndices[r]]
                FloatArray(remainingDetectionIndices.size) { c ->
                    val det = detections[remainingDetectionIndices[c]]
                    computeMatchScore(track, det)
                }
            }
            val costMatrix = Array(remainingTrackIndices.size) { r ->
                FloatArray(remainingDetectionIndices.size) { c ->
                    (1.0f - scoreMatrix[r][c]).coerceIn(0f, 1f)
                }
            }

            val maxCost = (1.0f - config.minMatchScore).coerceIn(0.1f, 0.90f)
            val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = maxCost)

            for (match in matchResult.matches) {
                val tIdx = remainingTrackIndices[match.first]
                val dIdx = remainingDetectionIndices[match.second]
                val track = tracks[tIdx]
                val det = detections[dIdx]
                val assignedScore = scoreMatrix[match.first][match.second]

                matchedTrackIndices.add(tIdx)
                matchedDetectionIndices.add(dIdx)

                val prevState = track.state
                val associationPredictedBbox = track.currentPredictedBbox
                val associationLastObservedBbox = track.lastObservedBbox
                val associationBBoxIoU = computeBBoxIoU(associationPredictedBbox, det.bbox)
                val associationMaskIoU = computeMaskIoU(getPredictedMask(track), det.mask)
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
                track.observedThisFrame = true
                track.currentObservedFootY = det.footY
                track.lastObservedFootY = det.footY
                track.kalman.update(det.bbox, timestampUs)

                NativeDiagnostics.event(
                    level = "INFO",
                    component = "TrackManager",
                    event = "GLOBAL_ASSIGNMENT_COMMIT",
                    fields = mapOf(
                        "track_id" to track.id,
                        "det_index" to dIdx,
                        "assigned_score" to assignedScore,
                        "bbox_iou" to associationBBoxIoU,
                        "mask_iou" to associationMaskIoU,
                        "prev_state" to prevState.name,
                        "predicted_bbox" to listOf(
                            associationPredictedBbox.left,
                            associationPredictedBbox.top,
                            associationPredictedBbox.right,
                            associationPredictedBbox.bottom
                        ),
                        "last_observed_bbox" to listOf(
                            associationLastObservedBbox.left,
                            associationLastObservedBbox.top,
                            associationLastObservedBbox.right,
                            associationLastObservedBbox.bottom
                        ),
                        "detection_bbox" to listOf(det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom),
                        "pts_us" to timestampUs
                    )
                )

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

        // 5. Handle Unmatched Tracks (State Transitions)
        for (i in tracks.indices) {
            if (!matchedTrackIndices.contains(i)) {
                val track = tracks[i]
                val predBox = track.currentPredictedBbox

                // Check overlap with tracks that received fresh observations this frame
                val freshlyMatchedOtherTracks = tracks.filter { other ->
                    other.id != track.id && matchedTrackIndices.contains(tracks.indexOf(other)) &&
                    run {
                        val interArea = computeBBoxIntersectionArea(predBox, other.currentPredictedBbox)
                        val minArea = minOf(
                            predBox.width * predBox.height,
                            other.currentPredictedBbox.width * other.currentPredictedBbox.height
                        )
                        val ratio = if (minArea > 0f) interArea / minArea else 0f
                        val wasOccludedByOther = track.occludedByTrackIds.contains(other.id)
                        val otherNearLastObserved = computeBBoxIoU(track.lastObservedBbox, other.currentPredictedBbox) >= config.occlusionOverlapRatio
                        ratio >= config.occlusionOverlapRatio || (wasOccludedByOther && otherNearLastObserved)
                    }
                }

                val inActiveGroup = occlusionGroups.any { it.trackIds.contains(track.id) && it.state == OcclusionGroupState.ACTIVE_OVERLAP }
                val inReacquiringGroup = occlusionGroups.any { it.trackIds.contains(track.id) && it.state == OcclusionGroupState.REACQUIRING }
                val isOccluded = freshlyMatchedOtherTracks.isNotEmpty()

                if (isOccluded) {
                    track.occludedFrames++
                    track.state = TrackState.OCCLUDED
                    track.occludedByTrackIds.clear()
                    for (other in freshlyMatchedOtherTracks) {
                        track.occludedByTrackIds.add(other.id)
                    }

                    // Anchor occluded track's motion to the primary occluder to prevent unanchored spatial drift
                    val primaryOccluder = freshlyMatchedOtherTracks.first()
                    val occluderDeltaX = primaryOccluder.currentPredictedBbox.centerX - primaryOccluder.lastObservedBbox.centerX
                    val occluderDeltaY = primaryOccluder.currentPredictedBbox.centerY - primaryOccluder.lastObservedBbox.centerY
                    val anchoredPredBox = track.lastObservedBbox.offset(occluderDeltaX, occluderDeltaY)
                    track.currentPredictedBbox = anchoredPredBox

                    track.kalman.dampenVelocity(0.50f)

                    // STRICT REQUIREMENT: OCCLUDED state MUST NEVER generate rectangle fallback!
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = warpMask(
                            sourceMask = track.lastObservedMask!!,
                            prevBbox = track.lastObservedBbox,
                            predBbox = anchoredPredBox,
                            missedFrames = 0
                        )
                    }
                } else if (inActiveGroup || inReacquiringGroup || track.state == TrackState.OCCLUDED || track.state == TrackState.REACQUIRING) {
                    // Unresolved group identity or ended occlusion -> REACQUIRING.
                    // In particular, if every group assignment was rejected as
                    // ambiguous there may be no freshly matched occluder to mark
                    // this track OCCLUDED; it still must not fall through to LOST.
                    if (track.state == TrackState.OCCLUDED) {
                        track.state = TrackState.REACQUIRING
                        track.lostFrames = 0
                        track.reacquireFrames = 1
                        track.occludedByTrackIds.clear()
                        if (track.lastObservedMask != null) {
                            track.currentRenderMask = warpMask(
                                sourceMask = track.lastObservedMask!!,
                                prevBbox = track.lastObservedBbox,
                                predBbox = track.currentPredictedBbox,
                                missedFrames = 0
                            )
                        }
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "REACQUIRE_START",
                            fields = mapOf("track_id" to track.id)
                        )
                    } else if (track.state != TrackState.REACQUIRING) {
                        track.state = TrackState.REACQUIRING
                        track.lostFrames = 0
                        track.reacquireFrames = 1
                        track.occludedByTrackIds.clear()
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
                            fields = mapOf(
                                "track_id" to track.id,
                                "reason" to "AMBIGUOUS_GROUP",
                                "pts_us" to timestampUs
                            )
                        )
                    } else {
                        track.reacquireFrames++
                        track.occludedByTrackIds.clear()
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
                } else {
                    when (track.state) {
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
                            track.occludedByTrackIds.clear()
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
                        TrackState.REMOVED, TrackState.OCCLUDED, TrackState.REACQUIRING -> {}
                    }
                }
            }
        }

        // 6. Ordinary Recovery Association on Remaining Truly LOST Tracks
        val unassignedDetections = mutableListOf<Pair<Int, PersonDetection>>()
        for (c in detections.indices) {
            if (!matchedDetectionIndices.contains(c) && !reservedGroupDetectionIndices.contains(c)) {
                unassignedDetections.add(c to detections[c])
            }
        }

        val recoverableTracks = tracks.filter {
            it.state == TrackState.LOST &&
            !matchedTrackIndices.contains(tracks.indexOf(it))
        }
        val reclaimedTrackIds = mutableSetOf<Int>()

        for ((detIndex, det) in unassignedDetections) {
            var bestTrack: InternalTrack? = null
            var bestDist = Float.MAX_VALUE

            for (candTrack in recoverableTracks) {
                if (reclaimedTrackIds.contains(candTrack.id)) continue
                val dx = candTrack.currentPredictedBbox.centerX - det.bbox.centerX
                val dy = candTrack.currentPredictedBbox.centerY - det.bbox.centerY
                val dist = sqrt(dx * dx + dy * dy)

                val bIoU = computeBBoxIoU(candTrack.currentPredictedBbox, det.bbox)
                val refDim = max(
                    max(candTrack.currentPredictedBbox.width, candTrack.currentPredictedBbox.height),
                    max(det.bbox.width, det.bbox.height)
                )

                // LOST recovery must follow the current motion prediction rather
                // than becoming more permissive with age. If the Kalman state has
                // advanced meaningfully from lastObserved, require the candidate
                // to make progress in that predicted direction (or overlap the
                // predicted box). This prevents a passer occupying the stale
                // lastObserved location from stealing the LOST identity.
                val predDx = candTrack.currentPredictedBbox.centerX - candTrack.lastObservedBbox.centerX
                val predDy = candTrack.currentPredictedBbox.centerY - candTrack.lastObservedBbox.centerY
                val predTravel = sqrt(predDx * predDx + predDy * predDy)
                val candDx = det.bbox.centerX - candTrack.lastObservedBbox.centerX
                val candDy = det.bbox.centerY - candTrack.lastObservedBbox.centerY
                val predictionProgress = if (predTravel > 1e-4f) {
                    (candDx * predDx + candDy * predDy) / (predTravel * predTravel)
                } else {
                    1.0f
                }
                val hasMeaningfulPrediction = predTravel >= refDim * 0.10f
                val motionConsistent = !hasMeaningfulPrediction || bIoU > 0.05f || predictionProgress >= 0.25f

                val maxRecoverDist = refDim * 0.8f
                val isNearby = bIoU > 0.05f || dist < maxRecoverDist

                if (isNearby && motionConsistent && dist < bestDist) {
                    bestDist = dist
                    bestTrack = candTrack
                }
            }

            if (bestTrack != null) {
                val recoveryPredictedBbox = bestTrack.currentPredictedBbox
                val recoveryLastObservedBbox = bestTrack.lastObservedBbox
                val recoveryBBoxIoU = computeBBoxIoU(recoveryPredictedBbox, det.bbox)
                val recoveryMaskIoU = computeMaskIoU(getPredictedMask(bestTrack), det.mask)
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
                bestTrack.observedThisFrame = true
                bestTrack.currentObservedFootY = det.footY
                bestTrack.lastObservedFootY = det.footY
                bestTrack.kalman.update(det.bbox, timestampUs)
                reclaimedTrackIds.add(bestTrack.id)
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "TrackManager",
                    event = "ORDINARY_RECOVERY_COMMIT",
                    fields = mapOf(
                        "track_id" to bestTrack.id,
                        "det_index" to detIndex,
                        "distance" to bestDist,
                        "bbox_iou" to recoveryBBoxIoU,
                        "mask_iou" to recoveryMaskIoU,
                        "predicted_bbox" to listOf(
                            recoveryPredictedBbox.left,
                            recoveryPredictedBbox.top,
                            recoveryPredictedBbox.right,
                            recoveryPredictedBbox.bottom
                        ),
                        "last_observed_bbox" to listOf(
                            recoveryLastObservedBbox.left,
                            recoveryLastObservedBbox.top,
                            recoveryLastObservedBbox.right,
                            recoveryLastObservedBbox.bottom
                        ),
                        "detection_bbox" to listOf(det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom),
                        "pts_us" to timestampUs
                    )
                )
            } else {
                val newTrackId = nextTrackId++
                val newTrack = InternalTrack(
                    id = newTrackId,
                    lastObservedBbox = det.bbox,
                    lastObservedMask = det.mask,
                    currentPredictedBbox = det.bbox,
                    currentRenderMask = det.mask,
                    confidence = det.confidence,
                    state = TrackState.ACTIVE,
                    observedThisFrame = true,
                    lastObservedFootY = det.footY,
                    currentObservedFootY = det.footY
                )
                tracks.add(newTrack)
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "TrackManager",
                    event = "NEW_TRACK_CREATED",
                    fields = mapOf(
                        "track_id" to newTrackId,
                        "det_index" to detIndex,
                        "bbox" to listOf(det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom),
                        "pts_us" to timestampUs
                    )
                )
            }
        }

        // 7. Filter out REMOVED tracks
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
                    val primaryOccluderId = track.occludedByTrackIds.firstOrNull()
                    val occluder = tracks.find { it.id == primaryOccluderId }
                    val anchoredPredBox = if (occluder != null) {
                        val dx = occluder.currentPredictedBbox.centerX - occluder.lastObservedBbox.centerX
                        val dy = occluder.currentPredictedBbox.centerY - occluder.lastObservedBbox.centerY
                        track.lastObservedBbox.offset(dx, dy)
                    } else {
                        track.lastObservedBbox
                    }
                    track.currentPredictedBbox = anchoredPredBox
                    track.kalman.dampenVelocity(0.50f)
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = warpMask(
                            sourceMask = track.lastObservedMask!!,
                            prevBbox = track.lastObservedBbox,
                            predBbox = anchoredPredBox,
                            missedFrames = 0
                        )
                    }
                } else if (track.state == TrackState.REACQUIRING) {
                    track.reacquireFrames++
                    track.kalman.dampenVelocity(0.70f)
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
            return warpMask(
                sourceMask = canonicalMask,
                prevBbox = observedBbox,
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

            val dilation = if (missedFrames > 10) 2 else if (missedFrames > 0) 1 else 0

            val tempArr = ByteArray(w * h)

            for (y in 0 until h) {
                val floatY = (y - predNormCenterY) / scaleY + prevNormCenterY
                val y0 = kotlin.math.floor(floatY).toInt()
                val y1 = y0 + 1
                val wy1 = (floatY - y0).coerceIn(0f, 1f)
                val wy0 = 1f - wy1

                for (x in 0 until w) {
                    val floatX = (x - predNormCenterX) / scaleX + prevNormCenterX
                    val x0 = kotlin.math.floor(floatX).toInt()
                    val x1 = x0 + 1
                    val wx1 = (floatX - x0).coerceIn(0f, 1f)
                    val wx0 = 1f - wx1

                    val v00 = if (x0 in 0 until w && y0 in 0 until h) (srcBuf.get(y0 * w + x0).toInt() and 0xFF) else 0
                    val v01 = if (x1 in 0 until w && y0 in 0 until h) (srcBuf.get(y0 * w + x1).toInt() and 0xFF) else 0
                    val v10 = if (x0 in 0 until w && y1 in 0 until h) (srcBuf.get(y1 * w + x0).toInt() and 0xFF) else 0
                    val v11 = if (x1 in 0 until w && y1 in 0 until h) (srcBuf.get(y1 * w + x1).toInt() and 0xFF) else 0

                    val interp = (v00 * wx0 + v01 * wx1) * wy0 + (v10 * wx0 + v11 * wx1) * wy1
                    tempArr[y * w + x] = interp.roundToInt().coerceIn(0, 255).toByte()
                }
            }

            // Apply slight organic dilation for LOST frames to prevent under-anonymization without rigid boxes
            if (dilation > 0) {
                val rad = dilation
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var maxVal: Byte = tempArr[y * w + x]
                        for (dy in -rad..rad) {
                            for (dx in -rad..rad) {
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
