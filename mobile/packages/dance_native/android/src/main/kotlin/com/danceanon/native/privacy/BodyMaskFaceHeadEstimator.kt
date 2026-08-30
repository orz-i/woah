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
    private const val MIN_SUPPORT_PIXELS = 3
    private const val PERSON_SIDE_MARGIN_RATIO = 0.08f
    private const val PERSON_TOP_MARGIN_RATIO = 0.10f
    private const val PERSON_HEAD_MAX_Y_RATIO = 0.42f

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
        val radiusProtoX = max(
            abs(mapper.sourceToProtoX(seedCenterX + searchHalfWidth) - seedProtoX),
            1f
        )
        val radiusProtoY = max(
            abs(mapper.sourceToProtoY(seedCenterY + searchHalfHeight) - seedProtoY),
            1f
        )

        var weightedX = 0.0
        var weightedY = 0.0
        var weightSum = 0.0
        var supportPixels = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val value = mask.buffer.get(y * mask.width + x).toInt() and 0xFF
                if (value < MASK_THRESHOLD) continue
                val dx = ((x + 0.5f) - seedProtoX) / radiusProtoX
                val dy = ((y + 0.5f) - seedProtoY) / radiusProtoY
                val distanceSq = dx * dx + dy * dy
                if (distanceSq > 1.45f) continue

                // Keep the seed as a prior, but let strong current mask support
                // move the estimate. A rational weight is cheaper than exp() on
                // this hot path and avoids snapping to distant arm pixels.
                val proximityWeight = 1f / (1f + 2.25f * distanceSq)
                val maskWeight = value / 255f
                val weight = (proximityWeight * maskWeight).toDouble()
                weightedX += (x + 0.5) * weight
                weightedY += (y + 0.5) * weight
                weightSum += weight
                supportPixels++
            }
        }
        if (supportPixels < MIN_SUPPORT_PIXELS || weightSum <= 0.25) return null

        val centerProtoX = (weightedX / weightSum).toFloat()
        val centerProtoY = (weightedY / weightSum).toFloat()
        val centerSourceX = protoToSourceX(centerProtoX, mask, mapper.modelInputSize)
        val centerSourceY = protoToSourceY(centerProtoY, mask, mapper.modelInputSize)

        // A single 160x160 mask pixel represents many source pixels. Clamp the
        // per-frame correction so coarse proto quantization cannot create sticker
        // jumps, while still allowing meaningful articulated head motion.
        val maxShiftX = max(seedRadiusX * 1.35f, personBbox.width * 0.20f)
        val maxShiftY = max(seedRadiusY * 1.20f, personBbox.height * 0.10f)
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
