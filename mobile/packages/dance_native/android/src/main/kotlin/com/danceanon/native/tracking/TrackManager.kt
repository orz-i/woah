package com.danceanon.native.tracking

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
    val minMatchScore: Float = 0.15f,
    val bboxIouWeight: Float = 0.40f,
    val maskIouWeight: Float = 0.20f,
    val motionWeight: Float = 0.40f
)

/**
 * Internal tracking representation enforcing strict separation between:
 * 1. Canonical observed segmentation (lastObservedMask, lastObservedBbox)
 * 2. Predicted motion state (currentPredictedBbox)
 * 3. Rendered privacy fallback mask (currentRenderMask)
 * This guarantees zero recursive warping deformation and immediate lossless mask restoration on reacquisition.
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
    var missedFrames: Int = 0,
    var age: Int = 1
) {
    init {
        kalman.init(lastObservedBbox)
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
            state = state
        )
    }
}

class TrackManager(
    val config: TrackingConfig = TrackingConfig()
) : PersonTracker {

    private val tracks = mutableListOf<InternalTrack>()
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

    override fun update(detections: List<PersonDetection>, timestampUs: Long): List<TrackedPerson> {
        if (!hasInitialized) {
            return initialize(detections)
        }

        if (detections.isEmpty()) {
            return predict(timestampUs)
        }

        if (tracks.isEmpty()) {
            // Already initialized, but all prior tracks were removed.
            // Assign fresh incremental IDs without resetting nextTrackId.
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
            pred
        }

        // 2. Compute cost matrix based on BBox IoU + Mask IoU + Motion Score
        val costMatrix = Array(tracks.size) { r ->
            val predBox = predictedBoxes[r]
            val trackMask = tracks[r].lastObservedMask
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

                // Spatial gating: maskIoU is meaningful if detections are proximate or aligned horizontally for vertical dance jump
                val isProximate = bIoU > 0f || dist < refDim * 1.2f || (absDx < refDim * 0.7f && absDy < refDim * 2.2f)
                val mIoU = if (isProximate) computeMaskIoU(trackMask, det.mask) else 0f

                // Anisotropic dance motion score: vertical jump (small dx, large dy) is favored over lateral drift
                val motionScore = if (refDim > 0f) {
                    val weightedDist = sqrt(dx * dx * 2.5f + dy * dy * 0.6f)
                    (1.0f - (weightedDist / (refDim * 2.5f))).coerceIn(0f, 1f)
                } else {
                    0f
                }

                val matchScore = config.bboxIouWeight * bIoU +
                                 config.maskIouWeight * mIoU +
                                 config.motionWeight * motionScore

                (1.0f - matchScore).coerceIn(0f, 1f)
            }
        }

        val maxCost = (1.0f - config.minMatchScore).coerceIn(0.1f, 0.90f)
        val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = maxCost)
        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        // 3. Update matched tracks with fresh canonical observation
        for (match in matchResult.matches) {
            val trackIdx = match.first
            val detIdx = match.second
            matchedTrackIndices.add(trackIdx)
            matchedDetectionIndices.add(detIdx)

            val track = tracks[trackIdx]
            val det = detections[detIdx]
            track.lastObservedBbox = det.bbox
            track.lastObservedMask = det.mask ?: track.lastObservedMask
            track.currentPredictedBbox = det.bbox
            track.currentRenderMask = det.mask ?: track.lastObservedMask
            track.confidence = det.confidence
            track.missedFrames = 0
            track.state = TrackState.ACTIVE
            track.kalman.update(det.bbox, timestampUs)
        }

        // 4. Handle unmatched (LOST) tracks: transform directly from canonical lastObservedMask
        for (i in tracks.indices) {
            if (!matchedTrackIndices.contains(i)) {
                val track = tracks[i]
                val predBox = predictedBoxes[i]

                track.missedFrames++
                track.currentPredictedBbox = predBox

                if (track.lastObservedMask != null) {
                    track.currentRenderMask = updateLostMask(
                        canonicalMask = track.lastObservedMask!!,
                        observedBbox = track.lastObservedBbox,
                        predBbox = predBox,
                        missedFrames = track.missedFrames
                    )
                }

                if (track.missedFrames > config.maxMissedFrames) {
                    track.state = TrackState.REMOVED
                } else {
                    track.state = TrackState.LOST
                }
            }
        }

        // 5. Recovery association: associate unmatched detections with LOST tracks before creating new tracks
        val unassignedDetections = mutableListOf<PersonDetection>()
        for (c in detections.indices) {
            if (!matchedDetectionIndices.contains(c)) {
                unassignedDetections.add(detections[c])
            }
        }

        // Try to match unassigned detections with currently LOST tracks by proximity and horizontal alignment
        val lostTracks = tracks.filter { it.state == TrackState.LOST && !matchedTrackIndices.contains(tracks.indexOf(it)) }
        val reclaimedTrackIds = mutableSetOf<Int>()

        for (det in unassignedDetections) {
            var bestTrack: InternalTrack? = null
            var bestDist = Float.MAX_VALUE

            for (lost in lostTracks) {
                if (reclaimedTrackIds.contains(lost.id)) continue
                val dx = lost.currentPredictedBbox.centerX - det.bbox.centerX
                val dy = lost.currentPredictedBbox.centerY - det.bbox.centerY
                val dist = sqrt(dx * dx + dy * dy)
                val bIoU = computeBBoxIoU(lost.currentPredictedBbox, det.bbox)
                val refDim = max(lost.currentPredictedBbox.width, lost.currentPredictedBbox.height)
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                val isNearby = bIoU > 0.05f || dist < refDim * 0.9f || (absDx < refDim * 0.5f && absDy < refDim * 2.5f)

                if (isNearby && dist < bestDist) {
                    bestDist = dist
                    bestTrack = lost
                }
            }

            if (bestTrack != null) {
                // Reacquisition: immediately restore organic segmentation from new detection
                bestTrack.lastObservedBbox = det.bbox
                bestTrack.lastObservedMask = det.mask ?: bestTrack.lastObservedMask
                bestTrack.currentPredictedBbox = det.bbox
                bestTrack.currentRenderMask = det.mask ?: bestTrack.lastObservedMask
                bestTrack.confidence = det.confidence
                bestTrack.missedFrames = 0
                bestTrack.state = TrackState.ACTIVE
                bestTrack.kalman.init(det.bbox, timestampUs)
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
                track.missedFrames++
                if (track.lastObservedMask != null) {
                    track.currentRenderMask = updateLostMask(
                        canonicalMask = track.lastObservedMask!!,
                        observedBbox = track.lastObservedBbox,
                        predBbox = predBox,
                        missedFrames = track.missedFrames
                    )
                }
                if (track.missedFrames > config.maxMissedFrames) {
                    track.state = TrackState.REMOVED
                } else {
                    track.state = TrackState.LOST
                }
            } else {
                // Prediction during skipped inference cadence (stride):
                // Warp smoothly from canonical lastObservedMask without mutating lastObservedMask
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

        // Guarantee internal collection purge so removed tracks never participate in matching or revive
        tracks.removeAll { it.state == TrackState.REMOVED }

        return activeOrLost
    }

    override fun reset() {
        tracks.clear()
        nextTrackId = 0
        hasInitialized = false
    }

    companion object {
        const val LOST_WARP_MAX_FRAMES = 3
        const val LOST_MARGIN_TIER1_RATIO = 0.15f // 15% margin for frames 4..10
        const val LOST_MARGIN_TIER2_RATIO = 0.25f // 25% margin for frames > 10

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
