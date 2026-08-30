package com.danceanon.native.privacy

import com.danceanon.native.face.FacePoint
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Uses the already-owned YOLO person segmentation as local geometric evidence
 * for FACE_ONLY fallback. It never assigns identity and never renders the body
 * mask itself; it only nudges a face/head seed toward current foreground pixels.
 *
 * The search is intentionally local around the existing face seed. This avoids
 * raised hands/arms or neighboring silhouettes stealing the fallback center while
 * still allowing the head to move inside a comparatively stable person bbox.
 */
object BodyMaskFaceHeadEstimator {
    private const val MASK_THRESHOLD = 96
    private const val MIN_SUPPORT_ROWS = 2
    private const val PERSON_SIDE_MARGIN_RATIO = 0.08f
    private const val PERSON_TOP_MARGIN_RATIO = 0.10f
    private const val PERSON_HEAD_MAX_Y_RATIO = 0.42f
    private const val MIN_RUN_WIDTH_RATIO = 0.30f
    private const val MAX_RUN_WIDTH_RATIO = 1.75f
    private const val MAX_RUN_CENTER_DISTANCE_RATIO = 1.05f

    fun estimate(
        mask: NativeMask?,
        personBbox: FloatRect,
        seedCenterX: Float,
        seedCenterY: Float,
        seedRadiusX: Float,
        seedRadiusY: Float
    ): FacePoint? {
        if (mask == null || personBbox.width <= 1f || personBbox.height <= 1f) return null
        if (mask.width <= 1 || mask.height <= 1 || mask.buffer.capacity() < mask.width * mask.height) return null
        val mapper = mask.mapper ?: return null

        val searchHalfWidth = max(
            max(seedRadiusX * 2.2f, personBbox.width * 0.34f),
            24f
        )
        val searchHalfHeight = max(
            max(seedRadiusY * 2.0f, personBbox.height * 0.15f),
            24f
        )
        val minSourceX = max(
            personBbox.left - personBbox.width * PERSON_SIDE_MARGIN_RATIO,
            seedCenterX - searchHalfWidth
        )
        val maxSourceX = min(
            personBbox.right + personBbox.width * PERSON_SIDE_MARGIN_RATIO,
            seedCenterX + searchHalfWidth
        )
        val minSourceY = max(
            personBbox.top - personBbox.height * PERSON_TOP_MARGIN_RATIO,
            seedCenterY - searchHalfHeight
        )
        val maxSourceY = min(
            personBbox.top + personBbox.height * PERSON_HEAD_MAX_Y_RATIO,
            seedCenterY + searchHalfHeight
        )
        if (maxSourceX <= minSourceX || maxSourceY <= minSourceY) return null

        val minX = floor(mapper.sourceToProtoX(minSourceX)).toInt().coerceIn(0, mask.width - 1)
        val maxX = ceil(mapper.sourceToProtoX(maxSourceX)).toInt().coerceIn(0, mask.width - 1)
        val minY = floor(mapper.sourceToProtoY(minSourceY)).toInt().coerceIn(0, mask.height - 1)
        val maxY = ceil(mapper.sourceToProtoY(maxSourceY)).toInt().coerceIn(0, mask.height - 1)
        if (maxX < minX || maxY < minY) return null

        val seedProtoX = mapper.sourceToProtoX(seedCenterX)
        val seedProtoY = mapper.sourceToProtoY(seedCenterY)
        val expectedRunWidth = max(
            abs(mapper.sourceToProtoX(seedCenterX + seedRadiusX * 0.70f) -
                mapper.sourceToProtoX(seedCenterX - seedRadiusX * 0.70f)),
            2f
        )
        val expectedHeadHalfHeight = max(
            abs(mapper.sourceToProtoY(seedCenterY + seedRadiusY * 0.72f) - seedProtoY),
            2f
        )

        // A full-person mask has no head/hand semantics. A weighted centroid of
        // all upper-body pixels therefore drifts toward shoulders or a nearby arm.
        // Instead, examine each scanline and keep only a *head-like narrow run*
        // nearest the trusted face seed. Wide shoulder/arm unions fail the run
        // width gate and contribute nothing. This uses the stable body mask as a
        // local motion cue rather than pretending its centroid is a face center.
        var weightedX = 0.0
        var weightedY = 0.0
        var weightSum = 0.0
        var supportRows = 0
        for (y in minY..maxY) {
            var bestCenterX: Float? = null
            var bestWidth = 0f
            var bestDistance = Float.POSITIVE_INFINITY
            var x = minX
            while (x <= maxX) {
                while (x <= maxX && (mask.buffer.get(y * mask.width + x).toInt() and 0xFF) < MASK_THRESHOLD) {
                    x++
                }
                if (x > maxX) break
                val runStart = x
                while (x <= maxX && (mask.buffer.get(y * mask.width + x).toInt() and 0xFF) >= MASK_THRESHOLD) {
                    x++
                }
                val runEndExclusive = x
                val runWidth = (runEndExclusive - runStart).toFloat()
                val widthRatio = runWidth / expectedRunWidth
                if (widthRatio !in MIN_RUN_WIDTH_RATIO..MAX_RUN_WIDTH_RATIO) continue

                val runCenterX = (runStart + runEndExclusive) * 0.5f
                val centerDistance = abs(runCenterX - seedProtoX) / expectedRunWidth
                if (centerDistance > MAX_RUN_CENTER_DISTANCE_RATIO) continue
                if (centerDistance < bestDistance) {
                    bestDistance = centerDistance
                    bestCenterX = runCenterX
                    bestWidth = runWidth
                }
            }

            val rowCenterX = bestCenterX ?: continue
            val yDistance = abs((y + 0.5f) - seedProtoY) / expectedHeadHalfHeight
            if (yDistance > 1.75f) continue
            val widthError = abs(bestWidth - expectedRunWidth) / expectedRunWidth
            val rowWeight = 1f / (1f + bestDistance * 2.2f + yDistance * 0.55f + widthError * 0.75f)
            weightedX += rowCenterX * rowWeight
            weightedY += (y + 0.5) * rowWeight
            weightSum += rowWeight
            supportRows++
        }
        if (supportRows < MIN_SUPPORT_ROWS || weightSum <= 0.50) return null

        val centerProtoX = (weightedX / weightSum).toFloat()
        val centerProtoY = (weightedY / weightSum).toFloat()
        val centerSourceX = protoToSourceX(centerProtoX, mask, mapper.modelInputSize)
        val centerSourceY = protoToSourceY(centerProtoY, mask, mapper.modelInputSize)

        // A single 160x160 mask pixel represents many source pixels. Keep the
        // correction deliberately smaller than the old centroid path. If the
        // silhouette cannot make a local, head-like case, holding the trusted
        // face is safer than following a body-mask feature.
        val maxShiftX = max(seedRadiusX * 0.80f, personBbox.width * 0.10f)
        val maxShiftY = max(seedRadiusY * 0.70f, personBbox.height * 0.055f)
        return FacePoint(
            x = centerSourceX.coerceIn(seedCenterX - maxShiftX, seedCenterX + maxShiftX),
            y = centerSourceY.coerceIn(seedCenterY - maxShiftY, seedCenterY + maxShiftY)
        )
    }

    private fun protoToSourceX(
        protoX: Float,
        mask: NativeMask,
        modelInputSize: Int
    ): Float {
        val mapper = requireNotNull(mask.mapper)
        val modelX = (protoX / mask.width.toFloat()) * modelInputSize.toFloat()
        return mapper.modelToSourceX(modelX)
    }

    private fun protoToSourceY(
        protoY: Float,
        mask: NativeMask,
        modelInputSize: Int
    ): Float {
        val mapper = requireNotNull(mask.mapper)
        val modelY = (protoY / mask.height.toFloat()) * modelInputSize.toFloat()
        return mapper.modelToSourceY(modelY)
    }
}
