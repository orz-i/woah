package com.danceanon.native.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun parallelLocatorMatchesSequentialResultsAndReducesTwoCallWallTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = context.assets.open(FACE_ROI_ASSET).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input))
        }
        val rgba = toTopDownRgba(bitmap)
        val sequential = MediaPipeFaceLocator(context)
        val parallel = ParallelMediaPipeFaceLocator(context, workerCount = 2)
        try {
            // Warm both execution paths before comparing steady-state wall time.
            repeat(2) {
                sequential.detectRgbaTopDown(rgba, bitmap.width, bitmap.height)
                parallel.detectBatchRgbaTopDown(
                    listOf(
                        FaceLocatorRequest(rgba, bitmap.width, bitmap.height),
                        FaceLocatorRequest(rgba, bitmap.width, bitmap.height)
                    )
                )
            }

            val sequentialWallMs = mutableListOf<Double>()
            val parallelWallMs = mutableListOf<Double>()
            repeat(5) {
                var startNs = System.nanoTime()
                val sequentialResults = listOf(
                    sequential.detectRgbaTopDown(rgba, bitmap.width, bitmap.height),
                    sequential.detectRgbaTopDown(rgba, bitmap.width, bitmap.height)
                )
                sequentialWallMs += (System.nanoTime() - startNs) / 1_000_000.0

                startNs = System.nanoTime()
                val parallelResults = parallel.detectBatchRgbaTopDown(
                    listOf(
                        FaceLocatorRequest(rgba, bitmap.width, bitmap.height),
                        FaceLocatorRequest(rgba, bitmap.width, bitmap.height)
                    )
                )
                parallelWallMs += (System.nanoTime() - startNs) / 1_000_000.0

                assertEquals(
                    sequentialResults.map(::observationSignature),
                    parallelResults.map(::observationSignature),
                    "parallel detector workers must preserve MediaPipe observations"
                )
            }

            val sequentialMedian = sequentialWallMs.sorted()[sequentialWallMs.size / 2]
            val parallelMedian = parallelWallMs.sorted()[parallelWallMs.size / 2]
            android.util.Log.i(
                "MediaPipeFaceLocatorTest",
                "two_call_sequential_median_ms=$sequentialMedian " +
                    "two_call_parallel_median_ms=$parallelMedian"
            )
            assertTrue(
                parallelMedian < sequentialMedian * 0.90,
                "parallel two-call path must reduce median wall time by at least 10%: " +
                    "sequential=$sequentialMedian parallel=$parallelMedian"
            )
        } finally {
            parallel.close()
            sequential.close()
            bitmap.recycle()
        }
    }

    private fun observationSignature(result: FaceLocatorResult): List<List<Int>> =
        result.observations.map { observation ->
            buildList {
                add((observation.bbox.left * 1024f).roundToInt())
                add((observation.bbox.top * 1024f).roundToInt())
                add((observation.bbox.right * 1024f).roundToInt())
                add((observation.bbox.bottom * 1024f).roundToInt())
                add((observation.confidence * 1_000_000f).roundToInt())
                observation.keypoints.forEach { point ->
                    add((point.x * 1024f).roundToInt())
                    add((point.y * 1024f).roundToInt())
                }
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
