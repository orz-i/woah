package art.gaoge.dance.engine.clarity

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.math.max
import kotlin.math.min

/**
 * Pure source-space geometry for selecting protected *review candidates* after
 * following/cropping. The input crop is the actual visual/top-left normalized
 * crop computed once by SmoothFollower; bboxes are source-space pixel coords.
 * The output is only a QA heuristic: a bbox is not proof that a mask/sticker
 * was actually drawn, nor that a face was hidden.
 */
object CropClarityCropPrivacyGeometry {
    private const val MIN_TRACK_VISIBLE_FRACTION = 0.12f
    private const val MIN_CROP_COVERAGE_FRACTION = 0.002f

    data class Evidence(
        val sourceProtectedCount: Int,
        val cropProtectedCount: Int,
        val cropProtectedAreaFraction: Float,
        val maxCropOverlap: Float
    ) {
        val visibleInFinalCrop: Boolean get() = cropProtectedCount > 0
    }

    /** Pixel source -> normalized visual source -> final *portrait* crop box. */
    fun projectToPortrait(
        box: FloatRect, crop: FloatRect, sourceWidth: Int, sourceHeight: Int
    ): FloatRect? {
        if (!valid(crop) || sourceWidth <= 0 || sourceHeight <= 0 || !valid(box)) return null
        val inSource = FloatRect(
            box.left / sourceWidth, box.top / sourceHeight,
            box.right / sourceWidth, box.bottom / sourceHeight
        )
        val clipped = intersect(inSource, crop) ?: return null
        return FloatRect(
            (clipped.left - crop.left) / crop.width,
            (clipped.top - crop.top) / crop.height,
            (clipped.right - crop.left) / crop.width,
            (clipped.bottom - crop.top) / crop.height
        )
    }

    fun evaluate(
        crop: FloatRect,
        sourceWidth: Int,
        sourceHeight: Int,
        protectedBoxes: List<FloatRect>,
        otherBoxes: List<FloatRect>
    ): Evidence {
        if (!valid(crop) || sourceWidth <= 0 || sourceHeight <= 0) {
            return Evidence(protectedBoxes.size, 0, 0f, 0f)
        }
        val cropArea = crop.width * crop.height
        val normalizedProtected = protectedBoxes.mapNotNull {
            normalizeAndClip(it, crop, sourceWidth, sourceHeight)
        }
        val visible = normalizedProtected.filter { (original, clipped) ->
            clipped.width * clipped.height >= original.width * original.height * MIN_TRACK_VISIBLE_FRACTION &&
                clipped.width * clipped.height >= cropArea * MIN_CROP_COVERAGE_FRACTION
        }.map { it.second }
        val otherVisible = otherBoxes.mapNotNull {
            normalizeAndClip(it, crop, sourceWidth, sourceHeight)?.second
        }
        val cover = visible.sumOf { (it.width * it.height).toDouble() }.toFloat() / cropArea
        var maxIoU = 0f
        for ((i, box) in visible.withIndex()) {
            for (other in otherVisible) {
                maxIoU = max(maxIoU, iou(box, other))
            }
            for (other in visible.drop(i + 1)) {
                maxIoU = max(maxIoU, iou(box, other))
            }
        }
        return Evidence(
            sourceProtectedCount = protectedBoxes.size,
            cropProtectedCount = visible.size,
            cropProtectedAreaFraction = cover.coerceIn(0f, 1f),
            maxCropOverlap = maxIoU.coerceIn(0f, 1f)
        )
    }

    /** Output normalized pixel -> original visual source normalized coordinate. */
    fun outputToSource(x: Float, y: Float, crop: FloatRect): Pair<Float, Float> =
        (crop.left + crop.width * x) to (crop.top + crop.height * y)

    private fun normalizeAndClip(
        box: FloatRect, crop: FloatRect, sourceWidth: Int, sourceHeight: Int
    ): Pair<FloatRect, FloatRect>? {
        if (!valid(box)) return null
        val normalized = FloatRect(
            box.left / sourceWidth, box.top / sourceHeight,
            box.right / sourceWidth, box.bottom / sourceHeight
        )
        val original = intersect(normalized, FloatRect(0f, 0f, 1f, 1f)) ?: return null
        val cropped = intersect(original, crop) ?: return null
        return original to cropped
    }

    private fun intersect(a: FloatRect, b: FloatRect): FloatRect? {
        if (!valid(a) || !valid(b)) return null
        val clipped = FloatRect(
            max(a.left, b.left), max(a.top, b.top),
            min(a.right, b.right), min(a.bottom, b.bottom)
        )
        return clipped.takeIf { valid(it) }
    }

    private fun iou(a: FloatRect, b: FloatRect): Float {
        val intersection = intersect(a, b) ?: return 0f
        val intersectionArea = intersection.width * intersection.height
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        return intersectionArea / (areaA + areaB - intersectionArea).coerceAtLeast(1e-8f)
    }

    private fun valid(box: FloatRect): Boolean =
        box.left.isFinite() && box.top.isFinite() && box.right.isFinite() &&
            box.bottom.isFinite() && box.width > 0f && box.height > 0f
}
