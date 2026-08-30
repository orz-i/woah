package com.danceanon.native.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.YoloLiteRtSegmenter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Measures production YOLO source-space geometry against deterministic affine
 * ground truth before TrackManager is involved. The derived face-ROI anchor and
 * crop-side error explain whether face localization failures originate in the
 * person detector geometry or later temporal tracking.
 */
@RunWith(AndroidJUnit4::class)
class FaceDynamicYoloGeometryBenchmarkInstrumentedTest {
    @Test
    fun benchmarkDynamicYoloGeometryAgainstGroundTruth() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        val sourceWidth = manifest.getInt("width")
        val sourceHeight = manifest.getInt("height")
        val frames = manifest.getJSONArray("frames")
        val mapper = ModelCoordinateMapper(sourceWidth, sourceHeight, MODEL_SIZE, 160)
        val yolo = YoloLiteRtSegmenter(context)
        val entries = JSONArray()
        val ious = mutableListOf<Double>()
        val anchorErrorRatios = mutableListOf<Double>()
        val sideRatios = mutableListOf<Double>()
        var withDetection = 0

        try {
            yolo.initialize()
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                val asset = frame.getString("asset")
                assertEquals(frame.getString("sha256"), sha256Asset(context, asset), asset)
                val gt = frame.getJSONArray("person_bbox").toFloatRect()
                val gtPlan = assertNotNull(FaceHeadRoiPlanner.plan(gt, sourceWidth, sourceHeight))
                val bitmap = decodeFixture(context, asset)
                try {
                    val modelBitmap = letterboxToModel(bitmap, mapper)
                    try {
                        val result = yolo.segmentGlReadbackRgbaSync(
                            rgbaBuffer = toBottomUpRgba(modelBitmap),
                            mapper = mapper,
                            timestampUs = frame.getInt("index") * FRAME_DURATION_US
                        )
                        val bestIndex = result.persons.indices.maxByOrNull { index ->
                            iou(result.persons[index].bbox, gt)
                        }
                        val best = bestIndex?.let { result.persons[it] }
                        val row = JSONObject()
                            .put("frame", frame.getInt("index"))
                            .put("detections", result.persons.size)
                        if (best != null) {
                            withDetection++
                            val bboxIou = iou(best.bbox, gt).toDouble()
                            val yoloPlan = assertNotNull(
                                FaceHeadRoiPlanner.plan(best.bbox, sourceWidth, sourceHeight)
                            )
                            val gtAnchorX = gtPlan.sourceRect.left + gtPlan.anchorX * gtPlan.sourceRect.width
                            val gtAnchorY = gtPlan.sourceRect.top + gtPlan.anchorY * gtPlan.sourceRect.height
                            val yoloAnchorX = yoloPlan.sourceRect.left + yoloPlan.anchorX * yoloPlan.sourceRect.width
                            val yoloAnchorY = yoloPlan.sourceRect.top + yoloPlan.anchorY * yoloPlan.sourceRect.height
                            val anchorErrorPx = hypot(
                                (yoloAnchorX - gtAnchorX).toDouble(),
                                (yoloAnchorY - gtAnchorY).toDouble()
                            )
                            val anchorErrorRatio = anchorErrorPx / gtPlan.sourceRect.width.coerceAtLeast(1f)
                            val sideRatio = yoloPlan.sourceRect.width / gtPlan.sourceRect.width.coerceAtLeast(1f)
                            ious += bboxIou
                            anchorErrorRatios += anchorErrorRatio
                            sideRatios += sideRatio.toDouble()
                            row
                                .put("best_detection_index", bestIndex)
                                .put("bbox_iou", bboxIou)
                                .put("anchor_error_px", anchorErrorPx)
                                .put("anchor_error_ratio", anchorErrorRatio)
                                .put("roi_side_ratio", sideRatio.toDouble())
                                .put("confidence", best.confidence.toDouble())
                            Log.i(
                                TAG,
                                "frame=${frame.getInt("index")} detections=${result.persons.size} best_index=$bestIndex iou=$bboxIou " +
                                    "anchor_error_ratio=$anchorErrorRatio side_ratio=$sideRatio conf=${best.confidence} " +
                                    "centers=${result.persons.map { it.bbox.centerX }}"
                            )
                        }
                        entries.put(row)
                    } finally {
                        modelBitmap.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            yolo.close()
        }

        val report = JSONObject()
            .put("backend", "YoloLiteRtSegmenter-production")
            .put("frames", frames.length())
            .put("frames_with_detection", withDetection)
            .put("bbox_iou", statsJson(ious))
            .put("anchor_error_ratio", statsJson(anchorErrorRatios))
            .put("roi_side_ratio", statsJson(sideRatios))
            .put("entries", entries)
        val reportFile = File(context.getExternalFilesDir(null) ?: context.filesDir, REPORT_FILE)
        reportFile.writeText(report.toString(2))
        Log.i(
            TAG,
            "summary frames=${frames.length()} detected=$withDetection " +
                "iou_p50=${percentile(ious, 0.50)} iou_p10=${percentile(ious, 0.10)} " +
                "anchor_error_p50=${percentile(anchorErrorRatios, 0.50)} " +
                "anchor_error_p95=${percentile(anchorErrorRatios, 0.95)} " +
                "side_ratio_p50=${percentile(sideRatios, 0.50)}"
        )
        Log.i(TAG, "FACE_DYNAMIC_YOLO_GEOMETRY_REPORT_PATH=${reportFile.absolutePath}")
        assertEquals(frames.length(), entries.length(), "YOLO geometry benchmark did not process every frame")
    }

    private fun letterboxToModel(source: Bitmap, mapper: ModelCoordinateMapper): Bitmap {
        val output = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            source,
            null,
            RectF(
                mapper.padLeft,
                mapper.padTop,
                mapper.padLeft + mapper.scaledW,
                mapper.padTop + mapper.scaledH
            ),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    private fun toBottomUpRgba(source: Bitmap): ByteBuffer {
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        source.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        return ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4).order(ByteOrder.nativeOrder()).apply {
            for (y in (MODEL_SIZE - 1) downTo 0) {
                val row = y * MODEL_SIZE
                for (x in 0 until MODEL_SIZE) {
                    val argb = pixels[row + x]
                    put(((argb shr 16) and 0xFF).toByte())
                    put(((argb shr 8) and 0xFF).toByte())
                    put((argb and 0xFF).toByte())
                    put(((argb ushr 24) and 0xFF).toByte())
                }
            }
            flip()
        }
    }

    private fun iou(a: FloatRect, b: FloatRect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0f) intersection / union else 0f
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

    private fun statsJson(values: List<Double>): JSONObject = JSONObject()
        .put("samples", values.size)
        .put("mean", if (values.isEmpty()) JSONObject.NULL else values.average())
        .put("p10", percentile(values, 0.10))
        .put("p50", percentile(values, 0.50))
        .put("p95", percentile(values, 0.95))

    private fun percentile(values: List<Double>, p: Double): Any {
        if (values.isEmpty()) return JSONObject.NULL
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        private const val TAG = "FACE_DYNAMIC_YOLO_GEOM"
        private const val MANIFEST_ASSET = "dynamic_manifest.json"
        private const val REPORT_FILE = "face_dynamic_yolo_geometry.json"
        private const val MODEL_SIZE = 640
        private const val FRAME_DURATION_US = 33_333L
    }
}
