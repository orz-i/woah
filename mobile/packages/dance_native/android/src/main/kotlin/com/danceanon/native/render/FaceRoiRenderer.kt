package com.danceanon.native.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Isolated source-texture crop renderer for face-location research.
 *
 * The crop is expressed in the same visual top-left source space as YOLO person
 * boxes. Before applying the caller-provided source texture matrix, visual Y is
 * converted to the repository's screen-GL convention (`screenY = 1 - visualY`).
 * This keeps the crop definition independent from whether the underlying source
 * is decoder OES or a 2D bitmap texture.
 *
 * This class is deliberately not wired into ExportPipeline yet. Its coordinate
 * contract must remain device-tested before production integration.
 */
class FaceRoiRenderer : AutoCloseable {
    private data class ProgramRefs(
        val programId: Int,
        val aPositionLoc: Int,
        val aTexCoordLoc: Int,
        val uTexMatrixLoc: Int,
        val uCropRectLoc: Int,
        val uBaseTextureLoc: Int
    )

    private var oesProgram: ProgramRefs? = null
    private var texture2dProgram: ProgramRefs? = null
    private var vertexBuffer: FloatBuffer? = null

    init {
        oesProgram = createProgram(FRAGMENT_SHADER_OES)
        texture2dProgram = createProgram(FRAGMENT_SHADER_2D)

        // Match InferenceRenderer's readback convention: the lower FBO row is
        // paired with logical visual-top y=0, so glReadPixels row 0 is already
        // semantic visual top for downstream CPU image consumers.
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
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
        textureId: Int,
        texMatrix: FloatArray,
        sourceRect: FloatRect,
        sourceWidth: Int,
        sourceHeight: Int,
        fbo: InferenceFbo,
        textureType: SourceTextureType
    ) {
        require(texMatrix.size >= 16) { "texMatrix must contain at least 16 floats" }
        require(sourceWidth > 0 && sourceHeight > 0) { "Invalid source size ${sourceWidth}x$sourceHeight" }
        require(sourceRect.width > 0f && sourceRect.height > 0f) { "Invalid source crop $sourceRect" }

        val left = (sourceRect.left / sourceWidth.toFloat()).coerceIn(0f, 1f)
        val top = (sourceRect.top / sourceHeight.toFloat()).coerceIn(0f, 1f)
        val right = (sourceRect.right / sourceWidth.toFloat()).coerceIn(0f, 1f)
        val bottom = (sourceRect.bottom / sourceHeight.toFloat()).coerceIn(0f, 1f)
        require(right > left && bottom > top) { "Crop falls outside source bounds: $sourceRect" }

        val program = when (textureType) {
            SourceTextureType.OES -> oesProgram
            SourceTextureType.TEXTURE_2D -> texture2dProgram
        } ?: error("FaceRoiRenderer program unavailable for $textureType")

        fbo.bind()
        try {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program.programId)

            GLES20.glUniformMatrix4fv(program.uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform4f(program.uCropRectLoc, left, top, right, bottom)

            val target = when (textureType) {
                SourceTextureType.OES -> GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                SourceTextureType.TEXTURE_2D -> GLES20.GL_TEXTURE_2D
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, textureId)
            GLES20.glUniform1i(program.uBaseTextureLoc, 0)

            val vb = checkNotNull(vertexBuffer)
            vb.position(0)
            GLES20.glVertexAttribPointer(program.aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vb)
            GLES20.glEnableVertexAttribArray(program.aPositionLoc)
            vb.position(2)
            GLES20.glVertexAttribPointer(program.aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vb)
            GLES20.glEnableVertexAttribArray(program.aTexCoordLoc)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(program.aPositionLoc)
            GLES20.glDisableVertexAttribArray(program.aTexCoordLoc)
            GlRenderer.checkGlError("FaceRoiRenderer.renderToFbo")
        } finally {
            fbo.unbind()
        }
    }

    private fun createProgram(fragmentShader: String): ProgramRefs {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            throw IllegalStateException("FaceRoiRenderer program link failed: $log")
        }

        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return ProgramRefs(
            programId = program,
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition"),
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord"),
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix"),
            uCropRectLoc = GLES20.glGetUniformLocation(program, "uCropRect"),
            uBaseTextureLoc = GLES20.glGetUniformLocation(program, "uBaseTexture")
        )
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("FaceRoiRenderer shader compile failed: $log")
        }
        return shader
    }

    override fun close() {
        oesProgram?.programId?.takeIf { it != 0 }?.let { GLES20.glDeleteProgram(it) }
        texture2dProgram?.programId?.takeIf { it != 0 }?.let { GLES20.glDeleteProgram(it) }
        oesProgram = null
        texture2dProgram = null
        vertexBuffer = null
    }

    companion object {
        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform vec4 uCropRect; // visual top-left normalized (left, top, right, bottom)
varying vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
    float visualX = mix(uCropRect.x, uCropRect.z, aTexCoord.x);
    float visualY = mix(uCropRect.y, uCropRect.w, aTexCoord.y);
    // uCropRect uses visual top-left coordinates while the full-frame texture
    // matrix contract consumes screen-GL UV (y=0 bottom, y=1 top).
    vec2 screenGlUv = vec2(visualX, 1.0 - visualY);
    vec4 transformed = uTexMatrix * vec4(screenGlUv, 0.0, 1.0);
    float invW = 1.0 / (transformed.w != 0.0 ? transformed.w : 1.0);
    vTexCoord = transformed.xy * invW;
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

        private const val FRAGMENT_SHADER_OES = """
#extension GL_OES_EGL_image_external : require
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
}
