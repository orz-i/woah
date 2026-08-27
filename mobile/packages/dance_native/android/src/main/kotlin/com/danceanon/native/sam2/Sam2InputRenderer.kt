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
    vTexCoord = transformed.xy;
}
"""

        private const val FRAGMENT_SHADER = """
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

    }

    init {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vShader)
        GLES20.glAttachShader(programId, fShader)
        GLES20.glLinkProgram(programId)

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        uBaseTextureLoc = GLES20.glGetUniformLocation(programId, "uBaseTexture")

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

    fun renderToFbo(
        oesTextureId: Int,
        texMatrix: FloatArray,
        fbo: Sam2InputFbo
    ) {
        val previousFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)

        try {
            fbo.bind()

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            if (uBaseTextureLoc >= 0) GLES20.glUniform1i(uBaseTextureLoc, 0)

            if (uTexMatrixLoc >= 0) {
                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            }

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

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
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
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }
}
