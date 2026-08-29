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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Simulates the proposed source-OES head ROI path with pre-rendered 256x256
 * fixtures. This measures the detector after preserving source-resolution face
 * detail rather than after shrinking the whole 3000x6534 frame to 640x640.
 */
@RunWith(AndroidJUnit4::class)
class FaceRoiBenchmarkInstrumentedTest {
    @Test
    fun benchmarkFullResolutionHeadRois() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            EXPECTED_MODEL_SHA256,
            sha256Asset(context, MediaPipeFaceBenchmarkBackend.MODEL_ASSET),
            "Unexpected BlazeFace full-range benchmark model"
        )
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        val entries = manifest.getJSONArray("entries")
        val sourceWidth = manifest.getInt("source_width")
        val sourceHeight = manifest.getInt("source_height")
        val backend: FaceBenchmarkBackend = MediaPipeFaceBenchmarkBackend(context)
        val reportEntries = JSONArray()
        val latencies = mutableListOf<Double>()
        val detectedByMode = linkedMapOf<String, Int>()
        val selectedByMode = linkedMapOf<String, Int>()
        val totalByMode = linkedMapOf<String, Int>()
        var completed = 0

        try {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val assetName = entry.getString("asset")
                val personId = entry.getInt("person_id")
                val mode = entry.getString("mode")
                assertEquals(entry.getString("sha256"), sha256Asset(context, assetName), assetName)

                val plan = if (mode == "upper") {
                    val bboxJson = entry.getJSONArray("source_person_bbox")
                    val upperPlan = assertNotNull(
                        FaceHeadRoiPlanner.plan(
                            personBbox = FloatRect(
                                bboxJson.getDouble(0).toFloat(),
                                bboxJson.getDouble(1).toFloat(),
                                bboxJson.getDouble(2).toFloat(),
                                bboxJson.getDouble(3).toFloat()
                            ),
                            frameWidth = sourceWidth,
                            frameHeight = sourceHeight
                        )
                    )
                    val cropJson = entry.getJSONArray("crop_source")
                    assertNear(cropJson.getDouble(0).toFloat(), upperPlan.sourceRect.left, 1.5f)
                    assertNear(cropJson.getDouble(1).toFloat(), upperPlan.sourceRect.top, 1.5f)
                    assertNear(cropJson.getDouble(2).toFloat(), upperPlan.sourceRect.right, 1.5f)
                    assertNear(cropJson.getDouble(3).toFloat(), upperPlan.sourceRect.bottom, 1.5f)
                    upperPlan
                } else {
                    null
                }

                val bitmap = decodeFixture(context, assetName)
                try {
                    val result = backend.detect(
                        rgbaTopDown = toTopDownRgba(bitmap),
                        width = bitmap.width,
                        height = bitmap.height,
                        timestampMs = i.toLong()
                    )
                    completed++
                    latencies += result.inferenceMs
                    totalByMode[mode] = (totalByMode[mode] ?: 0) + 1
                    if (result.detections.isNotEmpty()) {
                        detectedByMode[mode] = (detectedByMode[mode] ?: 0) + 1
                    }

                    val faceObservations = result.detections.map { det ->
                        FaceObservation(
                            bbox = FloatRect(
                                det.bboxInModelPixels.left,
                                det.bboxInModelPixels.top,
                                det.bboxInModelPixels.right,
                                det.bboxInModelPixels.bottom
                            ),
                            confidence = det.confidence
                        )
                    }
                    val selected = FaceRoiCandidateSelector.select(
                        faces = faceObservations,
                        roiWidth = bitmap.width,
                        roiHeight = bitmap.height,
                        anchorX = plan?.anchorX ?: 0.5f,
                        anchorY = plan?.anchorY ?: 0.5f
                    )
                    if (selected != null) {
                        selectedByMode[mode] = (selectedByMode[mode] ?: 0) + 1
                    }

                    val detectionsJson = JSONArray()
                    result.detections.forEach { det ->
                        detectionsJson.put(
                            JSONObject()
                                .put("confidence", det.confidence.toDouble())
                                .put("left", det.bboxInModelPixels.left.toDouble())
                                .put("top", det.bboxInModelPixels.top.toDouble())
                                .put("right", det.bboxInModelPixels.right.toDouble())
                                .put("bottom", det.bboxInModelPixels.bottom.toDouble())
                        )
                    }

                    Log.i(
                        TAG,
                        "roi_done person=$personId mode=$mode ms=${result.inferenceMs} " +
                            "faces=${result.detections.size} confidences=${result.detections.map { it.confidence }} " +
                            "selected_index=${selected?.faceIndex} selected_conf=${selected?.face?.confidence} " +
                            "selected_anchor_distance=${selected?.anchorDistanceRatio} " +
                            "anchor=${plan?.let { listOf(it.anchorX, it.anchorY) } ?: listOf(0.5f, 0.5f)}"
                    )
                    reportEntries.put(
                        JSONObject()
                            .put("person_id", personId)
                            .put("mode", mode)
                            .put("asset", assetName)
                            .put("inference_ms", result.inferenceMs)
                            .put("selected_face_index", selected?.faceIndex ?: JSONObject.NULL)
                            .put("selected_confidence", selected?.face?.confidence?.toDouble() ?: JSONObject.NULL)
                            .put("selected_anchor_distance", selected?.anchorDistanceRatio?.toDouble() ?: JSONObject.NULL)
                            .put("anchor_x", plan?.anchorX?.toDouble() ?: 0.5)
                            .put("anchor_y", plan?.anchorY?.toDouble() ?: 0.5)
                            .put("detections", detectionsJson)
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            backend.close()
        }

        val modeSummary = JSONObject()
        totalByMode.forEach { (mode, total) ->
            modeSummary.put(
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
            .put("backend", backend.name)
            .put("source", manifest.getString("source"))
            .put("roi_size", manifest.getInt("roi_size"))
            .put("latency", statsJson(latencies))
            .put("modes", modeSummary)
            .put("entries", reportEntries)

        val reportFile = File(context.getExternalFilesDir(null) ?: context.filesDir, REPORT_FILE)
        reportFile.writeText(report.toString(2))
        Log.i(TAG, "FACE_ROI_BENCHMARK_REPORT=${report.toString()}")
        Log.i(TAG, "FACE_ROI_BENCHMARK_REPORT_PATH=${reportFile.absolutePath}")

        assertEquals(entries.length(), completed, "ROI benchmark did not complete all fixtures")
        assertEquals(
            totalByMode["upper"],
            selectedByMode["upper"],
            "Upper ROI must select a central target face for every reviewed person fixture"
        )
    }

    private fun assertNear(expected: Float, actual: Float, epsilon: Float) {
        assertTrue(abs(expected - actual) <= epsilon, "expected=$expected actual=$actual epsilon=$epsilon")
    }

    private fun decodeFixture(context: Context, assetName: String): Bitmap =
        context.assets.open(assetName).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode $assetName" }
        }

    private fun toTopDownRgba(source: Bitmap): ByteBuffer {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (argb in pixels) {
            rgba.put(((argb shr 16) and 0xFF).toByte())
            rgba.put(((argb shr 8) and 0xFF).toByte())
            rgba.put((argb and 0xFF).toByte())
            rgba.put(((argb ushr 24) and 0xFF).toByte())
        }
        rgba.flip()
        return rgba
    }

    private fun sha256Asset(context: Context, assetName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetName).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        private const val TAG = "FACE_ROI_BENCHMARK"
        private const val MANIFEST_ASSET = "face_roi_manifest.json"
        private const val REPORT_FILE = "face_roi_benchmark.json"
        private const val EXPECTED_MODEL_SHA256 =
            "3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b"
    }
}
