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
    val bboxIouWeight: Float = 0.60f,
    val maskIouWeight: Float = 0.10f,
    val motionWeight: Float = 0.30f,
    val minMatchScore: Float = 0.20f,
    val maxMissedFrames: Int = 30
)


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
                bbox = det.bbox,
                mask = det.mask,
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
                    bbox = det.bbox,
                    mask = det.mask,
                    confidence = det.confidence,
                    state = TrackState.ACTIVE
                )
                tracks.add(newTrack)
            }
            return tracks.map { it.toTrackedPerson() }
        }


        // 1. Predict all tracks with 8D Kalman Filter
        val previousBoxes = tracks.map { it.bbox }
        val predictedBoxes = tracks.map { track ->
            val pred = track.kalman.predict(timestampUs)
            track.age++
            pred
        }

        // 2. Compute cost matrix based on BBox IoU + Mask IoU + Motion Score
        val costMatrix = Array(tracks.size) { r ->
            val predBox = predictedBoxes[r]
            val trackMask = tracks[r].mask
            FloatArray(detections.size) { c ->
                val det = detections[c]
                val detBox = det.bbox

                val bIoU = computeBBoxIoU(predBox, detBox)
                val refDim = max(predBox.width, predBox.height)
                val dx = predBox.centerX - detBox.centerX
                val dy = predBox.centerY - detBox.centerY
                val dist = sqrt(dx * dx + dy * dy)

                // Spatial gating: maskIoU is only meaningful if detections are spatially proximate
                val isProximate = bIoU > 0f || dist < refDim * 0.8f
                val mIoU = if (isProximate) computeMaskIoU(trackMask, det.mask) else 0f

                val motionScore = if (refDim > 0f) {
                    (1.0f - (dist / (refDim * 1.5f))).coerceIn(0f, 1f)
                } else {
                    0f
                }

                val matchScore = config.bboxIouWeight * bIoU +
                                 config.maskIouWeight * mIoU +
                                 config.motionWeight * motionScore

                (1.0f - matchScore).coerceIn(0f, 1f)
            }
        }

        val maxCost = (1.0f - config.minMatchScore).coerceIn(0.1f, 0.95f)
        val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = maxCost)
        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        // 3. Update matched tracks
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
            track.kalman.update(det.bbox, timestampUs)
        }

        // 4. Handle unmatched (LOST) tracks: synchronize warp mask and handle expansion
        for (i in tracks.indices) {
            if (!matchedTrackIndices.contains(i)) {
                val track = tracks[i]
                val prevBox = previousBoxes[i]
                val predBox = predictedBoxes[i]

                track.missedFrames++
                track.bbox = predBox

                // Update mask for LOST tracks: warp for frames 1..3, conservative bbox fallback for frames >= 4
                if (track.mask != null) {
                    track.mask = updateLostMask(
                        sourceMask = track.mask!!,
                        prevBbox = prevBox,
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

        // Try to match unassigned detections with currently LOST tracks by proximity
        val lostTracks = tracks.filter { it.state == TrackState.LOST && !matchedTrackIndices.contains(tracks.indexOf(it)) }
        val reclaimedTrackIds = mutableSetOf<Int>()

        for (det in unassignedDetections) {
            var bestTrack: InternalTrack? = null
            var bestDist = Float.MAX_VALUE

            for (lost in lostTracks) {
                if (reclaimedTrackIds.contains(lost.id)) continue
                val dx = lost.bbox.centerX - det.bbox.centerX
                val dy = lost.bbox.centerY - det.bbox.centerY
                val dist = sqrt(dx * dx + dy * dy)
                val bIoU = computeBBoxIoU(lost.bbox, det.bbox)
                val maxAllowedDist = max(lost.bbox.width, lost.bbox.height) * 0.8f
                val isNearby = bIoU > 0.05f || dist < maxAllowedDist

                if (isNearby && dist < bestDist) {
                    bestDist = dist
                    bestTrack = lost
                }
            }

            if (bestTrack != null) {
                bestTrack.bbox = det.bbox
                bestTrack.mask = det.mask ?: bestTrack.mask
                bestTrack.confidence = det.confidence
                bestTrack.missedFrames = 0
                bestTrack.state = TrackState.ACTIVE
                bestTrack.kalman.init(det.bbox, timestampUs)
                reclaimedTrackIds.add(bestTrack.id)
            } else {
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

        // 6. Filter out REMOVED tracks
        tracks.removeAll { it.state == TrackState.REMOVED }

        return tracks.map { it.toTrackedPerson() }
    }

    override fun predict(timestampUs: Long): List<TrackedPerson> {
        val activeOrLost = tracks.map { track ->
            val prevBox = track.bbox
            val predBox = track.kalman.predict(timestampUs)
            track.missedFrames++
            track.bbox = predBox
            if (track.mask != null) {
                track.mask = updateLostMask(
                    sourceMask = track.mask!!,
                    prevBbox = prevBox,
                    predBbox = predBox,
                    missedFrames = track.missedFrames
                )
            }
            if (track.missedFrames > config.maxMissedFrames) {
                track.state = TrackState.REMOVED
            } else {
                track.state = TrackState.LOST
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
            sourceMask: NativeMask,
            prevBbox: FloatRect,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            if (missedFrames <= LOST_WARP_MAX_FRAMES) {
                return warpMask(
                    sourceMask = sourceMask,
                    prevBbox = prevBbox,
                    predBbox = predBbox,
                    missedFrames = missedFrames
                )
            }
            return generateConservativeFallbackMask(
                sourceMask = sourceMask,
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

            // BBox relative center shift mapped to proto coordinates via ModelCoordinateMapper
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
