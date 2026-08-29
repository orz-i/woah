package com.danceanon.native.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.YoloLiteRtSegmenter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real-device controls used to distinguish a model/runtime defect from an
 * interaction between MediaPipe Tasks and the existing LiteRT GPU runtime.
 */
@RunWith(AndroidJUnit4::class)
class FaceRuntimeIsolationInstrumentedTest {
    @Test
    fun yoloOnlyCompletesAllFixtureFrames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frames = loadFrames(context)
        val mapper = ModelCoordinateMapper(MODEL_SIZE, MODEL_SIZE, MODEL_SIZE, 160)
        val yolo = YoloLiteRtSegmenter(context)
        var completed = 0

        try {
            yolo.initialize()
            Log.i(TAG, "yolo_only_init_done accelerator=${yolo.effectiveAccelerator}")
            for (i in 0 until frames.length()) {
                val frameInfo = frames.getJSONObject(i)
                val bitmap = decodeFixture(context, frameInfo.getString("asset"))
                try {
                    val rgba = toBottomUpRgba(bitmap)
                    val startNs = System.nanoTime()
                    val result = yolo.segmentGlReadbackRgbaSync(
                        rgbaBuffer = rgba,
                        mapper = mapper,
                        timestampUs = frameInfo.getLong("timestamp_ms") * 1000L
                    )
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                    completed++
                    Log.i(TAG, "yolo_only_done index=$i ms=$elapsedMs persons=${result.persons.size}")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            yolo.close()
        }

        assertEquals(frames.length(), completed, "YOLO-only control did not complete all fixture frames")
    }

    @Test
    fun mediaPipeImageOnlyCompletesAllFixtureFrames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frames = loadFrames(context)
        val backend: FaceBenchmarkBackend = MediaPipeFaceBenchmarkBackend(context)
        var completed = 0

        try {
            Log.i(TAG, "mediapipe_only_init_done backend=${backend.name}")
            for (i in 0 until frames.length()) {
                val frameInfo = frames.getJSONObject(i)
                val bitmap = decodeFixture(context, frameInfo.getString("asset"))
                try {
                    val rgba = toTopDownRgba(bitmap)
                    val result = backend.detect(
                        rgbaTopDown = rgba,
                        width = MODEL_SIZE,
                        height = MODEL_SIZE,
                        timestampMs = frameInfo.getLong("timestamp_ms")
                    )
                    completed++
                    Log.i(TAG, "mediapipe_only_done index=$i ms=${result.inferenceMs} faces=${result.detections.size}")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            backend.close()
        }

        assertEquals(frames.length(), completed, "MediaPipe IMAGE-only control did not complete all fixture frames")
    }

    private fun loadFrames(context: Context) =
        context.assets.open(FIXTURE_MANIFEST_ASSET).bufferedReader().use {
            val frames = JSONObject(it.readText()).getJSONArray("frames")
            for (i in 0 until frames.length()) {
                val fixture = frames.getJSONObject(i)
                val assetName = fixture.getString("asset")
                assertEquals(
                    fixture.getString("sha256"),
                    sha256Asset(context, assetName),
                    "Unexpected full-frame control fixture: $assetName"
                )
            }
            frames
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

    private fun decodeFixture(context: Context, assetName: String): Bitmap =
        context.assets.open(assetName).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode fixture $assetName" }
        }.also {
            require(it.width == MODEL_SIZE && it.height == MODEL_SIZE) {
                "Unexpected fixture geometry ${it.width}x${it.height} for $assetName"
            }
        }

    private fun toTopDownRgba(source: Bitmap): ByteBuffer =
        pixelsToRgba(source, rows = 0 until MODEL_SIZE)

    private fun toBottomUpRgba(source: Bitmap): ByteBuffer =
        pixelsToRgba(source, rows = (MODEL_SIZE - 1) downTo 0)

    private fun pixelsToRgba(source: Bitmap, rows: IntProgression): ByteBuffer {
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        source.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        val rgba = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4).order(ByteOrder.nativeOrder())
        for (y in rows) {
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

    companion object {
        private const val TAG = "FACE_RUNTIME_ISOLATION"
        private const val FIXTURE_MANIFEST_ASSET = "face_benchmark_manifest.json"
        private const val MODEL_SIZE = 640
    }
}
