package art.gaoge.dance.engine.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 3: explicitly opted-in, *test-only* MagicTouch API + runtime smoke.
 *
 * Never called by production, never opens media, never creates a visual
 * artifact, never returns an unmasking mask to the Woah renderer. The genuine
 * public MagicTouch .task must be SHA-verified and deployed separately into
 * the target debug package's private files directory, not bundled in any APK.
 *
 * Ordinary AndroidTest runs skip this costly optional experiment; enable by
 * instrumentation argument woahMagicTouchStage3Enabled=1.
 */
@RunWith(AndroidJUnit4::class)
class MagicTouchSyntheticSmokeInstrumentedTest {
    @Test
    fun testPinnedMagicTouchOnSyntheticShapesOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val enabled = instrumentation.arguments.getString(ENABLE_ARG) == "1"
        assumeTrue("Stage 3 is opt-in: no additional model in regular Android tests", enabled)
        val runToken = instrumentation.arguments.getString(RUN_TOKEN_ARG).orEmpty()
        assertTrue(runToken.matches(Regex("[0-9a-f]{16}")),
            "An explicit 16-hex Stage 3 run token is required for log isolation")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val asset = File(context.filesDir, TEST_MODEL_FILE)
        assertTrue(
            asset.isFile && asset.length() == MODEL_BYTES,
            "The Stage 3 pinned model is missing or has the wrong byte count"
        )
        assertEquals(MODEL_SHA256, fileSha256(asset), "Stage 3 model SHA mismatch")

        val pssBeforeKb = endPssKb()
        val thermalBefore = thermalStatus(context)

        // Zero-copy read-only mapping of the optional app-private .task file.
        // The mapping stays strongly reachable until the segmenter is closed.
        val mapped = FileInputStream(asset).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }

        val creationStarted = System.nanoTime()
        val options = InteractiveSegmenterOptions.builder().setBaseOptions(
            BaseOptions.builder()
                .setModelAssetBuffer(mapped)
                .setDelegate(Delegate.CPU)
                .build()
        ).build()
        val segmenter = InteractiveSegmenter.createFromOptions(context, options)
        val initializationMs = elapsedMs(creationStarted)

        val bitmap = makeSyntheticObjectImage(WIDTH, HEIGHT)
        val mpImage = BitmapImageBuilder(bitmap).build()
        val foregroundStrokes = listOf(
            Stroke.builder()
                .setBrushMode(Stroke.BrushMode.POSITIVE)
                .setPoints(
                    listOf(
                        NormalizedKeypoint.create(0.28f, 0.43f),
                        NormalizedKeypoint.create(0.31f, 0.45f)
                    )
                )
                .setCompleted(true)
                .build()
        )
        val alternativeStrokes = listOf(
            Stroke.builder()
                .setBrushMode(Stroke.BrushMode.POSITIVE)
                .setPoints(
                    listOf(
                        NormalizedKeypoint.create(0.75f, 0.39f),
                        NormalizedKeypoint.create(0.77f, 0.40f)
                    )
                )
                .setCompleted(true)
                .build(),
            Stroke.builder()
                .setBrushMode(Stroke.BrushMode.NEGATIVE)
                .setPoints(
                    listOf(
                        NormalizedKeypoint.create(0.28f, 0.43f),
                        NormalizedKeypoint.create(0.31f, 0.45f)
                    )
                )
                .setCompleted(true)
                .build()
        )

        val setImageTimings = mutableListOf<Double>()
        val segmentTimings = mutableListOf<Double>()
        var observedPixels = 0L
        var maxConfidence = 0f
        var minConfidence = 1f
        var nonzeroPixels = 0
        try {
            // One warmup plus five measured calls. Each sample explicitly
            // sets a *fresh* synthetic image and runs exactly one stroke.
            // A different stroke after the main series probes reuse of
            // already-set embeddings without re-reading private media.
            repeat(WARMUP + SAMPLES) { iteration ->
                val setStart = System.nanoTime()
                segmenter.setImage(mpImage)
                val setMs = elapsedMs(setStart)

                val runStart = System.nanoTime()
                val output = segmenter.segment(foregroundStrokes)
                val segmentMs = elapsedMs(runStart)

                try {
                    val stats = checkConfidenceImage(output, WIDTH, HEIGHT)
                    observedPixels += stats.pixelCount.toLong()
                    nonzeroPixels = stats.visiblePixels
                    minConfidence = minOf(minConfidence, stats.minValue)
                    maxConfidence = maxOf(maxConfidence, stats.maxValue)
                } finally {
                    output.close()
                }
                if (iteration >= WARMUP) {
                    setImageTimings.add(setMs)
                    segmentTimings.add(segmentMs)
                }
            }

            val alternative = segmenter.segment(alternativeStrokes)
            try {
                checkConfidenceImage(alternative, WIDTH, HEIGHT)
            } finally {
                alternative.close()
            }

            // Rebind a second *synthetic* 16:9 landscape image to falsify
            // hardcoded-square output coordinates or stale cached images.
            // No user pixels and no model outputs ever enter a file.
            val wideBitmap = makeSyntheticObjectImage(WIDE_WIDTH, WIDE_HEIGHT)
            val wideImage = BitmapImageBuilder(wideBitmap).build()
            var wideSetMs = 0.0
            var wideSegmentMs = 0.0
            try {
                val setStart = System.nanoTime()
                segmenter.setImage(wideImage)
                wideSetMs = elapsedMs(setStart)
                val runStart = System.nanoTime()
                val wideMask = segmenter.segment(foregroundStrokes)
                wideSegmentMs = elapsedMs(runStart)
                try {
                    checkConfidenceImage(wideMask, WIDE_WIDTH, WIDE_HEIGHT)
                } finally {
                    wideMask.close()
                }
            } finally {
                wideImage.close()
                wideBitmap.recycle()
            }

            assertEquals(SAMPLES, setImageTimings.size)
            assertEquals(SAMPLES, segmentTimings.size)
            assertTrue(setImageTimings.all { it >= 0.0 && it.isFinite() })
            assertTrue(segmentTimings.all { it >= 0.0 && it.isFinite() })
            assertTrue(observedPixels == (WARMUP + SAMPLES).toLong() * WIDTH * HEIGHT)

            // Counts + timings ONLY. The experiment does not assert
            // segmentation of a real person or privacy acceptance.
            Log.i(TAG, JSONObject().apply {
                put("schema", 1)
                put("scope", "STAGE3_PUBLIC_MODEL_SYNTHETIC_ONLY")
                put("run_token", runToken)
                put("git_commit", art.gaoge.dance.engine.BuildConfig.GIT_COMMIT_SHA)
                put("model_sha256", MODEL_SHA256)
                put("device_model", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
                put("source_video_used", false)
                put("person_identity_proven", false)
                put("protected_person_occupancy_verified", false)
                put("pixel_carve_authorization", "NEVER")
                put("privacy_visual_acceptance", "NOT_PROVEN")
                put("delegate", "CPU")
                put("model_sha256_verified", true)
                put("image_width", WIDTH)
                put("image_height", HEIGHT)
                put("warmups", WARMUP)
                put("measured_runs", SAMPLES)
                put("init_wall_ms", initializationMs)
                put("set_image_median_ms", median(setImageTimings))
                put("set_image_p95_ms", nearestP95(setImageTimings))
                put("segment_median_ms", median(segmentTimings))
                put("segment_p95_ms", nearestP95(segmentTimings))
                put("last_positive_mask_pixels_ge_0_5", nonzeroPixels)
                put("last_confidence_min", minConfidence.toDouble())
                put("last_confidence_max", maxConfidence.toDouble())
                put("wide_source_width", WIDE_WIDTH)
                put("wide_source_height", WIDE_HEIGHT)
                put("wide_source_output_geometry_verified", true)
                put("wide_set_image_ms", wideSetMs)
                put("wide_segment_ms", wideSegmentMs)
                put("pss_start_kb", pssBeforeKb)
                put("pss_end_kb", endPssKb())
                put("thermal_start", thermalBefore)
                put("thermal_end", thermalStatus(context))
                put("peak_ram_measured", false)
                put("gpu_latency_measured", false)
                put("final_video_verified", false)
            }.toString())
        } finally {
            mpImage.close()
            bitmap.recycle()
            segmenter.close()
        }
    }

    private data class ConfidenceStats(
        val pixelCount: Int,
        val visiblePixels: Int,
        val minValue: Float,
        val maxValue: Float
    )

    private fun checkConfidenceImage(
        image: com.google.mediapipe.framework.image.MPImage,
        expectedWidth: Int,
        expectedHeight: Int
    ): ConfidenceStats {
        assertEquals(expectedWidth, image.width, "Source-vs-result geometry must match")
        assertEquals(expectedHeight, image.height, "Source-vs-result geometry must match")
        val floatValues = ByteBufferExtractor.extract(image).asFloatBuffer().apply { rewind() }
        val total = expectedWidth * expectedHeight
        assertTrue(floatValues.remaining() >= total, "Missing float confidence samples")
        var visible = 0
        var minimum = 1f
        var maximum = 0f
        for (index in 0 until total) {
            val value = floatValues.get(index)
            assertTrue(value.isFinite() && value >= 0f && value <= 1f,
                "Unexpected confidence value at synthetic sample")
            if (value >= 0.5f) visible++
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }
        return ConfidenceStats(total, visible, minimum, maximum)
    }

    private fun makeSyntheticObjectImage(width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(36, 39, 44))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(50, 150, 215)
        canvas.drawRect(
            width * 0.12f, height * 0.18f,
            width * 0.49f, height * 0.76f, paint
        )
        paint.color = Color.rgb(230, 136, 77)
        canvas.drawCircle(width * 0.76f, height * 0.39f, height * 0.18f, paint)
        paint.color = Color.rgb(240, 245, 252)
        canvas.drawRect(
            width * 0.56f, height * 0.80f,
            width * 0.92f, height * 0.91f, paint
        )
        return result
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val block = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun endPssKb(): Int = Debug.MemoryInfo().let { snapshot ->
        Debug.getMemoryInfo(snapshot)
        snapshot.totalPss
    }

    private fun thermalStatus(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
        } else -1

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun nearestP95(values: List<Double>): Double =
        values.sorted()[((values.size - 1) * 95 + 99) / 100]

    private companion object {
        const val TAG = "WoahMagicTouchStage3"
        const val ENABLE_ARG = "woahMagicTouchStage3Enabled"
        const val RUN_TOKEN_ARG = "woahMagicTouchStage3RunToken"
        const val TEST_MODEL_FILE = "woah_magic_touch_stage3.task"
        const val MODEL_BYTES = 30_525_312L
        const val MODEL_SHA256 =
            "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03"
        const val WIDTH = 384
        const val HEIGHT = 384
        const val WIDE_WIDTH = 512
        const val WIDE_HEIGHT = 288
        const val WARMUP = 1
        const val SAMPLES = 5
    }
}
