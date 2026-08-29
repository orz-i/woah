package com.danceanon.native.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MediaPipeFaceLocatorInstrumentedTest {
    @Test
    fun productionLocatorFindsReviewedDistantFaceRoi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = context.assets.open(FACE_ROI_ASSET).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input))
        }
        val locator = assertNotNull(FaceLocatorProvider.createOrNull(context, enabled = true))
        try {
            val result = locator.detectRgbaTopDown(
                rgba = toTopDownRgba(bitmap),
                width = bitmap.width,
                height = bitmap.height
            )
            assertTrue(result.observations.isNotEmpty(), "Production face locator missed reviewed ROI")
            val selected = FaceRoiCandidateSelector.select(
                faces = result.observations,
                roiWidth = bitmap.width,
                roiHeight = bitmap.height,
                anchorX = 0.5f,
                anchorY = 0.5f
            )
            assertNotNull(selected, "Production face locator had no target-owned central candidate")
            assertTrue(result.inferenceMs > 0.0 && result.inferenceMs.isFinite())
        } finally {
            locator.close()
            bitmap.recycle()
        }
    }

    private fun toTopDownRgba(source: Bitmap): ByteBuffer {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteBuffer.allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder())
            .apply {
                for (argb in pixels) {
                    put(((argb shr 16) and 0xFF).toByte())
                    put(((argb shr 8) and 0xFF).toByte())
                    put((argb and 0xFF).toByte())
                    put(((argb ushr 24) and 0xFF).toByte())
                }
                flip()
            }
    }

    companion object {
        private const val FACE_ROI_ASSET = "face_roi_p3_upper.jpg"
    }
}
