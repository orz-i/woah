package com.danceanon.native

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.sam2.Sam2MaskPostprocessor
import com.danceanon.native.sam2.Sam2Preprocessor
import com.danceanon.native.sam2.Sam2TensorContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Sam2PreprocessorTest {

    @Test
    fun testBboxPromptCoordinateTransformationWithoutLetterboxPadding() {
        val srcW = 1280
        val srcH = 720
        val bbox = FloatRect(128f, 72f, 640f, 360f)

        val modelBbox = Sam2Preprocessor.transformBboxPrompt(bbox, srcW, srcH)
        // xModel = 128 / 1280 * 1024 = 102.4
        // yModel = 72 / 720 * 1024 = 102.4
        // x2Model = 640 / 1280 * 1024 = 512.0
        // y2Model = 360 / 720 * 1024 = 512.0
        assertEquals(102.4f, modelBbox[0], 0.01f)
        assertEquals(102.4f, modelBbox[1], 0.01f)
        assertEquals(512.0f, modelBbox[2], 0.01f)
        assertEquals(512.0f, modelBbox[3], 0.01f)
    }

    @Test
    fun testMaskBboxDerivationDesktopParity() {
        val w = 100
        val h = 100
        val mask = FloatArray(w * h) { 0f }

        // Create a 20x40 soft object at (30, 20) to (49, 59)
        for (y in 20..59) {
            for (x in 30..49) {
                mask[y * w + x] = 0.8f // above threshold 0.15
            }
        }

        val bbox = Sam2MaskPostprocessor.computeBboxFromMask(
            mask, w, h,
            threshold = 0.15f,
            expandRatio = 0.05f
        )

        val bw = 49 - 30 + 1 // 20
        val bh = 59 - 20 + 1 // 40
        val expandW = 20 * 0.05f // 1.0
        val expandH = 40 * 0.05f // 2.0

        assertEquals(29.0f, bbox.left, 0.01f)
        assertEquals(18.0f, bbox.top, 0.01f)
        assertEquals(51.0f, bbox.right, 0.01f)
        assertEquals(62.0f, bbox.bottom, 0.01f)
    }

    @Test
    fun testEmptyMaskReturnsFullFrame() {
        val w = 50
        val h = 50
        val mask = FloatArray(w * h) { 0f }

        val bbox = Sam2MaskPostprocessor.computeBboxFromMask(mask, w, h)
        assertEquals(0f, bbox.left)
        assertEquals(0f, bbox.top)
        assertEquals(50f, bbox.right)
        assertEquals(50f, bbox.bottom)
    }

    @Test
    fun testMaskBilinearInterpolation() {
        val srcMask = floatArrayOf(
            0f, 1f,
            1f, 0f
        )
        val resized = Sam2MaskPostprocessor.resizeMaskBilinear(srcMask, 2, 2, 3, 3)
        assertEquals(9, resized.size)
        // Center pixel should be average 0.5
        assertEquals(0.5f, resized[4], 0.01f)
    }
}

