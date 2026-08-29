package com.danceanon.native.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.benchmark.MediaPipeFaceBenchmarkBackend
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.inference.FloatRect
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FaceRoiRendererInstrumentedTest {
    @Test
    fun texture2dAndOesProduceSameVisualSourceCrop() {
        val egl = EglCore()
        val surface = egl.createOffscreenSurface(OUTPUT_SIZE, OUTPUT_SIZE)
        egl.makeCurrent(surface)

        val source = createCoordinateBitmap(SOURCE_W, SOURCE_H)
        val plan = assertNotNull(
            FaceHeadRoiPlanner.plan(
                personBbox = FloatRect(105f, 62f, 185f, 222f),
                frameWidth = SOURCE_W,
                frameHeight = SOURCE_H
            )
        )

        val renderer = FaceRoiRenderer()
        val fbo2d = InferenceFbo(OUTPUT_SIZE)
        val fboOes = InferenceFbo(OUTPUT_SIZE)
        var tex2d = 0
        var oesTex = 0
        var surfaceTexture: SurfaceTexture? = null
        var producerSurface: Surface? = null

        try {
            tex2d = create2dTexture(source)
            renderer.renderToFbo(
                textureId = tex2d,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                sourceRect = plan.sourceRect,
                sourceWidth = SOURCE_W,
                sourceHeight = SOURCE_H,
                fbo = fbo2d,
                textureType = SourceTextureType.TEXTURE_2D
            )
            val pixels2d = copyBuffer(fbo2d.readRgbaPixels())

            val oes = createOesTextureFromBitmap(source)
            oesTex = oes.textureId
            surfaceTexture = oes.surfaceTexture
            producerSurface = oes.surface
            val stMatrix = FloatArray(16)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)

            renderer.renderToFbo(
                textureId = oesTex,
                texMatrix = stMatrix,
                sourceRect = plan.sourceRect,
                sourceWidth = SOURCE_W,
                sourceHeight = SOURCE_H,
                fbo = fboOes,
                textureType = SourceTextureType.OES
            )
            val pixelsOes = copyBuffer(fboOes.readRgbaPixels())

            assertGradientCrop("TEXTURE_2D", pixels2d, plan.sourceRect)
            assertGradientCrop("OES", pixelsOes, plan.sourceRect)
            assertPathsAgree(pixels2d, pixelsOes)
        } finally {
            producerSurface?.release()
            surfaceTexture?.release()
            if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
            if (tex2d != 0) GLES20.glDeleteTextures(1, intArrayOf(tex2d), 0)
            fboOes.close()
            fbo2d.close()
            renderer.close()
            source.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    @Test
    fun benchmarkOesRenderAndReadback256() {
        val egl = EglCore()
        val surface = egl.createOffscreenSurface(OUTPUT_SIZE, OUTPUT_SIZE)
        egl.makeCurrent(surface)
        val source = createCoordinateBitmap(SOURCE_W, SOURCE_H)
        val plan = assertNotNull(
            FaceHeadRoiPlanner.plan(
                personBbox = FloatRect(105f, 62f, 185f, 222f),
                frameWidth = SOURCE_W,
                frameHeight = SOURCE_H
            )
        )
        val renderer = FaceRoiRenderer()
        val fbo = InferenceFbo(OUTPUT_SIZE)
        var oesTex = 0
        var surfaceTexture: SurfaceTexture? = null
        var producerSurface: Surface? = null

        try {
            val oes = createOesTextureFromBitmap(source)
            oesTex = oes.textureId
            surfaceTexture = oes.surfaceTexture
            producerSurface = oes.surface
            val stMatrix = FloatArray(16)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)

            repeat(GL_BENCHMARK_WARMUP) {
                renderer.renderToFbo(
                    textureId = oesTex,
                    texMatrix = stMatrix,
                    sourceRect = plan.sourceRect,
                    sourceWidth = SOURCE_W,
                    sourceHeight = SOURCE_H,
                    fbo = fbo,
                    textureType = SourceTextureType.OES
                )
                fbo.readRgbaPixels().get(0)
            }

            val latenciesMs = ArrayList<Double>(GL_BENCHMARK_SAMPLES)
            var checksum = 0
            repeat(GL_BENCHMARK_SAMPLES) {
                val startNs = System.nanoTime()
                renderer.renderToFbo(
                    textureId = oesTex,
                    texMatrix = stMatrix,
                    sourceRect = plan.sourceRect,
                    sourceWidth = SOURCE_W,
                    sourceHeight = SOURCE_H,
                    fbo = fbo,
                    textureType = SourceTextureType.OES
                )
                val pixels = fbo.readRgbaPixels()
                checksum = checksum xor (pixels.get(0).toInt() and 0xFF)
                latenciesMs += (System.nanoTime() - startNs) / 1_000_000.0
            }

            val sorted = latenciesMs.sorted()
            Log.i(
                GL_BENCHMARK_TAG,
                "FACE_ROI_GL_BENCHMARK samples=${sorted.size} mean_ms=${sorted.average()} " +
                    "p50_ms=${percentile(sorted, 0.50)} p95_ms=${percentile(sorted, 0.95)} " +
                    "min_ms=${sorted.first()} max_ms=${sorted.last()} checksum=$checksum"
            )
            assertTrue(sorted.size == GL_BENCHMARK_SAMPLES)
            assertTrue(sorted.all { it > 0.0 && it.isFinite() })
        } finally {
            producerSurface?.release()
            surfaceTexture?.release()
            if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
            fbo.close()
            renderer.close()
            source.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    @Test
    fun oesReadbackFeedsMediaPipeWithoutCpuFlip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.assets.open(FACE_ROI_ASSET).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Unable to decode $FACE_ROI_ASSET")
        assertTrue(source.width == OUTPUT_SIZE && source.height == OUTPUT_SIZE)

        val egl = EglCore()
        val surface = egl.createOffscreenSurface(OUTPUT_SIZE, OUTPUT_SIZE)
        egl.makeCurrent(surface)
        val renderer = FaceRoiRenderer()
        val fbo = InferenceFbo(OUTPUT_SIZE)
        val detector = MediaPipeFaceBenchmarkBackend(context)
        var oesTex = 0
        var surfaceTexture: SurfaceTexture? = null
        var producerSurface: Surface? = null

        try {
            val oes = createOesTextureFromBitmap(source)
            oesTex = oes.textureId
            surfaceTexture = oes.surfaceTexture
            producerSurface = oes.surface
            val stMatrix = FloatArray(16)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            val fullRect = FloatRect(0f, 0f, source.width.toFloat(), source.height.toFloat())

            repeat(E2E_BENCHMARK_WARMUP) {
                renderer.renderToFbo(
                    textureId = oesTex,
                    texMatrix = stMatrix,
                    sourceRect = fullRect,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    fbo = fbo,
                    textureType = SourceTextureType.OES
                )
                val faceResult = detector.detect(
                    rgbaTopDown = fbo.readRgbaPixels(),
                    width = OUTPUT_SIZE,
                    height = OUTPUT_SIZE,
                    timestampMs = it.toLong()
                )
                assertTrue(faceResult.detections.isNotEmpty(), "Warm-up lost the known ROI face")
            }

            val latenciesMs = ArrayList<Double>(E2E_BENCHMARK_SAMPLES)
            var detectedSamples = 0
            repeat(E2E_BENCHMARK_SAMPLES) { index ->
                val startNs = System.nanoTime()
                renderer.renderToFbo(
                    textureId = oesTex,
                    texMatrix = stMatrix,
                    sourceRect = fullRect,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    fbo = fbo,
                    textureType = SourceTextureType.OES
                )
                val faceResult = detector.detect(
                    rgbaTopDown = fbo.readRgbaPixels(),
                    width = OUTPUT_SIZE,
                    height = OUTPUT_SIZE,
                    timestampMs = (E2E_BENCHMARK_WARMUP + index).toLong()
                )
                if (faceResult.detections.isNotEmpty()) detectedSamples++
                latenciesMs += (System.nanoTime() - startNs) / 1_000_000.0
            }

            val sorted = latenciesMs.sorted()
            Log.i(
                E2E_BENCHMARK_TAG,
                "FACE_ROI_E2E_BENCHMARK samples=${sorted.size} detected=$detectedSamples " +
                    "mean_ms=${sorted.average()} p50_ms=${percentile(sorted, 0.50)} " +
                    "p95_ms=${percentile(sorted, 0.95)} min_ms=${sorted.first()} max_ms=${sorted.last()}"
            )
            assertTrue(
                detectedSamples == E2E_BENCHMARK_SAMPLES,
                "Direct OES readback -> MediaPipe lost face detections: $detectedSamples/$E2E_BENCHMARK_SAMPLES"
            )
        } finally {
            detector.close()
            producerSurface?.release()
            surfaceTexture?.release()
            if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
            fbo.close()
            renderer.close()
            source.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    private data class OesFixture(
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface
    )

    private fun createOesTextureFromBitmap(bitmap: Bitmap): OesFixture {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val frameReady = CountDownLatch(1)
        val surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(bitmap.width, bitmap.height)
            setOnFrameAvailableListener({ frameReady.countDown() }, Handler(Looper.getMainLooper()))
        }
        val surface = Surface(surfaceTexture)
        val canvas = surface.lockCanvas(null)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        surface.unlockCanvasAndPost(canvas)
        assertTrue(frameReady.await(3, TimeUnit.SECONDS), "OES fixture frame was not produced")
        return OesFixture(textureId, surfaceTexture, surface)
    }

    private fun create2dTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return textureId
    }

    private fun createCoordinateBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val green = (y * 255f / (height - 1)).roundToInt().coerceIn(0, 255)
            for (x in 0 until width) {
                val red = (x * 255f / (width - 1)).roundToInt().coerceIn(0, 255)
                // Blue is deliberately asymmetric as another flip/transposition signal.
                val blue = if (x < width / 2) 37 else if (y < height / 2) 149 else 231
                pixels[y * width + x] = Color.argb(255, red, green, blue)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun assertGradientCrop(label: String, rgba: ByteArray, crop: FloatRect) {
        val samplePositions = listOf(
            20 to 20,
            128 to 20,
            235 to 20,
            20 to 128,
            128 to 128,
            235 to 128,
            20 to 235,
            128 to 235,
            235 to 235
        )
        for ((x, y) in samplePositions) {
            val actual = rgbaAt(rgba, x, y)
            val logicalX = crop.left + ((x + 0.5f) / OUTPUT_SIZE) * crop.width
            val logicalY = crop.top + ((y + 0.5f) / OUTPUT_SIZE) * crop.height
            val expectedR = ((logicalX - 0.5f) * 255f / (SOURCE_W - 1)).roundToInt().coerceIn(0, 255)
            val expectedG = ((logicalY - 0.5f) * 255f / (SOURCE_H - 1)).roundToInt().coerceIn(0, 255)
            assertTrue(abs(actual[0] - expectedR) <= 6, "$label R mismatch at ($x,$y): ${actual[0]} vs $expectedR")
            assertTrue(abs(actual[1] - expectedG) <= 6, "$label G/Y mismatch at ($x,$y): ${actual[1]} vs $expectedG")
        }

        val top = rgbaAt(rgba, OUTPUT_SIZE / 2, 12)
        val bottom = rgbaAt(rgba, OUTPUT_SIZE / 2, OUTPUT_SIZE - 13)
        assertTrue(top[1] < bottom[1], "$label output is vertically flipped: topG=${top[1]} bottomG=${bottom[1]}")
    }

    private fun assertPathsAgree(a: ByteArray, b: ByteArray) {
        var totalDelta = 0L
        var samples = 0
        for (y in 8 until OUTPUT_SIZE step 16) {
            for (x in 8 until OUTPUT_SIZE step 16) {
                val pa = rgbaAt(a, x, y)
                val pb = rgbaAt(b, x, y)
                totalDelta += abs(pa[0] - pb[0]) + abs(pa[1] - pb[1]) + abs(pa[2] - pb[2])
                samples += 3
            }
        }
        val meanAbsDelta = totalDelta.toDouble() / samples.coerceAtLeast(1)
        assertTrue(meanAbsDelta <= 5.0, "OES/2D crop paths disagree: meanAbsDelta=$meanAbsDelta")
    }

    private fun rgbaAt(data: ByteArray, x: Int, visualY: Int): IntArray {
        // FaceRoiRenderer intentionally makes glReadPixels row 0 semantic visual top.
        val offset = (visualY * OUTPUT_SIZE + x) * 4
        return intArrayOf(
            data[offset].toInt() and 0xFF,
            data[offset + 1].toInt() and 0xFF,
            data[offset + 2].toInt() and 0xFF,
            data[offset + 3].toInt() and 0xFF
        )
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val readable = buffer.duplicate().apply { rewind() }
        return ByteArray(readable.remaining()).also { readable.get(it) }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        private const val SOURCE_W = 320
        private const val SOURCE_H = 240
        private const val OUTPUT_SIZE = 256
        private const val GL_BENCHMARK_WARMUP = 8
        private const val GL_BENCHMARK_SAMPLES = 60
        private const val GL_BENCHMARK_TAG = "FACE_ROI_GL_BENCHMARK"
        private const val FACE_ROI_ASSET = "face_roi_p3_upper.jpg"
        private const val E2E_BENCHMARK_WARMUP = 5
        private const val E2E_BENCHMARK_SAMPLES = 40
        private const val E2E_BENCHMARK_TAG = "FACE_ROI_E2E_BENCHMARK"
    }
}
