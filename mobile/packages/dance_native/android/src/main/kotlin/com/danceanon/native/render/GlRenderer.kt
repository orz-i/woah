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

    private var programId = 0
    private var vertexBuffer: FloatBuffer? = null
    private var width = 0
    private var height = 0
    private var maskTextureId = 0
    private var fboId = 0
    private var fboTexId = 0
    private val fboWidth = 640
    private val fboHeight = 640
    private var fboBuffer: ByteBuffer? = null
    private var captureBuffer: ByteBuffer? = null

    private val follower = com.danceanon.native.camera.SmoothFollower()
    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    override fun initialize(width: Int, height: Int) {
        this.width = width
        this.height = height

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, GlShaders.VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, GlShaders.FRAGMENT_SHADER)

        programId = GLES20.glCreateProgram()
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

        // Initialize mask texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        maskTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        initFboCapture()
    }

    private fun initFboCapture() {
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTexId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fboWidth, fboHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboBuffer = ByteBuffer.allocateDirect(fboWidth * fboHeight * 4).order(ByteOrder.nativeOrder())
        captureBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }

    fun captureFrameForInference(frameTexture: Int, texMatrix: FloatArray?): Bitmap? {
        val buf = fboBuffer ?: return null

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)
        val matrix = texMatrix ?: identityMatrix
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uTexMatrix"), 1, false, matrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, "uCropRect"), 0f, 0f, 1f, 1f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uHasMask"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uBaseTexture"), 0)

        val aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
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

        buf.rewind()
        GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val rawBmp = Bitmap.createBitmap(fboWidth, fboHeight, Bitmap.Config.ARGB_8888)
        buf.rewind()
        rawBmp.copyPixelsFromBuffer(buf)

        val flipMatrix = Matrix().apply { postScale(1f, -1f) }
        val rightSideUpBmp = Bitmap.createBitmap(rawBmp, 0, 0, fboWidth, fboHeight, flipMatrix, true)
        rawBmp.recycle()
        return rightSideUpBmp
    }

    fun captureRenderedFrame(): Bitmap? {
        val buf = captureBuffer ?: return null
        buf.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val fullBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.rewind()
        fullBmp.copyPixelsFromBuffer(buf)

        // Scale and flip vertically
        val scale = 640f / maxOf(width, height).coerceAtLeast(1)
        val matrix = Matrix().apply {
            postScale(scale, -scale)
        }
        val scaledBmp = Bitmap.createBitmap(fullBmp, 0, 0, width, height, matrix, true)
        fullBmp.recycle()
        return scaledBmp
    }

    fun render(
        frameTexture: Int,
        texMatrix: FloatArray? = null,
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        effects: EffectConfigDto,
        follow: FollowConfigDto,
        presentationTimeUs: Long
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        val matrix = texMatrix ?: identityMatrix
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uTexMatrix"), 1, false, matrix, 0)

        // Parse Fill Color ARGB
        val fc = effects.fillColorArgb.toInt()
        val fa = ((fc shr 24) and 0xFF) / 255f
        val fr = ((fc shr 16) and 0xFF) / 255f
        val fg = ((fc shr 8) and 0xFF) / 255f
        val fb = (fc and 0xFF) / 255f
        GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, "uFillColor"), fr, fg, fb, fa)

        // Parse Border/Outline Color ARGB
        val oc = effects.borderColorArgb.toInt()
        val oa = ((oc shr 24) and 0xFF) / 255f
        val or = ((oc shr 16) and 0xFF) / 255f
        val og = ((oc shr 8) and 0xFF) / 255f
        val ob = (oc and 0xFF) / 255f
        GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, "uOutlineColor"), or, og, ob, oa)

        // Parse Gradient Color ARGB
        GLES20.glUniform4f(GLES20.glGetUniformLocation(programId, "uGradientColor"), 0.5f, 0.2f, 0.9f, 1f)

        // Effect Mode: 0: solid, 1: outline, 2: blur, 3: gradient, 4: skin_whiten, 5: leg_stretch
        val effectMode = when (effects.fillMode.lowercase()) {
            "solid" -> if (effects.borderWidth > 0) 1 else 0
            "blur" -> 2
            "gradient" -> 3
            "mosaic" -> 2
            else -> if (effects.skinWhiten > 0) 4 else if (effects.legStretchEnabled) 5 else 0
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uEffectType"), effectMode)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uOpacity"), effects.opacity.toFloat().coerceIn(0.1f, 1.0f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uOutlineWidth"), effects.borderWidth.toFloat().coerceAtLeast(1.0f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uBlurRadius"), effects.blurStrength.toFloat().coerceAtLeast(1.0f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uSkinWhitenStrength"), effects.skinWhiten.toFloat())
        val legRatio = if (effects.legStretchEnabled) (1.0 + effects.legStretch).toFloat() else 1.0f
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uLegStretchRatio"), legRatio)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, "uTexelSize"), 1f / width.coerceAtLeast(1), 1f / height.coerceAtLeast(1))

        // Follow Crop Mapping
        val cropRect = if (follow.enabled && follow.targetPersonId != null) {
            val target = persons.firstOrNull { it.id == follow.targetPersonId!!.toInt() }
            if (target != null) {
                val cx = (target.bbox.centerX / width.toFloat()).coerceIn(0f, 1f)
                val cy = (target.bbox.centerY / height.toFloat()).coerceIn(0f, 1f)
                follower.update(cx, cy, follow.smoothFactor.toFloat())
            }
            follower.computeCropRect(follow.zoom.toFloat())
        } else {
            com.danceanon.native.inference.FloatRect(0f, 0f, 1f, 1f)
        }
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(programId, "uCropRect"),
            cropRect.left, cropRect.top, cropRect.right, cropRect.bottom
        )

        val selectedPersons = persons.filter { (selectedPersonIds.isEmpty() || selectedPersonIds.contains(it.id)) && it.mask != null }
        val hasSelected = selectedPersons.isNotEmpty()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uHasMask"), if (hasSelected) 1 else 0)

        // Setup base texture (OES external texture from SurfaceTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uBaseTexture"), 0)

        // Upload and bind mask texture to Texture 1 if present
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        if (hasSelected) {
            if (selectedPersons.size == 1) {
                val mask = selectedPersons[0].mask!!
                mask.buffer.rewind()
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    mask.width, mask.height, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mask.buffer
                )
            } else {
                val firstMask = selectedPersons[0].mask!!
                val totalPixels = firstMask.width * firstMask.height
                val mergedBuffer = ByteBuffer.allocateDirect(totalPixels)
                for (i in 0 until totalPixels) {
                    var maxVal: Byte = 0
                    for (p in selectedPersons) {
                        val b = p.mask!!.buffer.get(i)
                        if ((b.toInt() and 0xFF) > (maxVal.toInt() and 0xFF)) {
                            maxVal = b
                        }
                    }
                    mergedBuffer.put(maxVal)
                }
                mergedBuffer.rewind()
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    firstMask.width, firstMask.height, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mergedBuffer
                )
            }
            val lowestFoot = selectedPersons.maxOf { it.bbox.bottom }
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uFootY"), lowestFoot / height.toFloat())
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uMaskTexture"), 1)

        // Bind attributes
        val aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")

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

    override fun render(
        frameTexture: Int,
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        effects: EffectConfigDto,
        follow: FollowConfigDto,
        presentationTimeUs: Long
    ) {
        render(frameTexture, null, persons, selectedPersonIds, effects, follow, presentationTimeUs)
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
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        if (maskTextureId != 0) {
            val textures = intArrayOf(maskTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            maskTextureId = 0
        }
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            fboTexId = 0
        }
    }
}
