package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import kotlin.math.max
import kotlin.math.min

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
    private val maxMissedFrames: Int = 30
) : PersonTracker {

    private val tracks = mutableListOf<InternalTrack>()

    override fun initialize(detections: List<PersonDetection>): List<TrackedPerson> {
        tracks.clear()
        for ((index, det) in detections.withIndex()) {
            val track = InternalTrack(
                id = index,
                bbox = det.bbox,
                mask = det.mask,
                confidence = det.confidence,
                state = TrackState.ACTIVE
            )
            tracks.add(track)
        }
        return tracks.map { it.toTrackedPerson() }
    }

    override fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson> {
        if (tracks.isEmpty()) {
            return initialize(detections)
        }

        // 1. Predict all tracks with Kalman Filter
        val predictedBoxes = tracks.map { track ->
            val pred = track.kalman.predict()
            track.age++
            pred
        }

        // 2. First Stage: Match ACTIVE tracks with high-confidence detections
        val activeIndices = tracks.indices.filter { tracks[it].state == TrackState.ACTIVE }
        val costMatrixActive = Array(activeIndices.size) { r ->
            val trackIdx = activeIndices[r]
            val predBox = predictedBoxes[trackIdx]
            FloatArray(detections.size) { c ->
                1.0f - computeIoU(predBox, detections[c].bbox)
            }
        }

        val firstMatch = HungarianSolver.match(costMatrixActive, maxCostThreshold = 0.55f)
        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        for (match in firstMatch.matches) {
            val trackIdx = activeIndices[match.first]
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

        // 3. Second Stage: Match LOST tracks with remaining unmatched detections
        val lostIndices = tracks.indices.filter { tracks[it].state == TrackState.LOST && !matchedTrackIndices.contains(it) }
        val remainingDetIndices = detections.indices.filter { !matchedDetectionIndices.contains(it) }

        if (lostIndices.isNotEmpty() && remainingDetIndices.isNotEmpty()) {
            val costMatrixLost = Array(lostIndices.size) { r ->
                val trackIdx = lostIndices[r]
                val predBox = predictedBoxes[trackIdx]
                FloatArray(remainingDetIndices.size) { c ->
                    val detIdx = remainingDetIndices[c]
                    1.0f - computeIoU(predBox, detections[detIdx].bbox)
                }
            }

            val secondMatch = HungarianSolver.match(costMatrixLost, maxCostThreshold = 0.70f)
            for (match in secondMatch.matches) {
                val trackIdx = lostIndices[match.first]
                val detIdx = remainingDetIndices[match.second]
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
        }

        // 4. Handle unmatched tracks
        for (i in tracks.indices) {
            if (!matchedTrackIndices.contains(i)) {
                val track = tracks[i]
                track.missedFrames++
                track.bbox = predictedBoxes[i] // Use predicted bounding box as fallback
                if (track.missedFrames > maxMissedFrames) {
                    track.state = TrackState.REMOVED
                } else {
                    track.state = TrackState.LOST
                }
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
