package com.danceanon.native.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceLocator
import com.danceanon.native.face.FaceLocatorResult
import com.danceanon.native.face.FaceObservation
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.RenderCoordinateConvention
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FaceOnlyPrivacyFrameProcessorInstrumentedTest {
    @Test
    fun unambiguousFaceProducesDetectedFacePrivacyMask() {
        withProcessor(FixedLocator(listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)))) {
                processor, texture, mapper ->
            val target = person(7, FloatRect(220f, 40f, 420f, 350f))
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(7),
                ptsUs = 0L
            )
            assertTrue(result.readyForRender)
            assertEquals(setOf(7), result.detectedTrackIds)
            assertTrue(result.fallbackTrackIds.isEmpty())
            val mask = assertNotNull(result.resolvedPrivacy?.privacyMask)
            assertTrue(pixelAtSource(mask, mapper, 320f, 90f) > 0)
            assertEquals(0, pixelAtSource(mask, mapper, 320f, 300f))
        }
    }

    @Test
    fun fullBodyTargetCannotActAsSecondaryOccluderAgainstFaceOnlyTarget() {
        withProcessor(FixedLocator(emptyList())) { processor, texture, mapper ->
            val faceOnly = person(1, FloatRect(180f, 30f, 360f, 340f)).copy(
                mask = sourceRectMask(mapper, FloatRect(180f, 30f, 360f, 340f))
            )
            val fullBody = person(2, FloatRect(250f, 20f, 500f, 350f)).copy(
                mask = sourceRectMask(mapper, FloatRect(250f, 20f, 500f, 350f))
            )
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(faceOnly, fullBody),
                faceOnlyTrackIds = setOf(1),
                fullBodyTrackIds = setOf(2),
                ptsUs = 0L
            )
            assertTrue(result.readyForRender)
            assertEquals(setOf(1), result.fallbackTrackIds)
            val mask = assertNotNull(result.resolvedPrivacy?.privacyMask)
            val headX = faceOnly.bbox.centerX
            val headY = faceOnly.bbox.top + faceOnly.bbox.height * 0.14f
            assertTrue(
                pixelAtSource(mask, mapper, headX, headY) > 0,
                "FULL_BODY primary target must not carve secondary FACE_ONLY fallback"
            )
        }
    }

    @Test
    fun ambiguousFaceCandidatesFallBackToYoloHeadPrivacy() {
        withProcessor(
            FixedLocator(
                listOf(
                    FaceObservation(FloatRect(105f, 105f, 137f, 137f), 0.7f),
                    FaceObservation(FloatRect(119f, 111f, 151f, 143f), 0.99f)
                )
            )
        ) { processor, texture, mapper ->
            val target = person(9, FloatRect(220f, 40f, 420f, 350f))
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(9),
                ptsUs = 0L
            )
            assertTrue(result.readyForRender)
            assertEquals(setOf(9), result.fallbackTrackIds)
            assertTrue(result.detectedTrackIds.isEmpty())
            val mask = assertNotNull(result.resolvedPrivacy?.privacyMask)
            assertTrue(pixelAtSource(mask, mapper, target.bbox.centerX, target.bbox.top + target.bbox.height * 0.14f) > 0)
            assertEquals(0, pixelAtSource(mask, mapper, target.bbox.centerX, target.bbox.bottom - 20f))
        }
    }

    @Test
    fun missingRequestedTrackFailsClosedInsteadOfRenderingTransparent() {
        withProcessor(FixedLocator(emptyList())) { processor, texture, _ ->
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = emptyList(),
                faceOnlyTrackIds = setOf(99),
                ptsUs = 0L
            )
            assertFalse(result.readyForRender)
            assertEquals(setOf(99), result.unresolvedTrackIds)
        }
    }

    private fun withProcessor(
        locator: FaceLocator,
        block: (FaceOnlyPrivacyFrameProcessor, Int, ModelCoordinateMapper) -> Unit
    ) {
        val egl = EglCore()
        val surface = egl.createOffscreenSurface(FRAME_W, FRAME_H)
        egl.makeCurrent(surface)
        val bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        var texture = 0
        val mapper = ModelCoordinateMapper(FRAME_W, FRAME_H, 640, 160)
        val processor = FaceOnlyPrivacyFrameProcessor(locator = locator, mapper = mapper)
        try {
            texture = create2dTexture(bitmap)
            block(processor, texture, mapper)
        } finally {
            processor.close()
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            bitmap.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    private fun person(id: Int, bbox: FloatRect) = TrackedPerson(
        id = id,
        bbox = bbox,
        mask = null,
        confidence = 0.95f,
        state = TrackState.ACTIVE,
        observedThisFrame = true,
        footY = bbox.bottom
    )

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

    private fun pixelAtSource(
        mask: com.danceanon.native.inference.NativeMask,
        mapper: ModelCoordinateMapper,
        x: Float,
        y: Float
    ): Int {
        val px = mapper.sourceToProtoX(x).roundToInt().coerceIn(0, mask.width - 1)
        val py = mapper.sourceToProtoY(y).roundToInt().coerceIn(0, mask.height - 1)
        return mask.buffer.get(py * mask.width + px).toInt() and 0xFF
    }

    private fun sourceRectMask(
        mapper: ModelCoordinateMapper,
        rect: FloatRect
    ): com.danceanon.native.inference.NativeMask {
        val width = mapper.protoSize
        val height = mapper.protoSize
        val buffer = ByteBuffer.allocateDirect(width * height)
        val left = mapper.sourceToProtoX(rect.left).roundToInt().coerceIn(0, width - 1)
        val right = mapper.sourceToProtoX(rect.right).roundToInt().coerceIn(0, width - 1)
        val top = mapper.sourceToProtoY(rect.top).roundToInt().coerceIn(0, height - 1)
        val bottom = mapper.sourceToProtoY(rect.bottom).roundToInt().coerceIn(0, height - 1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(if (x in left..right && y in top..bottom) 255.toByte() else 0)
            }
        }
        buffer.rewind()
        return com.danceanon.native.inference.NativeMask(
            width = width,
            height = height,
            buffer = buffer,
            originalWidth = mapper.srcWidth,
            originalHeight = mapper.srcHeight,
            mapper = mapper
        )
    }

    private class FixedLocator(private val observations: List<FaceObservation>) : FaceLocator {
        override fun detectRgbaTopDown(rgba: ByteBuffer, width: Int, height: Int): FaceLocatorResult =
            FaceLocatorResult(observations = observations, inferenceMs = 1.0)

        override fun close() = Unit
    }

    companion object {
        private const val FRAME_W = 640
        private const val FRAME_H = 360
    }
}
