package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReframeGeometryTest {
    @Test
    fun visualTopLeftCropConvertsToScreenGlWithoutChangingHorizontalBounds() {
        val converted = ReframeGeometry.visualTopLeftToScreenGl(
            FloatRect(left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.7f)
        )

        assertEquals(0.2f, converted.left, 0.00001f)
        assertEquals(0.3f, converted.top, 0.00001f)
        assertEquals(0.6f, converted.right, 0.00001f)
        assertEquals(0.9f, converted.bottom, 0.00001f)
    }

    @Test
    fun fullFrameIsStableAcrossCoordinateConversion() {
        assertEquals(
            FloatRect(0f, 0f, 1f, 1f),
            ReframeGeometry.visualTopLeftToScreenGl(FloatRect(0f, 0f, 1f, 1f))
        )
    }

    @Test
    fun exactPortraitPreviewMatchesExportGeometryAndNeverEnlarges() {
        assertEquals(594 to 1056, ReframeGeometry.exactNineSixteenSize(1920, 1080, 1280))
        assertEquals(720 to 1280, ReframeGeometry.exactNineSixteenSize(3840, 2160, 1280))
        assertEquals(1080 to 1920, ReframeGeometry.exactNineSixteenSize(3840, 2160, 1920))
        assertEquals(720 to 1280, ReframeGeometry.exactNineSixteenSize(720, 1280, 1920))
    }

    @Test
    fun impossibleTinyFrameDoesNotClaimExactPortraitGeometry() {
        assertNull(ReframeGeometry.exactNineSixteenSize(17, 31, 1280))
    }

    @Test
    fun postCropCompositionKeepsFullSourceAspectWithoutRenderingUnneededPixels() {
        assertEquals(1878 to 1056, ReframeGeometry.postCropCompositionSize(1920, 1080, 594, 1056))
        assertEquals(3414 to 1920, ReframeGeometry.postCropCompositionSize(3840, 2160, 1080, 1920))
        assertEquals(720 to 1280, ReframeGeometry.postCropCompositionSize(720, 1280, 720, 1280))
    }

    @Test
    fun cropTextureMatrixMapsOutputUvIntoRequestedGlCrop() {
        val matrix = ReframeGeometry.textureMatrixForScreenGlCrop(
            FloatRect(left = 0.25f, top = 0.10f, right = 0.75f, bottom = 0.90f)
        )
        fun map(x: Float, y: Float): Pair<Float, Float> =
            matrix[0] * x + matrix[12] to matrix[5] * y + matrix[13]

        assertEquals(0.25f, map(0f, 0f).first, 0.00001f)
        assertEquals(0.10f, map(0f, 0f).second, 0.00001f)
        assertEquals(0.75f, map(1f, 1f).first, 0.00001f)
        assertEquals(0.90f, map(1f, 1f).second, 0.00001f)
    }
}
