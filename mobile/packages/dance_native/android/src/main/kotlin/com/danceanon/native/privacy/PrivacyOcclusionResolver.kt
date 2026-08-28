package com.danceanon.native.privacy

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ResolvedCompositorMasks(
    val privacyMask: NativeMask?,
    val occluderMask: NativeMask?,
    val hasPrivacy: Boolean,
    val hasOccluder: Boolean
)

data class OcclusionEvidence(
    val targetId: Int,
    val candidateOccluderId: Int,
    val freshObservation: Boolean,
    val bboxOverlapRatio: Float,
    val maskOverlapRatio: Float,
    val footYDelta: Float,
    val isStrongForeground: Boolean
)

object PrivacyOcclusionResolver {

    /**
     * Resolves selected privacy targets and explicit foreground occluders into
     * an effective, hole-free privacy mask.
     * Enforces:
     * 1. Explicit foreground determination: Never equate ACTIVE==foreground or OCCLUDED==background.
     * 2. Uses fresh observation, footY delta, and geometric mask overlap as depth proxy.
     * 3. Ambiguous depth defaults to PRIVACY WINS (never carved).
     * 4. Occluder core subtraction: unselected occluders are eroded (radius=1) before subtraction.
     * 5. Unselected persons are NEVER dilated; privacy target is dilated once.
     * 6. Multi-selected targets are evaluated per-target, then combined by union.
     */
    fun resolveMasks(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        applyDilationToPrivacyTargets: Boolean = true,
        dilationRadius: Int = 1,
        occluderErosionRadius: Int = 1,
        foregroundFootYMarginRatio: Float = 0.05f,
        ptsUs: Long = 0L
    ): ResolvedCompositorMasks {
        val selectedPersons = persons.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        if (selectedPersons.isEmpty()) {
            return ResolvedCompositorMasks(
                privacyMask = null,
                occluderMask = null,
                hasPrivacy = false,
                hasOccluder = false
            )
        }

        val unselectedPersons = persons.filter { !selectedPersonIds.contains(it.id) && it.mask != null }
        val effectiveSelectedMasks = mutableListOf<NativeMask>()

        for (target in selectedPersons) {
            val rawMask = target.mask ?: continue
            val dilatedMask = if (applyDilationToPrivacyTargets && dilationRadius > 0) {
                MaskPrivacyProcessor.dilate(rawMask, radius = dilationRadius)
            } else {
                rawMask
            }

            val acceptedOccluderCores = mutableListOf<NativeMask>()

            // Evaluate candidate occluders for all targets except REMOVED
            if (target.state != TrackState.REMOVED) {
                val targetBbox = target.bbox
                val targetArea = (targetBbox.width * targetBbox.height).coerceAtLeast(1e-4f)
                val targetFootY = target.footY ?: targetBbox.bottom

                for (cand in unselectedPersons) {
                    val candMask = cand.mask ?: continue
                    val candBbox = cand.bbox
                    val candArea = (candBbox.width * candBbox.height).coerceAtLeast(1e-4f)
                    val candFootY = cand.footY ?: candBbox.bottom

                    val interArea = computeBBoxIntersectionArea(targetBbox, candBbox)
                    val minBboxArea = minOf(targetArea, candArea)
                    val bboxOverlapRatio = if (minBboxArea > 0f) interArea / minBboxArea else 0f

                    if (bboxOverlapRatio < 0.10f) continue

                    val maskOverlapRatio = computeMaskOverlapRatio(dilatedMask, candMask)
                    if (maskOverlapRatio <= 0.02f) continue

                    // Depth evaluation
                    val footYDelta = candFootY - targetFootY // positive means cand is lower in frame / in front
                    val personMinH = minOf(targetBbox.height, candBbox.height).coerceAtLeast(10f)
                    val footYThreshold = personMinH * foregroundFootYMarginRatio

                    val isTargetFresh = target.observedThisFrame
                    val isCandFresh = cand.observedThisFrame

                    val isStrongForeground = if (!isCandFresh) {
                        // Stale unselected candidate is NEVER trusted to carve privacy
                        false
                    } else if (target.state == TrackState.OCCLUDED) {
                        // CASE A: Target is explicitly occluded by this candidate in tracking
                        val isExplicitOccluder = target.occludedByTrackIds.contains(cand.id)
                        isExplicitOccluder && (footYDelta > -footYThreshold)
                    } else {
                        // CASE B: Candidate has clear foreground depth evidence (footY lower in frame)
                        footYDelta > footYThreshold
                    }

                    val evidence = OcclusionEvidence(
                        targetId = target.id,
                        candidateOccluderId = cand.id,
                        freshObservation = isCandFresh,
                        bboxOverlapRatio = bboxOverlapRatio,
                        maskOverlapRatio = maskOverlapRatio,
                        footYDelta = footYDelta,
                        isStrongForeground = isStrongForeground
                    )

                    if (isStrongForeground) {
                        // Strong foreground occluder: erode raw occluder mask to create safety core
                        val occluderCore = if (occluderErosionRadius > 0) {
                            MaskPrivacyProcessor.erode(candMask, radius = occluderErosionRadius)
                        } else {
                            candMask
                        }
                        acceptedOccluderCores.add(occluderCore)

                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "PrivacyOcclusionResolver",
                            event = "FOREGROUND_OCCLUDER_ACCEPTED",
                            fields = mapOf(
                                "target_id" to target.id,
                                "occluder_id" to cand.id,
                                "foot_y_delta" to footYDelta,
                                "threshold" to footYThreshold,
                                "bbox_overlap" to bboxOverlapRatio,
                                "mask_overlap" to maskOverlapRatio,
                                "target_state" to target.state.name,
                                "pts_us" to ptsUs
                            )
                        )
                    } else {
                        // Ambiguous depth or candidate is background: PRIVACY WINS
                        NativeDiagnostics.event(
                            level = "INFO",
                            component = "PrivacyOcclusionResolver",
                            event = "FOREGROUND_OCCLUDER_REJECTED_AMBIGUOUS",
                            fields = mapOf(
                                "target_id" to target.id,
                                "candidate_id" to cand.id,
                                "foot_y_delta" to footYDelta,
                                "threshold" to footYThreshold,
                                "bbox_overlap" to bboxOverlapRatio,
                                "mask_overlap" to maskOverlapRatio,
                                "target_state" to target.state.name,
                                "pts_us" to ptsUs
                            )
                        )
                    }
                }
            }

            val effectiveMask = if (acceptedOccluderCores.isNotEmpty()) {
                val mergedOccluderCore = mergeMasks(acceptedOccluderCores)
                computeEffectivePrivacyMask(dilatedMask, mergedOccluderCore) ?: dilatedMask
            } else {
                dilatedMask
            }

            // Telemetry & under-coverage verification
            val rawArea = countMaskPixels(rawMask)
            val dilatedArea = countMaskPixels(dilatedMask)
            val effArea = countMaskPixels(effectiveMask)
            val removedArea = (dilatedArea - effArea).coerceAtLeast(0)
            val coverageRatio = if (dilatedArea > 0) effArea.toFloat() / dilatedArea.toFloat() else 1.0f

            if (coverageRatio < 0.65f) {
                NativeDiagnostics.event(
                    level = "WARN",
                    component = "PrivacyOcclusionResolver",
                    event = "PRIVACY_SEVERE_UNDERCOVERAGE",
                    fields = mapOf(
                        "target_id" to target.id,
                        "state" to target.state.name,
                        "coverage_ratio" to coverageRatio,
                        "raw_area" to rawArea,
                        "dilated_area" to dilatedArea,
                        "effective_area" to effArea,
                        "removed_area" to removedArea,
                        "pts_us" to ptsUs
                    )
                )
            } else if (coverageRatio < 0.85f) {
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "PrivacyOcclusionResolver",
                    event = "PRIVACY_COVERAGE_DROP",
                    fields = mapOf(
                        "target_id" to target.id,
                        "state" to target.state.name,
                        "coverage_ratio" to coverageRatio,
                        "raw_area" to rawArea,
                        "dilated_area" to dilatedArea,
                        "effective_area" to effArea,
                        "removed_area" to removedArea,
                        "pts_us" to ptsUs
                    )
                )
            }

            effectiveSelectedMasks.add(effectiveMask)
        }

        val mergedPrivacy = mergeMasks(effectiveSelectedMasks)

        return ResolvedCompositorMasks(
            privacyMask = mergedPrivacy,
            occluderMask = null,
            hasPrivacy = (mergedPrivacy != null),
            hasOccluder = false
        )
    }

    /**
     * Computes the ratio of mask intersection over the smaller mask area.
     */
    fun computeMaskOverlapRatio(maskA: NativeMask?, maskB: NativeMask?): Float {
        if (maskA == null || maskB == null) return 0f
        val bufA = maskA.buffer
        val bufB = maskB.buffer
        bufA.rewind()
        bufB.rewind()
        val len = minOf(bufA.capacity(), bufB.capacity(), maskA.width * maskA.height)
        var interCount = 0
        var minAreaCountA = 0
        var minAreaCountB = 0
        for (i in 0 until len) {
            val a = (bufA.get(i).toInt() and 0xFF) >= 128
            val b = (bufB.get(i).toInt() and 0xFF) >= 128
            if (a) minAreaCountA++
            if (b) minAreaCountB++
            if (a && b) interCount++
        }
        bufA.rewind()
        bufB.rewind()
        val minCount = minOf(minAreaCountA, minAreaCountB)
        return if (minCount > 0) interCount.toFloat() / minCount.toFloat() else 0f
    }

    private fun computeBBoxIntersectionArea(a: FloatRect, b: FloatRect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        return interW * interH
    }

    /**
     * Computes the union (element-wise maximum) of a list of masks.
     */
    fun mergeMasks(masks: List<NativeMask>): NativeMask? {
        if (masks.isEmpty()) return null
        if (masks.size == 1) return masks[0]

        val first = masks[0]
        val totalPixels = first.width * first.height
        val mergedBuf = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        val tempBytes = ByteArray(totalPixels)

        for (mask in masks) {
            val buf = mask.buffer
            buf.rewind()
            val len = minOf(totalPixels, buf.capacity())
            for (i in 0 until len) {
                val b = buf.get(i)
                if ((b.toInt() and 0xFF) > (tempBytes[i].toInt() and 0xFF)) {
                    tempBytes[i] = b
                }
            }
            buf.rewind()
        }

        mergedBuf.put(tempBytes)
        mergedBuf.rewind()

        return NativeMask(
            width = first.width,
            height = first.height,
            buffer = mergedBuf,
            originalWidth = first.originalWidth,
            originalHeight = first.originalHeight,
            mapper = first.mapper,
            samplingRect = first.samplingRect
        )
    }

    /**
     * Computes the software effective privacy mask:
     * effectivePrivacy = privacyMask * (1.0 - occluderCore)
     */
    fun computeEffectivePrivacyMask(
        privacyMask: NativeMask?,
        occluderCore: NativeMask?
    ): NativeMask? {
        if (privacyMask == null) return null
        if (occluderCore == null) return privacyMask

        val width = privacyMask.width
        val height = privacyMask.height
        val totalPixels = width * height

        val privBuf = privacyMask.buffer
        val occBuf = occluderCore.buffer
        privBuf.rewind()
        occBuf.rewind()

        val dstBuf = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        val tempBytes = ByteArray(totalPixels)

        for (i in 0 until totalPixels) {
            val pVal = privBuf.get(i).toInt() and 0xFF
            val oVal = if (i < occBuf.capacity()) (occBuf.get(i).toInt() and 0xFF) else 0

            // If occluder core is solid foreground (>= 128), subtract it from privacy
            val effectiveVal = if (oVal >= 128) {
                0
            } else {
                pVal
            }
            tempBytes[i] = effectiveVal.toByte()
        }

        privBuf.rewind()
        occBuf.rewind()

        dstBuf.put(tempBytes)
        dstBuf.rewind()

        return NativeMask(
            width = width,
            height = height,
            buffer = dstBuf,
            originalWidth = privacyMask.originalWidth,
            originalHeight = privacyMask.originalHeight,
            mapper = privacyMask.mapper,
            samplingRect = privacyMask.samplingRect
        )
    }

    fun countMaskPixels(mask: NativeMask?): Int {
        if (mask == null) return 0
        val buf = mask.buffer
        buf.rewind()
        var count = 0
        val len = buf.capacity()
        for (i in 0 until len) {
            if ((buf.get(i).toInt() and 0xFF) > 128) {
                count++
            }
        }
        buf.rewind()
        return count
    }
}

