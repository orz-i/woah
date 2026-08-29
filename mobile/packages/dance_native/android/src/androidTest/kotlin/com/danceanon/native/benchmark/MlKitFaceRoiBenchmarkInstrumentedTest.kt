package com.danceanon.native.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.face.FaceObservation
import com.danceanon.native.face.FaceRoiCandidateSelector
import com.danceanon.native.inference.FloatRect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bundled ML Kit A/B against the exact same source-resolution ROI assets used by
 * the MediaPipe benchmark. The dependency exists in androidTest only.
 */
@RunWith(AndroidJUnit4::class)
class MlKitFaceRoiBenchmarkInstrumentedTest {
    @Test
    fun benchmarkBundledFastDetectorOnSourceRois() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBenchmark(
            context = context,
            performanceMode = FaceDetectorOptions.PERFORMANCE_MODE_FAST,
            backendName = "mlkit_face_detection_16_1_7_bundled_fast",
            reportFileName = FAST_REPORT_FILE
        )
    }

    @Test
    fun benchmarkBundledAccurateDetectorOnSourceRois() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBenchmark(
            context = context,
            performanceMode = FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE,
            backendName = "mlkit_face_detection_16_1_7_bundled_accurate",
            reportFileName = ACCURATE_REPORT_FILE
        )
    }

    private fun runBenchmark(
        context: Context,
        performanceMode: Int,
        backendName: String,
        reportFileName: String
    ) {
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        val entries = manifest.getJSONArray("entries")
        val sourceWidth = manifest.getInt("source_width")
        val sourceHeight = manifest.getInt("source_height")

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(performanceMode)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
        val detector = FaceDetection.getClient(options)

        val warmup = decodeFixture(context, WARMUP_ASSET)
        try {
            repeat(WARMUP_COUNT) {
                val image = InputImage.fromBitmap(warmup, 0)
                Tasks.await(detector.process(image), TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } finally {
            warmup.recycle()
        }

        val latencies = mutableListOf<Double>()
        val totalByMode = linkedMapOf<String, Int>()
        val detectedByMode = linkedMapOf<String, Int>()
        val selectedByMode = linkedMapOf<String, Int>()
        val reportEntries = JSONArray()
        var completed = 0

        try {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val assetName = entry.getString("asset")
                val personId = entry.getInt("person_id")
                val mode = entry.getString("mode")
                val plan = if (mode == "upper") {
                    val bbox = entry.getJSONArray("source_person_bbox")
                    val upperPlan = assertNotNull(
                        FaceHeadRoiPlanner.plan(
                            personBbox = FloatRect(
                                bbox.getDouble(0).toFloat(),
                                bbox.getDouble(1).toFloat(),
                                bbox.getDouble(2).toFloat(),
                                bbox.getDouble(3).toFloat()
                            ),
                            frameWidth = sourceWidth,
                            frameHeight = sourceHeight
                        )
                    )
                    val crop = entry.getJSONArray("crop_source")
                    assertNear(crop.getDouble(0).toFloat(), upperPlan.sourceRect.left, 1.5f)
                    assertNear(crop.getDouble(1).toFloat(), upperPlan.sourceRect.top, 1.5f)
                    assertNear(crop.getDouble(2).toFloat(), upperPlan.sourceRect.right, 1.5f)
                    assertNear(crop.getDouble(3).toFloat(), upperPlan.sourceRect.bottom, 1.5f)
                    upperPlan
                } else {
                    null
                }

                val bitmap = decodeFixture(context, assetName)
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val startNs = System.nanoTime()
                    val faces = Tasks.await(
                        detector.process(image),
                        TASK_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                    latencies += elapsedMs
                    completed++
                    totalByMode[mode] = (totalByMode[mode] ?: 0) + 1
                    if (faces.isNotEmpty()) {
                        detectedByMode[mode] = (detectedByMode[mode] ?: 0) + 1
                    }

                    val observations = faces.map { face ->
                        val rect = face.boundingBox
                        FaceObservation(
                            bbox = FloatRect(
                                rect.left.toFloat(),
                                rect.top.toFloat(),
                                rect.right.toFloat(),
                                rect.bottom.toFloat()
                            ),
                            // ML Kit Face does not expose a detector score. Identity
                            // selection is anchor-distance first, so use neutral 1.0.
                            confidence = 1.0f
                        )
                    }
                    val selected = FaceRoiCandidateSelector.select(
                        faces = observations,
                        roiWidth = bitmap.width,
                        roiHeight = bitmap.height,
                        anchorX = plan?.anchorX ?: 0.5f,
                        anchorY = plan?.anchorY ?: 0.5f
                    )
                    if (selected != null) {
                        selectedByMode[mode] = (selectedByMode[mode] ?: 0) + 1
                    }

                    val detections = JSONArray()
                    faces.forEach { face ->
                        val rect = face.boundingBox
                        detections.put(
                            JSONObject()
                                .put("left", rect.left)
                                .put("top", rect.top)
                                .put("right", rect.right)
                                .put("bottom", rect.bottom)
                                .put("tracking_id", face.trackingId ?: JSONObject.NULL)
                        )
                    }
                    Log.i(
                        TAG,
                        "roi_done person=$personId mode=$mode ms=$elapsedMs faces=${faces.size} " +
                            "selected_index=${selected?.faceIndex} " +
                            "selected_anchor_distance=${selected?.anchorDistanceRatio}"
                    )
                    reportEntries.put(
                        JSONObject()
                            .put("person_id", personId)
                            .put("mode", mode)
                            .put("asset", assetName)
                            .put("inference_ms", elapsedMs)
                            .put("selected_face_index", selected?.faceIndex ?: JSONObject.NULL)
                            .put("selected_anchor_distance", selected?.anchorDistanceRatio?.toDouble() ?: JSONObject.NULL)
                            .put("detections", detections)
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            detector.close()
        }

        val modes = JSONObject()
        totalByMode.forEach { (mode, total) ->
            modes.put(
                mode,
                JSONObject()
                    .put("roi_count", total)
                    .put("roi_with_face", detectedByMode[mode] ?: 0)
                    .put("roi_with_selected_target", selectedByMode[mode] ?: 0)
                    .put(
                        "coverage_proxy",
                        if (total > 0) (detectedByMode[mode] ?: 0).toDouble() / total else 0.0
                    )
                    .put(
                        "selected_target_coverage_proxy",
                        if (total > 0) (selectedByMode[mode] ?: 0).toDouble() / total else 0.0
                    )
            )
        }
        val report = JSONObject()
            .put("backend", backendName)
            .put("min_face_size", 0.05)
            .put("source", manifest.getString("source"))
            .put("roi_size", manifest.getInt("roi_size"))
            .put("latency", statsJson(latencies))
            .put("modes", modes)
            .put("entries", reportEntries)
        val reportFile = File(context.getExternalFilesDir(null) ?: context.filesDir, reportFileName)
        reportFile.writeText(report.toString(2))
        Log.i(TAG, "MLKIT_FACE_ROI_BENCHMARK_REPORT=${report.toString()}")
        Log.i(TAG, "MLKIT_FACE_ROI_BENCHMARK_REPORT_PATH=${reportFile.absolutePath}")

        assertEquals(entries.length(), completed)
    }

    private fun decodeFixture(context: Context, assetName: String): Bitmap =
        context.assets.open(assetName).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode $assetName" }
        }

    private fun assertNear(expected: Float, actual: Float, epsilon: Float) {
        assertTrue(abs(expected - actual) <= epsilon, "expected=$expected actual=$actual epsilon=$epsilon")
    }

    private fun statsJson(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("samples", 0)
        val sorted = values.sorted()
        fun percentile(p: Double): Double {
            val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
        return JSONObject()
            .put("samples", sorted.size)
            .put("mean_ms", sorted.average())
            .put("p50_ms", percentile(0.50))
            .put("p95_ms", percentile(0.95))
            .put("min_ms", sorted.first())
            .put("max_ms", sorted.last())
    }

    companion object {
        private const val TAG = "MLKIT_FACE_ROI_BENCHMARK"
        private const val MANIFEST_ASSET = "face_roi_manifest.json"
        private const val WARMUP_ASSET = "face_roi_p3_upper.jpg"
        private const val WARMUP_COUNT = 4
        private const val TASK_TIMEOUT_SECONDS = 5L
        private const val FAST_REPORT_FILE = "mlkit_face_roi_benchmark_fast.json"
        private const val ACCURATE_REPORT_FILE = "mlkit_face_roi_benchmark_accurate.json"
    }
}
