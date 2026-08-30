package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.FollowConfigDto
import com.danceanon.native.bridge.JobStatusDto
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.media.Mp4Muxer
import com.danceanon.native.media.VideoEncoder
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.render.RenderCoordinateConvention
import com.danceanon.native.render.SourceTextureType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-device end-to-end smoke for the explicit FACE_ONLY export branch.
 *
 * The input AVC clip is generated on-device from a deterministic reviewed crop,
 * so the test exercises decoder OES -> YOLO -> TrackManager -> source ROI ->
 * MediaPipe -> secondary privacy -> production renderer -> AVC encoder.
 */
@RunWith(AndroidJUnit4::class)
class FaceOnlyExportPipelineInstrumentedTest {
    @Test
    fun faceOnlyRequestMasksHeadWithoutExpandingToFullBody() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(EXPECTED_SOURCE_SHA256, sha256Asset(context, SOURCE_ASSET))

        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "face_export_smoke").apply {
            mkdirs()
        }
        val input = File(root, "input_avc.mp4")
        val output = File(root, "output_face_only.mp4")
        input.delete()
        output.delete()

        val sourceBitmap = decodeAsset(context, SOURCE_ASSET)
        try {
            createAvcFixture(sourceBitmap, input)
            assertTrue(input.exists() && input.length() > 0L, "AVC input fixture was not created")

            val segmenter = YoloLiteRtSegmenter(context)
            val finalStatus = AtomicReference<JobStatusDto?>()
            try {
                val pipeline = ExportPipeline(context, segmenter)
                pipeline.execute(
                    jobId = "face_only_smoke",
                    sourceUri = input.absolutePath,
                    request = ExportRequestDto(
                        sourceUri = input.absolutePath,
                        analysisCacheId = "",
                        outputFilePath = output.absolutePath,
                        selectedPersonIds = emptyList(),
                        effects = solidRedEffects(),
                        follow = disabledFollow(),
                        targetWidth = FRAME_W.toLong(),
                        targetHeight = FRAME_H.toLong(),
                        targetFps = FPS.toDouble(),
                        videoBitrate = 4_000_000L,
                        processingProfile = "quality",
                        enableLivePreview = false,
                        faceOnlyPersonIds = listOf(TARGET_PERSON_ID)
                    ),
                    isCancelled = AtomicBoolean(false),
                    onStatusChange = { finalStatus.set(it) }
                )
            } finally {
                segmenter.close()
            }

            val status = assertNotNull(finalStatus.get())
            assertEquals("completed", status.state, "Export did not complete: ${status.errorMessage}")
            assertTrue(output.exists() && output.length() > 0L, "FACE_ONLY output was not produced")

            val inputFrame = decodeVideoFrame(input, VERIFY_TIME_US)
            val outputFrame = decodeVideoFrame(output, VERIFY_TIME_US)
            try {
                assertEquals(inputFrame.width, outputFrame.width)
                assertEquals(inputFrame.height, outputFrame.height)

                val privacyDelta = changedStickerStats(inputFrame, outputFrame)
                assertTrue(
                    privacyDelta.count >= 350,
                    "Expected a material FACE_ONLY sticker region; stats=$privacyDelta"
                )
                assertTrue(
                    privacyDelta.height <= (FRAME_H * 0.45).toInt(),
                    "Privacy expanded too far vertically for FACE_ONLY: stats=$privacyDelta"
                )

                val lowerBodyDelta = meanRgbDelta(
                    inputFrame,
                    outputFrame,
                    LOWER_X,
                    LOWER_Y,
                    SAMPLE_RADIUS
                )
                assertTrue(
                    lowerBodyDelta <= 45.0,
                    "FACE_ONLY unexpectedly altered lower body: mean RGB delta=$lowerBodyDelta"
                )
                val residualRed = changedRedPrivacyStats(inputFrame, outputFrame)
                assertTrue(
                    residualRed.count <= 180,
                    "FACE_ONLY export still exposed the solid-color privacy block under the sticker: $residualRed"
                )

            } finally {
                inputFrame.recycle()
                outputFrame.recycle()
            }
        } finally {
            sourceBitmap.recycle()
            input.delete()
            output.delete()
        }
    }

    private fun changedStickerStats(input: Bitmap, output: Bitmap): PrivacyDeltaStats {
        return changedStickerStatsInRect(
            input,
            output,
            left = 0,
            top = 0,
            rightExclusive = output.width,
            bottomExclusive = output.height
        )
    }

    private fun changedStickerStatsInRect(
        input: Bitmap,
        output: Bitmap,
        left: Int,
        top: Int,
        rightExclusive: Int,
        bottomExclusive: Int
    ): PrivacyDeltaStats {
        var count = 0
        var minX = output.width
        var minY = output.height
        var maxX = -1
        var maxY = -1
        val safeLeft = left.coerceIn(0, output.width)
        val safeTop = top.coerceIn(0, output.height)
        val safeRight = rightExclusive.coerceIn(safeLeft, output.width)
        val safeBottom = bottomExclusive.coerceIn(safeTop, output.height)
        for (y in safeTop until safeBottom) {
            for (x in safeLeft until safeRight) {
                val before = input.getPixel(x, y)
                val after = output.getPixel(x, y)
                val r = Color.red(after)
                val g = Color.green(after)
                val b = Color.blue(after)
                val stickerYellow = r >= 170 && g >= 120 && b <= 120 && r - b >= 70 && g - b >= 35
                val changed =
                    abs(Color.red(before) - r) +
                        abs(Color.green(before) - g) +
                        abs(Color.blue(before) - b) >= 90
                if (stickerYellow && changed) {
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

    @Test
    fun mixedFullBodyAndFaceOnlyTargetsRemainIndependent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(EXPECTED_SOURCE_SHA256, sha256Asset(context, SOURCE_ASSET))

        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "face_mixed_smoke").apply {
            mkdirs()
        }
        val input = File(root, "input_mixed_avc.mp4")
        val output = File(root, "output_mixed_privacy.mp4")
        input.delete()
        output.delete()

        val sourceBitmap = decodeAsset(context, SOURCE_ASSET)
        try {
            createAvcFixture(sourceBitmap, input)
            val segmenter = YoloLiteRtSegmenter(context)
            val finalStatus = AtomicReference<JobStatusDto?>()
            try {
                ExportPipeline(context, segmenter).execute(
                    jobId = "mixed_privacy_smoke",
                    sourceUri = input.absolutePath,
                    request = ExportRequestDto(
                        sourceUri = input.absolutePath,
                        analysisCacheId = "",
                        outputFilePath = output.absolutePath,
                        selectedPersonIds = listOf(SECONDARY_PERSON_ID),
                        effects = solidRedEffects(),
                        follow = disabledFollow(),
                        targetWidth = FRAME_W.toLong(),
                        targetHeight = FRAME_H.toLong(),
                        targetFps = FPS.toDouble(),
                        videoBitrate = 4_000_000L,
                        processingProfile = "quality",
                        enableLivePreview = false,
                        faceOnlyPersonIds = listOf(TARGET_PERSON_ID)
                    ),
                    isCancelled = AtomicBoolean(false),
                    onStatusChange = { finalStatus.set(it) }
                )
            } finally {
                segmenter.close()
            }

            val status = assertNotNull(finalStatus.get())
            assertEquals("completed", status.state, "Mixed export did not complete: ${status.errorMessage}")
            assertTrue(output.exists() && output.length() > 0L, "Mixed privacy output was not produced")

            val inputFrame = decodeVideoFrame(input, VERIFY_TIME_US)
            val outputFrame = decodeVideoFrame(output, VERIFY_TIME_US)
            try {
                val targetPrivacy = changedStickerStatsInRect(
                    inputFrame,
                    outputFrame,
                    left = 180,
                    top = 120,
                    rightExclusive = 560,
                    bottomExclusive = 820
                )
                val secondaryPrivacy = changedRedPrivacyStatsInRect(
                    inputFrame,
                    outputFrame,
                    left = 0,
                    top = 0,
                    rightExclusive = 180,
                    bottomExclusive = outputFrame.height
                )
                assertTrue(
                    targetPrivacy.count >= 300,
                    "Mixed Export lost FACE_ONLY target sticker: $targetPrivacy"
                )
                assertTrue(
                    secondaryPrivacy.count >= 300,
                    "Mixed Export lost independent FULL_BODY privacy for ID 0: $secondaryPrivacy"
                )
                val targetLowerBodyDelta = meanRgbDelta(
                    inputFrame,
                    outputFrame,
                    LOWER_X,
                    LOWER_Y,
                    SAMPLE_RADIUS
                )
                assertTrue(
                    targetLowerBodyDelta <= 45.0,
                    "Mixed Export expanded FACE_ONLY target to FULL_BODY: mean RGB delta=$targetLowerBodyDelta"
                )
            } finally {
                inputFrame.recycle()
                outputFrame.recycle()
            }
        } finally {
            sourceBitmap.recycle()
            input.delete()
            output.delete()
        }
    }

    @Test
    fun faceOnlyPrivacyFollowsDeterministicAffineDanceMotion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val meta = loadDynamicFrameMeta(context)
        assertEquals(DYNAMIC_FRAME_COUNT, meta.size)

        val frames = meta.map { frame ->
            assertEquals(frame.sha256, sha256Asset(context, frame.asset))
            decodeAsset(context, frame.asset)
        }
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "face_dynamic_smoke").apply {
            mkdirs()
        }
        val input = File(root, "input_dynamic_avc.mp4")
        val output = File(root, "output_dynamic_face_only.mp4")
        input.delete()
        output.delete()

        try {
            createAvcFixture(frames, input)
            assertTrue(input.exists() && input.length() > 0L, "Dynamic AVC input fixture was not created")

            val segmenter = YoloLiteRtSegmenter(context)
            val finalStatus = AtomicReference<JobStatusDto?>()
            try {
                ExportPipeline(context, segmenter).execute(
                    jobId = "face_only_dynamic_smoke",
                    sourceUri = input.absolutePath,
                    request = ExportRequestDto(
                        sourceUri = input.absolutePath,
                        analysisCacheId = "",
                        outputFilePath = output.absolutePath,
                        selectedPersonIds = emptyList(),
                        effects = solidRedEffects(),
                        follow = disabledFollow(),
                        targetWidth = FRAME_W.toLong(),
                        targetHeight = FRAME_H.toLong(),
                        targetFps = FPS.toDouble(),
                        videoBitrate = 4_000_000L,
                        processingProfile = "quality",
                        enableLivePreview = false,
                        faceOnlyPersonIds = listOf(TARGET_PERSON_ID)
                    ),
                    isCancelled = AtomicBoolean(false),
                    onStatusChange = { finalStatus.set(it) }
                )
            } finally {
                segmenter.close()
            }

            val status = assertNotNull(finalStatus.get())
            assertEquals("completed", status.state, "Dynamic export did not complete: ${status.errorMessage}")

            val inputRetriever = MediaMetadataRetriever()
            val outputRetriever = MediaMetadataRetriever()
            try {
                inputRetriever.setDataSource(input.absolutePath)
                outputRetriever.setDataSource(output.absolutePath)
                val sampleFrames = meta.filter { it.index % 3 == 0 || it.index == DYNAMIC_FRAME_COUNT - 1 }
                var firstPrivacyCenterX: Double? = null
                var firstPrivacyCenterY: Double? = null
                var firstExpectedX: Double? = null
                var firstExpectedY: Double? = null
                val observedCentersX = mutableListOf<Double>()
                val observedStickerWidths = mutableListOf<Int>()
                val observedStickerHeights = mutableListOf<Int>()

                sampleFrames.forEach { frame ->
                    val timeUs = frame.index * DYNAMIC_FRAME_DURATION_US
                    val inputFrame = assertNotNull(
                        inputRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    )
                    val outputFrame = assertNotNull(
                        outputRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    )
                    try {
                        val privacy = changedStickerStats(inputFrame, outputFrame)
                        assertTrue(
                            privacy.count >= 300,
                            "Dynamic frame ${frame.index} lost FACE_ONLY sticker: $privacy"
                        )
                        assertTrue(
                            privacy.height <= (FRAME_H * 0.45).toInt(),
                            "Dynamic frame ${frame.index} expanded toward FULL_BODY: $privacy"
                        )
                        val centerX = (privacy.minX + privacy.maxX) * 0.5
                        val centerY = (privacy.minY + privacy.maxY) * 0.5
                        observedCentersX += centerX
                        observedStickerWidths += privacy.width
                        observedStickerHeights += privacy.height

                        if (firstPrivacyCenterX == null) {
                            firstPrivacyCenterX = centerX
                            firstPrivacyCenterY = centerY
                            firstExpectedX = frame.headX
                            firstExpectedY = frame.headY
                        } else {
                            val actualDx = centerX - firstPrivacyCenterX!!
                            val actualDy = centerY - firstPrivacyCenterY!!
                            val expectedDx = frame.headX - firstExpectedX!!
                            val expectedDy = frame.headY - firstExpectedY!!
                            assertTrue(
                                abs(actualDx - expectedDx) <= DYNAMIC_MOTION_TOLERANCE_PX,
                                "Dynamic frame ${frame.index} privacy X motion diverged: actual=$actualDx expected=$expectedDx"
                            )
                            assertTrue(
                                abs(actualDy - expectedDy) <= DYNAMIC_MOTION_TOLERANCE_PX,
                                "Dynamic frame ${frame.index} privacy Y motion diverged: actual=$actualDy expected=$expectedDy"
                            )
                        }
                    } finally {
                        inputFrame.recycle()
                        outputFrame.recycle()
                    }
                }

                val observedSpanX = observedCentersX.maxOrNull()!! - observedCentersX.minOrNull()!!
                assertTrue(
                    observedSpanX >= DYNAMIC_MIN_OBSERVED_X_SPAN_PX,
                    "FACE_ONLY privacy did not visibly follow horizontal motion: span=$observedSpanX"
                )
                val widthRatio = observedStickerWidths.maxOrNull()!!.toDouble() /
                    observedStickerWidths.minOrNull()!!.coerceAtLeast(1)
                val heightRatio = observedStickerHeights.maxOrNull()!!.toDouble() /
                    observedStickerHeights.minOrNull()!!.coerceAtLeast(1)
                assertTrue(
                    widthRatio <= DYNAMIC_MAX_STICKER_SIZE_RATIO,
                    "FACE_ONLY sticker width still pumps across motion frames: ratio=$widthRatio widths=$observedStickerWidths"
                )
                assertTrue(
                    heightRatio <= DYNAMIC_MAX_STICKER_SIZE_RATIO,
                    "FACE_ONLY sticker height still pumps across motion frames: ratio=$heightRatio heights=$observedStickerHeights"
                )
            } finally {
                inputRetriever.release()
                outputRetriever.release()
            }
        } finally {
            frames.forEach { it.recycle() }
            input.delete()
            output.delete()
        }
    }

    private fun createAvcFixture(bitmap: Bitmap, output: File) {
        createAvcFixture(List(INPUT_FRAMES) { bitmap }, output)
    }

    private fun createAvcFixture(frames: List<Bitmap>, output: File) {
        require(frames.isNotEmpty())
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
        try {
            frames.forEachIndexed { index, frame ->
                val texture = create2dTexture(frame)
                try {
                    renderer.renderBase(
                        frameTexture = texture,
                        texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                        textureType = SourceTextureType.TEXTURE_2D
                    )
                    egl.setPresentationTime(eglSurface, index * FRAME_DURATION_NS)
                    assertTrue(egl.swapBuffers(eglSurface), "Input fixture swap failed at frame=$index")
                    encoder.drainEncoder(muxer, endOfStream = false)
                } finally {
                    GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                }
            }
            encoder.drainEncoder(muxer, endOfStream = true)
        } finally {
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

    private fun decodeVideoFrame(file: File, timeUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            requireNotNull(
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ) { "Unable to decode frame from ${file.name}" }
        } finally {
            retriever.release()
        }
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
        val width: Int get() = if (count == 0) 0 else maxX - minX + 1
        val height: Int get() = if (count == 0) 0 else maxY - minY + 1
    }

    private fun changedRedPrivacyStats(input: Bitmap, output: Bitmap): PrivacyDeltaStats {
        return changedRedPrivacyStatsInRect(
            input,
            output,
            left = 0,
            top = 0,
            rightExclusive = output.width,
            bottomExclusive = output.height
        )
    }

    private fun changedRedPrivacyStatsInRect(
        input: Bitmap,
        output: Bitmap,
        left: Int,
        top: Int,
        rightExclusive: Int,
        bottomExclusive: Int
    ): PrivacyDeltaStats {
        var count = 0
        var minX = output.width
        var minY = output.height
        var maxX = -1
        var maxY = -1
        val xStart = left.coerceIn(0, output.width)
        val xEnd = rightExclusive.coerceIn(xStart, output.width)
        val yStart = top.coerceIn(0, output.height)
        val yEnd = bottomExclusive.coerceIn(yStart, output.height)
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
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

    private fun sha256Asset(context: Context, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(name).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class DynamicFrameMeta(
        val index: Int,
        val asset: String,
        val headX: Double,
        val headY: Double,
        val sha256: String
    )

    private fun loadDynamicFrameMeta(context: Context): List<DynamicFrameMeta> {
        val json = context.assets.open(DYNAMIC_MANIFEST_ASSET).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        assertEquals(FRAME_W, root.getInt("width"))
        assertEquals(FRAME_H, root.getInt("height"))
        assertEquals(FPS, root.getInt("fps"))
        val frames = root.getJSONArray("frames")
        return (0 until frames.length()).map { i ->
            val frame = frames.getJSONObject(i)
            DynamicFrameMeta(
                index = frame.getInt("index"),
                asset = frame.getString("asset"),
                headX = frame.getDouble("head_x"),
                headY = frame.getDouble("head_y"),
                sha256 = frame.getString("sha256")
            )
        }
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
        private const val DYNAMIC_MANIFEST_ASSET = "dynamic_manifest.json"
        private const val EXPECTED_SOURCE_SHA256 =
            "bd3bf77fedb9fb85ab57faba66a99ef0afff11dc101d4784685efbc496d899d8"
        private const val FRAME_W = 720
        private const val FRAME_H = 1280
        private const val FPS = 30
        private const val INPUT_FRAMES = 18
        private const val FRAME_DURATION_NS = 33_333_333L
        private const val DYNAMIC_FRAME_DURATION_US = 33_333L
        private const val DYNAMIC_FRAME_COUNT = 18
        private const val DYNAMIC_MOTION_TOLERANCE_PX = 75.0
        private const val DYNAMIC_MIN_OBSERVED_X_SPAN_PX = 55.0
        private const val DYNAMIC_MAX_STICKER_SIZE_RATIO = 1.35
        // The reviewed real dancer is the second left-to-right raw YOLO person
        // on frame 0 of this fixture. TrackManager initialization therefore
        // assigns it ID 1 when no analysis cache is supplied.
        private const val TARGET_PERSON_ID = 1L
        private const val SECONDARY_PERSON_ID = 0L
        private const val VERIFY_TIME_US = 300_000L
        private const val LOWER_X = 337
        private const val LOWER_Y = 900
        private const val SAMPLE_RADIUS = 12
    }
}
