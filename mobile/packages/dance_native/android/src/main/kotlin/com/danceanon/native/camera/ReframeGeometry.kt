package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure geometry helpers shared by preview/export reframing. */
object ReframeGeometry {
    /**
     * Convert a crop expressed in visual, top-left source coordinates into the
     * screen-GL UV convention consumed by [com.danceanon.native.render.GlShaders].
     */
    fun visualTopLeftToScreenGl(crop: FloatRect): FloatRect = FloatRect(
        left = crop.left,
        top = 1f - crop.bottom,
        right = crop.right,
        bottom = 1f - crop.top
    )

    /**
     * Largest exact 9:16 frame that fits inside the visual source and optional
     * long-edge cap. Width/height are multiples of 18/32, hence encoder-even.
     */
    fun exactNineSixteenSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxHeight: Int = Int.MAX_VALUE
    ): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxHeight <= 0) return null
        val units = min(sourceWidth / 18, min(sourceHeight, maxHeight) / 32)
        if (units <= 0) return null
        return units * 18 to units * 32
    }

    /**
     * Full-frame intermediate for privacy composition before the final post-crop.
     * It keeps source aspect and is only as large as needed for a zoom=1 crop to
     * reach the requested output resolution.
     */
    fun postCropCompositionSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val requestedScale = if (sourceAspect >= targetAspect) {
            targetHeight.toFloat() / sourceHeight.toFloat()
        } else {
            targetWidth.toFloat() / sourceWidth.toFloat()
        }
        val scale = min(1f, requestedScale)
        fun even(value: Float): Int = max(2, ((value.roundToInt() + 1) / 2) * 2)
        return even(sourceWidth * scale) to even(sourceHeight * scale)
    }

    /** Texture matrix for sampling a GL-space crop from an already composed FBO. */
    fun textureMatrixForScreenGlCrop(crop: FloatRect): FloatArray {
        require(crop.width > 0f && crop.height > 0f) { "Invalid crop $crop" }
        return floatArrayOf(
            crop.width, 0f, 0f, 0f,
            0f, crop.height, 0f, 0f,
            0f, 0f, 1f, 0f,
            crop.left, crop.top, 0f, 1f
        )
    }
}
