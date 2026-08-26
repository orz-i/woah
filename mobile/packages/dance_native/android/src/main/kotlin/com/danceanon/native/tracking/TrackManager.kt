package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class InternalTrack(
    val id: Int,
    var bbox: FloatRect,
    var mask: NativeMask?,
    var confidence: Float,
    val kalman: KalmanFilter = KalmanFilter(),
    var state: TrackState = TrackState.ACTIVE,
    var missedFrames: Int = 0,
    var age: Int = 1
) {
    init {
        kalman.init(bbox)
    }

    fun toTrackedPerson(): TrackedPerson {
        return TrackedPerson(
            id = id,
            bbox = bbox,
            mask = mask,
            confidence = confidence,
            missedFrames = missedFrames,
            age = age,
            state = state
        )
    }
}

class TrackManager(
    private val maxMissedFrames: Int = 60
) : PersonTracker {

    private val tracks = mutableListOf<InternalTrack>()
    private var nextTrackId = 0

    override fun initialize(detections: List<PersonDetection>): List<TrackedPerson> {
        val defaultIds = detections.indices.toList()
        return initializeWithAssignedIds(detections, defaultIds)
    }

    fun initializeWithAssignedIds(
        detections: List<PersonDetection>,
        assignedIds: List<Int>
    ): List<TrackedPerson> {
        tracks.clear()
        nextTrackId = 0
        for ((index, det) in detections.withIndex()) {
            val trackId = if (index < assignedIds.size) assignedIds[index] else nextTrackId
            val track = InternalTrack(
                id = trackId,
                bbox = det.bbox,
                mask = det.mask,
                confidence = det.confidence,
                state = TrackState.ACTIVE
            )
            tracks.add(track)
            nextTrackId = maxOf(nextTrackId, trackId + 1)
        }
        return tracks.map { it.toTrackedPerson() }
    }

    override fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson> {
        if (tracks.isEmpty()) {
            return initialize(detections)
        }

        if (detections.isEmpty()) {
            return predict(timestampUs)
        }

        // 1. Predict all tracks with Kalman Filter
        val predictedBoxes = tracks.map { track ->
            val pred = track.kalman.predict()
            track.age++
            pred
        }

        // 2. Compute cost matrix based on IoU + Euclidean Center Distance
        val costMatrix = Array(tracks.size) { r ->
            val predBox = predictedBoxes[r]
            FloatArray(detections.size) { c ->
                val detBox = detections[c].bbox
                val iou = computeIoU(predBox, detBox)
                val dx = (predBox.centerX - detBox.centerX) / 640f
                val dy = (predBox.centerY - detBox.centerY) / 640f
                val dist = sqrt(dx * dx + dy * dy).coerceIn(0f, 1f)
                // Low cost when IoU is high or centers are close
                (0.6f * (1.0f - iou) + 0.4f * dist).coerceIn(0f, 1f)
            }
        }

        val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = 0.85f)
        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        for (match in matchResult.matches) {
            val trackIdx = match.first
            val detIdx = match.second
            matchedTrackIndices.add(trackIdx)
            matchedDetectionIndices.add(detIdx)

            val track = tracks[trackIdx]
            val det = detections[detIdx]
            track.bbox = det.bbox
            track.mask = det.mask ?: track.mask
            track.confidence = det.confidence
            track.missedFrames = 0
            track.state = TrackState.ACTIVE
            track.kalman.update(det.bbox)
        }

        // 3. Handle unmatched tracks
        for (i in tracks.indices) {
            if (!matchedTrackIndices.contains(i)) {
                val track = tracks[i]
                track.missedFrames++
                track.bbox = predictedBoxes[i]
                if (track.missedFrames > maxMissedFrames) {
                    track.state = TrackState.REMOVED
                } else {
                    track.state = TrackState.LOST
                }
            }
        }

        // 4. Add new tracks for unmatched detections
        for (c in detections.indices) {
            if (!matchedDetectionIndices.contains(c)) {
                val det = detections[c]
                val newTrack = InternalTrack(
                    id = nextTrackId++,
                    bbox = det.bbox,
                    mask = det.mask,
                    confidence = det.confidence,
                    state = TrackState.ACTIVE
                )
                tracks.add(newTrack)
            }
        }

        // 5. Filter out REMOVED tracks
        tracks.removeAll { it.state == TrackState.REMOVED }

        return tracks.map { it.toTrackedPerson() }
    }

    override fun predict(timestampUs: Long): List<TrackedPerson> {
        return tracks.map { track ->
            track.bbox = track.kalman.predict()
            track.toTrackedPerson()
        }
    }

    override fun reset() {
        tracks.clear()
        nextTrackId = 0
    }

    private fun computeIoU(boxA: FloatRect, boxB: FloatRect): Float {
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
}
