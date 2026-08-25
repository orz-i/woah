package com.danceanon.native.render

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

    private val vertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderSource = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uBaseTexture;
        uniform sampler2D uMaskTexture;
        uniform vec4 uFillColor;
        uniform float uOpacity;
        uniform int uHasMask;

        void main() {
            vec4 baseColor = texture(uBaseTexture, vTexCoord);
            if (uHasMask == 1) {
                float maskVal = texture(uMaskTexture, vTexCoord).r;
                if (maskVal > 0.5) {
                    vec4 effectColor = vec4(uFillColor.rgb, uOpacity * uFillColor.a);
                    fragColor = mix(baseColor, effectColor, effectColor.a);
                    return;
                }
            }
            fragColor = baseColor;
        }
    """.trimIndent()

    override fun initialize(width: Int, height: Int) {
        this.width = width
        this.height = height

        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexShaderSource)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderSource)

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
    }

    override fun render(
        frameTexture: Int,
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

        // Parse Fill Color ARGB
        val colorInt = effects.fillColorArgb.toInt()
        val a = ((colorInt shr 24) and 0xFF) / 255f
        val r = ((colorInt shr 16) and 0xFF) / 255f
        val g = ((colorInt shr 8) and 0xFF) / 255f
        val b = (colorInt and 0xFF) / 255f

        val uFillColorLoc = GLES30.glGetUniformLocation(programId, "uFillColor")
        GLES30.glUniform4f(uFillColorLoc, r, g, b, a)

        val uOpacityLoc = GLES30.glGetUniformLocation(programId, "uOpacity")
        GLES30.glUniform1f(uOpacityLoc, effects.opacity.toFloat())

        val uHasMaskLoc = GLES30.glGetUniformLocation(programId, "uHasMask")
        GLES30.glUniform1i(uHasMaskLoc, if (selectedPersonIds.isNotEmpty()) 1 else 0)

        // Setup base texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBaseTexture"), 0)

        // Draw quad
        vertexBuffer?.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer?.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
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
    }
}
