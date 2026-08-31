package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
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

    private val stateByTrackId = mutableMapOf<Int, State>()
    private var grayWorkspace = ByteArray(0)
    private var grayWorkspaceSize = 0
    private var grayWorkspacePtsUs = Long.MIN_VALUE

    fun retainTracks(trackIds: Set<Int>) {
        stateByTrackId.keys.retainAll(trackIds)
    }

    fun remove(trackId: Int) {
        stateByTrackId.remove(trackId)
    }

    fun hasUsableState(trackId: Int, ptsUs: Long): Boolean {
        val state = stateByTrackId[trackId] ?: return false
        val evidenceGapUs = ptsUs - state.lastPtsUs
        return evidenceGapUs in 0L..maxEvidenceGapUs
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
    }
}
