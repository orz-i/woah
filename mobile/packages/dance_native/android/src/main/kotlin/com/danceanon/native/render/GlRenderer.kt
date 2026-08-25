package com.danceanon.native.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
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

        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, GlShaders.VERTEX_SHADER)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, GlShaders.FRAGMENT_SHADER)

        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vShader)
        GLES30.glAttachShader(programId, fShader)
        GLES30.glLinkProgram(programId)

        // Quad vertices & texcoords
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
        GLES30.glGenTextures(1, textures, 0)
        maskTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
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
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(programId)

        val matrix = texMatrix ?: identityMatrix
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uTexMatrix"), 1, false, matrix, 0)

        // Parse Fill Color ARGB
        val fc = effects.fillColorArgb.toInt()
        val fa = ((fc shr 24) and 0xFF) / 255f
        val fr = ((fc shr 16) and 0xFF) / 255f
        val fg = ((fc shr 8) and 0xFF) / 255f
        val fb = (fc and 0xFF) / 255f
        GLES30.glUniform4f(GLES30.glGetUniformLocation(programId, "uFillColor"), fr, fg, fb, fa)

        // Parse Border/Outline Color ARGB
        val oc = effects.borderColorArgb.toInt()
        val oa = ((oc shr 24) and 0xFF) / 255f
        val or = ((oc shr 16) and 0xFF) / 255f
        val og = ((oc shr 8) and 0xFF) / 255f
        val ob = (oc and 0xFF) / 255f
        GLES30.glUniform4f(GLES30.glGetUniformLocation(programId, "uOutlineColor"), or, og, ob, oa)

        // Parse Gradient Color ARGB
        GLES30.glUniform4f(GLES30.glGetUniformLocation(programId, "uGradientColor"), 0.5f, 0.2f, 0.9f, 1f)

        // Effect Mode: 0: solid, 1: outline, 2: blur, 3: gradient, 4: skin_whiten, 5: leg_stretch
        val effectMode = when (effects.fillMode.lowercase()) {
            "solid" -> if (effects.borderWidth > 0) 1 else 0
            "blur" -> 2
            "gradient" -> 3
            "mosaic" -> 2
            else -> if (effects.skinWhiten > 0) 4 else if (effects.legStretchEnabled) 5 else 0
        }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uEffectType"), effectMode)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uOpacity"), effects.opacity.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uOutlineWidth"), effects.borderWidth.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurRadius"), effects.blurStrength.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSkinWhitenStrength"), effects.skinWhiten.toFloat())
        val legRatio = if (effects.legStretchEnabled) (1.0 + effects.legStretch).toFloat() else 1.0f
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLegStretchRatio"), legRatio)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uTexelSize"), 1f / width.coerceAtLeast(1), 1f / height.coerceAtLeast(1))

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
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(programId, "uCropRect"),
            cropRect.left, cropRect.top, cropRect.right, cropRect.bottom
        )

        val hasSelected = selectedPersonIds.isNotEmpty() && persons.any { selectedPersonIds.contains(it.id) }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uHasMask"), if (hasSelected) 1 else 0)

        // Setup base texture (OES external texture from SurfaceTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBaseTexture"), 0)

        // Upload and bind mask texture to Texture 1 if present
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        val selectedPerson = persons.firstOrNull { selectedPersonIds.contains(it.id) }
        if (selectedPerson?.mask != null) {
            val mask = selectedPerson.mask!!
            mask.buffer.rewind()
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                mask.width, mask.height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, mask.buffer
            )
            GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFootY"), selectedPerson.bbox.bottom / height.toFloat())
        }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaskTexture"), 1)

        // Draw quad
        vertexBuffer?.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer?.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
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
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    override fun close() {
        release()
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        if (maskTextureId != 0) {
            val textures = intArrayOf(maskTextureId)
            GLES30.glDeleteTextures(1, textures, 0)
            maskTextureId = 0
        }
    }
}
