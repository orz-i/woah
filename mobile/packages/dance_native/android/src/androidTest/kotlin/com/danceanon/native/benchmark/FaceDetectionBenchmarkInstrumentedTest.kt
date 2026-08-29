package com.danceanon.native.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceObservation
import com.danceanon.native.face.FacePersonAssociator
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.tracking.TrackManager
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FaceDetectionBenchmarkInstrumentedTest {
    @Test
    fun benchmarkMediaPipeCpuSidecarOnSharedYoloFrames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelHash = sha256Asset(context, MediaPipeFaceBenchmarkBackend.MODEL_ASSET)
        assertEquals(EXPECTED_MODEL_SHA256, modelHash, "Unexpected BlazeFace full-range test model")

        val videoFile = copyAssetToCache(context, VIDEO_ASSET)
        val retriever = MediaMetadataRetriever()
        val backend: FaceBenchmarkBackend = MediaPipeFaceBenchmarkBackend(context)
        val yolo = YoloLiteRtSegmenter(context)
        val trackManager = TrackManager()
        val frameReports = JSONArray()
        val faceLatencies = mutableListOf<Double>()
        val rowFlipLatencies = mutableListOf<Double>()
        val yoloLatencies = mutableListOf<Double>()
        var totalFaceDetections = 0
        var totalMatchedFaces = 0
        var totalTrackedPersons = 0
        var processedFrames = 0
        var initializedTracker = false

        try {
            yolo.initialize()
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: error("Missing duration for $VIDEO_ASSET")

            val sampleTimestamps = buildSampleTimestamps(durationMs)
            var topDownWorkspace: ByteBuffer? = null
            var rowScratch: ByteArray? = null

            for (timestampMs in sampleTimestamps) {
                val frame = retriever.getFrameAtTime(
                    timestampMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue

                try {
                    val mapper = ModelCoordinateMapper(
                        srcWidth = frame.width,
                        srcHeight = frame.height,
                        modelInputSize = MODEL_SIZE,
                        protoSize = 160
                    )
                    val bottomUpRgba = createLetterboxedBottomUpRgba(frame, mapper)
                    if (topDownWorkspace == null) {
                        topDownWorkspace = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4)
                            .order(ByteOrder.nativeOrder())
                        rowScratch = ByteArray(MODEL_SIZE * 4)
                    }

                    val yoloStartNs = System.nanoTime()
                    val segmentation = yolo.segmentGlReadbackRgbaSync(
                        rgbaBuffer = bottomUpRgba.duplicate(),
                        mapper = mapper,
                        timestampUs = timestampMs * 1000L
                    )
                    val yoloMs = (System.nanoTime() - yoloStartNs) / 1_000_000.0
                    yoloLatencies += yoloMs

                    val tracked = if (!initializedTracker) {
                        initializedTracker = true
                        trackManager.initialize(segmentation.persons)
                    } else if (segmentation.persons.isNotEmpty()) {
                        trackManager.update(segmentation.persons, timestampMs * 1000L)
                    } else {
                        trackManager.predict(timestampMs * 1000L)
                    }

                    val faceInput = checkNotNull(topDownWorkspace)
                    val scratch = checkNotNull(rowScratch)
                    val flipStartNs = System.nanoTime()
                    flipBottomUpRgbaToTopDown(
                        source = bottomUpRgba,
                        destination = faceInput,
                        rowScratch = scratch,
                        width = MODEL_SIZE,
                        height = MODEL_SIZE
                    )
                    val flipMs = (System.nanoTime() - flipStartNs) / 1_000_000.0
                    rowFlipLatencies += flipMs

                    val faceResult = backend.detect(
                        rgbaTopDown = faceInput,
                        width = MODEL_SIZE,
                        height = MODEL_SIZE,
                        timestampMs = timestampMs
                    )
                    faceLatencies += faceResult.inferenceMs

                    val faces = faceResult.detections.map { detected ->
                        FaceObservation(
                            bbox = mapper.modelRectToSource(detected.bboxInModelPixels),
                            confidence = detected.confidence
                        )
                    }
                    val association = FacePersonAssociator.associate(faces, tracked)

                    processedFrames++
                    totalFaceDetections += faces.size
                    totalMatchedFaces += association.matches.size
                    totalTrackedPersons += tracked.size

                    frameReports.put(
                        JSONObject()
                            .put("timestamp_ms", timestampMs)
                            .put("frame_width", frame.width)
                            .put("frame_height", frame.height)
                            .put("yolo_persons", segmentation.persons.size)
                            .put("tracked_persons", tracked.size)
                            .put("faces", faces.size)
                            .put("matched_faces", association.matches.size)
                            .put("unmatched_faces", JSONArray(association.unmatchedFaceIndices))
                            .put("unmatched_track_ids", JSONArray(association.unmatchedTrackIds))
                            .put("association_scores", JSONArray(association.matches.map { it.score.toDouble() }))
                            .put("row_flip_ms", flipMs)
                            .put("face_inference_ms", faceResult.inferenceMs)
                            .put("yolo_wall_ms", yoloMs)
                    )
                } finally {
                    frame.recycle()
                }
            }

            val warmupDrop = minOf(WARMUP_SAMPLES, faceLatencies.size)
            val steadyFaceLatencies = faceLatencies.drop(warmupDrop)
            val steadyFlipLatencies = rowFlipLatencies.drop(warmupDrop)
            val report = JSONObject()
                .put("backend", backend.name)
                .put("model_asset", MediaPipeFaceBenchmarkBackend.MODEL_ASSET)
                .put("model_sha256", modelHash)
                .put("video_asset", VIDEO_ASSET)
                .put("processed_frames", processedFrames)
                .put("total_face_detections", totalFaceDetections)
                .put("total_matched_faces", totalMatchedFaces)
                .put("total_tracked_person_frames", totalTrackedPersons)
                .put(
                    "matched_face_rate",
                    if (totalFaceDetections > 0) totalMatchedFaces.toDouble() / totalFaceDetections else 0.0
                )
                .put(
                    "face_per_person_frame_proxy",
                    if (totalTrackedPersons > 0) totalMatchedFaces.toDouble() / totalTrackedPersons else 0.0
                )
                .put("face_latency", statsJson(steadyFaceLatencies))
                .put("row_flip_latency", statsJson(steadyFlipLatencies))
                .put("yolo_wall_latency", statsJson(yoloLatencies.drop(warmupDrop)))
                .put("frames", frameReports)

            val reportFile = File(context.getExternalFilesDir(null) ?: context.filesDir, REPORT_FILE)
            reportFile.writeText(report.toString(2))
            Log.i(TAG, "FACE_BENCHMARK_REPORT=${report.toString()}")
            Log.i(TAG, "FACE_BENCHMARK_REPORT_PATH=${reportFile.absolutePath}")

            // These are smoke gates only. Detection quality remains benchmark
            // output until the video set has manually annotated face ground truth.
            assertTrue(processedFrames >= 5, "Too few decoded benchmark frames: $processedFrames")
            assertTrue(totalFaceDetections > 0, "Full-range face detector produced no faces")
            assertTrue(totalTrackedPersons > 0, "YOLO produced no person observations")
        } finally {
            retriever.release()
            backend.close()
            yolo.close()
        }
    }

    private fun buildSampleTimestamps(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return listOf(0L)
        val out = mutableListOf<Long>()
        var timestamp = 0L
        while (timestamp < durationMs && out.size < MAX_SAMPLES) {
            out += timestamp
            timestamp += SAMPLE_INTERVAL_MS
        }
        if (out.lastOrNull() != durationMs - 1L && out.size < MAX_SAMPLES) {
            out += (durationMs - 1L).coerceAtLeast(0L)
        }
        return out
    }

    private fun createLetterboxedBottomUpRgba(
        source: Bitmap,
        mapper: ModelCoordinateMapper
    ): ByteBuffer {
        val letterboxed = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            source,
            null,
            RectF(
                mapper.padLeft,
                mapper.padTop,
                mapper.padLeft + mapper.scaledW,
                mapper.padTop + mapper.scaledH
            ),
            paint
        )

        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        letterboxed.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        letterboxed.recycle()

        val rgba = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4).order(ByteOrder.nativeOrder())
        for (y in MODEL_SIZE - 1 downTo 0) {
            val row = y * MODEL_SIZE
            for (x in 0 until MODEL_SIZE) {
                val argb = pixels[row + x]
                rgba.put(((argb shr 16) and 0xFF).toByte())
                rgba.put(((argb shr 8) and 0xFF).toByte())
                rgba.put((argb and 0xFF).toByte())
                rgba.put(((argb ushr 24) and 0xFF).toByte())
            }
        }
        rgba.flip()
        return rgba
    }

    private fun flipBottomUpRgbaToTopDown(
        source: ByteBuffer,
        destination: ByteBuffer,
        rowScratch: ByteArray,
        width: Int,
        height: Int
    ) {
        val rowBytes = width * 4
        require(rowScratch.size >= rowBytes)
        val src = source.duplicate()
        destination.clear()
        for (outputRow in 0 until height) {
            val sourceRow = height - 1 - outputRow
            src.position(sourceRow * rowBytes)
            src.get(rowScratch, 0, rowBytes)
            destination.put(rowScratch, 0, rowBytes)
        }
        destination.flip()
    }

    private fun copyAssetToCache(context: Context, assetName: String): File {
        val file = File(context.cacheDir, "face_benchmark_$assetName")
        context.assets.open(assetName).use { input ->
            file.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return file
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
        if (values.isEmpty()) {
            return JSONObject().put("samples", 0)
        }
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
        private const val TAG = "FACE_BENCHMARK"
        private const val VIDEO_ASSET = "01_sample.mp4"
        private const val REPORT_FILE = "face_detection_benchmark.json"
        private const val MODEL_SIZE = 640
        private const val SAMPLE_INTERVAL_MS = 250L
        private const val MAX_SAMPLES = 24
        private const val WARMUP_SAMPLES = 3
        private const val EXPECTED_MODEL_SHA256 =
            "3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b"
    }
}
