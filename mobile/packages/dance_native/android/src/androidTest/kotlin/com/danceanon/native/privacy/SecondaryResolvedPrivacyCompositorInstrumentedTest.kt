package com.danceanon.native.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.FollowConfigDto
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SecondaryResolvedPrivacyCompositorInstrumentedTest {
    @Test
    fun fullBodyPrimaryAndSecondaryFacePrivacyRenderTogether() {
        val egl = EglCore()
        val surface = egl.createOffscreenSurface(FRAME_W, FRAME_H)
        egl.makeCurrent(surface)
        val base = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val renderer = GlRenderer()
        var baseTexture = 0

        try {
            baseTexture = create2dTexture(base)
            renderer.initialize(FRAME_W, FRAME_H)
            val mapper = ModelCoordinateMapper(FRAME_W, FRAME_H, 640, 160)

            val bodyMask = assertNotNull(
                FacePrivacyMaskBuilder.build(
                    listOf(
                        FacePrivacyEllipse(
                            centerX = 150f,
                            centerY = 210f,
                            radiusX = 90f,
                            radiusY = 135f,
                            source = FacePrivacyRegionSource.YOLO_HEAD_FALLBACK
                        )
                    ),
                    mapper
                )
            )
            val primary = TrackedPerson(
                id = 1,
                bbox = FloatRect(60f, 70f, 240f, 350f),
                mask = bodyMask,
                confidence = 0.95f,
                state = TrackState.ACTIVE,
                observedThisFrame = true,
                footY = 350f
            )

            val faceMask = assertNotNull(
                FacePrivacyMaskBuilder.build(
                    listOf(
                        FacePrivacyEllipse(
                            centerX = 480f,
                            centerY = 90f,
                            radiusX = 60f,
                            radiusY = 55f,
                            source = FacePrivacyRegionSource.DETECTED_FACE
                        )
                    ),
                    mapper
                )
            )
            val secondary = ResolvedCompositorMasks(
                privacyMask = faceMask,
                occluderMask = null,
                hasPrivacy = true,
                hasOccluder = false
            )

            renderer.render(
                frameTexture = baseTexture,
                texMatrix = null,
                persons = listOf(primary),
                selectedPersonIds = setOf(1),
                effects = solidRedEffects(),
                follow = disabledFollow(),
                presentationTimeUs = 0L,
                textureType = SourceTextureType.TEXTURE_2D,
                additionalResolvedPrivacy = secondary
            )

            val output = assertNotNull(renderer.captureRenderedFrame())
            try {
                assertRed(output, 150, 210, "primary full-body region")
                assertRed(output, 480, 90, "secondary face-only region")
                assertWhite(output, 480, 250, "secondary lower body must remain clear")
                assertWhite(output, 340, 180, "unselected gap must remain clear")
            } finally {
                output.recycle()
            }
        } finally {
            renderer.close()
            if (baseTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(baseTexture), 0)
            base.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
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

    private fun assertRed(bitmap: Bitmap, x: Int, y: Int, label: String) {
        val color = bitmap.getPixel(x, y)
        assertTrue(Color.red(color) >= 220, "$label red=${Color.red(color)}")
        assertTrue(Color.green(color) <= 40, "$label green=${Color.green(color)}")
        assertTrue(Color.blue(color) <= 40, "$label blue=${Color.blue(color)}")
    }

    private fun assertWhite(bitmap: Bitmap, x: Int, y: Int, label: String) {
        val color = bitmap.getPixel(x, y)
        assertTrue(abs(Color.red(color) - 255) <= 8, "$label red=${Color.red(color)}")
        assertTrue(abs(Color.green(color) - 255) <= 8, "$label green=${Color.green(color)}")
        assertTrue(abs(Color.blue(color) - 255) <= 8, "$label blue=${Color.blue(color)}")
    }

    companion object {
        private const val FRAME_W = 640
        private const val FRAME_H = 360
    }
}
