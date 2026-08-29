package com.danceanon.native.privacy

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.PrivacySelectionClass
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackManager
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

    private const val OWNERSHIP_MARGIN = 0.12f
    private const val MIN_UNSELECTED_PROBABILITY = 0.50f
    private const val MIN_STALE_RAW_PROBABILITY_ADVANTAGE = 0.10f
    private const val MIN_UNSELECTED_IDENTITY_AGE_FRAMES = 3
    private const val YOUNG_IDENTITY_OWNERSHIP_MARGIN = 0.30f
    private const val YOUNG_IDENTITY_RAW_PROBABILITY_ADVANTAGE = 0.25f
    private const val MAX_FOOT_Y_BIAS = 0.03f
    private const val STRONG_FRESH_FOREGROUND_FOOT_Y_RATIO = 0.10f
    // GlShaders starts visibly blending privacy at smoothstep(0.15, 0.85, maskVal).
    // Fresh foreground ownership therefore needs to cover the same visible mask
    // support instead of only the >= 0.50 binary-analysis core.
    private const val RENDER_VISIBLE_MASK_THRESHOLD_BYTE = 39

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
        ptsUs: Long = 0L,
        freshClassEvidence: List<FreshPrivacyClassEvidence> = emptyList(),
        suppressedSelectedTrackIds: Set<Int> = emptySet(),
        preferFreshClassPrimary: Boolean = false,
        expectedSelectedCount: Int = 0
    ): ResolvedCompositorMasks {
        if (selectedPersonIds.isEmpty()) {
            return ResolvedCompositorMasks(
                privacyMask = null,
                occluderMask = null,
                hasPrivacy = false,
                hasOccluder = false
            )
        }

        val evidenceSelectedIds = mutableSetOf<Int>()
        val evidencePersons = freshClassEvidence.mapIndexedNotNull { index, evidence ->
            val mask = evidence.detection.mask ?: return@mapIndexedNotNull null
            val evidenceId = Int.MIN_VALUE + index
            if (evidence.selectionClass == PrivacySelectionClass.SELECTED) {
                evidenceSelectedIds.add(evidenceId)
            }
            TrackedPerson(
                id = evidenceId,
                bbox = evidence.detection.bbox,
                mask = mask,
                confidence = evidence.detection.confidence,
                missedFrames = 0,
                age = MIN_UNSELECTED_IDENTITY_AGE_FRAMES,
                state = TrackState.ACTIVE,
                occludedByTrackIds = emptySet(),
                observedThisFrame = true,
                footY = evidence.detection.footY
            )
        }
        val useFreshPrimary = preferFreshClassPrimary && evidencePersons.isNotEmpty() && selectedPersonIds.isNotEmpty()
        val privacyPersons: List<TrackedPerson>
        val effectiveSelectedIds: Set<Int>

        if (useFreshPrimary) {
            val freshSelectedPersons = evidencePersons.filter { evidenceSelectedIds.contains(it.id) }
            val expectedCount = expectedSelectedCount.coerceAtLeast(selectedPersonIds.size)
            val fallbackDeficit = (expectedCount - freshSelectedPersons.size).coerceAtLeast(0)
            val fallbackSelectedPersons = selectFreshPrimaryFallbacks(
                persons = persons,
                selectedPersonIds = selectedPersonIds,
                freshEvidencePersons = evidencePersons,
                freshSelectedPersons = freshSelectedPersons,
                maxFallbackCount = fallbackDeficit
            )
            privacyPersons = evidencePersons + fallbackSelectedPersons
            effectiveSelectedIds = evidenceSelectedIds + fallbackSelectedPersons.map { it.id }

            NativeDiagnostics.event(
                level = "INFO",
                component = "PrivacyOcclusionResolver",
                event = "PRIVACY_FRESH_CLASS_PRIMARY_COMPOSITION",
                fields = mapOf(
                    "expected_selected" to expectedCount,
                    "fresh_selected" to freshSelectedPersons.size,
                    "fresh_unselected" to (evidencePersons.size - freshSelectedPersons.size),
                    "fresh_conservative_unknown" to freshClassEvidence.count { it.conservativeUnknown },
                    "fallback_selected" to fallbackSelectedPersons.size,
                    "fallback_ids" to fallbackSelectedPersons.map { it.id },
                    "pts_us" to ptsUs
                )
            )
        } else {
            val effectiveSuppressedSelectedTrackIds = suppressedSelectedTrackIds +
                inferStaleSelectedReplacements(
                    persons = persons,
                    selectedPersonIds = selectedPersonIds,
                    freshClassEvidence = freshClassEvidence,
                    alreadySuppressed = suppressedSelectedTrackIds,
                    ptsUs = ptsUs
                )

            privacyPersons = if (effectiveSuppressedSelectedTrackIds.isEmpty() && evidencePersons.isEmpty()) {
                persons
            } else {
                persons.filterNot {
                    selectedPersonIds.contains(it.id) && effectiveSuppressedSelectedTrackIds.contains(it.id)
                } + evidencePersons
            }
            effectiveSelectedIds = if (evidenceSelectedIds.isEmpty()) {
                selectedPersonIds
            } else {
                selectedPersonIds + evidenceSelectedIds
            }
        }

        val selectedPersons = privacyPersons.filter { effectiveSelectedIds.contains(it.id) && it.mask != null }
        if (selectedPersons.isEmpty()) {
            return ResolvedCompositorMasks(
                privacyMask = null,
                occluderMask = null,
                hasPrivacy = false,
                hasOccluder = false
            )
        }

        val unselectedPersons = privacyPersons.filter { !effectiveSelectedIds.contains(it.id) && it.mask != null }
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
                    val renderVisibleMaskOverlapRatio = if (
                        useFreshPrimary && evidenceSelectedIds.contains(target.id)
                    ) {
                        computeMaskOverlapRatio(
                            maskA = dilatedMask,
                            maskB = candMask,
                            thresholdA = RENDER_VISIBLE_MASK_THRESHOLD_BYTE,
                            thresholdB = RENDER_VISIBLE_MASK_THRESHOLD_BYTE
                        )
                    } else {
                        maskOverlapRatio
                    }
                    if (maxOf(maskOverlapRatio, renderVisibleMaskOverlapRatio) <= 0.02f) continue

                    // Depth evaluation
                    val footYDelta = candFootY - targetFootY // positive means cand is lower in frame / in front
                    val personMinH = minOf(targetBbox.height, candBbox.height).coerceAtLeast(10f)
                    val footYThreshold = personMinH * foregroundFootYMarginRatio

                    val isCandFresh = cand.observedThisFrame
                    val isExplicitOccluder = target.occludedByTrackIds.contains(cand.id)
                    val hasStableIdentity = isExplicitOccluder || cand.age >= MIN_UNSELECTED_IDENTITY_AGE_FRAMES
                    val normalizedFootYDelta = footYDelta / personMinH
                    val useFreshDepthCore =
                        useFreshPrimary &&
                            evidenceSelectedIds.contains(target.id) &&
                            target.observedThisFrame &&
                            isCandFresh &&
                            normalizedFootYDelta >= STRONG_FRESH_FOREGROUND_FOOT_Y_RATIO

                    val freshDepthCore = if (useFreshDepthCore) {
                        buildFreshDepthCore(candMask, occluderErosionRadius)
                    } else {
                        null
                    }
                    val freshDepthCorePixels = countMaskIntersectionPixels(
                        maskA = dilatedMask,
                        maskB = freshDepthCore,
                        thresholdA = RENDER_VISIBLE_MASK_THRESHOLD_BYTE,
                        thresholdB = 128
                    )
                    val ownershipMask = if (isCandFresh) {
                        computeUnselectedOwnershipMask(
                            selectedMask = rawMask,
                            selected = target,
                            unselectedMask = candMask,
                            unselected = cand,
                            footYDelta = footYDelta,
                            personHeight = personMinH,
                            explicitOccluder = isExplicitOccluder
                        )
                    } else {
                        null
                    }
                    val ownershipPixels = countMaskPixels(ownershipMask)
                    val usesFreshDepthCore = freshDepthCorePixels > 0
                    val isStrongForeground = usesFreshDepthCore || ownershipPixels > 0

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
                        // Fresh-class-primary detections can resolve depth at the
                        // instance level even when YOLO gives both overlapping
                        // instance masks similarly high pixel probabilities. If
                        // the unselected instance is clearly in front by footY,
                        // use only its eroded fresh mask core. Otherwise preserve
                        // the older probability-margin ownership rule.
                        val occluderCore = if (usesFreshDepthCore) {
                            freshDepthCore ?: continue
                        } else if (occluderErosionRadius > 0 && ownershipMask != null) {
                            MaskPrivacyProcessor.erode(ownershipMask, radius = occluderErosionRadius)
                        } else {
                            ownershipMask ?: continue
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
                                "render_visible_mask_overlap" to renderVisibleMaskOverlapRatio,
                                "ownership_pixels" to ownershipPixels,
                                "fresh_depth_core_pixels" to freshDepthCorePixels,
                                "ownership_mode" to if (usesFreshDepthCore) "FRESH_DEPTH_CORE" else "PROBABILITY_MARGIN",
                                "normalized_foot_y_delta" to normalizedFootYDelta,
                                "explicit_occluder" to isExplicitOccluder,
                                "candidate_age" to cand.age,
                                "identity_stable" to hasStableIdentity,
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
                                "render_visible_mask_overlap" to renderVisibleMaskOverlapRatio,
                                "ownership_pixels" to ownershipPixels,
                                "fresh_depth_core_pixels" to freshDepthCorePixels,
                                "normalized_foot_y_delta" to normalizedFootYDelta,
                                "explicit_occluder" to isExplicitOccluder,
                                "candidate_age" to cand.age,
                                "identity_stable" to hasStableIdentity,
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

            // Independent geometry-health telemetry. `coverageRatio` above only
            // measures pixels removed by foreground ownership; it cannot detect
            // a raw/warped selected mask that has already collapsed to a small
            // portion of the person's bbox. NativeMask carries the mapper needed
            // to compare source-space bbox geometry with proto-space mask pixels
            // without mixing coordinate systems.
            val mapper = rawMask.mapper
            val bboxProtoArea = if (mapper != null) {
                val protoLeft = mapper.sourceToProtoX(target.bbox.left)
                val protoTop = mapper.sourceToProtoY(target.bbox.top)
                val protoRight = mapper.sourceToProtoX(target.bbox.right)
                val protoBottom = mapper.sourceToProtoY(target.bbox.bottom)
                ((protoRight - protoLeft).coerceAtLeast(0f) *
                    (protoBottom - protoTop).coerceAtLeast(0f)).coerceAtLeast(1f)
            } else {
                0f
            }
            val maskBboxFillRatio = if (bboxProtoArea > 0f) {
                rawArea.toFloat() / bboxProtoArea
            } else {
                -1f
            }

            if (maskBboxFillRatio >= 0f && maskBboxFillRatio < 0.12f) {
                NativeDiagnostics.event(
                    level = "WARN",
                    component = "PrivacyOcclusionResolver",
                    event = "SELECTED_MASK_LOW_BBOX_OCCUPANCY",
                    fields = mapOf(
                        "target_id" to target.id,
                        "state" to target.state.name,
                        "observed_this_frame" to target.observedThisFrame,
                        "mask_bbox_fill_ratio" to maskBboxFillRatio,
                        "raw_area" to rawArea,
                        "bbox_proto_area" to bboxProtoArea,
                        "mask_width" to rawMask.width,
                        "mask_height" to rawMask.height,
                        "pts_us" to ptsUs
                    )
                )
            }

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
     * In fresh-class-primary mode, TrackManager masks are fallback only. Keep at
     * most the number of missing selected-class detections, preferring tracks
     * that are spatially absent from the current fresh YOLO roster. Tracks that
     * overlap a fresh selected mask are duplicates and are never useful fallback.
     */
    private fun selectFreshPrimaryFallbacks(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        freshEvidencePersons: List<TrackedPerson>,
        freshSelectedPersons: List<TrackedPerson>,
        maxFallbackCount: Int
    ): List<TrackedPerson> {
        if (maxFallbackCount <= 0) return emptyList()

        data class Candidate(
            val person: TrackedPerson,
            val maxFreshOverlap: Float,
            val maxFreshSelectedOverlap: Float
        )

        fun overlapRatio(a: FloatRect, b: FloatRect): Float {
            val inter = computeBBoxIntersectionArea(a, b)
            val minArea = minOf(
                (a.width * a.height).coerceAtLeast(1e-4f),
                (b.width * b.height).coerceAtLeast(1e-4f)
            )
            return inter / minArea
        }

        val candidates = persons.asSequence()
            .filter {
                selectedPersonIds.contains(it.id) &&
                    it.state != TrackState.REMOVED &&
                    it.mask != null
            }
            .map { person ->
                Candidate(
                    person = person,
                    maxFreshOverlap = freshEvidencePersons.maxOfOrNull { overlapRatio(person.bbox, it.bbox) } ?: 0f,
                    maxFreshSelectedOverlap = freshSelectedPersons.maxOfOrNull { overlapRatio(person.bbox, it.bbox) } ?: 0f
                )
            }
            // A track already covered by any fresh detection is not spatially
            // missing. In particular, do not let a stale selected identity land
            // on a current fresh unselected person and create an extra privacy
            // mask. Fresh-selected overlap still catches ordinary duplicates;
            // all-fresh overlap also covers current unselected/unknown roster.
            .filter {
                it.maxFreshSelectedOverlap < 0.30f &&
                    it.maxFreshOverlap < 0.30f
            }
            .sortedWith(
                compareBy<Candidate> { it.maxFreshOverlap }
                    .thenBy { if (it.person.observedThisFrame) 1 else 0 }
                    .thenBy { it.person.missedFrames }
            )
            .take(maxFallbackCount)
            .map { it.person }
            .toList()

        return candidates
    }

    /**
     * Temporal class evidence has no exact ID by design. When one fresh selected
     * detection has strong absolute geometry against one stale selected track,
     * replace that stale render mask one-to-one. This removes the common
     * "fresh mask + old ghost mask" artifact without disabling stale fallback for
     * unmatched selected identities.
     */
    private fun inferStaleSelectedReplacements(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        freshClassEvidence: List<FreshPrivacyClassEvidence>,
        alreadySuppressed: Set<Int>,
        ptsUs: Long
    ): Set<Int> {
        val temporalSelectedEvidence = freshClassEvidence.filter {
            it.selectionClass == PrivacySelectionClass.SELECTED &&
                it.residualTrackIds.isEmpty() &&
                it.detection.mask != null
        }
        if (temporalSelectedEvidence.isEmpty()) return emptySet()

        val staleSelected = persons.filter {
            selectedPersonIds.contains(it.id) &&
                !alreadySuppressed.contains(it.id) &&
                !it.observedThisFrame &&
                it.state != TrackState.REMOVED &&
                it.mask != null
        }
        if (staleSelected.isEmpty()) return emptySet()

        data class PairEvidence(val score: Float, val bboxIoU: Float, val maskIoU: Float, val accepted: Boolean)

        val evidenceMatrix = Array(staleSelected.size) { r ->
            Array(temporalSelectedEvidence.size) { c ->
                val track = staleSelected[r]
                val det = temporalSelectedEvidence[c].detection
                val bboxIoU = TrackManager.computeBBoxIoU(track.bbox, det.bbox)
                val maskIoU = TrackManager.computeMaskIoU(track.mask, det.mask, sampleStride = 4)
                val dx = track.bbox.centerX - det.bbox.centerX
                val dy = track.bbox.centerY - det.bbox.centerY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                val refDim = maxOf(track.bbox.width, track.bbox.height, det.bbox.width, det.bbox.height, 1f)
                val distanceScore = (1f - distance / (refDim * 1.5f)).coerceIn(0f, 1f)
                val score = (0.55f * bboxIoU + 0.35f * maskIoU + 0.10f * distanceScore).coerceIn(0f, 1f)
                val accepted = when (track.state) {
                    TrackState.LOST -> bboxIoU >= 0.45f || maskIoU >= 0.30f
                    TrackState.OCCLUDED, TrackState.REACQUIRING -> bboxIoU >= 0.35f || maskIoU >= 0.20f
                    else -> bboxIoU >= 0.30f || maskIoU >= 0.20f
                }
                PairEvidence(score, bboxIoU, maskIoU, accepted)
            }
        }
        val costs = Array(staleSelected.size) { r ->
            FloatArray(temporalSelectedEvidence.size) { c ->
                val pair = evidenceMatrix[r][c]
                if (pair.accepted) 1f - pair.score else 1f
            }
        }
        val matches = HungarianSolver.match(costs, maxCostThreshold = 0.65f)
        val suppressed = mutableSetOf<Int>()
        for ((trackIndex, evidenceIndex) in matches.matches) {
            val pair = evidenceMatrix[trackIndex][evidenceIndex]
            if (!pair.accepted || pair.score < 0.35f) continue
            val track = staleSelected[trackIndex]
            val evidence = temporalSelectedEvidence[evidenceIndex]
            suppressed.add(track.id)
            NativeDiagnostics.event(
                level = "INFO",
                component = "PrivacyOcclusionResolver",
                event = "PRIVACY_STALE_SELECTED_REPLACED_BY_FRESH_CLASS",
                fields = mapOf(
                    "track_id" to track.id,
                    "track_state" to track.state.name,
                    "detection_index" to evidence.detectionIndex,
                    "score" to pair.score,
                    "bbox_iou" to pair.bboxIoU,
                    "mask_iou" to pair.maskIoU,
                    "pts_us" to ptsUs
                )
            )
        }
        return suppressed
    }

    /**
     * Produces a binary ownership mask containing only pixels whose evidence
     * clearly favors the fresh unselected instance over the selected target.
     * Ambiguous pixels remain zero, which means privacy wins downstream.
     *
     * The YOLO mask buffer is already a 0..255 soft probability field. Identity
     * confidence is multiplied into that probability. Stale selected tracks get
     * a bounded evidence discount so a fresh foreground instance can recover
     * pixels from an obviously stale warped privacy mask, without making stale
     * state alone sufficient to carve privacy. footY and explicit occluder
     * relation contribute only small tie-breaking biases.
     */
    private fun computeUnselectedOwnershipMask(
        selectedMask: NativeMask,
        selected: TrackedPerson,
        unselectedMask: NativeMask,
        unselected: TrackedPerson,
        footYDelta: Float,
        personHeight: Float,
        explicitOccluder: Boolean
    ): NativeMask? {
        if (selectedMask.width != unselectedMask.width || selectedMask.height != unselectedMask.height) return null

        val width = selectedMask.width
        val height = selectedMask.height
        val totalPixels = width * height
        if (selectedMask.buffer.capacity() < totalPixels || unselectedMask.buffer.capacity() < totalPixels) return null

        val selectedStalenessScale = when {
            selected.observedThisFrame -> 1.0f
            selected.state == TrackState.REACQUIRING -> 0.70f
            selected.state == TrackState.LOST -> 0.55f
            selected.state == TrackState.OCCLUDED -> 0.80f
            else -> 0.85f
        }

        val normalizedFootDelta = if (personHeight > 1e-4f) {
            (footYDelta / personHeight).coerceIn(-0.25f, 0.25f)
        } else {
            0f
        }
        val footBias = (normalizedFootDelta * (MAX_FOOT_Y_BIAS / 0.25f))
            .coerceIn(-MAX_FOOT_Y_BIAS, MAX_FOOT_Y_BIAS)
        val selectedBuf = selectedMask.buffer
        val unselectedBuf = unselectedMask.buffer
        val ownershipBytes = ByteArray(totalPixels)
        val isYoungIdentity = !explicitOccluder && unselected.age < MIN_UNSELECTED_IDENTITY_AGE_FRAMES
        val requiredOwnershipMargin = if (isYoungIdentity) {
            YOUNG_IDENTITY_OWNERSHIP_MARGIN
        } else {
            OWNERSHIP_MARGIN
        }

        for (i in 0 until totalPixels) {
            val selectedProb = (selectedBuf.get(i).toInt() and 0xFF) / 255f
            val unselectedProb = (unselectedBuf.get(i).toInt() and 0xFF) / 255f
            if (unselectedProb < MIN_UNSELECTED_PROBABILITY || selectedProb <= 0f) continue

            val selectedEvidence = selectedProb * selected.confidence.coerceIn(0f, 1f) * selectedStalenessScale
            // An occlusion-group relation is identity/context evidence only. It
            // must never manufacture pixel ownership. Device diagnostics showed
            // near-identical selected/unselected masks being carved simply
            // because explicitOccluder contributed a positive evidence bias.
            val unselectedEvidence = unselectedProb * unselected.confidence.coerceIn(0f, 1f) + footBias
            val rawProbabilityAdvantage = unselectedProb - selectedProb
            val hasRawInstanceAdvantage = when {
                isYoungIdentity -> rawProbabilityAdvantage >= YOUNG_IDENTITY_RAW_PROBABILITY_ADVANTAGE
                selected.observedThisFrame -> true
                else -> rawProbabilityAdvantage >= MIN_STALE_RAW_PROBABILITY_ADVANTAGE
            }

            if (hasRawInstanceAdvantage && (unselectedEvidence - selectedEvidence) >= requiredOwnershipMargin) {
                ownershipBytes[i] = 255.toByte()
            }
        }

        if (ownershipBytes.none { (it.toInt() and 0xFF) != 0 }) return null

        val ownershipBuffer = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        ownershipBuffer.put(ownershipBytes)
        ownershipBuffer.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = ownershipBuffer,
            originalWidth = selectedMask.originalWidth,
            originalHeight = selectedMask.originalHeight,
            mapper = selectedMask.mapper,
            roiInProto = selectedMask.roiInProto,
            samplingRect = selectedMask.samplingRect
        )
    }

    /**
     * Computes the ratio of mask intersection over the smaller mask area.
     */
    fun computeMaskOverlapRatio(
        maskA: NativeMask?,
        maskB: NativeMask?,
        thresholdA: Int = 128,
        thresholdB: Int = 128
    ): Float {
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
            val a = (bufA.get(i).toInt() and 0xFF) >= thresholdA
            val b = (bufB.get(i).toInt() and 0xFF) >= thresholdB
            if (a) minAreaCountA++
            if (b) minAreaCountB++
            if (a && b) interCount++
        }
        bufA.rewind()
        bufB.rewind()
        val minCount = minOf(minAreaCountA, minAreaCountB)
        return if (minCount > 0) interCount.toFloat() / minCount.toFloat() else 0f
    }

    private fun buildFreshDepthCore(mask: NativeMask, erosionRadius: Int): NativeMask {
        val totalPixels = mask.width * mask.height
        val thresholdBuffer = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        for (i in 0 until totalPixels) {
            val value = mask.buffer.get(i).toInt() and 0xFF
            thresholdBuffer.put(if (value >= RENDER_VISIBLE_MASK_THRESHOLD_BYTE) 255.toByte() else 0.toByte())
        }
        thresholdBuffer.rewind()

        val binary = NativeMask(
            width = mask.width,
            height = mask.height,
            buffer = thresholdBuffer,
            originalWidth = mask.originalWidth,
            originalHeight = mask.originalHeight,
            mapper = mask.mapper,
            roiInProto = mask.roiInProto,
            samplingRect = mask.samplingRect
        )
        if (erosionRadius <= 0) return binary

        val eroded = MaskPrivacyProcessor.erode(binary, radius = erosionRadius)
        return NativeMask(
            width = eroded.width,
            height = eroded.height,
            buffer = eroded.buffer,
            originalWidth = eroded.originalWidth,
            originalHeight = eroded.originalHeight,
            mapper = eroded.mapper,
            roiInProto = eroded.roiInProto,
            samplingRect = mask.samplingRect
        )
    }

    private fun countMaskIntersectionPixels(
        maskA: NativeMask?,
        maskB: NativeMask?,
        thresholdA: Int = 128,
        thresholdB: Int = 128
    ): Int {
        if (maskA == null || maskB == null) return 0
        if (maskA.width != maskB.width || maskA.height != maskB.height) return 0
        val len = minOf(maskA.width * maskA.height, maskA.buffer.capacity(), maskB.buffer.capacity())
        var count = 0
        for (i in 0 until len) {
            val a = (maskA.buffer.get(i).toInt() and 0xFF) >= thresholdA
            val b = (maskB.buffer.get(i).toInt() and 0xFF) >= thresholdB
            if (a && b) count++
        }
        return count
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

