package com.danceanon.native.privacy

import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Short-lived face-local motion from pixels that are already read back for YOLO.
 *
 * This tracker never owns identity and never creates a face from scratch. A real
 * DETECTED_FACE seeds a fixed appearance template for one TrackManager-owned ID.
 * Subsequent current-frame 640x640 RGBA buffers may move only that face center
 * for a short bounded interval. Size stays fixed. Low-correlation or ambiguous
 * matches fail closed and let the existing detector/fallback policy decide.
 *
 * The input buffer is the OpenGL readback used by the YOLO QUALITY path, so rows
 * are bottom-to-top. All matching is performed in model coordinates.
 */
internal class FacePixelMotionTracker(
    private val maxEvidenceGapUs: Long = DEFAULT_MAX_EVIDENCE_GAP_US,
    private val minCorrelation: Float = DEFAULT_MIN_CORRELATION,
    private val minUniquenessGap: Float = DEFAULT_MIN_UNIQUENESS_GAP
) {
    data class Match(
        val region: FacePrivacyEllipse,
        val correlation: Float,
        val uniquenessGap: Float,
        val modelDx: Int,
        val modelDy: Int
    )

    private data class State(
        val template: FloatArray,
        val sampleDx: IntArray,
        val sampleDy: IntArray,
        val templateNorm: Float,
        val radiusXSource: Float,
        val radiusYSource: Float,
        val searchRadiusModel: Int,
        val correlationWorkspace: FloatArray,
        var centerModelX: Float,
        var centerModelY: Float,
        var lastPtsUs: Long
    )

    private data class RoiState(
        val template: FloatArray,
        val sampleDx: IntArray,
        val sampleDy: IntArray,
        val templateNorm: Float,
        val radiusXSource: Float,
        val radiusYSource: Float,
        val detectorCenterXSource: Float,
        val detectorCenterYSource: Float,
        val detectorPersonBbox: FloatRect,
        val detectorSeedPtsUs: Long,
        val patchHalfExtentLocal: Int,
        val searchRadiusLocal: Int,
        val correlationWorkspace: FloatArray,
        var centerXSource: Float,
        var centerYSource: Float,
        var lastEvidencePtsUs: Long
    )

    private val stateByTrackId = mutableMapOf<Int, State>()
    private val roiStateByTrackId = mutableMapOf<Int, RoiState>()
    private var grayWorkspace = ByteArray(0)
    private var grayWorkspaceSize = 0
    private var grayWorkspacePtsUs = Long.MIN_VALUE
    private var roiGrayWorkspace = ByteArray(0)

    fun retainTracks(trackIds: Set<Int>) {
        stateByTrackId.keys.retainAll(trackIds)
    }

    fun retainRoiTracks(trackIds: Set<Int>) {
        roiStateByTrackId.keys.retainAll(trackIds)
    }

    fun remove(trackId: Int) {
        stateByTrackId.remove(trackId)
    }

    fun removeRoi(trackId: Int) {
        roiStateByTrackId.remove(trackId)
    }

    fun hasUsableRoiState(trackId: Int, ptsUs: Long): Boolean {
        val state = roiStateByTrackId[trackId] ?: return false
        val evidenceGapUs = ptsUs - state.lastEvidencePtsUs
        val detectorAgeUs = ptsUs - state.detectorSeedPtsUs
        return evidenceGapUs in 0L..ROI_MAX_EVIDENCE_GAP_US &&
            detectorAgeUs in 0L..ROI_MAX_DETECTOR_SEED_AGE_US
    }

    fun currentRoiRegion(trackId: Int, ptsUs: Long): FacePrivacyEllipse? {
        if (!hasUsableRoiState(trackId, ptsUs)) return null
        val state = roiStateByTrackId[trackId] ?: return null
        return FacePrivacyEllipse(
            centerX = state.centerXSource,
            centerY = state.centerYSource,
            radiusX = state.radiusXSource,
            radiusY = state.radiusYSource,
            source = FacePrivacyRegionSource.PREDICTED_FACE
        )
    }

    fun hasUsableState(trackId: Int, ptsUs: Long): Boolean {
        val state = stateByTrackId[trackId] ?: return false
        val evidenceGapUs = ptsUs - state.lastPtsUs
        return evidenceGapUs in 0L..maxEvidenceGapUs
    }

    fun seedRoi(
        trackId: Int,
        rgbaTopDown: ByteBuffer,
        roiPlan: FaceHeadRoiPlan,
        detected: FacePrivacyEllipse,
        personBbox: FloatRect,
        ptsUs: Long
    ): Boolean {
        if (detected.source != FacePrivacyRegionSource.DETECTED_FACE) return false
        val size = roiPlan.outputSize
        if (size <= 1 || rgbaTopDown.capacity() < size * size * RGBA_STRIDE) return false
        if (roiPlan.sourceRect.width <= 1f || roiPlan.sourceRect.height <= 1f) return false

        val centerX = sourceToRoiX(roiPlan, detected.centerX)
        val centerY = sourceToRoiY(roiPlan, detected.centerY)
        if (centerX !in 0f..size.toFloat() || centerY !in 0f..size.toFloat()) return false
        val gray = ensureRoiGray(rgbaTopDown, size)

        val localScale = size.toFloat() / roiPlan.sourceRect.width
        val minRadiusLocal = minOf(detected.radiusX, detected.radiusY) * localScale
        val maxRadiusLocal = max(detected.radiusX, detected.radiusY) * localScale
        val patchHalfExtent = (minRadiusLocal * ROI_PATCH_RADIUS_FRACTION)
            .roundToInt()
            .coerceIn(ROI_MIN_PATCH_HALF_EXTENT, ROI_MAX_PATCH_HALF_EXTENT)
        val searchRadius = (maxRadiusLocal * ROI_SEARCH_RADIUS_FACE_FRACTION)
            .roundToInt()
            .coerceIn(ROI_MIN_SEARCH_RADIUS, ROI_MAX_SEARCH_RADIUS)
        val offsets = buildSampleOffsets(patchHalfExtent)
        val raw = FloatArray(offsets.first.size)
        for (i in raw.indices) {
            val value = roiGrayAt(
                gray,
                size,
                centerX.roundToInt() + offsets.first[i],
                centerY.roundToInt() + offsets.second[i]
            ) ?: return false
            raw[i] = value
        }
        val mean = raw.average().toFloat()
        var normSq = 0f
        for (i in raw.indices) {
            raw[i] -= mean
            normSq += raw[i] * raw[i]
        }
        if (normSq < ROI_MIN_TEMPLATE_NORM_SQ) return false

        roiStateByTrackId[trackId] = RoiState(
            template = raw,
            sampleDx = offsets.first,
            sampleDy = offsets.second,
            templateNorm = sqrt(normSq),
            radiusXSource = detected.radiusX,
            radiusYSource = detected.radiusY,
            detectorCenterXSource = detected.centerX,
            detectorCenterYSource = detected.centerY,
            detectorPersonBbox = personBbox,
            detectorSeedPtsUs = ptsUs,
            patchHalfExtentLocal = patchHalfExtent,
            searchRadiusLocal = searchRadius,
            correlationWorkspace = FloatArray((searchRadius * 2 + 1) * (searchRadius * 2 + 1)),
            centerXSource = detected.centerX,
            centerYSource = detected.centerY,
            lastEvidencePtsUs = ptsUs
        )
        return true
    }

    fun matchRoi(
        trackId: Int,
        rgbaTopDown: ByteBuffer,
        roiPlan: FaceHeadRoiPlan,
        personBbox: FloatRect,
        personObservedThisFrame: Boolean,
        ptsUs: Long
    ): Match? {
        val state = roiStateByTrackId[trackId] ?: return null
        val evidenceGapUs = ptsUs - state.lastEvidencePtsUs
        val detectorAgeUs = ptsUs - state.detectorSeedPtsUs
        if (
            evidenceGapUs !in 0L..ROI_MAX_EVIDENCE_GAP_US ||
            detectorAgeUs !in 0L..ROI_MAX_DETECTOR_SEED_AGE_US
        ) {
            roiStateByTrackId.remove(trackId)
            return null
        }

        val size = roiPlan.outputSize
        if (size <= 1 || rgbaTopDown.capacity() < size * size * RGBA_STRIDE) return null
        val gray = ensureRoiGray(rgbaTopDown, size)
        val baseX = sourceToRoiX(roiPlan, state.centerXSource).roundToInt()
        val baseY = sourceToRoiY(roiPlan, state.centerYSource).roundToInt()
        val radius = state.searchRadiusLocal
        java.util.Arrays.fill(state.correlationWorkspace, INVALID_CORRELATION)

        var bestCorr = -1f
        var bestDx = 0
        var bestDy = 0
        fun consider(dx: Int, dy: Int) {
            if (dx !in -radius..radius || dy !in -radius..radius) return
            val sourceX = roiToSourceX(roiPlan, (baseX + dx).toFloat())
            val sourceY = roiToSourceY(roiPlan, (baseY + dy).toFloat())
            if (
                personObservedThisFrame &&
                !candidateInsideObservedPersonHeadGate(sourceX, sourceY, personBbox)
            ) return
            val corr = roiCorrelationAt(
                gray = gray,
                size = size,
                state = state,
                centerX = baseX + dx,
                centerY = baseY + dy
            ) ?: return
            val index = (dy + radius) * (radius * 2 + 1) + (dx + radius)
            state.correlationWorkspace[index] = corr
            if (corr > bestCorr) {
                bestCorr = corr
                bestDx = dx
                bestDy = dy
            }
        }

        var coarseDy = -radius
        while (coarseDy <= radius) {
            var coarseDx = -radius
            while (coarseDx <= radius) {
                consider(coarseDx, coarseDy)
                coarseDx += ROI_COARSE_STEP
            }
            coarseDy += ROI_COARSE_STEP
        }
        // The coarse grid is only a basin locator. A high-frequency small face
        // can have a weak score one pixel away from the true peak, so applying
        // the final correlation threshold here would reject exactly the distant
        // faces this ROI path is meant to preserve. Refine first, then apply the
        // unchanged final confidence/uniqueness gates below.
        if (bestCorr <= INVALID_CORRELATION) return null

        val coarseBestDx = bestDx
        val coarseBestDy = bestDy
        for (refineDy in (coarseBestDy - ROI_COARSE_STEP)..(coarseBestDy + ROI_COARSE_STEP)) {
            for (refineDx in (coarseBestDx - ROI_COARSE_STEP)..(coarseBestDx + ROI_COARSE_STEP)) {
                consider(refineDx, refineDy)
            }
        }
        if (bestCorr < ROI_MIN_CORRELATION) return null

        var secondCorr = -1f
        var workspaceIndex = 0
        for (scanDy in -radius..radius) {
            for (scanDx in -radius..radius) {
                val corr = state.correlationWorkspace[workspaceIndex++]
                if (corr <= INVALID_CORRELATION) continue
                if (
                    max(
                        kotlin.math.abs(scanDx - bestDx),
                        kotlin.math.abs(scanDy - bestDy)
                    ) <= ROI_PEAK_NEIGHBORHOOD_RADIUS
                ) continue
                if (corr > secondCorr) secondCorr = corr
            }
        }
        val uniquenessGap = bestCorr - secondCorr.coerceAtLeast(-1f)
        if (secondCorr >= -0.5f && uniquenessGap < ROI_MIN_UNIQUENESS_GAP) return null

        val newCenterX = roiToSourceX(roiPlan, (baseX + bestDx).toFloat())
        val newCenterY = roiToSourceY(roiPlan, (baseY + bestDy).toFloat())
        val stepDx = newCenterX - state.centerXSource
        val stepDy = newCenterY - state.centerYSource
        val step = sqrt(stepDx * stepDx + stepDy * stepDy)
        val faceDiameter = max(state.radiusXSource, state.radiusYSource) * 2f
        val maxStep = max(12f, faceDiameter * ROI_MAX_FACE_DIAMETER_STEP)
        if (step > maxStep) return null

        if (personObservedThisFrame) {
            val bodyTranslation = PersonBboxMotionEstimator.estimate(
                previous = state.detectorPersonBbox,
                current = personBbox
            )
            val expectedX = state.detectorCenterXSource + bodyTranslation.dx
            val expectedY = state.detectorCenterYSource + bodyTranslation.dy
            val residualDx = newCenterX - expectedX
            val residualDy = newCenterY - expectedY
            val residual = sqrt(residualDx * residualDx + residualDy * residualDy)
            val maxResidual = max(24f, faceDiameter * ROI_MAX_BODY_ANCHOR_RESIDUAL_DIAMETERS)
            if (residual > maxResidual) return null
        }

        state.centerXSource = newCenterX
        state.centerYSource = newCenterY
        state.lastEvidencePtsUs = ptsUs
        return Match(
            region = FacePrivacyEllipse(
                centerX = newCenterX,
                centerY = newCenterY,
                radiusX = state.radiusXSource,
                radiusY = state.radiusYSource,
                source = FacePrivacyRegionSource.PREDICTED_FACE
            ),
            correlation = bestCorr,
            uniquenessGap = uniquenessGap,
            modelDx = bestDx,
            modelDy = bestDy
        )
    }

    private fun roiCorrelationAt(
        gray: ByteArray,
        size: Int,
        state: RoiState,
        centerX: Int,
        centerY: Int
    ): Float? {
        val halfExtent = state.patchHalfExtentLocal
        if (
            centerX - halfExtent < 0 || centerX + halfExtent >= size ||
            centerY - halfExtent < 0 || centerY + halfExtent >= size
        ) return null

        var sum = 0f
        for (i in state.template.indices) {
            val index = (centerY + state.sampleDy[i]) * size + centerX + state.sampleDx[i]
            val value = (gray[index].toInt() and 0xFF).toFloat()
            sum += value
        }
        val mean = sum / state.template.size.coerceAtLeast(1)
        var covariance = 0f
        var candidateNormSq = 0f
        for (i in state.template.indices) {
            val index = (centerY + state.sampleDy[i]) * size + centerX + state.sampleDx[i]
            val value = (gray[index].toInt() and 0xFF).toFloat()
            val centered = value - mean
            covariance += state.template[i] * centered
            candidateNormSq += centered * centered
        }
        if (candidateNormSq < ROI_MIN_CANDIDATE_NORM_SQ) return null
        return (covariance / (state.templateNorm * sqrt(candidateNormSq))).coerceIn(-1f, 1f)
    }

    private fun sourceToRoiX(plan: FaceHeadRoiPlan, sourceX: Float): Float =
        ((sourceX - plan.sourceRect.left) / plan.sourceRect.width) * plan.outputSize

    private fun sourceToRoiY(plan: FaceHeadRoiPlan, sourceY: Float): Float =
        ((sourceY - plan.sourceRect.top) / plan.sourceRect.height) * plan.outputSize

    private fun roiToSourceX(plan: FaceHeadRoiPlan, roiX: Float): Float =
        plan.sourceRect.left + (roiX / plan.outputSize.toFloat()) * plan.sourceRect.width

    private fun roiToSourceY(plan: FaceHeadRoiPlan, roiY: Float): Float =
        plan.sourceRect.top + (roiY / plan.outputSize.toFloat()) * plan.sourceRect.height

    private fun ensureRoiGray(rgbaTopDown: ByteBuffer, size: Int): ByteArray {
        val totalPixels = size * size
        if (roiGrayWorkspace.size != totalPixels) {
            roiGrayWorkspace = ByteArray(totalPixels)
        }
        val previousOrder = rgbaTopDown.order()
        rgbaTopDown.order(ByteOrder.LITTLE_ENDIAN)
        try {
            var srcOffset = 0
            for (i in 0 until totalPixels) {
                // RGBA bytes read as a little-endian Int become 0xAABBGGRR.
                // One direct-buffer getInt replaces three absolute byte gets.
                val rgba = rgbaTopDown.getInt(srcOffset)
                val r = rgba and 0xFF
                val g = (rgba ushr 8) and 0xFF
                val b = (rgba ushr 16) and 0xFF
                roiGrayWorkspace[i] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
                srcOffset += RGBA_STRIDE
            }
        } finally {
            rgbaTopDown.order(previousOrder)
        }
        return roiGrayWorkspace
    }

    private fun roiGrayAt(gray: ByteArray, size: Int, x: Int, y: Int): Float? {
        if (x !in 0 until size || y !in 0 until size) return null
        return (gray[y * size + x].toInt() and 0xFF).toFloat()
    }

    private fun candidateInsideObservedPersonHeadGate(
        sourceX: Float,
        sourceY: Float,
        personBbox: FloatRect
    ): Boolean {
        if (personBbox.width <= 1f || personBbox.height <= 1f) return false
        val xMargin = personBbox.width * PERSON_X_MARGIN_RATIO
        val yTopMargin = personBbox.height * PERSON_TOP_MARGIN_RATIO
        return sourceX >= personBbox.left - xMargin &&
            sourceX <= personBbox.right + xMargin &&
            sourceY >= personBbox.top - yTopMargin &&
            sourceY <= personBbox.top + personBbox.height * PERSON_HEAD_BOTTOM_RATIO
    }

    fun seed(
        trackId: Int,
        rgbaBottomUp: ByteBuffer,
        mapper: ModelCoordinateMapper,
        detected: FacePrivacyEllipse,
        ptsUs: Long
    ): Boolean {
        if (detected.source != FacePrivacyRegionSource.DETECTED_FACE) return false
        if (rgbaBottomUp.capacity() < mapper.modelInputSize * mapper.modelInputSize * RGBA_STRIDE) return false

        val centerX = mapper.sourceToModelX(detected.centerX)
        val centerY = mapper.sourceToModelY(detected.centerY)
        val minRadiusModel = minOf(detected.radiusX, detected.radiusY) * mapper.scale
        val maxRadiusModel = max(detected.radiusX, detected.radiusY) * mapper.scale
        val patchHalfExtent = (minRadiusModel * PATCH_RADIUS_FRACTION)
            .roundToInt()
            .coerceIn(MIN_PATCH_HALF_EXTENT, MAX_PATCH_HALF_EXTENT)
        val searchRadius = (maxRadiusModel * SEARCH_RADIUS_FACE_FRACTION)
            .roundToInt()
            .coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)

        val offsets = buildSampleOffsets(patchHalfExtent)
        val raw = FloatArray(offsets.first.size)
        for (i in raw.indices) {
            val x = centerX.roundToInt() + offsets.first[i]
            val y = centerY.roundToInt() + offsets.second[i]
            val value = luminanceAt(rgbaBottomUp, mapper.modelInputSize, x, y) ?: return false
            raw[i] = value
        }
        val mean = raw.average().toFloat()
        var normSq = 0f
        for (i in raw.indices) {
            raw[i] -= mean
            normSq += raw[i] * raw[i]
        }
        if (normSq < MIN_TEMPLATE_NORM_SQ) return false

        stateByTrackId[trackId] = State(
            template = raw,
            sampleDx = offsets.first,
            sampleDy = offsets.second,
            templateNorm = sqrt(normSq),
            radiusXSource = detected.radiusX,
            radiusYSource = detected.radiusY,
            searchRadiusModel = searchRadius,
            correlationWorkspace = FloatArray((searchRadius * 2 + 1) * (searchRadius * 2 + 1)),
            centerModelX = centerX,
            centerModelY = centerY,
            lastPtsUs = ptsUs
        )
        return true
    }

    fun match(
        trackId: Int,
        rgbaBottomUp: ByteBuffer,
        mapper: ModelCoordinateMapper,
        ptsUs: Long,
        personBbox: FloatRect? = null
    ): Match? {
        val state = stateByTrackId[trackId] ?: return null
        val evidenceGapUs = ptsUs - state.lastPtsUs
        if (evidenceGapUs !in 0L..maxEvidenceGapUs || ptsUs < state.lastPtsUs) {
            stateByTrackId.remove(trackId)
            return null
        }
        if (rgbaBottomUp.capacity() < mapper.modelInputSize * mapper.modelInputSize * RGBA_STRIDE) return null

        val gray = ensureGrayFrame(rgbaBottomUp, mapper.modelInputSize, ptsUs)

        val baseX = state.centerModelX.roundToInt()
        val baseY = state.centerModelY.roundToInt()
        var bestCorr = -1f
        var bestDx = 0
        var bestDy = 0
        var workspaceIndex = 0

        // Coarse-to-fine search keeps the same local motion envelope while
        // avoiding a full 41x41 NCC scan for every face on every frame. The
        // winning coarse peak is refined at one-pixel resolution below.
        for (dy in -state.searchRadiusModel..state.searchRadiusModel step COARSE_SEARCH_STEP) {
            for (dx in -state.searchRadiusModel..state.searchRadiusModel step COARSE_SEARCH_STEP) {
                val candidateX = baseX + dx
                val candidateY = baseY + dy
                val corr = if (candidateInsidePersonHeadGate(candidateX, candidateY, mapper, personBbox)) {
                    correlationAt(gray, mapper.modelInputSize, state, candidateX, candidateY)
                } else {
                    null
                }
                state.correlationWorkspace[workspaceIndex++] = corr ?: INVALID_CORRELATION
                if (corr == null) continue
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        if (bestCorr < minCorrelation) return null

        val coarseBestDx = bestDx
        val coarseBestDy = bestDy
        for (dy in (coarseBestDy - COARSE_SEARCH_STEP)..(coarseBestDy + COARSE_SEARCH_STEP)) {
            if (dy !in -state.searchRadiusModel..state.searchRadiusModel) continue
            for (dx in (coarseBestDx - COARSE_SEARCH_STEP)..(coarseBestDx + COARSE_SEARCH_STEP)) {
                if (dx !in -state.searchRadiusModel..state.searchRadiusModel) continue
                val candidateX = baseX + dx
                val candidateY = baseY + dy
                if (!candidateInsidePersonHeadGate(candidateX, candidateY, mapper, personBbox)) continue
                val corr = correlationAt(gray, mapper.modelInputSize, state, candidateX, candidateY) ?: continue
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // Adjacent one-pixel candidates naturally form one correlation peak. The
        // uniqueness check compares the winning peak with the strongest spatially
        // separate peak instead of rejecting normal subpixel plateaus.
        var secondCorr = -1f
        workspaceIndex = 0
        for (dy in -state.searchRadiusModel..state.searchRadiusModel step COARSE_SEARCH_STEP) {
            for (dx in -state.searchRadiusModel..state.searchRadiusModel step COARSE_SEARCH_STEP) {
                val corr = state.correlationWorkspace[workspaceIndex++]
                if (max(kotlin.math.abs(dx - bestDx), kotlin.math.abs(dy - bestDy)) <= PEAK_NEIGHBORHOOD_RADIUS) {
                    continue
                }
                if (corr <= INVALID_CORRELATION) continue
                if (corr > secondCorr) secondCorr = corr
            }
        }
        val uniquenessGap = bestCorr - secondCorr.coerceAtLeast(-1f)
        if (secondCorr >= -0.5f && uniquenessGap < minUniquenessGap) return null

        state.centerModelX = (baseX + bestDx).toFloat()
        state.centerModelY = (baseY + bestDy).toFloat()
        state.lastPtsUs = ptsUs
        return Match(
            region = FacePrivacyEllipse(
                centerX = mapper.modelToSourceX(state.centerModelX),
                centerY = mapper.modelToSourceY(state.centerModelY),
                radiusX = state.radiusXSource,
                radiusY = state.radiusYSource,
                source = FacePrivacyRegionSource.PREDICTED_FACE
            ),
            correlation = bestCorr,
            uniquenessGap = uniquenessGap,
            modelDx = bestDx,
            modelDy = bestDy
        )
    }

    private fun correlationAt(
        gray: ByteArray,
        size: Int,
        state: State,
        centerX: Int,
        centerY: Int
    ): Float? {
        var sum = 0f
        for (i in state.template.indices) {
            val value = grayAt(gray, size, centerX + state.sampleDx[i], centerY + state.sampleDy[i]) ?: return null
            sum += value
        }
        val mean = sum / state.template.size.coerceAtLeast(1)
        var covariance = 0f
        var candidateNormSq = 0f
        for (i in state.template.indices) {
            val value = grayAt(gray, size, centerX + state.sampleDx[i], centerY + state.sampleDy[i]) ?: return null
            val centered = value - mean
            covariance += state.template[i] * centered
            candidateNormSq += centered * centered
        }
        if (candidateNormSq < MIN_CANDIDATE_NORM_SQ) return null
        return (covariance / (state.templateNorm * sqrt(candidateNormSq))).coerceIn(-1f, 1f)
    }

    private fun ensureGrayFrame(rgbaBottomUp: ByteBuffer, size: Int, ptsUs: Long): ByteArray {
        val totalPixels = size * size
        if (grayWorkspace.size != totalPixels) {
            grayWorkspace = ByteArray(totalPixels)
            grayWorkspaceSize = size
            grayWorkspacePtsUs = Long.MIN_VALUE
        }
        if (grayWorkspacePtsUs == ptsUs && grayWorkspaceSize == size) return grayWorkspace

        for (visualY in 0 until size) {
            val bufferY = size - 1 - visualY
            var srcOffset = bufferY * size * RGBA_STRIDE
            var dstOffset = visualY * size
            for (x in 0 until size) {
                val r = rgbaBottomUp.get(srcOffset).toInt() and 0xFF
                val g = rgbaBottomUp.get(srcOffset + 1).toInt() and 0xFF
                val b = rgbaBottomUp.get(srcOffset + 2).toInt() and 0xFF
                grayWorkspace[dstOffset] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
                srcOffset += RGBA_STRIDE
                dstOffset++
            }
        }
        grayWorkspaceSize = size
        grayWorkspacePtsUs = ptsUs
        return grayWorkspace
    }

    private fun grayAt(gray: ByteArray, size: Int, x: Int, y: Int): Float? {
        if (x !in 0 until size || y !in 0 until size) return null
        return (gray[y * size + x].toInt() and 0xFF).toFloat()
    }

    private fun candidateInsidePersonHeadGate(
        modelX: Int,
        modelY: Int,
        mapper: ModelCoordinateMapper,
        personBbox: FloatRect?
    ): Boolean {
        if (personBbox == null || personBbox.width <= 1f || personBbox.height <= 1f) return true
        val x = mapper.modelToSourceX(modelX.toFloat())
        val y = mapper.modelToSourceY(modelY.toFloat())
        val xMargin = personBbox.width * PERSON_X_MARGIN_RATIO
        val yTopMargin = personBbox.height * PERSON_TOP_MARGIN_RATIO
        return x >= personBbox.left - xMargin &&
            x <= personBbox.right + xMargin &&
            y >= personBbox.top - yTopMargin &&
            y <= personBbox.top + personBbox.height * PERSON_HEAD_BOTTOM_RATIO
    }

    private fun buildSampleOffsets(halfExtent: Int): Pair<IntArray, IntArray> {
        val dx = IntArray(SAMPLE_GRID * SAMPLE_GRID)
        val dy = IntArray(SAMPLE_GRID * SAMPLE_GRID)
        var index = 0
        for (gy in 0 until SAMPLE_GRID) {
            val oy = (-halfExtent + (2f * halfExtent * gy / (SAMPLE_GRID - 1))).roundToInt()
            for (gx in 0 until SAMPLE_GRID) {
                val ox = (-halfExtent + (2f * halfExtent * gx / (SAMPLE_GRID - 1))).roundToInt()
                dx[index] = ox
                dy[index] = oy
                index++
            }
        }
        return dx to dy
    }

    private fun luminanceAt(rgbaBottomUp: ByteBuffer, size: Int, visualX: Int, visualY: Int): Float? {
        if (visualX !in 0 until size || visualY !in 0 until size) return null
        val bufferY = size - 1 - visualY
        val offset = (bufferY * size + visualX) * RGBA_STRIDE
        if (offset < 0 || offset + 2 >= rgbaBottomUp.capacity()) return null
        val r = rgbaBottomUp.get(offset).toInt() and 0xFF
        val g = rgbaBottomUp.get(offset + 1).toInt() and 0xFF
        val b = rgbaBottomUp.get(offset + 2).toInt() and 0xFF
        return ((77 * r + 150 * g + 29 * b) ushr 8).toFloat()
    }

    companion object {
        private const val RGBA_STRIDE = 4
        private const val INVALID_CORRELATION = -2f
        private const val SAMPLE_GRID = 9
        private const val PATCH_RADIUS_FRACTION = 0.62f
        private const val SEARCH_RADIUS_FACE_FRACTION = 1.15f
        private const val MIN_PATCH_HALF_EXTENT = 4
        private const val MAX_PATCH_HALF_EXTENT = 12
        private const val MIN_SEARCH_RADIUS = 6
        private const val MAX_SEARCH_RADIUS = 20
        private const val COARSE_SEARCH_STEP = 2
        private const val PEAK_NEIGHBORHOOD_RADIUS = 2
        private const val MIN_TEMPLATE_NORM_SQ = 1_500f
        private const val MIN_CANDIDATE_NORM_SQ = 1_000f
        private const val PERSON_X_MARGIN_RATIO = 0.18f
        private const val PERSON_TOP_MARGIN_RATIO = 0.15f
        private const val PERSON_HEAD_BOTTOM_RATIO = 0.58f

        // This is a gap between *current pixel evidence* samples, not a total
        // lifetime from the detector seed. Continuous high-correlation current
        // pixels renew the localization lease; a real evidence gap expires it.
        const val DEFAULT_MAX_EVIDENCE_GAP_US = 150_000L
        private const val DEFAULT_MIN_CORRELATION = 0.72f
        private const val DEFAULT_MIN_UNIQUENESS_GAP = 0.035f

        // High-resolution face-ROI tracker. The evidence gap remains 150 ms,
        // but detector ownership itself has a separate hard age cap so a fixed
        // appearance template cannot drift indefinitely just because each frame
        // finds a weakly plausible successor.
        const val ROI_MAX_EVIDENCE_GAP_US = 150_000L
        const val ROI_MAX_DETECTOR_SEED_AGE_US = 800_000L
        private const val ROI_PATCH_RADIUS_FRACTION = 0.48f
        private const val ROI_SEARCH_RADIUS_FACE_FRACTION = 1.15f
        private const val ROI_MIN_PATCH_HALF_EXTENT = 6
        private const val ROI_MAX_PATCH_HALF_EXTENT = 24
        private const val ROI_MIN_SEARCH_RADIUS = 12
        private const val ROI_MAX_SEARCH_RADIUS = 52
        private const val ROI_COARSE_STEP = 4
        private const val ROI_PEAK_NEIGHBORHOOD_RADIUS = 3
        private const val ROI_MIN_TEMPLATE_NORM_SQ = 2_000f
        private const val ROI_MIN_CANDIDATE_NORM_SQ = 1_500f
        private const val ROI_MIN_CORRELATION = 0.68f
        private const val ROI_MIN_UNIQUENESS_GAP = 0.03f
        private const val ROI_MAX_FACE_DIAMETER_STEP = 0.80f
        private const val ROI_MAX_BODY_ANCHOR_RESIDUAL_DIAMETERS = 1.35f
    }
}
