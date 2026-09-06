package com.danceanon.native.privacy

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.PrivacySelectionClass
import com.danceanon.native.tracking.TrackManager
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.sqrt

/**
 * Tracks only the privacy class of fresh YOLO detections. It intentionally does
 * not expose or mutate person IDs. Exact identity association can therefore stay
 * conservative (AMBIGUOUS = DO NOT COMMIT) while fresh segmentation continuity
 * still survives ordinary selected/unselected crossings.
 */
class PrivacyClassTemporalTracker(
    private val minClassScore: Float = 0.42f,
    private val minSingleClassScore: Float = 0.65f,
    private val minClassMargin: Float = 0.12f,
    private val maxPrototypeMisses: Int = 4,
    private val reuseFrameSimilarityCache: Boolean = true,
    private val reuseFrameWarpedMaskSupportCache: Boolean = true,
    private val countSimilarityEvaluations: Boolean = false
) {
    private data class Prototype(
        val selectionClass: PrivacySelectionClass,
        var bbox: FloatRect,
        var mask: NativeMask?,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var misses: Int = 0,
        var reliability: Float = 1f
    ) {
        fun predictedBbox(): FloatRect = bbox.offset(velocityX, velocityY)
    }

    private val prototypes = mutableListOf<Prototype>()
    private var rootSeeded = false
    internal var lastSimilarityEvaluationCount: Int = 0
        private set

    private data class WarpedMaskSupport(
        val width: Int,
        val height: Int,
        val sampleStride: Int,
        val sampledForeground: BooleanArray
    )

    fun reset() {
        prototypes.clear()
        rootSeeded = false
    }

    /**
     * The first non-empty hard-class map is the immutable root selection from
     * analysis/export initialization. Later hard maps are deliberately ignored:
     * runtime TrackManager IDs are not privacy-class truth because an ambiguous
     * identity can be re-created under a new unprotected ID.
     *
     * Returns a privacy-class decision for every current detection that has a
     * mask. Detections whose class remains ambiguous are emitted conservatively
     * as SELECTED for rendering, but they do not update class prototypes.
     */
    fun update(
        detections: List<PersonDetection>,
        hardClassByDetectionIndex: Map<Int, PrivacySelectionClass>,
        ptsUs: Long
    ): List<FreshPrivacyClassEvidence> {
        lastSimilarityEvaluationCount = 0
        if (detections.isEmpty()) {
            advanceMissingPrototypes()
            emitSummary(
                ptsUs = ptsUs,
                hardSelected = 0,
                hardUnselected = 0,
                inferredSelected = 0,
                inferredUnselected = 0,
                unknown = 0
            )
            return emptyList()
        }

        val rootClassByDetectionIndex = if (!rootSeeded && hardClassByDetectionIndex.isNotEmpty()) {
            rootSeeded = true
            hardClassByDetectionIndex
        } else {
            emptyMap()
        }

        val classified = mutableMapOf<Int, PrivacySelectionClass>()
        var hardSelected = 0
        var hardUnselected = 0
        var inferredSelected = 0
        var inferredUnselected = 0
        val hasSelectedHistory = prototypes.any { it.selectionClass == PrivacySelectionClass.SELECTED }
        val hasUnselectedHistory = prototypes.any { it.selectionClass == PrivacySelectionClass.UNSELECTED }
        val similarityCache = if (reuseFrameSimilarityCache) {
            IdentityHashMap<Prototype, FloatArray>()
        } else {
            null
        }
        val warpedMaskSupportCache = if (reuseFrameWarpedMaskSupportCache) {
            IdentityHashMap<Prototype, WarpedMaskSupport>()
        } else {
            null
        }

        for ((index, selectionClass) in rootClassByDetectionIndex) {
            if (index !in detections.indices) continue
            classified[index] = selectionClass
            if (selectionClass == PrivacySelectionClass.SELECTED) hardSelected++ else hardUnselected++
        }

        for (index in detections.indices) {
            if (classified.containsKey(index)) continue
            val detection = detections[index]
            val selectedScore = bestClassScore(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = index,
                detection = detection,
                detections = detections,
                similarityCache = similarityCache,
                warpedMaskSupportCache = warpedMaskSupportCache
            )
            val unselectedScore = bestClassScore(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = index,
                detection = detection,
                detections = detections,
                similarityCache = similarityCache,
                warpedMaskSupportCache = warpedMaskSupportCache
            )

            val inferredClass = when {
                hasSelectedHistory && hasUnselectedHistory &&
                    selectedScore >= minClassScore &&
                    (selectedScore - unselectedScore) >= minClassMargin -> PrivacySelectionClass.SELECTED

                hasSelectedHistory && hasUnselectedHistory &&
                    unselectedScore >= minClassScore &&
                    (unselectedScore - selectedScore) >= minClassMargin -> PrivacySelectionClass.UNSELECTED

                hasSelectedHistory && !hasUnselectedHistory && selectedScore >= minSingleClassScore ->
                    PrivacySelectionClass.SELECTED

                hasUnselectedHistory && !hasSelectedHistory && unselectedScore >= minSingleClassScore ->
                    PrivacySelectionClass.UNSELECTED

                else -> null
            }

            if (inferredClass != null) {
                classified[index] = inferredClass
                if (inferredClass == PrivacySelectionClass.SELECTED) inferredSelected++ else inferredUnselected++
            }
        }

        updatePrototypes(
            detections = detections,
            classified = classified,
            hardClassByDetectionIndex = rootClassByDetectionIndex,
            similarityCache = similarityCache,
            warpedMaskSupportCache = warpedMaskSupportCache
        )

        val unknown = detections.size - classified.size
        val frameEvidence = detections.indices.mapNotNull { index ->
            val detection = detections[index]
            if (detection.mask == null) return@mapNotNull null
            FreshPrivacyClassEvidence(
                selectionClass = classified[index] ?: PrivacySelectionClass.SELECTED,
                detectionIndex = index,
                detection = detection,
                residualTrackIds = emptySet(),
                conservativeUnknown = !classified.containsKey(index)
            )
        }

        emitSummary(
            ptsUs = ptsUs,
            hardSelected = hardSelected,
            hardUnselected = hardUnselected,
            inferredSelected = inferredSelected,
            inferredUnselected = inferredUnselected,
            unknown = unknown
        )
        return frameEvidence
    }

    private fun bestClassScore(
        selectionClass: PrivacySelectionClass,
        detectionIndex: Int,
        detection: PersonDetection,
        detections: List<PersonDetection>,
        similarityCache: IdentityHashMap<Prototype, FloatArray>?,
        warpedMaskSupportCache: IdentityHashMap<Prototype, WarpedMaskSupport>?
    ): Float {
        var best = 0f
        for (prototype in prototypes) {
            if (prototype.selectionClass != selectionClass) continue
            val score = similarityForFrame(
                prototype = prototype,
                detectionIndex = detectionIndex,
                detection = detection,
                detections = detections,
                similarityCache = similarityCache,
                warpedMaskSupportCache = warpedMaskSupportCache
            ) * prototype.reliability.coerceIn(0f, 1f)
            if (score > best) best = score
        }
        return best
    }

    private fun similarityForFrame(
        prototype: Prototype,
        detectionIndex: Int,
        detection: PersonDetection,
        detections: List<PersonDetection>,
        similarityCache: IdentityHashMap<Prototype, FloatArray>?,
        warpedMaskSupportCache: IdentityHashMap<Prototype, WarpedMaskSupport>?
    ): Float {
        if (similarityCache == null) {
            if (countSimilarityEvaluations) lastSimilarityEvaluationCount++
            return similarity(prototype, detection, warpedMaskSupportCache)
        }
        val values = similarityCache[prototype] ?: FloatArray(detections.size) { Float.NaN }
            .also { similarityCache[prototype] = it }
        val cached = values[detectionIndex]
        if (!cached.isNaN()) return cached
        if (countSimilarityEvaluations) lastSimilarityEvaluationCount++
        return similarity(prototype, detection, warpedMaskSupportCache).also { values[detectionIndex] = it }
    }

    private fun similarity(
        prototype: Prototype,
        detection: PersonDetection,
        warpedMaskSupportCache: IdentityHashMap<Prototype, WarpedMaskSupport>?
    ): Float {
        val predicted = prototype.predictedBbox()
        val bboxIoU = TrackManager.computeBBoxIoU(predicted, detection.bbox)
        val dx = predicted.centerX - detection.bbox.centerX
        val dy = predicted.centerY - detection.bbox.centerY
        val distance = sqrt(dx * dx + dy * dy)
        val referenceDim = maxOf(
            predicted.width,
            predicted.height,
            detection.bbox.width,
            detection.bbox.height,
            1f
        )
        val distanceScore = (1f - distance / (referenceDim * 1.5f)).coerceIn(0f, 1f)
        val maskIoU = if (warpedMaskSupportCache == null) {
            TrackManager.computeWarpedMaskIoU(
                sourceMask = prototype.mask,
                prevBbox = prototype.bbox,
                predBbox = predicted,
                candidateMask = detection.mask,
                sampleStride = 4
            )
        } else {
            maskIoUFromCachedSupport(
                prototype = prototype,
                predicted = predicted,
                candidateMask = detection.mask,
                warpedMaskSupportCache = warpedMaskSupportCache
            )
        }
        return (0.40f * bboxIoU + 0.40f * maskIoU + 0.20f * distanceScore).coerceIn(0f, 1f)
    }

    private fun maskIoUFromCachedSupport(
        prototype: Prototype,
        predicted: FloatRect,
        candidateMask: NativeMask?,
        warpedMaskSupportCache: IdentityHashMap<Prototype, WarpedMaskSupport>
    ): Float {
        val sourceMask = prototype.mask ?: return 0f
        val candidate = candidateMask ?: return 0f
        if (sourceMask.width != candidate.width || sourceMask.height != candidate.height) return 0f
        val support = warpedMaskSupportCache[prototype] ?: buildWarpedMaskSupport(
            sourceMask = sourceMask,
            prevBbox = prototype.bbox,
            predBbox = predicted,
            sampleStride = 4
        ).also { warpedMaskSupportCache[prototype] = it }

        var intersection = 0
        var union = 0
        var sampleIndex = 0
        var y = 0
        while (y < support.height) {
            val row = y * support.width
            var x = 0
            while (x < support.width) {
                val a = support.sampledForeground[sampleIndex++]
                val b = (candidate.buffer.get(row + x).toInt() and 0xFF) > 128
                if (a && b) intersection++
                if (a || b) union++
                x += support.sampleStride
            }
            y += support.sampleStride
        }
        return if (union == 0) 1.0f else intersection.toFloat() / union.toFloat()
    }

    private fun buildWarpedMaskSupport(
        sourceMask: NativeMask,
        prevBbox: FloatRect,
        predBbox: FloatRect,
        sampleStride: Int
    ): WarpedMaskSupport {
        val w = sourceMask.width
        val h = sourceMask.height
        val stride = sampleStride.coerceAtLeast(1)
        val sourceBuf = sourceMask.buffer
        val prevW = maxOf(1f, prevBbox.width)
        val prevH = maxOf(1f, prevBbox.height)
        val predW = maxOf(1f, predBbox.width)
        val predH = maxOf(1f, predBbox.height)
        val scaleX = predW / prevW
        val scaleY = predH / prevH
        val mapper = sourceMask.mapper ?: com.danceanon.native.geometry.ModelCoordinateMapper(
            srcWidth = maxOf(1, sourceMask.originalWidth),
            srcHeight = maxOf(1, sourceMask.originalHeight),
            modelInputSize = 640,
            protoSize = w
        )
        val prevCenterX = mapper.sourceToProtoX(prevBbox.centerX)
        val prevCenterY = mapper.sourceToProtoY(prevBbox.centerY)
        val predCenterX = mapper.sourceToProtoX(predBbox.centerX)
        val predCenterY = mapper.sourceToProtoY(predBbox.centerY)
        val sampleWidth = (w + stride - 1) / stride
        val sampleHeight = (h + stride - 1) / stride
        val sampledForeground = BooleanArray(sampleWidth * sampleHeight)
        var sampleIndex = 0
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
                fun sample(ix: Int, iy: Int): Int =
                    if (ix in 0 until w && iy in 0 until h) {
                        sourceBuf.get(iy * w + ix).toInt() and 0xFF
                    } else {
                        0
                    }
                val v00 = sample(x0, y0)
                val v01 = sample(x1, y0)
                val v10 = sample(x0, y1)
                val v11 = sample(x1, y1)
                val warped = (v00 * wx0 + v01 * wx1) * wy0 + (v10 * wx0 + v11 * wx1) * wy1
                sampledForeground[sampleIndex++] = warped > 128f
                x += stride
            }
            y += stride
        }
        return WarpedMaskSupport(w, h, stride, sampledForeground)
    }

    private fun updatePrototypes(
        detections: List<PersonDetection>,
        classified: Map<Int, PrivacySelectionClass>,
        hardClassByDetectionIndex: Map<Int, PrivacySelectionClass>,
        similarityCache: IdentityHashMap<Prototype, FloatArray>?,
        warpedMaskSupportCache: IdentityHashMap<Prototype, WarpedMaskSupport>?
    ) {
        val updated = Collections.newSetFromMap(IdentityHashMap<Prototype, Boolean>())

        for (selectionClass in PrivacySelectionClass.entries) {
            val oldClassPrototypes = prototypes.filter { it.selectionClass == selectionClass }
            val currentIndices = classified.entries
                .filter { it.value == selectionClass }
                .map { it.key }

            if (oldClassPrototypes.isNotEmpty() && currentIndices.isNotEmpty()) {
                val costs = Array(oldClassPrototypes.size) { r ->
                    FloatArray(currentIndices.size) { c ->
                        val detectionIndex = currentIndices[c]
                        1f - similarityForFrame(
                            prototype = oldClassPrototypes[r],
                            detectionIndex = detectionIndex,
                            detection = detections[detectionIndex],
                            detections = detections,
                            similarityCache = similarityCache,
                            warpedMaskSupportCache = warpedMaskSupportCache
                        )
                    }
                }
                val matches = HungarianSolver.match(costs, maxCostThreshold = 0.75f)
                val matchedCurrent = mutableSetOf<Int>()

                for ((prototypeRow, currentCol) in matches.matches) {
                    val prototype = oldClassPrototypes[prototypeRow]
                    val detectionIndex = currentIndices[currentCol]
                    val detection = detections[detectionIndex]
                    val dx = detection.bbox.centerX - prototype.bbox.centerX
                    val dy = detection.bbox.centerY - prototype.bbox.centerY
                    prototype.velocityX = prototype.velocityX * 0.45f + dx * 0.55f
                    prototype.velocityY = prototype.velocityY * 0.45f + dy * 0.55f
                    prototype.bbox = detection.bbox
                    prototype.mask = detection.mask
                    prototype.misses = 0
                    prototype.reliability = if (hardClassByDetectionIndex[detectionIndex] == selectionClass) {
                        1f
                    } else {
                        (prototype.reliability + 0.08f).coerceAtMost(0.92f)
                    }
                    updated.add(prototype)
                    matchedCurrent.add(detectionIndex)
                }

                for (detectionIndex in currentIndices) {
                    if (matchedCurrent.contains(detectionIndex)) continue
                    val prototype = Prototype(
                        selectionClass = selectionClass,
                        bbox = detections[detectionIndex].bbox,
                        mask = detections[detectionIndex].mask,
                        reliability = if (hardClassByDetectionIndex[detectionIndex] == selectionClass) 1f else 0.78f
                    )
                    prototypes.add(prototype)
                    updated.add(prototype)
                }
            } else if (currentIndices.isNotEmpty()) {
                for (detectionIndex in currentIndices) {
                    val prototype = Prototype(
                        selectionClass = selectionClass,
                        bbox = detections[detectionIndex].bbox,
                        mask = detections[detectionIndex].mask,
                        reliability = if (hardClassByDetectionIndex[detectionIndex] == selectionClass) 1f else 0.78f
                    )
                    prototypes.add(prototype)
                    updated.add(prototype)
                }
            }
        }

        val iterator = prototypes.iterator()
        while (iterator.hasNext()) {
            val prototype = iterator.next()
            if (updated.contains(prototype)) continue
            prototype.bbox = prototype.predictedBbox()
            prototype.velocityX *= 0.75f
            prototype.velocityY *= 0.75f
            prototype.misses++
            prototype.reliability *= 0.72f
            if (prototype.misses > maxPrototypeMisses || prototype.reliability < 0.18f) {
                iterator.remove()
            }
        }
    }

    private fun advanceMissingPrototypes() {
        val iterator = prototypes.iterator()
        while (iterator.hasNext()) {
            val prototype = iterator.next()
            prototype.bbox = prototype.predictedBbox()
            prototype.velocityX *= 0.75f
            prototype.velocityY *= 0.75f
            prototype.misses++
            prototype.reliability *= 0.72f
            if (prototype.misses > maxPrototypeMisses || prototype.reliability < 0.18f) {
                iterator.remove()
            }
        }
    }

    private fun emitSummary(
        ptsUs: Long,
        hardSelected: Int,
        hardUnselected: Int,
        inferredSelected: Int,
        inferredUnselected: Int,
        unknown: Int
    ) {
        NativeDiagnostics.eventLazy(
            level = "INFO",
            component = "PrivacyClassTemporalTracker",
            event = "PRIVACY_CLASS_TEMPORAL_SUMMARY",
            fields = { mapOf(
                "hard_selected" to hardSelected,
                "hard_unselected" to hardUnselected,
                "inferred_selected" to inferredSelected,
                "inferred_unselected" to inferredUnselected,
                "unknown" to unknown,
                "selected_prototypes" to prototypes.count { it.selectionClass == PrivacySelectionClass.SELECTED },
                "unselected_prototypes" to prototypes.count { it.selectionClass == PrivacySelectionClass.UNSELECTED },
                "pts_us" to ptsUs
            ) }
        )
    }
}
