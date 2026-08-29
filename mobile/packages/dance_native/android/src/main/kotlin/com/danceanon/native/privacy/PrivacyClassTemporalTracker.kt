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
    private val maxPrototypeMisses: Int = 4
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

    fun reset() {
        prototypes.clear()
    }

    /**
     * Returns only temporally inferred evidence. Hard evidence is consumed as a
     * seed/update signal but is already represented by TrackManager's fresh
     * tracked persons or deterministic residual evidence in the render path.
     */
    fun update(
        detections: List<PersonDetection>,
        hardClassByDetectionIndex: Map<Int, PrivacySelectionClass>,
        ptsUs: Long
    ): List<FreshPrivacyClassEvidence> {
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

        val classified = mutableMapOf<Int, PrivacySelectionClass>()
        val inferredIndices = mutableSetOf<Int>()
        var hardSelected = 0
        var hardUnselected = 0
        var inferredSelected = 0
        var inferredUnselected = 0

        for ((index, selectionClass) in hardClassByDetectionIndex) {
            if (index !in detections.indices) continue
            classified[index] = selectionClass
            if (selectionClass == PrivacySelectionClass.SELECTED) hardSelected++ else hardUnselected++
        }

        for (index in detections.indices) {
            if (classified.containsKey(index)) continue
            val detection = detections[index]
            val selectedScore = bestClassScore(PrivacySelectionClass.SELECTED, detection)
            val unselectedScore = bestClassScore(PrivacySelectionClass.UNSELECTED, detection)
            val hasSelectedHistory = prototypes.any { it.selectionClass == PrivacySelectionClass.SELECTED }
            val hasUnselectedHistory = prototypes.any { it.selectionClass == PrivacySelectionClass.UNSELECTED }

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
                inferredIndices.add(index)
                if (inferredClass == PrivacySelectionClass.SELECTED) inferredSelected++ else inferredUnselected++
            }
        }

        updatePrototypes(detections, classified, hardClassByDetectionIndex)

        val inferredEvidence = inferredIndices.mapNotNull { index ->
            val detection = detections[index]
            if (detection.mask == null) return@mapNotNull null
            FreshPrivacyClassEvidence(
                selectionClass = classified.getValue(index),
                detectionIndex = index,
                detection = detection,
                residualTrackIds = emptySet()
            )
        }

        emitSummary(
            ptsUs = ptsUs,
            hardSelected = hardSelected,
            hardUnselected = hardUnselected,
            inferredSelected = inferredSelected,
            inferredUnselected = inferredUnselected,
            unknown = detections.size - classified.size
        )
        return inferredEvidence
    }

    private fun bestClassScore(
        selectionClass: PrivacySelectionClass,
        detection: PersonDetection
    ): Float {
        var best = 0f
        for (prototype in prototypes) {
            if (prototype.selectionClass != selectionClass) continue
            val score = similarity(prototype, detection) * prototype.reliability.coerceIn(0f, 1f)
            if (score > best) best = score
        }
        return best
    }

    private fun similarity(prototype: Prototype, detection: PersonDetection): Float {
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
        val maskIoU = TrackManager.computeWarpedMaskIoU(
            sourceMask = prototype.mask,
            prevBbox = prototype.bbox,
            predBbox = predicted,
            candidateMask = detection.mask,
            sampleStride = 4
        )
        return (0.40f * bboxIoU + 0.40f * maskIoU + 0.20f * distanceScore).coerceIn(0f, 1f)
    }

    private fun updatePrototypes(
        detections: List<PersonDetection>,
        classified: Map<Int, PrivacySelectionClass>,
        hardClassByDetectionIndex: Map<Int, PrivacySelectionClass>
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
                        1f - similarity(oldClassPrototypes[r], detections[currentIndices[c]])
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
        NativeDiagnostics.event(
            level = "INFO",
            component = "PrivacyClassTemporalTracker",
            event = "PRIVACY_CLASS_TEMPORAL_SUMMARY",
            fields = mapOf(
                "hard_selected" to hardSelected,
                "hard_unselected" to hardUnselected,
                "inferred_selected" to inferredSelected,
                "inferred_unselected" to inferredUnselected,
                "unknown" to unknown,
                "selected_prototypes" to prototypes.count { it.selectionClass == PrivacySelectionClass.SELECTED },
                "unselected_prototypes" to prototypes.count { it.selectionClass == PrivacySelectionClass.UNSELECTED },
                "pts_us" to ptsUs
            )
        )
    }
}
