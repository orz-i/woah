package art.gaoge.dance.engine.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import art.gaoge.dance.engine.bridge.EffectConfigDto
import art.gaoge.dance.engine.bridge.FollowConfigDto
import art.gaoge.dance.engine.camera.ReframeGeometry
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.render.EglCore
import art.gaoge.dance.engine.render.FaceStickerPlacement
import art.gaoge.dance.engine.render.GlRenderer
import art.gaoge.dance.engine.render.SourceTextureType
import art.gaoge.dance.engine.render.TextureRenderTarget
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
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
            // An asymmetric, unprotected source patch also catches a
            // regression that mirrors only the privacy masks.
            for (y in 20 until 55) {
                for (x in 270 until 305) setPixel(x, y, Color.GREEN)
            }
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

            fun draw(mirror: Boolean = false, sticker: Boolean = false) {
                renderer.render(
                    frameTexture = baseTexture,
                    texMatrix = null,
                    persons = listOf(primary),
                    selectedPersonIds = setOf(1),
                    effects = if (sticker) solidRedEffects().copy(
                        faceStickerEnabled = true,
                        stickerAssetId = "builtin:sunglasses"
                    ) else solidRedEffects(),
                    follow = disabledFollow(),
                    presentationTimeUs = 0L,
                    textureType = SourceTextureType.TEXTURE_2D,
                    additionalResolvedPrivacy = secondary,
                    faceStickerPlacements = if (sticker) listOf(
                        FaceStickerPlacement(
                            trackId = 2,
                            sourceRect = FloatRect(420f, 35f, 540f, 145f),
                            sourceWidth = FRAME_W,
                            sourceHeight = FRAME_H,
                            source = FacePrivacyRegionSource.DETECTED_FACE
                        )
                    ) else emptyList(),
                    mirrorHorizontally = mirror
                )
            }

            draw()
            val output = assertNotNull(renderer.captureRenderedFrame())
            try {
                assertRed(output, 150, 210, "primary full-body region")
                assertRed(output, 480, 90, "secondary face-only region")
                assertWhite(output, 480, 250, "secondary lower body must remain clear")
                assertWhite(output, 340, 180, "unselected gap must remain clear")
                draw(mirror = true)
                val mirrored = assertNotNull(renderer.captureRenderedFrame())
                try {
                    assertRed(mirrored, FRAME_W - 1 - 150, 210, "mirrored full-body region")
                    assertRed(mirrored, FRAME_W - 1 - 480, 90, "mirrored face-only region")
                    assertMirrored(output, mirrored)
                } finally {
                    mirrored.recycle()
                }
            } finally {
                output.recycle()
            }

            draw(sticker = true)
            val stickerOutput = assertNotNull(renderer.captureRenderedFrame())
            try {
                draw(mirror = true, sticker = true)
                val mirrored = assertNotNull(renderer.captureRenderedFrame())
                try {
                    assertMirrored(stickerOutput, mirrored)
                } finally {
                    mirrored.recycle()
                }
            } finally {
                stickerOutput.recycle()
            }

            TextureRenderTarget(FRAME_W, FRAME_H).use { target ->
                val previous = target.bind()
                try {
                    draw(sticker = true)
                } finally {
                    target.restore(previous)
                }
                // Off-center crop of a complete protected frame, as in export.
                val crop = ReframeGeometry.textureMatrixForScreenGlCrop(
                    ReframeGeometry.visualTopLeftToScreenGl(FloatRect(0.05f, 0f, 0.78f, 1f))
                )
                renderer.renderBase(target.textureId, crop, SourceTextureType.TEXTURE_2D)
                val cropped = assertNotNull(renderer.captureRenderedFrame())
                try {
                    renderer.renderBase(
                        target.textureId, crop, SourceTextureType.TEXTURE_2D,
                        mirrorHorizontally = true
                    )
                    val mirrored = assertNotNull(renderer.captureRenderedFrame())
                    try {
                        assertMirrored(cropped, mirrored)
                    } finally {
                        mirrored.recycle()
                    }
                } finally {
                    cropped.recycle()
                }
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

    private fun assertMirrored(normal: Bitmap, mirrored: Bitmap) {
        var count = 0
        var matches = 0
        var changed = 0
        for (y in 0 until normal.height step 3) {
            for (x in 0 until normal.width step 3) {
                val a = normal.getPixel(x, y)
                val b = mirrored.getPixel(normal.width - 1 - x, y)
                if (a != mirrored.getPixel(x, y)) changed++
                if (abs(Color.red(a) - Color.red(b)) <= 8 &&
                    abs(Color.green(a) - Color.green(b)) <= 8 &&
                    abs(Color.blue(a) - Color.blue(b)) <= 8) {
                    matches++
                }
                count++
            }
        }
        assertTrue(changed > 0, "mirror must change asymmetric content")
        assertTrue(matches.toDouble() / count >= 0.99, "reflected pixels: $matches/$count")
    }

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
