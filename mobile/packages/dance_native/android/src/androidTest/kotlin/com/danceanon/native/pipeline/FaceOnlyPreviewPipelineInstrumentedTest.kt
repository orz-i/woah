package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.bridge.DanceNativeException
import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.FollowConfigDto
import com.danceanon.native.bridge.PreviewRequestDto
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.media.Mp4Muxer
import com.danceanon.native.media.VideoEncoder
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.render.RenderCoordinateConvention
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.storage.CacheManager
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end single-frame preview verification for the internal FACE_ONLY path. */
@RunWith(AndroidJUnit4::class)
class FaceOnlyPreviewPipelineInstrumentedTest {
    @Test
    fun previewFaceOnlyMasksHeadAndMissingIdFailsClosed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = CacheManager(context)
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "face_preview_smoke").apply { mkdirs() }
        val input = File(root, "preview_input_avc.mp4")
        val cacheId = "face_preview_smoke_cache"
        input.delete()
        cache.clearAnalysisCache(cacheId)

        val sourceBitmap = decodeAsset(context, SOURCE_ASSET)
        val segmenter = YoloLiteRtSegmenter(context)
        val pipeline = PreviewPipeline(context, segmenter, cache)
        var baselineBitmap: Bitmap? = null
        var faceBitmap: Bitmap? = null
        try {
            createAvcFixture(sourceBitmap, input)
            assertTrue(input.exists() && input.length() > 0L, "Preview AVC fixture was not created")
            cache.saveVideoUri(cacheId, input.absolutePath)

            val baseline = pipeline.renderPreview(
                previewRequest(cacheId = cacheId, faceOnlyIds = null)
            )
            baselineBitmap = assertNotNull(BitmapFactory.decodeFile(baseline.thumbnailPath))

            val faceOnly = pipeline.renderPreview(
                previewRequest(cacheId = cacheId, faceOnlyIds = listOf(TARGET_PERSON_ID))
            )
            faceBitmap = assertNotNull(BitmapFactory.decodeFile(faceOnly.thumbnailPath))
            assertEquals(baselineBitmap.width, faceBitmap.width)
            assertEquals(baselineBitmap.height, faceBitmap.height)

            val privacyDelta = changedRedPrivacyStats(baselineBitmap, faceBitmap)
            assertTrue(privacyDelta.count >= 800, "Expected visible FACE_ONLY preview privacy: $privacyDelta")
            assertTrue(
                privacyDelta.height <= (faceBitmap.height * 0.45).toInt(),
                "Preview FACE_ONLY expanded too far vertically: $privacyDelta"
            )
            val lowerBodyDelta = meanRgbDelta(
                baselineBitmap,
                faceBitmap,
                LOWER_X,
                LOWER_Y,
                SAMPLE_RADIUS
            )
            assertTrue(
                lowerBodyDelta <= 45.0,
                "Preview FACE_ONLY unexpectedly altered lower body: mean RGB delta=$lowerBodyDelta"
            )

            var failedClosed = false
            try {
                pipeline.renderPreview(
                    previewRequest(cacheId = cacheId, faceOnlyIds = listOf(999L))
                )
            } catch (e: DanceNativeException) {
                failedClosed = true
                assertTrue(
                    e.message.orEmpty().contains("FACE_ONLY preview privacy unresolved"),
                    "Unexpected missing-ID preview failure: ${e.message}"
                )
            }
            assertTrue(failedClosed, "Missing FACE_ONLY preview target must fail closed")
        } finally {
            baselineBitmap?.recycle()
            faceBitmap?.recycle()
            sourceBitmap.recycle()
            pipeline.clearForAnalysis(cacheId)
            segmenter.close()
            cache.clearAnalysisCache(cacheId)
            input.delete()
        }
        Unit
    }

    private fun previewRequest(cacheId: String, faceOnlyIds: List<Long>?) = PreviewRequestDto(
        analysisCacheId = cacheId,
        timestampMs = 0L,
        selectedPersonIds = emptyList(),
        effects = solidRedEffects(),
        follow = disabledFollow(),
        faceOnlyPersonIds = faceOnlyIds
    )

    private fun createAvcFixture(bitmap: Bitmap, output: File) {
        val encoder = VideoEncoder(
            width = FRAME_W,
            height = FRAME_H,
            bitrate = 3_000_000,
            fps = FPS.toFloat(),
            iFrameInterval = 1
        )
        val muxer = Mp4Muxer(output.absolutePath, expectedTracks = 1)
        val inputSurface = encoder.prepare()
        val egl = EglCore()
        val eglSurface = egl.createWindowSurface(inputSurface)
        egl.makeCurrent(eglSurface)
        val renderer = GlRenderer().apply { initialize(FRAME_W, FRAME_H) }
        var texture = 0
        try {
            texture = create2dTexture(bitmap)
            repeat(INPUT_FRAMES) { index ->
                renderer.renderBase(
                    frameTexture = texture,
                    texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                    textureType = SourceTextureType.TEXTURE_2D
                )
                egl.setPresentationTime(eglSurface, index * FRAME_DURATION_NS)
                assertTrue(egl.swapBuffers(eglSurface), "Preview fixture swap failed at frame=$index")
                encoder.drainEncoder(muxer, endOfStream = false)
            }
            encoder.drainEncoder(muxer, endOfStream = true)
        } finally {
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            renderer.close()
            egl.releaseSurface(eglSurface)
            egl.close()
            encoder.close()
            muxer.close()
        }
    }

    private fun create2dTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return texture
    }

    private fun decodeAsset(context: Context, name: String): Bitmap =
        context.assets.open(name).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode asset $name" }
        }

    private data class PrivacyDeltaStats(
        val count: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val height: Int get() = if (count == 0) 0 else maxY - minY + 1
    }

    private fun changedRedPrivacyStats(input: Bitmap, output: Bitmap): PrivacyDeltaStats {
        var count = 0
        var minX = output.width
        var minY = output.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until output.height) {
            for (x in 0 until output.width) {
                val before = input.getPixel(x, y)
                val after = output.getPixel(x, y)
                val r = Color.red(after)
                val g = Color.green(after)
                val b = Color.blue(after)
                val redPrivacy = r >= 180 && r - g >= 80 && r - b >= 80
                val changed =
                    abs(Color.red(before) - r) +
                        abs(Color.green(before) - g) +
                        abs(Color.blue(before) - b) >= 120
                if (redPrivacy && changed) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return PrivacyDeltaStats(count, minX, minY, maxX, maxY)
    }

    private fun meanRgbDelta(a: Bitmap, b: Bitmap, cx: Int, cy: Int, radius: Int): Double {
        var sum = 0L
        var channels = 0
        for (y in (cy - radius)..(cy + radius)) {
            for (x in (cx - radius)..(cx + radius)) {
                if (x !in 0 until a.width || y !in 0 until a.height) continue
                val ca = a.getPixel(x, y)
                val cb = b.getPixel(x, y)
                sum += abs(Color.red(ca) - Color.red(cb))
                sum += abs(Color.green(ca) - Color.green(cb))
                sum += abs(Color.blue(ca) - Color.blue(cb))
                channels += 3
            }
        }
        return sum.toDouble() / channels.coerceAtLeast(1)
    }

    private fun solidRedEffects() = EffectConfigDto(
        fillMode = "solid",
        fillColorArgb = 0xFFFF0000L,
        borderColorArgb = 0x00000000L,
        opacity = 1.0,
        borderWidth = 0.0,
        blurStrength = 1.0,
        faceStickerEnabled = false,
        stickerAssetId = null,
        stickerScale = 1.0,
        skinWhiten = 0.0,
        legStretchEnabled = false,
        legStretch = 0.0,
        legZoneTop = 0.5,
        legZoneBottom = 0.75
    )

    private fun disabledFollow() = FollowConfigDto(
        enabled = false,
        targetPersonId = null,
        zoom = 1.0,
        smoothFactor = 0.0
    )

    companion object {
        private const val SOURCE_ASSET = "person3_frame.jpg"
        private const val TARGET_PERSON_ID = 1L
        private const val FRAME_W = 720
        private const val FRAME_H = 1280
        private const val FPS = 10
        private const val INPUT_FRAMES = 6
        private const val FRAME_DURATION_NS = 100_000_000L
        private const val LOWER_X = 337
        private const val LOWER_Y = 900
        private const val SAMPLE_RADIUS = 12
    }
}
