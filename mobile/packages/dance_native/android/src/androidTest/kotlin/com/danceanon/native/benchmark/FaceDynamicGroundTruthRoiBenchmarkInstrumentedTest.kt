package com.danceanon.native.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.face.FaceRoiCandidateSelector
import com.danceanon.native.face.MediaPipeFaceLocator
import com.danceanon.native.inference.FloatRect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Isolates ROI geometry from YOLO/TrackManager on the deterministic moving
 * real-person fixture. Ground-truth person boxes are affine transforms of the
 * reviewed source bbox, so any remaining miss is attributable to ROI/detector
 * behavior rather than runtime person-box drift.
 */
@RunWith(AndroidJUnit4::class)
class FaceDynamicGroundTruthRoiBenchmarkInstrumentedTest {
    @Test
    fun compareGroundTruthRoiScales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        val frameWidth = manifest.getInt("width")
        val frameHeight = manifest.getInt("height")
        val frames = manifest.getJSONArray("frames")
        val locator = MediaPipeFaceLocator(context)
        val summaries = JSONObject()
        val reportEntries = JSONArray()

        try {
            ROI_SCALES.forEach { scale ->
                var completed = 0
                var withObservation = 0
                var withSelectedTarget = 0
                var rejected = 0
                val latencies = mutableListOf<Double>()

                for (i in 0 until frames.length()) {
                    val frame = frames.getJSONObject(i)
                    val asset = frame.getString("asset")
                    assertEquals(frame.getString("sha256"), sha256Asset(context, asset), asset)
                    val bbox = frame.getJSONArray("person_bbox").toFloatRect()
                    val basePlan = assertNotNull(
                        FaceHeadRoiPlanner.plan(
                            personBbox = bbox,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight
                        )
                    )
                    val plan = scalePlan(basePlan, scale, frameWidth, frameHeight)
                    val bitmap = decodeFixture(context, asset)
                    try {
                        val roi = cropToRoi(bitmap, plan.sourceRect, FaceHeadRoiPlanner.OUTPUT_SIZE)
                        try {
                            val result = locator.detectRgbaTopDown(
                                rgba = toTopDownRgba(roi),
                                width = roi.width,
                                height = roi.height
                            )
                            completed++
                            latencies += result.inferenceMs
                            if (result.observations.isNotEmpty()) withObservation++
                            val selected = FaceRoiCandidateSelector.select(
                                faces = result.observations,
                                roiWidth = roi.width,
                                roiHeight = roi.height,
                                anchorX = plan.anchorX,
                                anchorY = plan.anchorY
                            )
                            if (selected != null) {
                                withSelectedTarget++
                            } else if (result.observations.isNotEmpty()) {
                                rejected++
                            }

                            reportEntries.put(
                                JSONObject()
                                    .put("frame", frame.getInt("index"))
                                    .put("scale", scale.toDouble())
                                    .put("observations", result.observations.size)
                                    .put("selected", selected != null)
                                    .put("selected_anchor_distance", selected?.anchorDistanceRatio?.toDouble() ?: JSONObject.NULL)
                                    .put("inference_ms", result.inferenceMs)
                            )
                        } finally {
                            roi.recycle()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }

                val key = scaleKey(scale)
                summaries.put(
                    key,
                    JSONObject()
                        .put("scale", scale.toDouble())
                        .put("frames", completed)
                        .put("with_observation", withObservation)
                        .put("with_selected_target", withSelectedTarget)
                        .put("rejected_nonempty", rejected)
                        .put("selected_target_coverage", withSelectedTarget.toDouble() / completed.coerceAtLeast(1))
                        .put("latency", statsJson(latencies))
                )
                Log.i(
                    TAG,
                    "scale_summary key=$key scale=$scale frames=$completed observations=$withObservation " +
                        "selected=$withSelectedTarget rejected=$rejected " +
                        "coverage=${withSelectedTarget.toDouble() / completed.coerceAtLeast(1)} " +
                        "mean_ms=${latencies.average()}"
                )
                assertEquals(frames.length(), completed, "ROI scale $scale did not process all dynamic frames")
            }
        } finally {
            locator.close()
        }

        val report = JSONObject()
            .put("backend", "MediaPipeFaceLocator-production")
            .put("min_detection_confidence", MediaPipeFaceLocator.DEFAULT_MIN_DETECTION_CONFIDENCE.toDouble())
            .put("frames", frames.length())
            .put("scales", summaries)
            .put("entries", reportEntries)
        val reportFile = File(context.getExternalFilesDir(null) ?: context.filesDir, REPORT_FILE)
        reportFile.writeText(report.toString(2))
        Log.i(TAG, "FACE_DYNAMIC_GT_ROI_REPORT=${report.toString()}")
        Log.i(TAG, "FACE_DYNAMIC_GT_ROI_REPORT_PATH=${reportFile.absolutePath}")
    }

    private fun scalePlan(
        base: FaceHeadRoiPlan,
        scale: Float,
        frameWidth: Int,
        frameHeight: Int
    ): FaceHeadRoiPlan {
        val baseSide = base.sourceRect.width
        val anchorSourceX = base.sourceRect.left + base.anchorX * baseSide
        val anchorSourceY = base.sourceRect.top + base.anchorY * baseSide
        val side = (baseSide * scale)
            .coerceAtMost(minOf(frameWidth, frameHeight).toFloat())
            .coerceAtLeast(2f)
        val maxLeft = (frameWidth - side).coerceAtLeast(0f)
        val maxTop = (frameHeight - side).coerceAtLeast(0f)
        val left = (anchorSourceX - side * 0.5f).coerceIn(0f, maxLeft)
        val top = (anchorSourceY - side * 0.5f).coerceIn(0f, maxTop)
        return FaceHeadRoiPlan(
            sourceRect = FloatRect(left, top, left + side, top + side),
            anchorX = ((anchorSourceX - left) / side).coerceIn(0f, 1f),
            anchorY = ((anchorSourceY - top) / side).coerceIn(0f, 1f),
            outputSize = FaceHeadRoiPlanner.OUTPUT_SIZE
        )
    }

    private fun cropToRoi(source: Bitmap, sourceRect: FloatRect, outputSize: Int): Bitmap {
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val src = Rect(
            sourceRect.left.roundToInt().coerceIn(0, source.width - 1),
            sourceRect.top.roundToInt().coerceIn(0, source.height - 1),
            sourceRect.right.roundToInt().coerceIn(1, source.width),
            sourceRect.bottom.roundToInt().coerceIn(1, source.height)
        )
        require(src.width() > 1 && src.height() > 1) { "Invalid dynamic ROI crop $src" }
        Canvas(output).drawBitmap(
            source,
            src,
            RectF(0f, 0f, outputSize.toFloat(), outputSize.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    private fun JSONArray.toFloatRect(): FloatRect = FloatRect(
        getDouble(0).toFloat(),
        getDouble(1).toFloat(),
        getDouble(2).toFloat(),
        getDouble(3).toFloat()
    )

    private fun decodeFixture(context: Context, asset: String): Bitmap =
        context.assets.open(asset).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode $asset" }
        }

    private fun toTopDownRgba(source: Bitmap): ByteBuffer {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).apply {
            pixels.forEach { argb ->
                put(((argb shr 16) and 0xFF).toByte())
                put(((argb shr 8) and 0xFF).toByte())
                put((argb and 0xFF).toByte())
                put(((argb ushr 24) and 0xFF).toByte())
            }
            flip()
        }
    }

    private fun sha256Asset(context: Context, asset: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).buffered().use { input ->
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

    private fun scaleKey(scale: Float): String = "x" + (scale * 100).roundToInt()

    companion object {
        private const val TAG = "FACE_DYNAMIC_GT_ROI"
        private const val MANIFEST_ASSET = "dynamic_manifest.json"
        private const val REPORT_FILE = "face_dynamic_gt_roi_benchmark.json"
        private val ROI_SCALES = listOf(0.65f, 0.80f, 1.00f, 1.20f)
    }
}
