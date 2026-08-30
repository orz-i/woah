package com.danceanon.native.privacy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.RenderCoordinateConvention
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Long-running production-locator smoke for the FACE_ONLY sidecar itself.
 *
 * This deliberately excludes YOLO/video codec cost so repeated MediaPipe + GL
 * ROI + privacy-mask lifecycle can be stressed in a small deterministic test.
 */
@RunWith(AndroidJUnit4::class)
class FaceOnlyPrivacyProductionStressInstrumentedTest {
    @Test
    fun productionLocatorRunsThreeHundredFramesWithoutFallbackOrRuntimeFailure() {
        Log.i(TAG, "stage=test_start")
        val context = ApplicationProvider.getApplicationContext<Context>()
        Log.i(TAG, "stage=context_ready")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hostActivity = instrumentation.startActivitySync(
            Intent(context, FaceOnlyStressHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ) as FaceOnlyStressHostActivity
        instrumentation.waitForIdleSync()
        Log.i(TAG, "stage=foreground_host_ready")
        val source = decodeAsset(context, SOURCE_ASSET)
        Log.i(TAG, "stage=source_ready")
        val egl = EglCore()
        val eglSurface = egl.createOffscreenSurface(FRAME_W, FRAME_H)
        egl.makeCurrent(eglSurface)
        Log.i(TAG, "stage=egl_ready")
        val mapper = ModelCoordinateMapper(FRAME_W, FRAME_H, 640, 160)
        Log.i(TAG, "stage=processor_create_start")
        val processor = FaceOnlyPrivacyFrameProcessor.create(context, mapper)
        Log.i(TAG, "stage=processor_create_done")
        var texture = 0
        var detectedFrames = 0
        var predictedFrames = 0
        var fallbackFrames = 0
        var detectorCalls = 0
        var unresolvedFrames = 0
        val detectorTimes = mutableListOf<Double>()
        val nativeHeapBefore = Debug.getNativeHeapAllocatedSize()
        val pssBeforeKb = Debug.getPss()

        try {
            texture = create2dTexture(source)
            Log.i(TAG, "stage=loop_start frames=$FRAMES")
            val person = TrackedPerson(
                id = TARGET_ID,
                bbox = FloatRect(TARGET_LEFT, TARGET_TOP, TARGET_RIGHT, TARGET_BOTTOM),
                mask = null,
                confidence = 0.95f,
                state = TrackState.ACTIVE,
                observedThisFrame = true,
                footY = TARGET_BOTTOM
            )

            repeat(FRAMES) { frameIndex ->
                if (frameIndex % 25 == 0) {
                    Log.i(TAG, "stage=frame_start index=$frameIndex detector_calls=$detectorCalls")
                }
                val result = processor.resolveFrame(
                    frameTexture = texture,
                    texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                    textureType = SourceTextureType.TEXTURE_2D,
                    persons = listOf(person),
                    faceOnlyTrackIds = setOf(TARGET_ID),
                    ptsUs = frameIndex * FRAME_DURATION_US
                )
                if (!result.readyForRender) unresolvedFrames++
                if (TARGET_ID in result.detectedTrackIds) detectedFrames++
                if (TARGET_ID in result.predictedTrackIds) predictedFrames++
                if (TARGET_ID in result.fallbackTrackIds) fallbackFrames++
                detectorCalls += result.detectorCallCount
                if (result.faceInferenceMs > 0.0) detectorTimes += result.faceInferenceMs
                assertTrue(result.readyForRender, "FACE_ONLY stress frame $frameIndex was unresolved")
                if (frameIndex % 25 == 0) {
                    Log.i(
                        TAG,
                        "stage=frame_done index=$frameIndex detector_calls=$detectorCalls " +
                            "detected=$detectedFrames predicted=$predictedFrames fallback=$fallbackFrames"
                    )
                }
            }
            Log.i(TAG, "stage=loop_done detector_calls=$detectorCalls")
        } finally {
            Log.i(TAG, "stage=processor_close_start")
            processor.close()
            Log.i(TAG, "stage=processor_close_done")
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            source.recycle()
            egl.releaseSurface(eglSurface)
            egl.close()
            Log.i(TAG, "stage=egl_close_done")
            hostActivity.runOnUiThread { hostActivity.finish() }
            instrumentation.waitForIdleSync()
            Log.i(TAG, "stage=foreground_host_closed")
        }

        val nativeHeapAfter = Debug.getNativeHeapAllocatedSize()
        val pssAfterKb = Debug.getPss()
        val sorted = detectorTimes.sorted()
        val p95 = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * 0.95).roundToInt()]
        Log.i(
            TAG,
            "frames=$FRAMES detector_calls=$detectorCalls detected=$detectedFrames predicted=$predictedFrames " +
                "fallback=$fallbackFrames unresolved=$unresolvedFrames detector_p95_ms=$p95 " +
                "native_heap_before=$nativeHeapBefore native_heap_after=$nativeHeapAfter " +
                "pss_before_kb=$pssBeforeKb pss_after_kb=$pssAfterKb"
        )

        assertEquals(0, unresolvedFrames)
        assertEquals(0, fallbackFrames)
        assertEquals(FRAMES / 2, detectorCalls)
        assertEquals(FRAMES / 2, detectedFrames)
        assertEquals(FRAMES / 2, predictedFrames)
        assertTrue(p95 <= 40.0, "Production MediaPipe detector p95 unexpectedly high: $p95 ms")
    }

    private fun decodeAsset(context: Context, name: String): Bitmap =
        context.assets.open(name).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode asset $name" }
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

    companion object {
        private const val TAG = "FACE_ONLY_STRESS"
        private const val SOURCE_ASSET = "person3_frame.jpg"
        private const val FRAME_W = 720
        private const val FRAME_H = 1280
        private const val TARGET_ID = 1
        private const val FRAMES = 300
        private const val FRAME_DURATION_US = 33_333L
        // Reviewed person-3 bbox in the committed 720x1280 source crop.
        private const val TARGET_LEFT = 201.542f
        private const val TARGET_TOP = 317.429f
        private const val TARGET_RIGHT = 473.949f
        private const val TARGET_BOTTOM = 1013.206f
    }
}
