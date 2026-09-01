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

enum class PrivacySelectionClass {
    SELECTED,
    UNSELECTED
}

/**
 * Fresh YOLO evidence whose privacy class is known even though its exact track
 * identity is intentionally left unresolved. This is only emitted for balanced
 * occlusion groups where one privacy class has been fully committed already and
 * every remaining track belongs to the other class.
 */
data class FreshPrivacyClassEvidence(
    val selectionClass: PrivacySelectionClass,
    val detectionIndex: Int,
    val detection: PersonDetection,
    val residualTrackIds: Set<Int>,
    val conservativeUnknown: Boolean = false
)

/**
 * Current-frame YOLO geometry that is strong enough to move a protected
 * FACE_ONLY render anchor, but intentionally not strong enough to commit track
 * identity. This is emitted only for reciprocal-best protected candidates where
 * absolute bbox/mask evidence passes and identity commitment was deferred by an
 * ambiguity margin. Consumers must treat it as motion-only evidence.
 */
data class ProtectedTrackMotionEvidence(
    val trackId: Int,
    val detectionIndex: Int,
    val detection: PersonDetection,
    val assignedScore: Float,
    val bboxIou: Float,
    val maskIou: Float,
    val timestampUs: Long
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
    var framesSinceLastObservation: Int = 0,
    val occludedByTrackIds: MutableSet<Int> = mutableSetOf(),
    var occlusionMotionBbox: FloatRect? = null,
    var age: Int = 1,
    var observedThisFrame: Boolean = false,
    var observedOnPreviousFrame: Boolean = false,
    var hasFreshObservedMotion: Boolean = false,
    var freshObservedMotionDx: Float = 0f,
    var freshObservedMotionDy: Float = 0f,
    var lastObservedFootY: Float = lastObservedBbox.bottom,
    var currentObservedFootY: Float? = null,
    var lastReliableObservedMotionDx: Float = 0f,
    var lastReliableObservedMotionDy: Float = 0f,
    var lastReliableObservedMotionTimestampUs: Long = Long.MIN_VALUE,
    var offscreenDormant: Boolean = false
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
            framesSinceLastObservation = framesSinceLastObservation,
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
    private val protectedTrackIds = mutableSetOf<Int>()
    private val privacySelectedTrackIds = mutableSetOf<Int>()
    private val currentPrivacyClassEvidence = mutableListOf<FreshPrivacyClassEvidence>()
    private val currentStrictUnselectedPrivacyEvidence = mutableListOf<FreshPrivacyClassEvidence>()
    private val currentProtectedTrackMotionEvidence = mutableMapOf<Int, ProtectedTrackMotionEvidence>()
    private val currentUncertainOccluderTrackIdsByProtectedTrackId = mutableMapOf<Int, MutableSet<Int>>()
    private val currentPrivacySuppressedSelectedTrackIds = mutableSetOf<Int>()
    private val currentHardPrivacyClassByDetectionIndex = mutableMapOf<Int, PrivacySelectionClass>()
    private var nextTrackId = 0
    private var hasInitialized = false
    private var privacyOffscreenDormancyEnabled = false

    private fun isDormantMixedFullBodyIdentity(track: InternalTrack): Boolean {
        // In mixed FULL_BODY + FACE_ONLY mode, a protected FULL_BODY identity is
        // intentionally retained after its visible LOST mask expires so a later
        // strong recovery can keep the user's selected ID.  That tombstone must
        // not participate in occlusion groups or scene-motion estimation while
        // it has no renderable privacy evidence.  Otherwise an unrelated person
        // crossing the stale predicted box can pull the tombstone into
        // REACQUIRING and revive an old full-body mask before identity is proven.
        return privacyOffscreenDormancyEnabled &&
            privacySelectedTrackIds.contains(track.id) &&
            track.state == TrackState.LOST &&
            track.lostFrames > config.maxMissedFrames &&
            track.currentRenderMask == null
    }

    private fun suppressStaleMixedFullBodyMaskIfNeeded(track: InternalTrack, timestampUs: Long) {
        if (!privacyOffscreenDormancyEnabled ||
            !privacySelectedTrackIds.contains(track.id) ||
            track.observedThisFrame ||
            track.framesSinceLastObservation < MIXED_FULL_BODY_MAX_RENDER_MISS_FRAMES ||
            track.currentRenderMask == null
        ) {
            return
        }

        // The real mixed clip repeatedly showed a selected FULL_BODY identity
        // with no fresh observation but a warped canonical mask still visible
        // through OCCLUDED/REACQUIRING grace. In mixed mode that stale body mask
        // is particularly destructive because it can cover unrelated FACE_ONLY
        // people. Preserve the identity state and all recovery evidence, but stop
        // rendering the old segmentation after a very short real detection miss.
        // FULL_BODY-only callers never enable this mixed-mode policy.
        track.currentRenderMask = null
        if (track.framesSinceLastObservation == MIXED_FULL_BODY_MAX_RENDER_MISS_FRAMES) {
            NativeDiagnostics.event(
                level = "INFO",
                component = "TrackManager",
                event = "MIXED_FULL_BODY_STALE_MASK_SUPPRESSED",
                fields = mapOf(
                    "track_id" to track.id,
                    "state" to track.state.name,
                    "frames_since_last_observation" to track.framesSinceLastObservation,
                    "pts_us" to timestampUs
                )
            )
        }
    }

    private fun isLikelyProtectedOffscreenExit(track: InternalTrack): Boolean {
        // Offscreen dormancy is a FULL_BODY privacy-render policy. FACE_ONLY IDs
        // are identity-protected too, but their separate face fallback/sticker
        // lifecycle must not be altered by this full-body exit shortcut.
        if (!privacyOffscreenDormancyEnabled ||
            !privacySelectedTrackIds.contains(track.id) ||
            track.offscreenDormant
        ) return false
        val mask = track.lastObservedMask ?: return false
        val frameWidth = mask.originalWidth.toFloat().coerceAtLeast(2f)
        val frameHeight = mask.originalHeight.toFloat().coerceAtLeast(2f)
        val observed = track.lastObservedBbox
        val dx = track.lastReliableObservedMotionDx
        val dy = track.lastReliableObservedMotionDy
        val minHorizontalSpeed = max(4f, observed.width * OFFSCREEN_EXIT_MIN_STEP_RATIO)
        val minVerticalSpeed = max(4f, observed.height * OFFSCREEN_EXIT_MIN_STEP_RATIO)

        val leftExit =
            observed.left <= frameWidth * OFFSCREEN_EXIT_EDGE_RATIO &&
                dx <= -minHorizontalSpeed
        val rightExit =
            observed.right >= frameWidth * (1f - OFFSCREEN_EXIT_EDGE_RATIO) &&
                dx >= minHorizontalSpeed
        val topExit =
            observed.top <= frameHeight * OFFSCREEN_EXIT_EDGE_RATIO &&
                dy <= -minVerticalSpeed
        val bottomExit =
            observed.bottom >= frameHeight * (1f - OFFSCREEN_EXIT_EDGE_RATIO) &&
                dy >= minVerticalSpeed
        return leftExit || rightExit || topExit || bottomExit
    }

    private fun markProtectedOffscreenDormant(track: InternalTrack, timestampUs: Long) {
        track.offscreenDormant = true
        track.state = TrackState.LOST
        track.lostFrames = config.maxMissedFrames + 1
        track.reacquireFrames = 0
        track.occludedFrames = 0
        track.currentRenderMask = null
        track.occludedByTrackIds.clear()
        track.occlusionMotionBbox = null
        occlusionGroups.forEach { group -> group.trackIds.remove(track.id) }
        occlusionGroups.removeAll { it.trackIds.size < 2 }
        NativeDiagnostics.event(
            level = "INFO",
            component = "TrackManager",
            event = "PROTECTED_OFFSCREEN_DORMANT",
            fields = mapOf(
                "track_id" to track.id,
                "last_bbox" to listOf(
                    track.lastObservedBbox.left,
                    track.lastObservedBbox.top,
                    track.lastObservedBbox.right,
                    track.lastObservedBbox.bottom
                ),
                "predicted_bbox" to listOf(
                    track.currentPredictedBbox.left,
                    track.currentPredictedBbox.top,
                    track.currentPredictedBbox.right,
                    track.currentPredictedBbox.bottom
                ),
                "motion_dx" to track.lastReliableObservedMotionDx,
                "motion_dy" to track.lastReliableObservedMotionDy,
                "pts_us" to timestampUs
            )
        )
    }

    /**
     * Privacy-selected identities are durable identity slots. They may stop
     * rendering after the normal LOST grace window, but must not be destroyed
     * and later re-created under a different ID while the export is running.
     *
     * Legacy callers use the same ID set for identity protection and privacy
     * classification, so this method intentionally updates both sets.
     */
    fun setProtectedTrackIds(ids: Set<Int>) {
        setIdentityProtectedTrackIds(ids)
        setPrivacySelectedTrackIds(ids)
    }

    fun setIdentityProtectedTrackIds(ids: Set<Int>) {
        protectedTrackIds.clear()
        protectedTrackIds.addAll(ids)
    }

    fun setPrivacySelectedTrackIds(ids: Set<Int>) {
        privacySelectedTrackIds.clear()
        privacySelectedTrackIds.addAll(ids)
    }

    fun setPrivacyOffscreenDormancyEnabled(enabled: Boolean) {
        privacyOffscreenDormancyEnabled = enabled
    }

    fun getFreshPrivacyClassEvidence(): List<FreshPrivacyClassEvidence> = currentPrivacyClassEvidence

    fun getFreshStrictUnselectedPrivacyEvidence(): List<FreshPrivacyClassEvidence> =
        currentStrictUnselectedPrivacyEvidence

    fun getFreshProtectedTrackMotionEvidence(): List<ProtectedTrackMotionEvidence> =
        currentProtectedTrackMotionEvidence.values.toList()

    fun getMaxMissedFrames(): Int = config.maxMissedFrames

    fun getPrivacySuppressedSelectedTrackIds(): Set<Int> = currentPrivacySuppressedSelectedTrackIds

    fun getHardPrivacyClassByDetectionIndex(): Map<Int, PrivacySelectionClass> =
        currentHardPrivacyClassByDetectionIndex.toMap()

    private fun boundProtectedUnobservedPrediction(
        track: InternalTrack,
        predictedBbox: FloatRect
    ): FloatRect {
        if (!protectedTrackIds.contains(track.id)) return predictedBbox
        val anchor = track.occlusionMotionBbox ?: track.lastObservedBbox
        return boundPredictionAroundAnchor(
            anchor = anchor,
            predicted = predictedBbox,
            maxCenterTravelRatio = PROTECTED_UNOBSERVED_MAX_CENTER_TRAVEL_RATIO,
            minScale = PROTECTED_UNOBSERVED_MIN_SCALE,
            maxScale = PROTECTED_UNOBSERVED_MAX_SCALE
        )
    }

    private fun privacyClassForTrackId(trackId: Int): PrivacySelectionClass =
        if (privacySelectedTrackIds.contains(trackId)) {
            PrivacySelectionClass.SELECTED
        } else {
            PrivacySelectionClass.UNSELECTED
        }

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
        currentPrivacyClassEvidence.clear()
        currentStrictUnselectedPrivacyEvidence.clear()
        currentProtectedTrackMotionEvidence.clear()
        currentUncertainOccluderTrackIdsByProtectedTrackId.clear()
        currentPrivacySuppressedSelectedTrackIds.clear()
        currentHardPrivacyClassByDetectionIndex.clear()
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
            currentHardPrivacyClassByDetectionIndex[index] = privacyClassForTrackId(trackId)
            nextTrackId = maxOf(nextTrackId, trackId + 1)
        }
        hasInitialized = true
        return tracks.map { it.toTrackedPerson() }
    }

    private fun updateOcclusionGroups(predictedTracks: List<InternalTrack>, timestampUs: Long) {
        // 1. Build adjacency graph of current spatial overlaps based on predicted boxes
        val overlapAdj = mutableMapOf<Int, MutableSet<Int>>()
        val validTracks = predictedTracks.filter {
            it.state != TrackState.REMOVED &&
                !it.offscreenDormant &&
                !isDormantMixedFullBodyIdentity(it)
        }
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
                val previouslySameGroup = occlusionGroups.any { group ->
                    group.trackIds.contains(trackA.id) && group.trackIds.contains(trackB.id)
                }
                if (
                    abs(overlapRatio - config.occlusionOverlapRatio) <= OCCLUSION_OVERLAP_EDGE_TELEMETRY_MARGIN ||
                    (previouslySameGroup && overlapRatio < config.occlusionOverlapRatio)
                ) {
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "OCCLUSION_PAIR_OVERLAP_EDGE",
                        fields = mapOf(
                            "track_a_id" to trackA.id,
                            "track_b_id" to trackB.id,
                            "overlap_ratio" to overlapRatio,
                            "threshold" to config.occlusionOverlapRatio,
                            "intersection_area" to interArea,
                            "min_area" to minArea,
                            "previously_same_group" to previouslySameGroup,
                            "track_a_predicted_bbox" to listOf(
                                trackA.currentPredictedBbox.left,
                                trackA.currentPredictedBbox.top,
                                trackA.currentPredictedBbox.right,
                                trackA.currentPredictedBbox.bottom
                            ),
                            "track_b_predicted_bbox" to listOf(
                                trackB.currentPredictedBbox.left,
                                trackB.currentPredictedBbox.top,
                                trackB.currentPredictedBbox.right,
                                trackB.currentPredictedBbox.bottom
                            ),
                            "pts_us" to timestampUs
                        )
                    )
                }
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
                val previousTrackIds = existing.trackIds.toSet()
                if (previousTrackIds != comp) {
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "OCCLUSION_GROUP_MEMBERSHIP_CHANGE",
                        fields = mapOf(
                            "previous_track_ids" to previousTrackIds.toList().sorted(),
                            "new_track_ids" to comp.toList().sorted(),
                            "group_started_at_us" to existing.startedAtUs,
                            "pts_us" to timestampUs
                        )
                    )
                }
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
            val aliveTracks = predictedTracks.filter {
                group.trackIds.contains(it.id) &&
                    it.state != TrackState.REMOVED &&
                    !it.offscreenDormant &&
                    !isDormantMixedFullBodyIdentity(it)
            }
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

        currentPrivacyClassEvidence.clear()
        currentStrictUnselectedPrivacyEvidence.clear()
        currentProtectedTrackMotionEvidence.clear()
        currentUncertainOccluderTrackIdsByProtectedTrackId.clear()
        currentPrivacySuppressedSelectedTrackIds.clear()
        currentHardPrivacyClassByDetectionIndex.clear()

        if (detections.isEmpty()) {
            return predict(timestampUs)
        }

        // Reset per-frame observation state
        for (t in tracks) {
            t.framesSinceLastObservation = (t.framesSinceLastObservation + 1).coerceAtMost(Int.MAX_VALUE)
            t.observedOnPreviousFrame = t.observedThisFrame
            t.observedThisFrame = false
            t.hasFreshObservedMotion = false
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

        val sceneMotion = SceneMotionEstimator.estimateSceneMotion(
            tracks.filter {
                !it.offscreenDormant && !isDormantMixedFullBodyIdentity(it)
            },
            detections,
            config
        )
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

        fun computePredictedMaskIoU(track: InternalTrack, detMask: NativeMask?): Float {
            val src = track.lastObservedMask ?: return 0f
            return computeWarpedMaskIoU(
                sourceMask = src,
                prevBbox = track.lastObservedBbox,
                predBbox = track.currentPredictedBbox,
                candidateMask = detMask,
                sampleStride = ASSOCIATION_MASK_IOU_SAMPLE_STRIDE
            )
        }

        fun computeMatchScore(track: InternalTrack, det: PersonDetection): Float {
            val predBox = track.currentPredictedBbox
            val detBox = det.bbox
            val bIoU = computeBBoxIoU(predBox, detBox)
            val gateDist = track.kalman.gatingDistance(det.bbox)

            val dxPred = detBox.centerX - predBox.centerX
            val dyPred = detBox.centerY - predBox.centerY
            val distToPred = sqrt(dxPred * dxPred + dyPred * dyPred)

            // Association is prediction-first.  A REACQUIRING identity must not
            // be pulled back toward its stale lastObserved location after two
            // people cross: that makes A prefer the person who moved into A's
            // old position.  The Kalman prediction already carries the motion
            // evidence; lastObserved remains useful for direction diagnostics,
            // but is not a competing distance shortcut for identity commitment.
            val dist = distToPred
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

            val mIoU = computePredictedMaskIoU(track, det.mask)
            val distScore = (1.0f - (dist / maxAllowedDist)).coerceIn(0f, 1f)
            val motionScore = maxOf(distScore, (1.0f - (gateDist / (config.kalmanGatingThreshold * 2f))).coerceIn(0f, 1f))

            // During the short post-ambiguity reacquisition window, preserve
            // the sign of the last reliable observed->observed displacement.
            // This is deliberately not a permanent direction lock: the evidence
            // expires after 0.5s.  If two people cross, returning to the old side
            // (projection ~= 0 or negative) must not beat the identity that
            // continues along the recent trajectory merely because bbox IoU is
            // temporarily larger there.
            if (track.state == TrackState.REACQUIRING) {
                val historyDx = track.lastReliableObservedMotionDx
                val historyDy = track.lastReliableObservedMotionDy
                val historyMagSq = historyDx * historyDx + historyDy * historyDy
                val historyAgeUs = if (track.lastReliableObservedMotionTimestampUs == Long.MIN_VALUE) {
                    Long.MAX_VALUE
                } else {
                    (timestampUs - track.lastReliableObservedMotionTimestampUs).coerceAtLeast(0L)
                }
                val minReliableMotion = max(10f, max(track.lastObservedBbox.width, track.lastObservedBbox.height) * 0.08f)
                if (historyAgeUs <= 500_000L && historyMagSq >= minReliableMotion * minReliableMotion) {
                    val candidateDx = detBox.centerX - track.lastObservedBbox.centerX
                    val candidateDy = detBox.centerY - track.lastObservedBbox.centerY
                    val progress = (candidateDx * historyDx + candidateDy * historyDy) / historyMagSq
                    if (progress < 0.15f) {
                        return 0f
                    }
                }
            }

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
            val maskPenalty = if (track.lastObservedMask != null && det.mask != null && mIoU < 0.05f) 0.4f else 1.0f

            return (rawScore * maskPenalty).coerceIn(0f, 1f)
        }

        fun recordReliableObservedMotion(track: InternalTrack, det: PersonDetection) {
            val dx = det.bbox.centerX - track.lastObservedBbox.centerX
            val dy = det.bbox.centerY - track.lastObservedBbox.centerY
            if (track.observedOnPreviousFrame) {
                track.hasFreshObservedMotion = true
                track.freshObservedMotionDx = dx
                track.freshObservedMotionDy = dy
            }
            val magnitude = sqrt(dx * dx + dy * dy)
            val minReliableMotion = max(5f, max(track.lastObservedBbox.width, track.lastObservedBbox.height) * 0.03f)
            if (magnitude >= minReliableMotion) {
                track.lastReliableObservedMotionDx = dx
                track.lastReliableObservedMotionDy = dy
                track.lastReliableObservedMotionTimestampUs = timestampUs
            }
        }

        // 3. PHASE B: OCCLUSION / REACQUIRING GROUP-FIRST ASSOCIATION WITH RESERVATION ISOLATION
        val reservedGroupTrackIndices = mutableSetOf<Int>()
        val reservedGroupDetectionIndices = mutableSetOf<Int>()
        val reservedGroupOwnerTrackIdsByDetectionIndex = mutableMapOf<Int, MutableSet<Int>>()
        val reservedGroupOwnerGroupsByDetectionIndex = mutableMapOf<Int, MutableSet<Set<Int>>>()
        val reservedGlobalDetectionIndices = mutableSetOf<Int>()
        val globalAmbiguousTrackIndices = mutableSetOf<Int>()

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

            // Behavior-neutral diagnostics for the case that never reaches the
            // reciprocal-best checks below: Hungarian may leave an entire group
            // row unmatched. This is especially important when an unprotected
            // neighbor (for example an occluder) controls the state of a protected
            // identity in the same group. Emit only for groups that contain at
            // least one protected identity to keep trace volume bounded.
            if (groupTrackIndices.any { protectedTrackIds.contains(tracks[it].id) }) {
                val matchedColByRow = matchResult.matches.associate { it.first to it.second }
                for (r in groupTrackIndices.indices) {
                    if (matchedColByRow.containsKey(r)) continue

                    val track = tracks[groupTrackIndices[r]]
                    val bestCol = candidateDetectionIndices.indices.maxByOrNull { c -> scoreMatrix[r][c] }
                        ?: continue
                    val bestDetectionIndex = candidateDetectionIndices[bestCol]
                    val bestDetection = detections[bestDetectionIndex]
                    val bestScore = scoreMatrix[r][bestCol]
                    val bestColScores = groupTrackIndices.indices.map { row -> scoreMatrix[row][bestCol] }
                    val bestColBest = bestColScores.maxOrNull() ?: 0f
                    val bestColSecondBest = bestColScores
                        .filterIndexed { row, _ -> row != r }
                        .maxOrNull()
                    val bestColMargin = bestColSecondBest?.let { bestScore - it } ?: Float.POSITIVE_INFINITY
                    val bestBBoxIoU = computeBBoxIoU(track.currentPredictedBbox, bestDetection.bbox)
                    val bestMaskIoU = computePredictedMaskIoU(track, bestDetection.mask)
                    val winningPairForBestCol = matchResult.matches.firstOrNull { it.second == bestCol }
                    val winnerTrack = winningPairForBestCol?.let { pair -> tracks[groupTrackIndices[pair.first]] }
                    val winnerMaskIoU = winnerTrack?.let { computePredictedMaskIoU(it, bestDetection.mask) }
                    val protectedIdentityEvidenceOk = if (protectedTrackIds.contains(track.id)) {
                        isProtectedGroupIdentityEvidenceSufficient(track.state, bestBBoxIoU, bestMaskIoU)
                    } else {
                        false
                    }
                    val uncertainOccluderEvidence = isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
                        groupTrackCount = groupTrackIndices.size,
                        trackState = track.state,
                        identityProtected = protectedTrackIds.contains(track.id),
                        privacySelected = privacySelectedTrackIds.contains(track.id),
                        bestScore = bestScore,
                        minMatchScore = config.minMatchScore,
                        protectedIdentityEvidenceOk = protectedIdentityEvidenceOk,
                        protectedMaskIoU = bestMaskIoU,
                        winnerIdentityProtected = winnerTrack?.let { protectedTrackIds.contains(it.id) } ?: true,
                        winnerMaskIoU = winnerMaskIoU
                    )
                    if (uncertainOccluderEvidence && winningPairForBestCol != null) {
                        val confirmedWinnerTrack = tracks[groupTrackIndices[winningPairForBestCol.first]]
                        currentUncertainOccluderTrackIdsByProtectedTrackId
                            .getOrPut(track.id) { mutableSetOf() }
                            .add(confirmedWinnerTrack.id)
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "PROTECTED_GROUP_UNCERTAIN_OCCLUDER_EVIDENCE",
                            fields = mapOf(
                                "group_id" to group.trackIds.toList().sorted(),
                                "track_id" to track.id,
                                "det_index" to bestDetectionIndex,
                                "best_score" to bestScore,
                                "winner_track_id" to confirmedWinnerTrack.id,
                                "winner_score" to scoreMatrix[winningPairForBestCol.first][winningPairForBestCol.second],
                                "protected_bbox_iou" to bestBBoxIoU,
                                "protected_mask_iou" to bestMaskIoU,
                                "winner_mask_iou" to winnerMaskIoU,
                                "pts_us" to timestampUs
                            )
                        )
                    }

                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "GROUP_ASSIGNMENT_NO_HUNGARIAN_MATCH",
                        fields = mapOf(
                            "group_id" to group.trackIds.toList().sorted(),
                            "track_id" to track.id,
                            "track_state" to track.state.name,
                            "best_det_index" to bestDetectionIndex,
                            "best_score" to bestScore,
                            "best_col_best" to bestColBest,
                            "best_col_second_best" to bestColSecondBest,
                            "best_col_margin" to bestColMargin,
                            "best_bbox_iou" to bestBBoxIoU,
                            "best_mask_iou" to bestMaskIoU,
                            "identity_protected" to protectedTrackIds.contains(track.id),
                            "privacy_selected" to privacySelectedTrackIds.contains(track.id),
                            "track_count" to groupTrackIndices.size,
                            "candidate_count" to candidateDetectionIndices.size,
                            "min_score" to config.minMatchScore,
                            "max_cost" to maxCost,
                            "hungarian_pairs" to matchResult.matches.map { pair ->
                                mapOf(
                                    "track_id" to tracks[groupTrackIndices[pair.first]].id,
                                    "det_index" to candidateDetectionIndices[pair.second],
                                    "score" to scoreMatrix[pair.first][pair.second]
                                )
                            },
                            "track_predicted_bbox" to listOf(
                                track.currentPredictedBbox.left,
                                track.currentPredictedBbox.top,
                                track.currentPredictedBbox.right,
                                track.currentPredictedBbox.bottom
                            ),
                            "track_last_observed_bbox" to listOf(
                                track.lastObservedBbox.left,
                                track.lastObservedBbox.top,
                                track.lastObservedBbox.right,
                                track.lastObservedBbox.bottom
                            ),
                            "best_detection_bbox" to listOf(
                                bestDetection.bbox.left,
                                bestDetection.bbox.top,
                                bestDetection.bbox.right,
                                bestDetection.bbox.bottom
                            ),
                            "pts_us" to timestampUs
                        )
                    )
                }
            }

            val matchedGroupRows = mutableSetOf<Int>()
            val matchedGroupCols = mutableSetOf<Int>()
            val reservedThisGroup = mutableSetOf<Int>()

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
                val candidateBBoxIoU = computeBBoxIoU(track.currentPredictedBbox, det.bbox)
                val candidateMaskIoU = computePredictedMaskIoU(track, det.mask)
                val protectedIdentityEvidenceOk = if (protectedTrackIds.contains(track.id)) {
                    isProtectedGroupIdentityEvidenceSufficient(track.state, candidateBBoxIoU, candidateMaskIoU)
                } else {
                    true
                }

                val isReciprocalBest = isScoreValid && isRowTop && isColTop &&
                    hasRowSeparation && hasColSeparation && protectedIdentityEvidenceOk

                if (!isReciprocalBest) {
                    val motionEvidenceEligible =
                        protectedTrackIds.contains(track.id) &&
                            !privacySelectedTrackIds.contains(track.id) &&
                            det.mask != null &&
                            isScoreValid && isColTop &&
                            assignedScore >= rowBest - config.associationAmbiguityMargin * 2f &&
                            isProtectedMotionEvidenceSufficient(candidateBBoxIoU, candidateMaskIoU)
                    if (motionEvidenceEligible) {
                        currentProtectedTrackMotionEvidence[track.id] = ProtectedTrackMotionEvidence(
                            trackId = track.id,
                            detectionIndex = dIdx,
                            detection = det,
                            assignedScore = assignedScore,
                            bboxIou = candidateBBoxIoU,
                            maskIou = candidateMaskIoU,
                            timestampUs = timestampUs
                        )
                    }
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
                            "bbox_iou" to candidateBBoxIoU,
                            "mask_iou" to candidateMaskIoU,
                            "protected_identity_evidence_ok" to protectedIdentityEvidenceOk,
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
                    reservedGroupOwnerTrackIdsByDetectionIndex
                        .getOrPut(dIdx) { mutableSetOf() }
                        .addAll(group.trackIds)
                    reservedGroupOwnerGroupsByDetectionIndex
                        .getOrPut(dIdx) { mutableSetOf() }
                        .add(group.trackIds.toSet())
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
                val associationBBoxIoU = candidateBBoxIoU
                val associationMaskIoU = candidateMaskIoU
                recordReliableObservedMotion(track, det)
                track.lastObservedBbox = det.bbox
                track.lastObservedMask = det.mask ?: track.lastObservedMask
                track.currentPredictedBbox = det.bbox
                track.currentRenderMask = det.mask ?: track.lastObservedMask
                track.confidence = det.confidence
                track.lostFrames = 0
                track.occludedFrames = 0
                track.reacquireFrames = 0
                track.occludedByTrackIds.clear()
                track.occlusionMotionBbox = null
                track.state = TrackState.ACTIVE
                track.offscreenDormant = false
                track.observedThisFrame = true
                track.framesSinceLastObservation = 0
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
                        hasMeaningfulGroupReservationOverlap(groupTrack.currentPredictedBbox, detBox) ||
                            hasMeaningfulGroupReservationOverlap(groupTrack.lastObservedBbox, detBox)
                    }
                    if (overlapsGroupMember) {
                        reservedGroupDetectionIndices.add(dIdx)
                        reservedGroupOwnerTrackIdsByDetectionIndex
                            .getOrPut(dIdx) { mutableSetOf() }
                            .addAll(group.trackIds)
                        reservedGroupOwnerGroupsByDetectionIndex
                            .getOrPut(dIdx) { mutableSetOf() }
                            .add(group.trackIds.toSet())
                        reservedThisGroup.add(dIdx)
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

            // Identity may remain ambiguous while privacy membership is already
            // deterministic at the group-set level. Only infer a class when the
            // group is cardinality-balanced (one candidate per track), every
            // remaining candidate is still owned by the group, and all remaining
            // tracks belong to the same selected/unselected class. This does not
            // assign any detection to a concrete track ID.
            val residualTrackIndices = groupTrackIndices.filter { !matchedTrackIndices.contains(it) }
            val residualDetectionIndices = candidateDetectionIndices.filter { !matchedDetectionIndices.contains(it) }
            val balancedResidualSet =
                candidateDetectionIndices.size == groupTrackIndices.size &&
                    residualTrackIndices.isNotEmpty() &&
                    residualTrackIndices.size == residualDetectionIndices.size &&
                    residualDetectionIndices.all { reservedThisGroup.contains(it) }

            if (balancedResidualSet) {
                val residualTrackIds = residualTrackIndices.map { tracks[it].id }.toSet()
                val allSelected = residualTrackIds.all { privacySelectedTrackIds.contains(it) }
                val allUnselected = residualTrackIds.none { privacySelectedTrackIds.contains(it) }
                val selectionClass = when {
                    allSelected -> PrivacySelectionClass.SELECTED
                    allUnselected -> PrivacySelectionClass.UNSELECTED
                    else -> null
                }

                if (selectionClass != null && residualDetectionIndices.all { detections[it].mask != null }) {
                    for (dIdx in residualDetectionIndices) {
                        currentPrivacyClassEvidence.add(
                            FreshPrivacyClassEvidence(
                                selectionClass = selectionClass,
                                detectionIndex = dIdx,
                                detection = detections[dIdx],
                                residualTrackIds = residualTrackIds
                            )
                        )
                    }
                    if (selectionClass == PrivacySelectionClass.SELECTED) {
                        currentPrivacySuppressedSelectedTrackIds.addAll(residualTrackIds)
                    }
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "PRIVACY_CLASS_EVIDENCE_INFERRED",
                        fields = mapOf(
                            "group_id" to group.trackIds.toList(),
                            "selection_class" to selectionClass.name,
                            "residual_track_ids" to residualTrackIds.toList(),
                            "detection_indices" to residualDetectionIndices,
                            "candidate_count" to candidateDetectionIndices.size,
                            "track_count" to groupTrackIndices.size,
                            "pts_us" to timestampUs
                        )
                    )
                }
            }
        }

        // 4. Global Hungarian Matching on Remaining Non-Group Unmatched Tracks & Detections
        val remainingTrackIndices = tracks.indices.filter {
            !matchedTrackIndices.contains(it) &&
                !reservedGroupTrackIndices.contains(it) &&
                (tracks[it].state == TrackState.ACTIVE || tracks[it].state == TrackState.NEW)
        }
        val strictFaceOnlyRescueDetectionIndices = mutableSetOf<Int>()

        // Behavior-neutral shadow Global probe. Evaluate the exact counterfactual
        // where group-reserved detections are visible to the same non-group
        // ACTIVE/NEW tracks that are about to enter Global Hungarian. The real
        // association below still uses remainingDetectionIndices, so this probe
        // cannot commit, unreserve, create, or mutate any identity.
        val shadowGlobalDetectionIndices = detections.indices.filter {
            !matchedDetectionIndices.contains(it)
        }
        if (
            remainingTrackIndices.isNotEmpty() &&
            reservedGroupDetectionIndices.isNotEmpty() &&
            shadowGlobalDetectionIndices.isNotEmpty()
        ) {
            val shadowScoreMatrix = Array(remainingTrackIndices.size) { r ->
                val track = tracks[remainingTrackIndices[r]]
                FloatArray(shadowGlobalDetectionIndices.size) { c ->
                    computeMatchScore(track, detections[shadowGlobalDetectionIndices[c]])
                }
            }
            val shadowCostMatrix = Array(remainingTrackIndices.size) { r ->
                FloatArray(shadowGlobalDetectionIndices.size) { c ->
                    (1.0f - shadowScoreMatrix[r][c]).coerceIn(0f, 1f)
                }
            }
            val shadowMaxCost = (1.0f - config.minMatchScore).coerceIn(0.1f, 0.90f)
            val shadowMatches = HungarianSolver.match(shadowCostMatrix, maxCostThreshold = shadowMaxCost)
            val shadowSelectedPairs = shadowMatches.matches.toSet()

            for (row in remainingTrackIndices.indices) {
                val tIdx = remainingTrackIndices[row]
                val track = tracks[tIdx]
                for (col in shadowGlobalDetectionIndices.indices) {
                    val dIdx = shadowGlobalDetectionIndices[col]
                    if (!reservedGroupDetectionIndices.contains(dIdx)) continue

                    val reservationOwnerTrackIds = reservedGroupOwnerTrackIdsByDetectionIndex[dIdx].orEmpty()
                    val reservationOwnerGroups = reservedGroupOwnerGroupsByDetectionIndex[dIdx].orEmpty()
                    if (reservationOwnerTrackIds.contains(track.id)) continue

                    val det = detections[dIdx]
                    val assignedScore = shadowScoreMatrix[row][col]
                    val rowBest = shadowScoreMatrix[row].maxOrNull() ?: 0f
                    val colBest = shadowScoreMatrix.indices.maxOfOrNull { r -> shadowScoreMatrix[r][col] } ?: 0f
                    val rowSecondBest = shadowScoreMatrix[row]
                        .indices
                        .filter { it != col }
                        .map { shadowScoreMatrix[row][it] }
                        .maxOrNull()
                    val colSecondBest = shadowScoreMatrix.indices
                        .filter { it != row }
                        .map { shadowScoreMatrix[it][col] }
                        .maxOrNull()
                    val epsilon = 1e-6f
                    val rowMargin = rowSecondBest?.let { assignedScore - it } ?: Float.POSITIVE_INFINITY
                    val colMargin = colSecondBest?.let { assignedScore - it } ?: Float.POSITIVE_INFINITY
                    val rowHasConfusableAlternative = shadowGlobalDetectionIndices.indices.any { altCol ->
                        if (altCol == col) return@any false
                        val altScore = shadowScoreMatrix[row][altCol]
                        if ((rowBest - altScore) > config.associationAmbiguityMargin) return@any false
                        val altDet = detections[shadowGlobalDetectionIndices[altCol]]
                        val inter = computeBBoxIntersectionArea(det.bbox, altDet.bbox)
                        val minArea = minOf(
                            det.bbox.width * det.bbox.height,
                            altDet.bbox.width * altDet.bbox.height
                        ).coerceAtLeast(1e-4f)
                        (inter / minArea) >= 0.10f
                    }
                    val colHasConfusableAlternative = remainingTrackIndices.indices.any { altRow ->
                        if (altRow == row) return@any false
                        val altScore = shadowScoreMatrix[altRow][col]
                        if ((colBest - altScore) > config.associationAmbiguityMargin) return@any false
                        val altTrack = tracks[remainingTrackIndices[altRow]]
                        val inter = computeBBoxIntersectionArea(track.currentPredictedBbox, altTrack.currentPredictedBbox)
                        val minArea = minOf(
                            track.currentPredictedBbox.width * track.currentPredictedBbox.height,
                            altTrack.currentPredictedBbox.width * altTrack.currentPredictedBbox.height
                        ).coerceAtLeast(1e-4f)
                        (inter / minArea) >= 0.10f
                    }
                    val hasAmbiguousGeometry =
                        (rowMargin < config.associationAmbiguityMargin && rowHasConfusableAlternative) ||
                        (colMargin < config.associationAmbiguityMargin && colHasConfusableAlternative)
                    val candidateBBoxIoU = computeBBoxIoU(track.currentPredictedBbox, det.bbox)
                    val candidateMaskIoU = computePredictedMaskIoU(track, det.mask)
                    val protectedIdentityEvidenceOk = if (protectedTrackIds.contains(track.id)) {
                        isProtectedGroupIdentityEvidenceSufficient(
                            track.state,
                            candidateBBoxIoU,
                            candidateMaskIoU
                        )
                    } else {
                        true
                    }
                    val hungarianSelected = shadowSelectedPairs.contains(row to col)
                    val wouldStrictGlobalCommit = hungarianSelected &&
                        assignedScore >= config.minMatchScore &&
                        assignedScore >= rowBest - epsilon &&
                        assignedScore >= colBest - epsilon &&
                        !hasAmbiguousGeometry &&
                        protectedIdentityEvidenceOk
                    val faceOnlyIdentityProtected =
                        protectedTrackIds.contains(track.id) &&
                            !privacySelectedTrackIds.contains(track.id)
                    val multiGroupReservation = reservationOwnerGroups.size >= 2

                    // Rendering-only ownership evidence. A group-reserved YOLO
                    // detection can be known to belong to a FACE_ONLY identity
                    // even when reservation policy intentionally refuses to
                    // commit that identity. Expose that fresh mask only as an
                    // UNSELECTED compositor occluder; do not match the track,
                    // unreserve the detection, or alter any TrackManager state.
                    if (
                        wouldStrictGlobalCommit &&
                        faceOnlyIdentityProtected &&
                        det.mask != null &&
                        currentStrictUnselectedPrivacyEvidence.none { it.detectionIndex == dIdx }
                    ) {
                        currentStrictUnselectedPrivacyEvidence.add(
                            FreshPrivacyClassEvidence(
                                selectionClass = PrivacySelectionClass.UNSELECTED,
                                detectionIndex = dIdx,
                                detection = det,
                                residualTrackIds = setOf(track.id)
                            )
                        )
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "GROUP_RESERVED_STRICT_UNSELECTED_PRIVACY_EVIDENCE",
                            fields = mapOf(
                                "track_id" to track.id,
                                "det_index" to dIdx,
                                "reservation_owner_track_ids" to reservationOwnerTrackIds.toList().sorted(),
                                "reservation_owner_group_count" to reservationOwnerGroups.size,
                                "assigned_score" to assignedScore,
                                "bbox_iou" to candidateBBoxIoU,
                                "mask_iou" to candidateMaskIoU,
                                "pts_us" to timestampUs
                            )
                        )
                    }

                    if (
                        isStrictFaceOnlyReservationRescueEligible(
                            wouldStrictGlobalCommit = wouldStrictGlobalCommit,
                            faceOnlyIdentityProtected = faceOnlyIdentityProtected,
                            reservationOwnerGroupCount = reservationOwnerGroups.size
                        )
                    ) {
                        strictFaceOnlyRescueDetectionIndices.add(dIdx)
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "GROUP_RESERVED_STRICT_RESCUE_ELIGIBLE",
                            fields = mapOf(
                                "track_id" to track.id,
                                "det_index" to dIdx,
                                "reservation_owner_track_ids" to reservationOwnerTrackIds.toList().sorted(),
                                "reservation_owner_group_count" to reservationOwnerGroups.size,
                                "reservation_owner_groups" to reservationOwnerGroups
                                    .map { it.toList().sorted() }
                                    .sortedBy { it.joinToString(",") },
                                "assigned_score" to assignedScore,
                                "bbox_iou" to candidateBBoxIoU,
                                "mask_iou" to candidateMaskIoU,
                                "row_margin" to rowMargin,
                                "col_margin" to colMargin,
                                "pts_us" to timestampUs
                            )
                        )
                    }

                    if (protectedTrackIds.contains(track.id) || wouldStrictGlobalCommit) {
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "GROUP_RESERVED_STRICT_RESCUE_CANDIDATE",
                            fields = mapOf(
                                "track_id" to track.id,
                                "track_state" to track.state.name,
                                "det_index" to dIdx,
                                "reservation_owner_track_ids" to reservationOwnerTrackIds.toList().sorted(),
                                "assigned_score" to assignedScore,
                                "row_best" to rowBest,
                                "col_best" to colBest,
                                "row_second_best" to rowSecondBest,
                                "col_second_best" to colSecondBest,
                                "row_margin" to rowMargin,
                                "col_margin" to colMargin,
                                "row_confusable_geometry" to rowHasConfusableAlternative,
                                "col_confusable_geometry" to colHasConfusableAlternative,
                                "bbox_iou" to candidateBBoxIoU,
                                "mask_iou" to candidateMaskIoU,
                                "identity_protected" to protectedTrackIds.contains(track.id),
                                "privacy_selected" to privacySelectedTrackIds.contains(track.id),
                                "multi_group_reservation" to multiGroupReservation,
                                "protected_identity_evidence_ok" to protectedIdentityEvidenceOk,
                                "hungarian_selected" to hungarianSelected,
                                "would_strict_global_commit" to wouldStrictGlobalCommit,
                                "track_predicted_bbox" to listOf(
                                    track.currentPredictedBbox.left,
                                    track.currentPredictedBbox.top,
                                    track.currentPredictedBbox.right,
                                    track.currentPredictedBbox.bottom
                                ),
                                "track_last_observed_bbox" to listOf(
                                    track.lastObservedBbox.left,
                                    track.lastObservedBbox.top,
                                    track.lastObservedBbox.right,
                                    track.lastObservedBbox.bottom
                                ),
                                "detection_bbox" to listOf(
                                    det.bbox.left,
                                    det.bbox.top,
                                    det.bbox.right,
                                    det.bbox.bottom
                                ),
                                "pts_us" to timestampUs
                            )
                        )
                    }
                }
            }
        }

        // Group reservation remains the default isolation boundary. A reserved
        // detection is exposed to the real Global Hungarian only when at least
        // two independent occlusion groups simultaneously quarantine it and the
        // counterfactual full candidate set already proves a strict, unique
        // commit to a FACE_ONLY identity-protected track outside all owners.
        // A single owning group keeps the original isolation semantics. This
        // prevents ordinary neighboring-group detections from broadly escaping
        // reservation while still resolving the multi-group quarantine seen at
        // the cross-device topology fork. FULL_BODY privacy-selected tracks and
        // ordinary tracks never use this escape. Keep the detection in
        // reservedGroupDetectionIndices so a failed real Global commit still
        // cannot fall through to recovery or new-track creation in the same frame.
        val remainingDetectionIndices = detections.indices.filter { dIdx ->
            !matchedDetectionIndices.contains(dIdx) &&
                (
                    !reservedGroupDetectionIndices.contains(dIdx) ||
                        strictFaceOnlyRescueDetectionIndices.contains(dIdx)
                    )
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

                val rowBest = scoreMatrix[match.first].maxOrNull() ?: 0f
                val colBest = scoreMatrix.indices.maxOfOrNull { r -> scoreMatrix[r][match.second] } ?: 0f
                val rowSecondBest = scoreMatrix[match.first]
                    .indices
                    .filter { it != match.second }
                    .map { scoreMatrix[match.first][it] }
                    .maxOrNull()
                val colSecondBest = scoreMatrix.indices
                    .filter { it != match.first }
                    .map { scoreMatrix[it][match.second] }
                    .maxOrNull()
                val epsilon = 1e-6f
                val rowMargin = rowSecondBest?.let { assignedScore - it } ?: Float.POSITIVE_INFINITY
                val colMargin = colSecondBest?.let { assignedScore - it } ?: Float.POSITIVE_INFINITY
                val rowHasConfusableAlternative = remainingDetectionIndices.indices.any { altCol ->
                    if (altCol == match.second) return@any false
                    val altScore = scoreMatrix[match.first][altCol]
                    if ((rowBest - altScore) > config.associationAmbiguityMargin) return@any false
                    val altDet = detections[remainingDetectionIndices[altCol]]
                    val inter = computeBBoxIntersectionArea(det.bbox, altDet.bbox)
                    val minArea = minOf(
                        det.bbox.width * det.bbox.height,
                        altDet.bbox.width * altDet.bbox.height
                    ).coerceAtLeast(1e-4f)
                    (inter / minArea) >= 0.10f
                }
                val colHasConfusableAlternative = remainingTrackIndices.indices.any { altRow ->
                    if (altRow == match.first) return@any false
                    val altScore = scoreMatrix[altRow][match.second]
                    if ((colBest - altScore) > config.associationAmbiguityMargin) return@any false
                    val altTrack = tracks[remainingTrackIndices[altRow]]
                    val inter = computeBBoxIntersectionArea(track.currentPredictedBbox, altTrack.currentPredictedBbox)
                    val minArea = minOf(
                        track.currentPredictedBbox.width * track.currentPredictedBbox.height,
                        altTrack.currentPredictedBbox.width * altTrack.currentPredictedBbox.height
                    ).coerceAtLeast(1e-4f)
                    (inter / minArea) >= 0.10f
                }
                val hasAmbiguousGeometry =
                    (rowMargin < config.associationAmbiguityMargin && rowHasConfusableAlternative) ||
                    (colMargin < config.associationAmbiguityMargin && colHasConfusableAlternative)
                val candidateBBoxIoU = computeBBoxIoU(track.currentPredictedBbox, det.bbox)
                val candidateMaskIoU = computePredictedMaskIoU(track, det.mask)
                val protectedIdentityEvidenceOk = if (protectedTrackIds.contains(track.id)) {
                    isProtectedGroupIdentityEvidenceSufficient(
                        track.state,
                        candidateBBoxIoU,
                        candidateMaskIoU
                    )
                } else {
                    true
                }
                val isCommitValid = assignedScore >= config.minMatchScore &&
                    assignedScore >= rowBest - epsilon &&
                    assignedScore >= colBest - epsilon &&
                    !hasAmbiguousGeometry &&
                    protectedIdentityEvidenceOk

                if (!isCommitValid) {
                    val motionEvidenceEligible =
                        protectedTrackIds.contains(track.id) &&
                            !privacySelectedTrackIds.contains(track.id) &&
                            det.mask != null &&
                            assignedScore >= config.minMatchScore &&
                            assignedScore >= colBest - epsilon &&
                            assignedScore >= rowBest - config.associationAmbiguityMargin * 2f &&
                            isProtectedMotionEvidenceSufficient(candidateBBoxIoU, candidateMaskIoU) &&
                            hasAmbiguousGeometry
                    if (motionEvidenceEligible) {
                        currentProtectedTrackMotionEvidence[track.id] = ProtectedTrackMotionEvidence(
                            trackId = track.id,
                            detectionIndex = dIdx,
                            detection = det,
                            assignedScore = assignedScore,
                            bboxIou = candidateBBoxIoU,
                            maskIou = candidateMaskIoU,
                            timestampUs = timestampUs
                        )
                    }
                    globalAmbiguousTrackIndices.add(tIdx)
                    reservedGlobalDetectionIndices.add(dIdx)
                    val confusableDetectionIndices = mutableSetOf(dIdx)

                    // Reserve the whole confusable detection cluster for this
                    // ambiguous row, not only the single Hungarian assignment.
                    // Otherwise a near-identical alternative can fall through
                    // to ordinary recovery/new-track creation in the same frame,
                    // producing exactly the duplicate-ID churn ambiguity deferral
                    // is intended to prevent.
                    for (altCol in remainingDetectionIndices.indices) {
                        if (altCol == match.second) continue
                        val altScore = scoreMatrix[match.first][altCol]
                        if ((rowBest - altScore) > config.associationAmbiguityMargin) continue
                        val altDetIndex = remainingDetectionIndices[altCol]
                        val altDet = detections[altDetIndex]
                        val inter = computeBBoxIntersectionArea(det.bbox, altDet.bbox)
                        val minArea = minOf(
                            det.bbox.width * det.bbox.height,
                            altDet.bbox.width * altDet.bbox.height
                        ).coerceAtLeast(1e-4f)
                        if ((inter / minArea) >= 0.10f) {
                            reservedGlobalDetectionIndices.add(altDetIndex)
                            confusableDetectionIndices.add(altDetIndex)
                        }
                    }

                    // If Global Hungarian itself discovers a spatially
                    // confusable multi-person assignment before predicted
                    // track boxes have overlapped, promote the competing
                    // tracks into an occlusion group immediately.  Otherwise
                    // they become REACQUIRING without any group owning their
                    // next-frame separation, leaving no matcher allowed to
                    // commit the identities again.
                    val ambiguityTrackIds = mutableSetOf<Int>()
                    for (candidateRow in remainingTrackIndices.indices) {
                        val hasPlausibleEdgeIntoCluster = confusableDetectionIndices.any { candidateDetIndex ->
                            val candidateCol = remainingDetectionIndices.indexOf(candidateDetIndex)
                            candidateCol >= 0 && scoreMatrix[candidateRow][candidateCol] >= config.minMatchScore
                        }
                        if (hasPlausibleEdgeIntoCluster) {
                            ambiguityTrackIds.add(tracks[remainingTrackIndices[candidateRow]].id)
                        }
                    }
                    if (ambiguityTrackIds.size >= 2) {
                        val existing = occlusionGroups.firstOrNull { group ->
                            group.trackIds.any { ambiguityTrackIds.contains(it) }
                        }
                        if (existing != null) {
                            existing.trackIds.addAll(ambiguityTrackIds)
                            existing.state = OcclusionGroupState.REACQUIRING
                            existing.reacquireFrames = maxOf(existing.reacquireFrames, 1)
                        } else {
                            occlusionGroups.add(
                                OcclusionGroup(
                                    trackIds = ambiguityTrackIds,
                                    startedAtUs = timestampUs,
                                    lastOverlapTimestampUs = timestampUs,
                                    state = OcclusionGroupState.REACQUIRING,
                                    reacquireFrames = 1
                                )
                            )
                            NativeDiagnostics.event(
                                level = "INFO",
                                component = "TrackManager",
                                event = "OCCLUSION_GROUP_CREATE",
                                fields = mapOf(
                                    "track_ids" to ambiguityTrackIds.toList(),
                                    "reason" to "GLOBAL_AMBIGUITY",
                                    "pts_us" to timestampUs
                                )
                            )
                        }
                    }

                    NativeDiagnostics.event(
                        level = "WARN",
                        component = "TrackManager",
                        event = "GLOBAL_ASSOCIATION_AMBIGUOUS",
                        fields = mapOf(
                            "track_id" to track.id,
                            "det_index" to dIdx,
                            "assigned_score" to assignedScore,
                            "row_best" to rowBest,
                            "col_best" to colBest,
                            "row_second_best" to rowSecondBest,
                            "col_second_best" to colSecondBest,
                            "row_margin" to rowMargin,
                            "col_margin" to colMargin,
                            "row_confusable_geometry" to rowHasConfusableAlternative,
                            "col_confusable_geometry" to colHasConfusableAlternative,
                            "bbox_iou" to candidateBBoxIoU,
                            "mask_iou" to candidateMaskIoU,
                            "identity_protected" to protectedTrackIds.contains(track.id),
                            "privacy_selected" to privacySelectedTrackIds.contains(track.id),
                            "protected_identity_evidence_ok" to protectedIdentityEvidenceOk,
                            "reserved_detection_indices" to reservedGlobalDetectionIndices.toList(),
                            "required_margin" to config.associationAmbiguityMargin,
                            "pts_us" to timestampUs
                        )
                    )
                    continue
                }

                matchedTrackIndices.add(tIdx)
                matchedDetectionIndices.add(dIdx)

                val prevState = track.state
                val associationPredictedBbox = track.currentPredictedBbox
                val associationLastObservedBbox = track.lastObservedBbox
                val associationBBoxIoU = candidateBBoxIoU
                val associationMaskIoU = candidateMaskIoU
                recordReliableObservedMotion(track, det)
                track.lastObservedBbox = det.bbox
                track.lastObservedMask = det.mask ?: track.lastObservedMask
                track.currentPredictedBbox = det.bbox
                track.currentRenderMask = det.mask ?: track.lastObservedMask
                track.confidence = det.confidence
                track.lostFrames = 0
                track.occludedFrames = 0
                track.reacquireFrames = 0
                track.occludedByTrackIds.clear()
                track.occlusionMotionBbox = null
                track.state = TrackState.ACTIVE
                track.offscreenDormant = false
                track.observedThisFrame = true
                track.framesSinceLastObservation = 0
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

                if (track.offscreenDormant) {
                    track.currentRenderMask = null
                    continue
                }
                if (isDormantMixedFullBodyIdentity(track)) {
                    // A long-lost mixed-mode FULL_BODY slot is identity evidence
                    // only. Do not let a freshly observed neighboring person turn
                    // it into OCCLUDED/REACQUIRING and implicitly restore the old
                    // segmentation mask before strict LOST recovery succeeds.
                    track.state = TrackState.LOST
                    track.currentRenderMask = null
                    track.occludedByTrackIds.clear()
                    track.occlusionMotionBbox = null
                    continue
                }
                if (isLikelyProtectedOffscreenExit(track)) {
                    markProtectedOffscreenDormant(track, timestampUs)
                    continue
                }

                // Check overlap with tracks that received fresh observations this frame.
                // A committed unprotected group winner is not definitive occluder
                // evidence when the same current instance mask is owned at least as
                // strongly by this OCCLUDED FACE_ONLY protected identity. In that
                // narrow two-track case, ignore only that winner for the occlusion
                // state test; any other independently matched occluder still counts.
                val uncertainOccluderTrackIds =
                    currentUncertainOccluderTrackIdsByProtectedTrackId[track.id].orEmpty()
                val matchedUncertainOccluderTrackIds = uncertainOccluderTrackIds.filter { otherId ->
                    val otherIndex = tracks.indexOfFirst { it.id == otherId }
                    otherIndex >= 0 && matchedTrackIndices.contains(otherIndex)
                }
                if (matchedUncertainOccluderTrackIds.isNotEmpty()) {
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "PROTECTED_GROUP_UNCERTAIN_OCCLUDER_IGNORED",
                        fields = mapOf(
                            "track_id" to track.id,
                            "occluder_track_ids" to matchedUncertainOccluderTrackIds.sorted(),
                            "pts_us" to timestampUs
                        )
                    )
                }
                val freshlyMatchedOtherTracks = tracks.filter { other ->
                    other.id != track.id &&
                    !uncertainOccluderTrackIds.contains(other.id) &&
                    matchedTrackIndices.contains(tracks.indexOf(other)) &&
                    run {
                        val interArea = computeBBoxIntersectionArea(predBox, other.currentPredictedBbox)
                        val minArea = minOf(
                            predBox.width * predBox.height,
                            other.currentPredictedBbox.width * other.currentPredictedBbox.height
                        )
                        val ratio = if (minArea > 0f) interArea / minArea else 0f
                        val wasOccludedByOther = track.occludedByTrackIds.contains(other.id)
                        val otherNearLastObserved = computeBBoxIoU(track.lastObservedBbox, other.currentPredictedBbox) >= config.occlusionOverlapRatio
                        val anchorInterArea = computeBBoxIntersectionArea(track.lastObservedBbox, other.currentPredictedBbox)
                        val anchorMinArea = minOf(
                            track.lastObservedBbox.width * track.lastObservedBbox.height,
                            other.currentPredictedBbox.width * other.currentPredictedBbox.height
                        )
                        val anchorFreshOverlapRatio = if (anchorMinArea > 0f) anchorInterArea / anchorMinArea else 0f
                        val predictionAnchorIoU = computeBBoxIoU(predBox, track.lastObservedBbox)
                        val protectedLostAnchorOcclusion = isProtectedLostAnchorOcclusionSupported(
                            trackState = track.state,
                            identityProtected = protectedTrackIds.contains(track.id),
                            predictionAnchorIoU = predictionAnchorIoU,
                            anchorFreshOverlapRatio = anchorFreshOverlapRatio,
                            overlapThreshold = config.occlusionOverlapRatio
                        )
                        if (protectedLostAnchorOcclusion && ratio < config.occlusionOverlapRatio) {
                            NativeDiagnostics.event(
                                level = "INFO",
                                component = "TrackManager",
                                event = "PROTECTED_LOST_ANCHOR_OCCLUSION_CONFIRMED",
                                fields = mapOf(
                                    "track_id" to track.id,
                                    "occluder_track_id" to other.id,
                                    "current_overlap_ratio" to ratio,
                                    "anchor_fresh_overlap_ratio" to anchorFreshOverlapRatio,
                                    "prediction_anchor_iou" to predictionAnchorIoU,
                                    "threshold" to config.occlusionOverlapRatio,
                                    "pts_us" to timestampUs
                                )
                            )
                        }
                        ratio >= config.occlusionOverlapRatio ||
                            (wasOccludedByOther && otherNearLastObserved) ||
                            protectedLostAnchorOcclusion
                    }
                }

                val inActiveGroup = occlusionGroups.any { it.trackIds.contains(track.id) && it.state == OcclusionGroupState.ACTIVE_OVERLAP }
                val inReacquiringGroup = occlusionGroups.any { it.trackIds.contains(track.id) && it.state == OcclusionGroupState.REACQUIRING }
                val isOccluded = freshlyMatchedOtherTracks.isNotEmpty()

                if (isOccluded) {
                    val wasAlreadyOccluded = track.state == TrackState.OCCLUDED
                    track.occludedFrames++
                    track.state = TrackState.OCCLUDED
                    track.occludedByTrackIds.clear()
                    for (other in freshlyMatchedOtherTracks) {
                        track.occludedByTrackIds.add(other.id)
                    }

                    // Occlusion motion must not be owned by an arbitrary single
                    // foreground person.  Use a robust consensus of all fresh
                    // overlapping tracks, then blend that group motion with this
                    // identity's own Kalman prediction.  This keeps long
                    // occlusions spatially bounded without making a selected
                    // target visually follow one unselected person.
                    val freshMotionTracks = freshlyMatchedOtherTracks.filter { it.hasFreshObservedMotion }
                    val consensusStepDx = medianOf(freshMotionTracks.map { it.freshObservedMotionDx })
                    val consensusStepDy = medianOf(freshMotionTracks.map { it.freshObservedMotionDy })

                    // Keep an accumulated group-motion anchor across the entire
                    // occlusion. Re-basing every frame on lastObservedBbox only
                    // applies one frame of motion and makes the privacy mask lag
                    // or appear frozen during sustained movement.
                    val previousGroupPredBox = track.occlusionMotionBbox ?: track.lastObservedBbox
                    val groupPredBox = previousGroupPredBox.offset(consensusStepDx, consensusStepDy)
                    track.occlusionMotionBbox = groupPredBox
                    val blendedPredBox = blendBboxes(predBox, groupPredBox, OCCLUSION_GROUP_MOTION_BLEND)
                    track.currentPredictedBbox = blendedPredBox

                    val ownDx = predBox.centerX - track.lastObservedBbox.centerX
                    val ownDy = predBox.centerY - track.lastObservedBbox.centerY
                    val ownMotion = sqrt(ownDx * ownDx + ownDy * ownDy)
                    val groupDx = groupPredBox.centerX - track.lastObservedBbox.centerX
                    val groupDy = groupPredBox.centerY - track.lastObservedBbox.centerY
                    val groupMotion = sqrt(groupDx * groupDx + groupDy * groupDy)
                    val motionAgreement = if (ownMotion > 1e-3f && groupMotion > 1e-3f) {
                        (ownDx * groupDx + ownDy * groupDy) / (ownMotion * groupMotion)
                    } else {
                        0f
                    }
                    val groupStepMotion = sqrt(
                        consensusStepDx * consensusStepDx + consensusStepDy * consensusStepDy
                    )
                    val groupIsNearlyStatic = groupStepMotion <
                        max(track.lastObservedBbox.width, track.lastObservedBbox.height) * 0.02f
                    val motionDisagrees = freshMotionTracks.isEmpty() || groupIsNearlyStatic || motionAgreement < 0.25f

                    // Preserve momentum while the identity prediction agrees with
                    // the observed group's motion.  Only damp repeatedly when the
                    // prediction is diverging from the group, which is the case
                    // where long unobserved trajectories otherwise run away.
                    if (!wasAlreadyOccluded || motionDisagrees) {
                        track.kalman.dampenVelocity(0.50f)
                    }

                    // STRICT REQUIREMENT: OCCLUDED state MUST NEVER generate rectangle fallback!
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = warpMask(
                            sourceMask = track.lastObservedMask!!,
                            prevBbox = track.lastObservedBbox,
                            predBbox = blendedPredBox,
                            missedFrames = 0
                        )
                    }
                } else if (
                    inActiveGroup || inReacquiringGroup || globalAmbiguousTrackIndices.contains(i) ||
                    track.state == TrackState.OCCLUDED || track.state == TrackState.REACQUIRING
                ) {
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
                        val boundedPredBox = boundProtectedUnobservedPrediction(track, predBox)
                        track.currentPredictedBbox = boundedPredBox
                        if (track.lastObservedMask != null) {
                            track.currentRenderMask = warpMask(
                                sourceMask = track.lastObservedMask!!,
                                prevBbox = track.lastObservedBbox,
                                predBbox = boundedPredBox,
                                missedFrames = 0
                            )
                        }
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "TrackManager",
                            event = "REACQUIRE_START",
                            fields = mapOf(
                                "track_id" to track.id,
                                "reason" to if (globalAmbiguousTrackIndices.contains(i)) "AMBIGUOUS_GLOBAL" else "AMBIGUOUS_GROUP",
                                "pts_us" to timestampUs
                            )
                        )
                    } else {
                        track.reacquireFrames++
                        track.occludedByTrackIds.clear()
                        val boundedPredBox = boundProtectedUnobservedPrediction(track, predBox)
                        track.currentPredictedBbox = boundedPredBox
                        if (track.reacquireFrames <= config.postOcclusionGraceFrames) {
                            if (track.lastObservedMask != null) {
                                track.currentRenderMask = warpMask(
                                    sourceMask = track.lastObservedMask!!,
                                    prevBbox = track.lastObservedBbox,
                                    predBbox = boundedPredBox,
                                    missedFrames = 0
                                )
                            }
                        } else {
                            track.state = TrackState.LOST
                            track.lostFrames = 1
                            track.occludedByTrackIds.clear()
                            track.occlusionMotionBbox = null
                            if (track.lastObservedMask != null) {
                                track.currentRenderMask = updateLostMask(
                                    canonicalMask = track.lastObservedMask!!,
                                    observedBbox = track.lastObservedBbox,
                                    predBbox = boundedPredBox,
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
                            track.occlusionMotionBbox = null
                            val boundedPredBox = boundProtectedUnobservedPrediction(track, predBox)
                            track.currentPredictedBbox = boundedPredBox
                            if (track.lastObservedMask != null) {
                                track.currentRenderMask = updateLostMask(
                                    canonicalMask = track.lastObservedMask!!,
                                    observedBbox = track.lastObservedBbox,
                                    predBbox = boundedPredBox,
                                    missedFrames = track.lostFrames
                                )
                            }
                        }
                        TrackState.LOST -> {
                            track.lostFrames++
                            track.occludedByTrackIds.clear()
                            val boundedPredBox = boundProtectedUnobservedPrediction(track, predBox)
                            track.currentPredictedBbox = boundedPredBox
                            if (track.lostFrames > config.maxMissedFrames) {
                                if (protectedTrackIds.contains(track.id)) {
                                    // Preserve only identity evidence. A very stale
                                    // warped mask must not keep covering unrelated
                                    // foreground pixels while the target is absent.
                                    track.currentRenderMask = null
                                    if (track.lostFrames == config.maxMissedFrames + 1) {
                                        NativeDiagnostics.event(
                                            level = "WARN",
                                            component = "TrackManager",
                                            event = "PROTECTED_TRACK_RETAINED_LOST",
                                            fields = mapOf(
                                                "track_id" to track.id,
                                                "lost_frames" to track.lostFrames,
                                                "max_missed" to config.maxMissedFrames
                                            )
                                        )
                                    }
                                } else {
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
                                }
                            } else {
                                if (track.lastObservedMask != null) {
                                    track.currentRenderMask = updateLostMask(
                                        canonicalMask = track.lastObservedMask!!,
                                        observedBbox = track.lastObservedBbox,
                                        predBbox = boundedPredBox,
                                        missedFrames = track.lostFrames
                                    )
                                }
                            }
                        }
                        TrackState.REMOVED, TrackState.OCCLUDED, TrackState.REACQUIRING -> {}
                    }
                }

                suppressStaleMixedFullBodyMaskIfNeeded(track, timestampUs)
            }
        }

        // 6. Ordinary Recovery Association on Remaining Truly LOST Tracks
        val unassignedDetections = mutableListOf<Pair<Int, PersonDetection>>()
        for (c in detections.indices) {
            if (
                !matchedDetectionIndices.contains(c) &&
                !reservedGroupDetectionIndices.contains(c) &&
                !reservedGlobalDetectionIndices.contains(c)
            ) {
                val det = detections[c]
                val plausiblyOwnedByUnresolvedIdentity = tracks.indices.any { tIdx ->
                    val track = tracks[tIdx]
                    val unresolved =
                        track.state == TrackState.REACQUIRING ||
                        globalAmbiguousTrackIndices.contains(tIdx) ||
                        occlusionGroups.any {
                            it.state == OcclusionGroupState.REACQUIRING && it.trackIds.contains(track.id)
                        }
                    unresolved && computeMatchScore(track, det) >= config.minMatchScore
                }

                if (plausiblyOwnedByUnresolvedIdentity) {
                    reservedGlobalDetectionIndices.add(c)
                    NativeDiagnostics.event(
                        level = "INFO",
                        component = "TrackManager",
                        event = "UNRESOLVED_IDENTITY_DETECTION_RESERVED",
                        fields = mapOf(
                            "det_index" to c,
                            "bbox" to listOf(det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom),
                            "pts_us" to timestampUs
                        )
                    )
                } else {
                    unassignedDetections.add(c to det)
                }
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

                val recoveryMaskIoU = computePredictedMaskIoU(candTrack, det.mask)
                val protectedIdentityEvidenceOk = if (protectedTrackIds.contains(candTrack.id)) {
                    bIoU >= PROTECTED_RECOVERY_MIN_BBOX_IOU ||
                        recoveryMaskIoU >= PROTECTED_RECOVERY_MIN_MASK_IOU
                } else {
                    true
                }

                if (isNearby && motionConsistent && protectedIdentityEvidenceOk && dist < bestDist) {
                    bestDist = dist
                    bestTrack = candTrack
                }
            }

            if (bestTrack != null) {
                val recoveryPredictedBbox = bestTrack.currentPredictedBbox
                val recoveryLastObservedBbox = bestTrack.lastObservedBbox
                val recoveryBBoxIoU = computeBBoxIoU(recoveryPredictedBbox, det.bbox)
                val recoveryMaskIoU = computePredictedMaskIoU(bestTrack, det.mask)
                recordReliableObservedMotion(bestTrack, det)
                bestTrack.lastObservedBbox = det.bbox
                bestTrack.lastObservedMask = det.mask ?: bestTrack.lastObservedMask
                bestTrack.currentPredictedBbox = det.bbox
                bestTrack.currentRenderMask = det.mask ?: bestTrack.lastObservedMask
                bestTrack.confidence = det.confidence
                bestTrack.lostFrames = 0
                bestTrack.occludedFrames = 0
                bestTrack.reacquireFrames = 0
                bestTrack.occludedByTrackIds.clear()
                bestTrack.occlusionMotionBbox = null
                bestTrack.state = TrackState.ACTIVE
                bestTrack.offscreenDormant = false
                bestTrack.observedThisFrame = true
                bestTrack.framesSinceLastObservation = 0
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
        currentPrivacyClassEvidence.clear()
        currentStrictUnselectedPrivacyEvidence.clear()
        currentProtectedTrackMotionEvidence.clear()
        currentUncertainOccluderTrackIdsByProtectedTrackId.clear()
        currentPrivacySuppressedSelectedTrackIds.clear()
        currentHardPrivacyClassByDetectionIndex.clear()

        // A prediction-only frame is not a fresh person observation. Keeping the
        // previous frame's flag set would let a later association treat a
        // multi-frame displacement as one-frame fresh group motion.
        for (track in tracks) {
            track.framesSinceLastObservation = (track.framesSinceLastObservation + 1).coerceAtMost(Int.MAX_VALUE)
            track.observedOnPreviousFrame = false
            track.observedThisFrame = false
            track.hasFreshObservedMotion = false
            track.currentObservedFootY = null
        }

        val activeOrLost = tracks.map { track ->
            val predBox = track.kalman.predict(timestampUs)
            track.currentPredictedBbox = predBox

            if (track.offscreenDormant) {
                track.state = TrackState.LOST
                track.currentRenderMask = null
                track.lostFrames = max(track.lostFrames, config.maxMissedFrames + 1)
                return@map track.toTrackedPerson()
            }
            if (countAsDetectionMiss && isLikelyProtectedOffscreenExit(track)) {
                markProtectedOffscreenDormant(track, timestampUs)
                return@map track.toTrackedPerson()
            }

            if (countAsDetectionMiss) {
                val inOcclusionGroup = occlusionGroups.any { it.trackIds.contains(track.id) }
                val isOccluded = inOcclusionGroup || track.state == TrackState.OCCLUDED

                if (isOccluded) {
                    track.occludedFrames++
                    track.state = TrackState.OCCLUDED
                    // No fresh observation exists on this frame, so never hand
                    // motion ownership to an arbitrary occluder. Hold the last
                    // accumulated group anchor and blend it with this identity's
                    // own Kalman prediction to remain bounded until evidence
                    // returns.
                    val groupPredBox = track.occlusionMotionBbox ?: track.lastObservedBbox
                    track.occlusionMotionBbox = groupPredBox
                    val boundedPredBox = blendBboxes(predBox, groupPredBox, OCCLUSION_GROUP_MOTION_BLEND)
                    track.currentPredictedBbox = boundedPredBox
                    track.kalman.dampenVelocity(0.50f)
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = warpMask(
                            sourceMask = track.lastObservedMask!!,
                            prevBbox = track.lastObservedBbox,
                            predBbox = boundedPredBox,
                            missedFrames = 0
                        )
                    }
                } else if (track.state == TrackState.REACQUIRING) {
                    track.reacquireFrames++
                    track.kalman.dampenVelocity(0.70f)
                    val boundedPredBox = boundProtectedUnobservedPrediction(track, predBox)
                    track.currentPredictedBbox = boundedPredBox
                    if (track.reacquireFrames <= config.postOcclusionGraceFrames) {
                        if (track.lastObservedMask != null) {
                            track.currentRenderMask = warpMask(
                                sourceMask = track.lastObservedMask!!,
                                prevBbox = track.lastObservedBbox,
                                predBbox = boundedPredBox,
                                missedFrames = 0
                            )
                        }
                    } else {
                        track.state = TrackState.LOST
                        track.lostFrames = 1
                        track.occludedByTrackIds.clear()
                        track.occlusionMotionBbox = null
                        if (track.lastObservedMask != null) {
                            track.currentRenderMask = updateLostMask(
                                canonicalMask = track.lastObservedMask!!,
                                observedBbox = track.lastObservedBbox,
                                predBbox = boundedPredBox,
                                missedFrames = track.lostFrames
                            )
                        }
                    }
                } else {
                    track.state = TrackState.LOST
                    track.lostFrames++
                    track.occlusionMotionBbox = null
                    val boundedPredBox = boundProtectedUnobservedPrediction(track, predBox)
                    track.currentPredictedBbox = boundedPredBox
                    if (track.lastObservedMask != null) {
                        track.currentRenderMask = updateLostMask(
                            canonicalMask = track.lastObservedMask!!,
                            observedBbox = track.lastObservedBbox,
                            predBbox = boundedPredBox,
                            missedFrames = track.lostFrames
                        )
                    }
                    if (track.lostFrames > config.maxMissedFrames) {
                        if (protectedTrackIds.contains(track.id)) {
                            track.currentRenderMask = null
                        } else {
                            track.state = TrackState.REMOVED
                        }
                    }
                }
            } else {
                // Prediction during skipped inference cadence (stride):
                if (protectedTrackIds.contains(track.id) &&
                    track.state == TrackState.LOST &&
                    track.lostFrames > config.maxMissedFrames
                ) {
                    track.currentRenderMask = null
                } else if (track.lastObservedMask != null) {
                    val boundedPredBox = if (
                        protectedTrackIds.contains(track.id) &&
                        (track.state == TrackState.REACQUIRING || track.state == TrackState.LOST)
                    ) {
                        boundProtectedUnobservedPrediction(track, predBox)
                    } else {
                        predBox
                    }
                    track.currentPredictedBbox = boundedPredBox
                    track.currentRenderMask = warpMask(
                        sourceMask = track.lastObservedMask!!,
                        prevBbox = track.lastObservedBbox,
                        predBbox = boundedPredBox,
                        missedFrames = 0
                    )
                }
            }

            if (countAsDetectionMiss) {
                suppressStaleMixedFullBodyMaskIfNeeded(track, timestampUs)
            }

            track.toTrackedPerson()
        }.filter { it.state != TrackState.REMOVED }

        tracks.removeAll { it.state == TrackState.REMOVED }

        return activeOrLost
    }

    override fun reset() {
        tracks.clear()
        occlusionGroups.clear()
        protectedTrackIds.clear()
        currentPrivacyClassEvidence.clear()
        currentStrictUnselectedPrivacyEvidence.clear()
        currentProtectedTrackMotionEvidence.clear()
        currentUncertainOccluderTrackIdsByProtectedTrackId.clear()
        currentPrivacySuppressedSelectedTrackIds.clear()
        currentHardPrivacyClassByDetectionIndex.clear()
        nextTrackId = 0
        hasInitialized = false
        privacyOffscreenDormancyEnabled = false
    }

    companion object {
        private const val ASSOCIATION_MASK_IOU_SAMPLE_STRIDE = 4
        private const val OCCLUSION_GROUP_MOTION_BLEND = 0.50f
        private const val PROTECTED_GROUP_ACTIVE_MIN_BBOX_IOU = 0.35f
        private const val PROTECTED_GROUP_ACTIVE_MIN_MASK_IOU = 0.20f
        private const val PROTECTED_GROUP_REACQUIRE_MIN_BBOX_IOU = 0.45f
        private const val PROTECTED_GROUP_REACQUIRE_MIN_MASK_IOU = 0.25f
        private const val PROTECTED_RECOVERY_MIN_BBOX_IOU = 0.50f
        private const val PROTECTED_RECOVERY_MIN_MASK_IOU = 0.45f
        private const val PROTECTED_MOTION_MIN_BBOX_IOU = 0.20f
        private const val PROTECTED_MOTION_MIN_MASK_IOU = 0.08f
        private const val PROTECTED_UNOBSERVED_MAX_CENTER_TRAVEL_RATIO = 0.30f
        private const val PROTECTED_UNOBSERVED_MIN_SCALE = 0.82f
        private const val PROTECTED_UNOBSERVED_MAX_SCALE = 1.18f
        private const val OFFSCREEN_EXIT_EDGE_RATIO = 0.06f
        private const val OFFSCREEN_EXIT_MIN_STEP_RATIO = 0.03f
        private const val MIXED_FULL_BODY_MAX_RENDER_MISS_FRAMES = 3
        private const val GROUP_RESERVATION_MIN_EDGE_PENETRATION_PX = 1.0f
        private const val GROUP_RESERVATION_MIN_EDGE_PENETRATION_RATIO = 0.01f
        private const val OCCLUSION_OVERLAP_EDGE_TELEMETRY_MARGIN = 0.10f

        internal fun isStrictFaceOnlyReservationRescueEligible(
            wouldStrictGlobalCommit: Boolean,
            faceOnlyIdentityProtected: Boolean,
            reservationOwnerGroupCount: Int
        ): Boolean =
            wouldStrictGlobalCommit &&
                faceOnlyIdentityProtected &&
                reservationOwnerGroupCount >= 2

        internal fun isProtectedLostAnchorOcclusionSupported(
            trackState: TrackState,
            identityProtected: Boolean,
            predictionAnchorIoU: Float,
            anchorFreshOverlapRatio: Float,
            overlapThreshold: Float
        ): Boolean =
            identityProtected &&
                trackState == TrackState.LOST &&
                predictionAnchorIoU >= overlapThreshold &&
                anchorFreshOverlapRatio >= overlapThreshold

        internal fun isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
            groupTrackCount: Int,
            trackState: TrackState,
            identityProtected: Boolean,
            privacySelected: Boolean,
            bestScore: Float,
            minMatchScore: Float,
            protectedIdentityEvidenceOk: Boolean,
            protectedMaskIoU: Float,
            winnerIdentityProtected: Boolean,
            winnerMaskIoU: Float?
        ): Boolean =
            groupTrackCount == 2 &&
                trackState == TrackState.OCCLUDED &&
                identityProtected &&
                !privacySelected &&
                bestScore >= minMatchScore &&
                protectedIdentityEvidenceOk &&
                protectedMaskIoU > 0f &&
                !winnerIdentityProtected &&
                winnerMaskIoU != null &&
                protectedMaskIoU >= winnerMaskIoU

        fun boundPredictionAroundAnchor(
            anchor: FloatRect,
            predicted: FloatRect,
            maxCenterTravelRatio: Float = PROTECTED_UNOBSERVED_MAX_CENTER_TRAVEL_RATIO,
            minScale: Float = PROTECTED_UNOBSERVED_MIN_SCALE,
            maxScale: Float = PROTECTED_UNOBSERVED_MAX_SCALE
        ): FloatRect {
            val anchorWidth = anchor.width.coerceAtLeast(1f)
            val anchorHeight = anchor.height.coerceAtLeast(1f)
            val refDim = max(anchorWidth, anchorHeight)
            val maxTravel = refDim * maxCenterTravelRatio.coerceAtLeast(0f)
            val dx = predicted.centerX - anchor.centerX
            val dy = predicted.centerY - anchor.centerY
            val distance = sqrt(dx * dx + dy * dy)
            val travelScale = if (distance > maxTravel && distance > 1e-4f) {
                maxTravel / distance
            } else {
                1f
            }
            val centerX = anchor.centerX + dx * travelScale
            val centerY = anchor.centerY + dy * travelScale
            val safeMinScale = minScale.coerceAtLeast(0.1f)
            val safeMaxScale = max(maxScale, safeMinScale)
            val width = predicted.width.coerceIn(anchorWidth * safeMinScale, anchorWidth * safeMaxScale)
            val height = predicted.height.coerceIn(anchorHeight * safeMinScale, anchorHeight * safeMaxScale)
            return FloatRect(
                left = centerX - width * 0.5f,
                top = centerY - height * 0.5f,
                right = centerX + width * 0.5f,
                bottom = centerY + height * 0.5f
            )
        }

        fun medianOf(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) * 0.5f
            }
        }

        fun blendBboxes(own: FloatRect, group: FloatRect, groupWeight: Float): FloatRect {
            val w = groupWeight.coerceIn(0f, 1f)
            val ownWeight = 1f - w
            return FloatRect(
                left = own.left * ownWeight + group.left * w,
                top = own.top * ownWeight + group.top * w,
                right = own.right * ownWeight + group.right * w,
                bottom = own.bottom * ownWeight + group.bottom * w
            )
        }
        const val LOST_WARP_MAX_FRAMES = 3
        const val LOST_MARGIN_TIER1_RATIO = 0.15f // 15% margin for frames 4..10
        const val LOST_MARGIN_TIER2_RATIO = 0.25f // 25% margin for frames > 10

        fun isProtectedGroupIdentityEvidenceSufficient(
            state: TrackState,
            bboxIoU: Float,
            maskIoU: Float
        ): Boolean {
            return when (state) {
                // Once a protected identity has timed out to LOST, group/global
                // association must not become an easier recovery path than the
                // dedicated LOST recovery stage. A real mixed-video failure had
                // FULL_BODY id=4 commit from LOST at bboxIoU=0.458/maskIoU=0.20,
                // which passed the former ACTIVE fallback threshold despite
                // being too weak for ordinary protected recovery.
                TrackState.LOST ->
                    bboxIoU >= PROTECTED_RECOVERY_MIN_BBOX_IOU ||
                        maskIoU >= PROTECTED_RECOVERY_MIN_MASK_IOU
                TrackState.OCCLUDED, TrackState.REACQUIRING ->
                    bboxIoU >= PROTECTED_GROUP_REACQUIRE_MIN_BBOX_IOU ||
                        maskIoU >= PROTECTED_GROUP_REACQUIRE_MIN_MASK_IOU
                else ->
                    bboxIoU >= PROTECTED_GROUP_ACTIVE_MIN_BBOX_IOU ||
                        maskIoU >= PROTECTED_GROUP_ACTIVE_MIN_MASK_IOU
            }
        }

        fun isProtectedMotionEvidenceSufficient(
            bboxIoU: Float,
            maskIoU: Float
        ): Boolean =
            bboxIoU >= PROTECTED_MOTION_MIN_BBOX_IOU ||
                maskIoU >= PROTECTED_MOTION_MIN_MASK_IOU

        fun computeBBoxIntersectionArea(boxA: FloatRect, boxB: FloatRect): Float {
            val interX1 = max(boxA.left, boxB.left)
            val interY1 = max(boxA.top, boxB.top)
            val interX2 = min(boxA.right, boxB.right)
            val interY2 = min(boxA.bottom, boxB.bottom)

            val interW = max(0f, interX2 - interX1)
            val interH = max(0f, interY2 - interY1)
            return interW * interH
        }

        private fun hasMeaningfulGroupReservationOverlap(boxA: FloatRect, boxB: FloatRect): Boolean {
            val interW = min(boxA.right, boxB.right) - max(boxA.left, boxB.left)
            val interH = min(boxA.bottom, boxB.bottom) - max(boxA.top, boxB.top)
            if (interW <= 0f || interH <= 0f) return false

            // Group reservation exists to keep real fragments / duplicate detections
            // inside an occlusion group from leaking into Global Hungarian. A mere
            // edge graze must not claim ownership: the same 1080p frame diverged
            // across devices when one bbox edge differed by sub-pixel amounts and
            // the old `intersectionArea > 0` boolean flipped. Require a tiny but
            // meaningful penetration on both axes while leaving all identity,
            // motion, and occlusion-group commit gates unchanged.
            val minWidth = min(boxA.width, boxB.width).coerceAtLeast(1f)
            val minHeight = min(boxA.height, boxB.height).coerceAtLeast(1f)
            val requiredW = max(
                GROUP_RESERVATION_MIN_EDGE_PENETRATION_PX,
                minWidth * GROUP_RESERVATION_MIN_EDGE_PENETRATION_RATIO
            )
            val requiredH = max(
                GROUP_RESERVATION_MIN_EDGE_PENETRATION_PX,
                minHeight * GROUP_RESERVATION_MIN_EDGE_PENETRATION_RATIO
            )
            return interW >= requiredW && interH >= requiredH
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

        fun computeMaskIoU(maskA: NativeMask?, maskB: NativeMask?, sampleStride: Int = 1): Float {
            if (maskA == null || maskB == null) return 0f
            if (maskA.width != maskB.width || maskA.height != maskB.height) return 0f

            val stride = sampleStride.coerceAtLeast(1)
            val width = maskA.width
            val height = maskA.height
            val bufA = maskA.buffer
            val bufB = maskB.buffer
            bufA.rewind()
            bufB.rewind()

            var intersection = 0
            var union = 0
            for (y in 0 until height step stride) {
                val row = y * width
                for (x in 0 until width step stride) {
                    val i = row + x
                    val a = (bufA.get(i).toInt() and 0xFF) > 128
                    val b = (bufB.get(i).toInt() and 0xFF) > 128
                    if (a && b) intersection++
                    if (a || b) union++
                }
            }

            return if (union == 0) 1.0f else intersection.toFloat() / union.toFloat()
        }

        fun computeWarpedMaskIoU(
            sourceMask: NativeMask?,
            prevBbox: FloatRect,
            predBbox: FloatRect,
            candidateMask: NativeMask?,
            sampleStride: Int = ASSOCIATION_MASK_IOU_SAMPLE_STRIDE
        ): Float {
            if (sourceMask == null || candidateMask == null) return 0f
            if (sourceMask.width != candidateMask.width || sourceMask.height != candidateMask.height) return 0f

            val w = sourceMask.width
            val h = sourceMask.height
            val stride = sampleStride.coerceAtLeast(1)
            val sourceBuf = sourceMask.buffer
            val candidateBuf = candidateMask.buffer

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
            val prevCenterX = mapper.sourceToProtoX(prevBbox.centerX)
            val prevCenterY = mapper.sourceToProtoY(prevBbox.centerY)
            val predCenterX = mapper.sourceToProtoX(predBbox.centerX)
            val predCenterY = mapper.sourceToProtoY(predBbox.centerY)

            var intersection = 0
            var union = 0
            var y = 0
            while (y < h) {
                val floatY = (y - predCenterY) / scaleY + prevCenterY
                val y0 = kotlin.math.floor(floatY).toInt()
                val y1 = y0 + 1
                val wy1 = (floatY - y0).coerceIn(0f, 1f)
                val wy0 = 1f - wy1

                var x = 0
                while (x < w) {
                    val floatX = (x - predCenterX) / scaleX + prevCenterX
                    val x0 = kotlin.math.floor(floatX).toInt()
                    val x1 = x0 + 1
                    val wx1 = (floatX - x0).coerceIn(0f, 1f)
                    val wx0 = 1f - wx1

                    fun sample(ix: Int, iy: Int): Int {
                        return if (ix in 0 until w && iy in 0 until h) {
                            sourceBuf.get(iy * w + ix).toInt() and 0xFF
                        } else {
                            0
                        }
                    }

                    val v00 = sample(x0, y0)
                    val v01 = sample(x1, y0)
                    val v10 = sample(x0, y1)
                    val v11 = sample(x1, y1)
                    val warped = (v00 * wx0 + v01 * wx1) * wy0 + (v10 * wx0 + v11 * wx1) * wy1
                    val a = warped > 128f
                    val b = (candidateBuf.get(y * w + x).toInt() and 0xFF) > 128
                    if (a && b) intersection++
                    if (a || b) union++
                    x += stride
                }
                y += stride
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
