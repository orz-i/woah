package com.danceanon.native.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderCoordinateConventionTest {

    @Test
    fun glBottomMapsToVisualBottom() {
        assertEquals(
            1f,
            RenderCoordinateConvention.glYToVisualY(0f),
            0.0001f
        )
    }

    @Test
    fun glTopMapsToVisualTop() {
        assertEquals(
            0f,
            RenderCoordinateConvention.glYToVisualY(1f),
            0.0001f
        )
    }

    @Test
    fun glCenterRemainsCenter() {
        assertEquals(
            0.5f,
            RenderCoordinateConvention.glYToVisualY(0.5f),
            0.0001f
        )
    }

    @Test
    fun testBitmapTextureMatrixFlipsY() {
        val mat = RenderCoordinateConvention.bitmapTextureMatrix()
        // Column-major 4x4 matrix multiplication: y' = mat[1]*x + mat[5]*y + mat[9]*z + mat[13]*w
        // Input: (0, 0, 0, 1) -> y' = 0 * 0 + (-1) * 0 + 0 * 0 + 1 * 1 = 1
        val yFor0 = mat[1] * 0f + mat[5] * 0f + mat[9] * 0f + mat[13] * 1f
        // Input: (0, 1, 0, 1) -> y' = 0 * 0 + (-1) * 1 + 0 * 0 + 1 * 1 = 0
        val yFor1 = mat[1] * 0f + mat[5] * 1f + mat[9] * 0f + mat[13] * 1f

        assertEquals(1f, yFor0, 0.0001f)
        assertEquals(0f, yFor1, 0.0001f)
    }

    /**
     * This regression guard prevents accidental reintroduction
     * of direct GL contentUv mask sampling into top-left model masks.
     */
    @Test
    fun testMaskShaderUsesVisualTopLeftYConvention() {
        assertTrue(
            GlShaders.VERTEX_SHADER.contains("1.0 - contentUv.y")
        )
    }

    @Test
    fun testComputeTransformMatrixDegenerateProtection() {
        val zeroMat = FloatArray(16) // all zeros
        val safeMat = GlRenderer.computeTransformMatrix(zeroMat, 0)
        assertEquals(1f, safeMat[0])
        assertEquals(1f, safeMat[5])
        assertEquals(1f, safeMat[10])
        assertEquals(1f, safeMat[15])
    }
}




