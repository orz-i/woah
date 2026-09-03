package com.danceanon.native.render

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.danceanon.native.bridge.EffectConfigDto
import com.danceanon.native.bridge.FollowConfigDto
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GlRenderer : FrameRenderer {

    private class ProgramLocations(
        val programId: Int,
        val aPositionLoc: Int,
        val aTexCoordLoc: Int,
        val uTexMatrixLoc: Int,
        val uCropRectLoc: Int,
        val uMaskCropRectLoc: Int,
        val uOccluderCropRectLoc: Int,
        val uHasMaskLoc: Int,
        val uHasOccluderLoc: Int,
        val uFillModeLoc: Int,
        val uFillColorLoc: Int,
        val uBorderColorLoc: Int,
        val uGradientColorLoc: Int,
        val uOpacityLoc: Int,
        val uBorderWidthLoc: Int,
        val uBlurRadiusLoc: Int,
        val uSkinWhitenLoc: Int,
        val uLegStretchEnabledLoc: Int,
        val uLegStretchLoc: Int,
        val uLegZoneTopLoc: Int,
        val uLegZoneBottomLoc: Int,
        val uHasStickerLoc: Int,
        val uStickerRectLoc: Int,
        val uTexelSizeLoc: Int,
        val uFootYLoc: Int,
        val uBaseTextureLoc: Int,
        val uMaskTextureLoc: Int,
        val uOccluderTextureLoc: Int,
        val uStickerTextureLoc: Int
    )

    private var oesProgram: ProgramLocations? = null
    private var texture2DProgram: ProgramLocations? = null
    private var stickerOverlayProgram: ProgramLocations? = null

    private var vertexBuffer: FloatBuffer? = null
    private var width = 0
    private var height = 0
    private var maskTextureId = 0
    private var occluderTextureId = 0
    private var stickerTextureId = 0
    private var loadedStickerAssetId: String? = null
    private var stickerTextureLoaded = false
    private var captureBuffer: ByteBuffer? = null
    private var mergedMaskBuffer: ByteBuffer? = null
    private var mergedMaskCapacity = 0

    private val follower = com.danceanon.native.camera.SmoothFollower()
    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    private val bitmapTextureMatrix = floatArrayOf(
        1f,  0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f,  0f, 1f, 0f,
        0f,  1f, 0f, 1f
    )

    private fun resolveTextureMatrix(
        textureType: SourceTextureType,
        provided: FloatArray?
    ): FloatArray {
        if (provided != null) return provided
        return when (textureType) {
            SourceTextureType.TEXTURE_2D -> bitmapTextureMatrix
            SourceTextureType.OES -> identityMatrix
        }
    }

    private fun defaultLetterboxSamplingRect(
        sourceWidth: Int,
        sourceHeight: Int
    ): com.danceanon.native.inference.FloatRect {
        val maxDim = maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        val downW = sourceWidth * 640f / maxDim
        val downH = sourceHeight * 640f / maxDim
        val letterScale = minOf(640f / downW, 640f / downH)
        val scaledW = downW * letterScale
        val scaledH = downH * letterScale
        val padLeft = (640f - scaledW) / 2f
        val padTop = (640f - scaledH) / 2f
        return com.danceanon.native.inference.FloatRect(
            left = padLeft / 640f,
            top = padTop / 640f,
            right = (padLeft + scaledW) / 640f,
            bottom = (padTop + scaledH) / 640f
        )
    }

    companion object {

        fun computeTransformMatrix(stMatrix: FloatArray, rotation: Int = 0): FloatArray {
            val isDegenerate = stMatrix[0] == 0f && stMatrix[5] == 0f && stMatrix[10] == 0f && stMatrix[15] == 0f
            if (isDegenerate) {
                return floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f
                )
            }
            // SurfaceTexture.getTransformMatrix already contains decoder orientation metadata in Android 7.0+.
            // Double rotation pushes texture coordinates outside [0, 1] resulting in black screen.
            return stMatrix
        }


        fun checkGlError(stage: String) {

            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                android.util.Log.e("GlRenderer", "GL Error after $stage: 0x${Integer.toHexString(error)}")
            }
        }

        fun createDefaultStickerBitmap(assetId: String? = null): Bitmap? {
            return try {
                val size = 128
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) ?: return null
                val canvas = android.graphics.Canvas(bitmap)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

                val stickerKind = assetId?.removePrefix("builtin:") ?: "sunglasses"
                val faceColor = when (stickerKind) {
                    "blush" -> android.graphics.Color.rgb(255, 154, 158)
                    "panda" -> android.graphics.Color.rgb(250, 250, 248)
                    "cat" -> android.graphics.Color.rgb(255, 218, 166)
                    "bear" -> android.graphics.Color.rgb(190, 132, 88)
                    else -> android.graphics.Color.rgb(255, 215, 0)
                }

                paint.color = faceColor
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

                if (stickerKind == "panda" || stickerKind == "cat" || stickerKind == "bear") {
                    paint.color = if (stickerKind == "panda") {
                        android.graphics.Color.rgb(30, 30, 30)
                    } else {
                        faceColor
                    }
                    val earRadius = if (stickerKind == "cat") 19f else 21f
                    canvas.drawCircle(28f, 25f, earRadius, paint)
                    canvas.drawCircle(100f, 25f, earRadius, paint)
                }

                // Dark border outline
                paint.color = android.graphics.Color.argb(255, 30, 30, 30)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 6f
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

                paint.style = android.graphics.Paint.Style.FILL
                paint.color = android.graphics.Color.BLACK
                if (stickerKind == "sunglasses") {
                    canvas.drawRoundRect(24f, 40f, 60f, 65f, 8f, 8f, paint)
                    canvas.drawRoundRect(68f, 40f, 104f, 65f, 8f, 8f, paint)
                    canvas.drawRect(56f, 48f, 72f, 56f, paint)
                } else if (stickerKind == "panda") {
                    canvas.drawOval(android.graphics.RectF(27f, 38f, 57f, 70f), paint)
                    canvas.drawOval(android.graphics.RectF(71f, 38f, 101f, 70f), paint)
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawCircle(43f, 54f, 6f, paint)
                    canvas.drawCircle(85f, 54f, 6f, paint)
                } else {
                    canvas.drawCircle(43f, 53f, 6f, paint)
                    canvas.drawCircle(85f, 53f, 6f, paint)
                }

                if (stickerKind == "blush") {
                    paint.color = android.graphics.Color.rgb(235, 80, 100)
                    canvas.drawCircle(27f, 72f, 10f, paint)
                    canvas.drawCircle(101f, 72f, 10f, paint)
                    paint.color = android.graphics.Color.BLACK
                }

                // Smile
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                val mouthRect = android.graphics.RectF(38f, 62f, 90f, 98f)
                canvas.drawArc(mouthRect, 20f, 140f, false, paint)

                bitmap
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun initialize(width: Int, height: Int) {
        this.width = width
        this.height = height

        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"
        android.util.Log.i("GlRenderer", "[Telemetry] GL initialized: Vendor=$vendor, Renderer=$renderer, Version=$version, Canvas=${width}x${height}")

        oesProgram = buildProgram(GlShaders.FRAGMENT_SHADER_OES)
        texture2DProgram = buildProgram(GlShaders.FRAGMENT_SHADER_2D)
        stickerOverlayProgram = buildProgram(GlShaders.STICKER_OVERLAY_FRAGMENT_SHADER)
        checkGlError("buildPrograms")


        // Quad vertices & texcoords (x, y, u, v)
        val vertices = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        // Initialize 2D auxiliary textures (privacy mask, occluder mask, face sticker)
        val textures = IntArray(3)
        GLES20.glGenTextures(3, textures, 0)
        maskTextureId = textures[0]
        occluderTextureId = textures[1]
        stickerTextureId = textures[2]

        val blankPixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(0.toByte()); put(0.toByte()); put(0.toByte()); put(0.toByte())
            rewind()
        }
        for (texId in listOf(maskTextureId, occluderTextureId, stickerTextureId)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            blankPixel.rewind()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, blankPixel)
        }
        checkGlError("initAuxTextures")
        // captureBuffer is lazily allocated on first capture call to avoid reserving 8~33MB during export
    }

    private fun getOrCreateCaptureBuffer(): ByteBuffer {
        val reqCapacity = width * height * 4
        var buf = captureBuffer
        if (buf == null || buf.capacity() < reqCapacity) {
            buf = ByteBuffer.allocateDirect(reqCapacity).order(ByteOrder.nativeOrder())
            captureBuffer = buf
        }
        return buf
    }

    private fun ensureStickerTexture(assetId: String?): Int {
        if (stickerTextureId != 0 && stickerTextureLoaded && loadedStickerAssetId == assetId) {
            return stickerTextureId
        }

        var bitmap: Bitmap? = null
        try {
            if (!assetId.isNullOrBlank() && assetId.startsWith("builtin:")) {
                bitmap = createDefaultStickerBitmap(assetId)
            } else if (!assetId.isNullOrBlank()) {
                val f = java.io.File(assetId)
                if (f.exists() && f.length() > 0) {
                    bitmap = android.graphics.BitmapFactory.decodeFile(assetId)
                }
            }
        } catch (_: Throwable) {}

        if (bitmap == null) {
            bitmap = createDefaultStickerBitmap(assetId)
        }

        if (stickerTextureId == 0) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            stickerTextureId = tex[0]
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stickerTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        if (bitmap != null) {
            try {
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
            } catch (_: Throwable) {}
        } else {
            val fallbackBuf = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until 16 * 16) {
                fallbackBuf.put(255.toByte()) // R
                fallbackBuf.put(215.toByte()) // G
                fallbackBuf.put(0.toByte())   // B
                fallbackBuf.put(255.toByte()) // A
            }
            fallbackBuf.rewind()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 16, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fallbackBuf)
        }

        loadedStickerAssetId = assetId
        stickerTextureLoaded = true
        return stickerTextureId
    }

    private fun buildProgram(fragmentShaderSource: String): ProgramLocations {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, GlShaders.VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

        val programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vShader)
        GLES20.glAttachShader(programId, fShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(programId)
            android.util.Log.e("GlRenderer", "Program link failed: $log")
            throw RuntimeException("Program link failed: $log")
        }

        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)

        return ProgramLocations(
            programId = programId,
            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition"),
            aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord"),
            uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix"),
            uCropRectLoc = GLES20.glGetUniformLocation(programId, "uCropRect"),
            uMaskCropRectLoc = GLES20.glGetUniformLocation(programId, "uMaskCropRect"),
            uOccluderCropRectLoc = GLES20.glGetUniformLocation(programId, "uOccluderCropRect"),
            uHasMaskLoc = GLES20.glGetUniformLocation(programId, "uHasMask"),
            uHasOccluderLoc = GLES20.glGetUniformLocation(programId, "uHasOccluder"),
            uFillModeLoc = GLES20.glGetUniformLocation(programId, "uFillMode"),
            uFillColorLoc = GLES20.glGetUniformLocation(programId, "uFillColor"),
            uBorderColorLoc = GLES20.glGetUniformLocation(programId, "uBorderColor"),
            uGradientColorLoc = GLES20.glGetUniformLocation(programId, "uGradientColor"),
            uOpacityLoc = GLES20.glGetUniformLocation(programId, "uOpacity"),
            uBorderWidthLoc = GLES20.glGetUniformLocation(programId, "uBorderWidth"),
            uBlurRadiusLoc = GLES20.glGetUniformLocation(programId, "uBlurRadius"),
            uSkinWhitenLoc = GLES20.glGetUniformLocation(programId, "uSkinWhiten"),
            uLegStretchEnabledLoc = GLES20.glGetUniformLocation(programId, "uLegStretchEnabled"),
            uLegStretchLoc = GLES20.glGetUniformLocation(programId, "uLegStretch"),
            uLegZoneTopLoc = GLES20.glGetUniformLocation(programId, "uLegZoneTop"),
            uLegZoneBottomLoc = GLES20.glGetUniformLocation(programId, "uLegZoneBottom"),
            uHasStickerLoc = GLES20.glGetUniformLocation(programId, "uHasSticker"),
            uStickerRectLoc = GLES20.glGetUniformLocation(programId, "uStickerRect"),
            uTexelSizeLoc = GLES20.glGetUniformLocation(programId, "uTexelSize"),
            uFootYLoc = GLES20.glGetUniformLocation(programId, "uFootY"),
            uBaseTextureLoc = GLES20.glGetUniformLocation(programId, "uBaseTexture"),
            uMaskTextureLoc = GLES20.glGetUniformLocation(programId, "uMaskTexture"),
            uOccluderTextureLoc = GLES20.glGetUniformLocation(programId, "uOccluderTexture"),
            uStickerTextureLoc = GLES20.glGetUniformLocation(programId, "uStickerTexture")
        )
    }

    fun renderBase(
        frameTexture: Int,
        texMatrix: FloatArray?,
        textureType: SourceTextureType = SourceTextureType.OES
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val prog = if (textureType == SourceTextureType.OES) oesProgram else texture2DProgram
        if (prog == null) return

        GLES20.glUseProgram(prog.programId)

        val matrix = resolveTextureMatrix(textureType, texMatrix)
        if (prog.uTexMatrixLoc >= 0) GLES20.glUniformMatrix4fv(prog.uTexMatrixLoc, 1, false, matrix, 0)
        if (prog.uCropRectLoc >= 0) GLES20.glUniform4f(prog.uCropRectLoc, 0f, 0f, 1f, 1f)
        if (prog.uMaskCropRectLoc >= 0) GLES20.glUniform4f(prog.uMaskCropRectLoc, 0f, 0f, 1f, 1f)
        if (prog.uOccluderCropRectLoc >= 0) GLES20.glUniform4f(prog.uOccluderCropRectLoc, 0f, 0f, 1f, 1f)
        if (prog.uHasMaskLoc >= 0) GLES20.glUniform1i(prog.uHasMaskLoc, 0)
        if (prog.uHasOccluderLoc >= 0) GLES20.glUniform1i(prog.uHasOccluderLoc, 0)
        if (prog.uHasStickerLoc >= 0) GLES20.glUniform1i(prog.uHasStickerLoc, 0)

        val target = if (textureType == SourceTextureType.OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, frameTexture)
        if (prog.uBaseTextureLoc >= 0) GLES20.glUniform1i(prog.uBaseTextureLoc, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        if (prog.uMaskTextureLoc >= 0) GLES20.glUniform1i(prog.uMaskTextureLoc, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, occluderTextureId)
        if (prog.uOccluderTextureLoc >= 0) GLES20.glUniform1i(prog.uOccluderTextureLoc, 2)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stickerTextureId)
        if (prog.uStickerTextureLoc >= 0) GLES20.glUniform1i(prog.uStickerTextureLoc, 3)

        drawQuad(prog)
        checkGlError("renderBase")
    }

    fun captureFrameForInference(): Bitmap? {
        val buf = getOrCreateCaptureBuffer()
        buf.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val fullBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.rewind()
        fullBmp.copyPixelsFromBuffer(buf)

        val scale = 640f / maxOf(width, height).coerceAtLeast(1)
        val matrix = Matrix().apply {
            postScale(scale, -scale)
        }
        val scaledBmp = Bitmap.createBitmap(fullBmp, 0, 0, width, height, matrix, true)
        fullBmp.recycle()
        return scaledBmp
    }

    fun captureRenderedFrame(): Bitmap? {
        val buf = getOrCreateCaptureBuffer()
        buf.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val fullBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.rewind()
        fullBmp.copyPixelsFromBuffer(buf)

        val flipMatrix = Matrix().apply {
            postScale(1f, -1f)
        }
        val visualBitmap = Bitmap.createBitmap(fullBmp, 0, 0, width, height, flipMatrix, true)
        if (visualBitmap !== fullBmp) {
            fullBmp.recycle()
        }
        return visualBitmap
    }


    fun render(
        frameTexture: Int,
        texMatrix: FloatArray? = null,
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        effects: EffectConfigDto,
        follow: FollowConfigDto,
        presentationTimeUs: Long,
        textureType: SourceTextureType = SourceTextureType.OES,
        freshPrivacyClassEvidence: List<com.danceanon.native.tracking.FreshPrivacyClassEvidence> = emptyList(),
        freshSelectedCoveredTrackIds: Set<Int> = emptySet(),
        suppressedSelectedPrivacyTrackIds: Set<Int> = emptySet(),
        preferFreshPrivacyClassPrimary: Boolean = false,
        expectedSelectedPrivacyCount: Int = 0,
        maxFallbackObservationAgeFrames: Int = 15,
        conservativePrimaryUnobservedOccluderPolicy: Boolean = false,
        additionalResolvedPrivacy: com.danceanon.native.privacy.ResolvedCompositorMasks? = null,
        faceStickerPlacements: List<FaceStickerPlacement> = emptyList()
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val prog = if (textureType == SourceTextureType.OES) oesProgram else texture2DProgram
        if (prog == null) return

        GLES20.glUseProgram(prog.programId)

        val matrix = resolveTextureMatrix(textureType, texMatrix)
        if (prog.uTexMatrixLoc >= 0) GLES20.glUniformMatrix4fv(prog.uTexMatrixLoc, 1, false, matrix, 0)


        // Parse Fill Color ARGB
        val fc = effects.fillColorArgb.toInt()
        val fa = ((fc shr 24) and 0xFF) / 255f
        val fr = ((fc shr 16) and 0xFF) / 255f
        val fg = ((fc shr 8) and 0xFF) / 255f
        val fb = (fc and 0xFF) / 255f
        if (prog.uFillColorLoc >= 0) GLES20.glUniform4f(prog.uFillColorLoc, fr, fg, fb, fa)

        // Parse Border/Outline Color ARGB
        val oc = effects.borderColorArgb.toInt()
        val oa = ((oc shr 24) and 0xFF) / 255f
        val or = ((oc shr 16) and 0xFF) / 255f
        val og = ((oc shr 8) and 0xFF) / 255f
        val ob = (oc and 0xFF) / 255f
        if (prog.uBorderColorLoc >= 0) GLES20.glUniform4f(prog.uBorderColorLoc, or, og, ob, oa)

        // Parse Gradient Color ARGB
        if (prog.uGradientColorLoc >= 0) GLES20.glUniform4f(prog.uGradientColorLoc, 0.5f, 0.2f, 0.9f, 1f)

        // Composed Fill Mode: 0: solid, 2: blur, 3: gradient, 5: mosaic
        val fillModeInt = when (effects.fillMode.lowercase()) {
            "solid" -> 0
            "blur" -> 2
            "gradient" -> 3
            "mosaic" -> 5
            else -> 0
        }
        if (prog.uFillModeLoc >= 0) GLES20.glUniform1i(prog.uFillModeLoc, fillModeInt)
        if (prog.uOpacityLoc >= 0) GLES20.glUniform1f(prog.uOpacityLoc, effects.opacity.toFloat().coerceIn(0.0f, 1.0f))
        if (prog.uBorderWidthLoc >= 0) GLES20.glUniform1f(prog.uBorderWidthLoc, effects.borderWidth.toFloat())
        if (prog.uBlurRadiusLoc >= 0) GLES20.glUniform1f(prog.uBlurRadiusLoc, effects.blurStrength.toFloat().coerceAtLeast(1.0f))
        if (prog.uSkinWhitenLoc >= 0) GLES20.glUniform1f(prog.uSkinWhitenLoc, effects.skinWhiten.toFloat())

        val legStretchEnabled = if (effects.legStretchEnabled) 1 else 0
        val legStretch = (1.0 + effects.legStretch).toFloat().coerceAtLeast(1.0f)
        if (prog.uLegStretchEnabledLoc >= 0) GLES20.glUniform1i(prog.uLegStretchEnabledLoc, legStretchEnabled)
        if (prog.uLegStretchLoc >= 0) GLES20.glUniform1f(prog.uLegStretchLoc, legStretch)
        if (prog.uLegZoneTopLoc >= 0) GLES20.glUniform1f(prog.uLegZoneTopLoc, effects.legZoneTop.toFloat().coerceIn(0f, 1f))
        if (prog.uLegZoneBottomLoc >= 0) GLES20.glUniform1f(prog.uLegZoneBottomLoc, effects.legZoneBottom.toFloat().coerceIn(0f, 1f))
        if (prog.uTexelSizeLoc >= 0) GLES20.glUniform2f(prog.uTexelSizeLoc, 1f / width.coerceAtLeast(1), 1f / height.coerceAtLeast(1))

        // Follow Crop Mapping
        val cropRect = if (follow.enabled) {
            val targetId = follow.targetPersonId?.toInt() ?: selectedPersonIds.firstOrNull() ?: persons.firstOrNull()?.id
            val target = persons.firstOrNull { it.id == targetId }
            if (target != null) {
                val refW = maxOf(1, target.mask?.originalWidth ?: width)
                val refH = maxOf(1, target.mask?.originalHeight ?: height)
                val cx = (target.bbox.centerX / refW.toFloat()).coerceIn(0f, 1f)
                val cy = (target.bbox.centerY / refH.toFloat()).coerceIn(0f, 1f)
                follower.update(cx, cy, follow.smoothFactor.toFloat())
            }
            follower.computeCropRect(follow.zoom.toFloat())
        } else {
            com.danceanon.native.inference.FloatRect(0f, 0f, 1f, 1f)
        }

        if (prog.uCropRectLoc >= 0) {
            GLES20.glUniform4f(
                prog.uCropRectLoc,
                cropRect.left, cropRect.top, cropRect.right, cropRect.bottom
            )
        }

        val primaryResolved = com.danceanon.native.privacy.PrivacyOcclusionResolver.resolveMasks(
            persons = persons,
            selectedPersonIds = selectedPersonIds,
            ptsUs = presentationTimeUs,
            freshClassEvidence = freshPrivacyClassEvidence,
            freshSelectedCoveredTrackIds = freshSelectedCoveredTrackIds,
            suppressedSelectedTrackIds = suppressedSelectedPrivacyTrackIds,
            preferFreshClassPrimary = preferFreshPrivacyClassPrimary,
            expectedSelectedCount = expectedSelectedPrivacyCount,
            maxFallbackObservationAgeFrames = maxFallbackObservationAgeFrames,
            conservativeUnobservedOccluderPolicy = conservativePrimaryUnobservedOccluderPolicy
        )
        val mergedResolved = if (additionalResolvedPrivacy == null) {
            primaryResolved
        } else {
            com.danceanon.native.privacy.PrivacyOcclusionResolver.mergeResolvedMasks(
                listOf(primaryResolved, additionalResolvedPrivacy)
            )
        }
        val renderFacePrivacyAsSticker =
            effects.faceStickerEnabled &&
                faceStickerPlacements.isNotEmpty() &&
                additionalResolvedPrivacy?.privacyMask != null
        // FACE_ONLY is visually rendered by the privacy-sticker pass. Keep the
        // secondary face mask out of the main fill compositor so the sticker
        // replaces the solid/blur/mosaic block instead of being layered on it.
        // If the face mask is unexpectedly missing we fall back to the merged
        // compositor path, preserving fail-closed privacy behavior.
        val resolved = if (renderFacePrivacyAsSticker) primaryResolved else mergedResolved
        val hasSelected = resolved.hasPrivacy
        val hasOccluder = resolved.hasOccluder

        // Mask sampling rect for privacyMask
        val privacySamplingRect = resolved.privacyMask?.samplingRect
            ?: defaultLetterboxSamplingRect(width, height)
        if (prog.uMaskCropRectLoc >= 0) {
            GLES20.glUniform4f(
                prog.uMaskCropRectLoc,
                privacySamplingRect.left,
                privacySamplingRect.top,
                privacySamplingRect.right,
                privacySamplingRect.bottom
            )
        }

        // Mask sampling rect for occluderMask
        val occluderSamplingRect = resolved.occluderMask?.samplingRect
            ?: defaultLetterboxSamplingRect(width, height)
        if (prog.uOccluderCropRectLoc >= 0) {
            GLES20.glUniform4f(
                prog.uOccluderCropRectLoc,
                occluderSamplingRect.left,
                occluderSamplingRect.top,
                occluderSamplingRect.right,
                occluderSamplingRect.bottom
            )
        }

        if (prog.uHasMaskLoc >= 0) GLES20.glUniform1i(prog.uHasMaskLoc, if (hasSelected) 1 else 0)
        if (prog.uHasOccluderLoc >= 0) GLES20.glUniform1i(prog.uHasOccluderLoc, if (hasOccluder) 1 else 0)


        // Setup base texture
        val target = if (textureType == SourceTextureType.OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, frameTexture)
        if (prog.uBaseTextureLoc >= 0) GLES20.glUniform1i(prog.uBaseTextureLoc, 0)

        // Upload and bind privacy mask texture to Texture 1 if present
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        if (hasSelected && resolved.privacyMask != null) {
            val pMask = resolved.privacyMask
            pMask.buffer.rewind()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                pMask.width, pMask.height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pMask.buffer
            )
            val selectedPersons = persons.filter { selectedPersonIds.contains(it.id) }
            val lowestFoot = selectedPersons.maxOfOrNull { it.bbox.bottom } ?: height.toFloat()
            if (prog.uFootYLoc >= 0) GLES20.glUniform1f(prog.uFootYLoc, lowestFoot / height.toFloat())
        }
        if (prog.uMaskTextureLoc >= 0) GLES20.glUniform1i(prog.uMaskTextureLoc, 1)

        // Upload and bind occluder mask texture to Texture 2 if present
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, occluderTextureId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        if (hasOccluder && resolved.occluderMask != null) {
            val oMask = resolved.occluderMask
            oMask.buffer.rewind()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                oMask.width, oMask.height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, oMask.buffer
            )
        }
        if (prog.uOccluderTextureLoc >= 0) GLES20.glUniform1i(prog.uOccluderTextureLoc, 2)

        // Sticker effect runtime connection (bound to Texture 3)
        val selectedPersons = persons.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        val effectiveStickerTexId = if (effects.faceStickerEnabled && hasSelected && selectedPersons.isNotEmpty()) {
            ensureStickerTexture(effects.stickerAssetId)
        } else {
            stickerTextureId
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, effectiveStickerTexId)
        if (prog.uStickerTextureLoc >= 0) GLES20.glUniform1i(prog.uStickerTextureLoc, 3)

        if (effects.faceStickerEnabled && hasSelected && selectedPersons.isNotEmpty()) {
            val primaryPerson = selectedPersons[0]
            val refW = maxOf(1, primaryPerson.mask?.originalWidth ?: width)
            val refH = maxOf(1, primaryPerson.mask?.originalHeight ?: height)

            // Approximate head zone: top 25% of target person's bounding box
            val headZoneHeight = primaryPerson.bbox.height * 0.25f
            val headCenterX = primaryPerson.bbox.centerX
            val headCenterY = primaryPerson.bbox.top + headZoneHeight * 0.5f
            val scale = effects.stickerScale.toFloat().coerceIn(1.0f, 3.0f)
            val halfDim = (maxOf(primaryPerson.bbox.width * 0.35f, headZoneHeight * 0.6f) * scale).coerceAtLeast(10f)

            val sLeft = ((headCenterX - halfDim) / refW.toFloat()).coerceIn(0f, 1f)
            val sRight = ((headCenterX + halfDim) / refW.toFloat()).coerceIn(0f, 1f)
            val sTop = ((headCenterY - halfDim) / refH.toFloat()).coerceIn(0f, 1f)
            val sBottom = ((headCenterY + halfDim) / refH.toFloat()).coerceIn(0f, 1f)

            if (prog.uStickerRectLoc >= 0) GLES20.glUniform4f(prog.uStickerRectLoc, sLeft, sTop, sRight, sBottom)
            if (prog.uHasStickerLoc >= 0) GLES20.glUniform1i(prog.uHasStickerLoc, 1)
        } else {
            if (prog.uHasStickerLoc >= 0) GLES20.glUniform1i(prog.uHasStickerLoc, 0)
        }

        drawQuad(prog)
        if (renderFacePrivacyAsSticker) {
            renderFaceStickerOverlays(
                placements = faceStickerPlacements,
                textureMatrix = matrix,
                cropRect = cropRect,
                stickerPrivacy = requireNotNull(additionalResolvedPrivacy),
                effects = effects
            )
        }
        checkGlError("render")
    }

    private fun renderFaceStickerOverlays(
        placements: List<FaceStickerPlacement>,
        textureMatrix: FloatArray,
        cropRect: com.danceanon.native.inference.FloatRect,
        stickerPrivacy: com.danceanon.native.privacy.ResolvedCompositorMasks,
        effects: com.danceanon.native.bridge.EffectConfigDto
    ) {
        val prog = stickerOverlayProgram ?: return
        if (placements.isEmpty()) return
        val privacyMask = stickerPrivacy.privacyMask ?: return

        // Transparent asset holes are still privacy-safe because the shader
        // fills them with an opaque fallback color inside the resolved face mask.
        val stickerTexId = ensureStickerTexture(effects.stickerAssetId)
        GLES20.glUseProgram(prog.programId)
        if (prog.uTexMatrixLoc >= 0) {
            GLES20.glUniformMatrix4fv(prog.uTexMatrixLoc, 1, false, textureMatrix, 0)
        }
        if (prog.uCropRectLoc >= 0) {
            GLES20.glUniform4f(prog.uCropRectLoc, cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        }
        val maskRect = privacyMask.samplingRect
            ?: defaultLetterboxSamplingRect(privacyMask.originalWidth, privacyMask.originalHeight)
        if (prog.uMaskCropRectLoc >= 0) {
            GLES20.glUniform4f(prog.uMaskCropRectLoc, maskRect.left, maskRect.top, maskRect.right, maskRect.bottom)
        }
        val stickerOccluder = stickerPrivacy.occluderMask
        val occluderRect = stickerOccluder?.samplingRect
            ?: defaultLetterboxSamplingRect(privacyMask.originalWidth, privacyMask.originalHeight)
        if (prog.uOccluderCropRectLoc >= 0) {
            GLES20.glUniform4f(
                prog.uOccluderCropRectLoc,
                occluderRect.left,
                occluderRect.top,
                occluderRect.right,
                occluderRect.bottom
            )
        }
        if (prog.uHasMaskLoc >= 0) GLES20.glUniform1i(prog.uHasMaskLoc, 1)
        if (prog.uHasOccluderLoc >= 0) {
            GLES20.glUniform1i(prog.uHasOccluderLoc, if (stickerOccluder != null) 1 else 0)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        privacyMask.buffer.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            privacyMask.width, privacyMask.height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, privacyMask.buffer
        )
        if (prog.uMaskTextureLoc >= 0) GLES20.glUniform1i(prog.uMaskTextureLoc, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, occluderTextureId)
        if (stickerOccluder != null) {
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            stickerOccluder.buffer.rewind()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                stickerOccluder.width, stickerOccluder.height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, stickerOccluder.buffer
            )
        }
        if (prog.uOccluderTextureLoc >= 0) GLES20.glUniform1i(prog.uOccluderTextureLoc, 2)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stickerTexId)
        if (prog.uStickerTextureLoc >= 0) GLES20.glUniform1i(prog.uStickerTextureLoc, 3)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        try {
            val scale = effects.stickerScale.toFloat().coerceIn(0.5f, 3.0f)
            placements.sortedBy { it.trackId }.forEach { placement ->
                val refW = placement.sourceWidth.coerceAtLeast(1).toFloat()
                val refH = placement.sourceHeight.coerceAtLeast(1).toFloat()
                val rect = placement.sourceRect
                val cx = rect.centerX / refW
                val cy = rect.centerY / refH
                val halfW = (rect.width * 0.5f / refW) * scale
                val halfH = (rect.height * 0.5f / refH) * scale
                val left = (cx - halfW).coerceIn(0f, 1f)
                val right = (cx + halfW).coerceIn(0f, 1f)
                val top = (cy - halfH).coerceIn(0f, 1f)
                val bottom = (cy + halfH).coerceIn(0f, 1f)
                if (right - left <= 0.001f || bottom - top <= 0.001f) return@forEach
                if (prog.uStickerRectLoc >= 0) {
                    GLES20.glUniform4f(prog.uStickerRectLoc, left, top, right, bottom)
                }
                drawQuad(prog)
            }
        } finally {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    override fun render(
        frameTexture: Int,
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        effects: EffectConfigDto,
        follow: FollowConfigDto,
        presentationTimeUs: Long
    ) {
        render(frameTexture, null, persons, selectedPersonIds, effects, follow, presentationTimeUs, SourceTextureType.OES)
    }

    private fun drawQuad(prog: ProgramLocations) {
        val aPositionLoc = prog.aPositionLoc
        val aTexCoordLoc = prog.aTexCoordLoc

        if (aPositionLoc >= 0 && aTexCoordLoc >= 0) {
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            vertexBuffer?.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e("GlRenderer", "Shader compilation failed ($type): $log")
            throw RuntimeException("Shader compilation failed ($type): $log")
        }
        return shader
    }

    override fun close() {
        release()
    }

    fun release() {
        oesProgram?.let {
            if (it.programId != 0) {
                GLES20.glDeleteProgram(it.programId)
            }
        }
        oesProgram = null

        texture2DProgram?.let {
            if (it.programId != 0) {
                GLES20.glDeleteProgram(it.programId)
            }
        }
        texture2DProgram = null

        stickerOverlayProgram?.let {
            if (it.programId != 0) {
                GLES20.glDeleteProgram(it.programId)
            }
        }
        stickerOverlayProgram = null

        if (maskTextureId != 0) {
            val textures = intArrayOf(maskTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            maskTextureId = 0
        }

        if (occluderTextureId != 0) {
            val textures = intArrayOf(occluderTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            occluderTextureId = 0
        }

        if (stickerTextureId != 0) {
            val textures = intArrayOf(stickerTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            stickerTextureId = 0
            loadedStickerAssetId = null
            stickerTextureLoaded = false
        }

        captureBuffer = null
        mergedMaskBuffer = null
    }
}



