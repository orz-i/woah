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
    vTexCoord = transformed.xy;
}
"""

        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
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
        uLetterboxRectLoc = GLES20.glGetUniformLocation(programId, "uLetterboxRect")
        uBaseTextureLoc = GLES20.glGetUniformLocation(programId, "uBaseTexture")

        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)

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
        mapper: ModelCoordinateMapper,
        fbo: InferenceFbo
    ) {
        fbo.bind()

        // Clear with YOLO letterbox gray: 114 / 255 = ~0.447
        GLES20.glClearColor(0.44705883f, 0.44705883f, 0.44705883f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        // Convert mapper padLeft / scaledW into NDC range [-1, 1]
        val ndcLeft = (mapper.padLeft / mapper.modelInputSize) * 2.0f - 1.0f
        val ndcRight = ((mapper.padLeft + mapper.scaledW) / mapper.modelInputSize) * 2.0f - 1.0f
        // Bottom-up GL: top in image corresponds to higher Y in NDC
        val ndcTop = 1.0f - (mapper.padTop / mapper.modelInputSize) * 2.0f
        val ndcBottom = 1.0f - ((mapper.padTop + mapper.scaledH) / mapper.modelInputSize) * 2.0f

        if (uTexMatrixLoc >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        }
        if (uLetterboxRectLoc >= 0) {
            GLES20.glUniform4f(uLetterboxRectLoc, ndcLeft, minOf(ndcBottom, ndcTop), ndcRight, maxOf(ndcBottom, ndcTop))
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (uBaseTextureLoc >= 0) {
            GLES20.glUniform1i(uBaseTextureLoc, 0)
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
            android.util.Log.e("InferenceRenderer", "Shader compilation failed: ")
            throw RuntimeException("Shader compilation failed: ")
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
