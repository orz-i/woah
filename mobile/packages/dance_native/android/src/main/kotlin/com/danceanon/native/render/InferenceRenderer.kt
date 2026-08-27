package com.danceanon.native.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.danceanon.native.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class InferenceRenderer : AutoCloseable {

    private var programId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTexMatrixLoc = -1
    private var uLetterboxRectLoc = -1
    private var uBaseTextureLoc = -1
    private var vertexBuffer: FloatBuffer? = null

    companion object {
        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform vec4 uLetterboxRect; // (leftNorm, topNorm, rightNorm, bottomNorm) in NDC [-1, 1]
varying vec2 vTexCoord;

void main() {
    // Map standard [-1, 1] quad into letterbox sub-rectangle
    float x = mix(uLetterboxRect.x, uLetterboxRect.z, (aPosition.x + 1.0) * 0.5);
    float y = mix(uLetterboxRect.y, uLetterboxRect.w, (aPosition.y + 1.0) * 0.5);
    gl_Position = vec4(x, y, 0.0, 1.0);

    vec4 transformed = uTexMatrix * vec4(aTexCoord, 0.0, 1.0);
    float invW = 1.0 / (transformed.w != 0.0 ? transformed.w : 1.0);
    vTexCoord = transformed.xy * invW;
}
"""

        private const val FRAGMENT_SHADER_OES = """
#extension GL_OES_EGL_image_external : require
#extension GL_OES_EGL_image_external_essl3 : enable
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTexCoord;
uniform samplerExternalOES uBaseTexture;

void main() {
    gl_FragColor = texture2D(uBaseTexture, vTexCoord);
}
"""

        private const val FRAGMENT_SHADER_2D = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTexCoord;
uniform sampler2D uBaseTexture;

void main() {
    gl_FragColor = texture2D(uBaseTexture, vTexCoord);
}
"""
    }

    private class ProgramRefs(
        val programId: Int,
        val aPositionLoc: Int,
        val aTexCoordLoc: Int,
        val uTexMatrixLoc: Int,
        val uLetterboxRectLoc: Int,
        val uBaseTextureLoc: Int
    )

    private var oesProgram: ProgramRefs? = null
    private var tex2dProgram: ProgramRefs? = null

    init {
        oesProgram = createProgram(FRAGMENT_SHADER_OES)
        tex2dProgram = createProgram(FRAGMENT_SHADER_2D)

        // Align Y in quad vertices so video TOP is drawn at the lower Y of FBO (Row 0).
        // Under SurfaceTexture stMatrix: v=0 is video TOP, v=1 is video BOTTOM.
        // When glReadPixels reads bottom-up from row 0, it reads video TOP first!
        val vertices = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f, // NDC left, bottom of sub-rect -> video TOP (u=0, v=0)
             1.0f, -1.0f, 1.0f, 0.0f, // NDC right, bottom of sub-rect -> video TOP (u=1, v=0)
            -1.0f,  1.0f, 0.0f, 1.0f, // NDC left, top of sub-rect -> video BOTTOM (u=0, v=1)
             1.0f,  1.0f, 1.0f, 1.0f  // NDC right, top of sub-rect -> video BOTTOM (u=1, v=1)
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
    }

    private fun createProgram(fragShaderSrc: String): ProgramRefs {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragShaderSrc)

        val progId = GLES20.glCreateProgram()
        GLES20.glAttachShader(progId, vShader)
        GLES20.glAttachShader(progId, fShader)
        GLES20.glLinkProgram(progId)

        val refs = ProgramRefs(
            programId = progId,
            aPositionLoc = GLES20.glGetAttribLocation(progId, "aPosition"),
            aTexCoordLoc = GLES20.glGetAttribLocation(progId, "aTexCoord"),
            uTexMatrixLoc = GLES20.glGetUniformLocation(progId, "uTexMatrix"),
            uLetterboxRectLoc = GLES20.glGetUniformLocation(progId, "uLetterboxRect"),
            uBaseTextureLoc = GLES20.glGetUniformLocation(progId, "uBaseTexture")
        )

        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        return refs
    }

    fun renderToFbo(
        textureId: Int,
        texMatrix: FloatArray,
        mapper: ModelCoordinateMapper,
        fbo: InferenceFbo,
        textureType: SourceTextureType = SourceTextureType.OES
    ) {
        fbo.bind()

        // Clear with YOLO letterbox gray: 114 / 255 = ~0.447
        GLES20.glClearColor(0.44705883f, 0.44705883f, 0.44705883f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val prog = if (textureType == SourceTextureType.OES) oesProgram else tex2dProgram
        if (prog == null) {
            fbo.unbind()
            return
        }

        GLES20.glUseProgram(prog.programId)

        // Convert mapper padLeft / scaledW into NDC range [-1, 1]
        val ndcLeft = (mapper.padLeft / mapper.modelInputSize) * 2.0f - 1.0f
        val ndcRight = ((mapper.padLeft + mapper.scaledW) / mapper.modelInputSize) * 2.0f - 1.0f
        val ndcSubRectBottom = (mapper.padTop / mapper.modelInputSize) * 2.0f - 1.0f
        val ndcSubRectTop = ((mapper.padTop + mapper.scaledH) / mapper.modelInputSize) * 2.0f - 1.0f

        if (prog.uTexMatrixLoc >= 0) {
            GLES20.glUniformMatrix4fv(prog.uTexMatrixLoc, 1, false, texMatrix, 0)
        }
        if (prog.uLetterboxRectLoc >= 0) {
            GLES20.glUniform4f(prog.uLetterboxRectLoc, ndcLeft, ndcSubRectBottom, ndcRight, ndcSubRectTop)
        }

        val target = if (textureType == SourceTextureType.OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        if (prog.uBaseTextureLoc >= 0) {
            GLES20.glUniform1i(prog.uBaseTextureLoc, 0)
        }

        if (prog.aPositionLoc >= 0 && prog.aTexCoordLoc >= 0) {
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(prog.aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(prog.aPositionLoc)

            vertexBuffer?.position(2)
            GLES20.glVertexAttribPointer(prog.aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(prog.aTexCoordLoc)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(prog.aPositionLoc)
            GLES20.glDisableVertexAttribArray(prog.aTexCoordLoc)
        }

        fbo.unbind()
    }


    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e("InferenceRenderer", "Shader compilation failed: $log")
            throw RuntimeException("Shader compilation failed: $log")
        }
        return shader
    }

    override fun close() {
        oesProgram?.let {
            if (it.programId != 0) GLES20.glDeleteProgram(it.programId)
        }
        oesProgram = null

        tex2dProgram?.let {
            if (it.programId != 0) GLES20.glDeleteProgram(it.programId)
        }
        tex2dProgram = null
    }
}

