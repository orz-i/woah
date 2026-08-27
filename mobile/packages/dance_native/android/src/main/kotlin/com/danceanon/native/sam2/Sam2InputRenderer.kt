package com.danceanon.native.sam2

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Dedicated renderer for drawing OES decoded video textures directly into SAM2 square input FBO.
 * Fits entire visual frame into square model space (no letterbox padding).
 */
class Sam2InputRenderer : AutoCloseable {

    private var programId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTexMatrixLoc = -1
    private var uBaseTextureLoc = -1
    private var vertexBuffer: FloatBuffer? = null

    companion object {
        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
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
        val uBaseTextureLoc: Int
    )

    private var oesProgram: ProgramRefs? = null
    private var tex2dProgram: ProgramRefs? = null

    init {
        oesProgram = createProgram(FRAGMENT_SHADER_OES)
        tex2dProgram = createProgram(FRAGMENT_SHADER_2D)

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
            uBaseTextureLoc = GLES20.glGetUniformLocation(progId, "uBaseTexture")
        )

        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        return refs
    }

    fun renderToFbo(
        textureId: Int,
        texMatrix: FloatArray,
        fbo: Sam2InputFbo,
        textureType: com.danceanon.native.render.SourceTextureType = com.danceanon.native.render.SourceTextureType.OES
    ) {
        val previousFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)

        try {
            fbo.bind()

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val prog = if (textureType == com.danceanon.native.render.SourceTextureType.OES) oesProgram else tex2dProgram
            if (prog == null) return

            GLES20.glUseProgram(prog.programId)

            val target = if (textureType == com.danceanon.native.render.SourceTextureType.OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, textureId)
            if (prog.uBaseTextureLoc >= 0) GLES20.glUniform1i(prog.uBaseTextureLoc, 0)

            if (prog.uTexMatrixLoc >= 0) {
                GLES20.glUniformMatrix4fv(prog.uTexMatrixLoc, 1, false, texMatrix, 0)
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

            GLES20.glBindTexture(target, 0)
            GLES20.glUseProgram(0)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
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
            android.util.Log.e("Sam2InputRenderer", "Shader compilation failed ($type): $log")
            throw RuntimeException("Shader compilation failed ($type): $log")
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

