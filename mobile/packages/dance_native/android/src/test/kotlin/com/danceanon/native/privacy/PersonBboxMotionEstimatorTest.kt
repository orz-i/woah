package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonBboxMotionEstimatorTest {
    private val base = FloatRect(100f, 100f, 300f, 700f)

    @Test
    fun `coherent whole-person translation follows both axes`() {
        val moved = FloatRect(140f, 135f, 340f, 735f)
        val result = PersonBboxMotionEstimator.estimate(base, moved)
        assertEquals(40f, result.dx)
        assertEquals(35f, result.dy)
    }

    @Test
    fun `top-edge-only coverage jitter does not become vertical translation`() {
        val jittered = FloatRect(100f, 200f, 300f, 700f)
        val result = PersonBboxMotionEstimator.estimate(base, jittered)
        assertEquals(0f, result.dy)
    }

    @Test
    fun `bottom-edge-only coverage jitter does not become vertical translation`() {
        val jittered = FloatRect(100f, 100f, 300f, 758f)
        val result = PersonBboxMotionEstimator.estimate(base, jittered)
        assertEquals(0f, result.dy)
    }

    @Test
    fun `one-sided width change does not become horizontal translation`() {
        val widened = FloatRect(100f, 100f, 360f, 700f)
        val result = PersonBboxMotionEstimator.estimate(base, widened)
        assertEquals(0f, result.dx)
    }

    @Test
    fun `small edge disagreement keeps average translation`() {
        val movedAndResized = FloatRect(122f, 112f, 318f, 716f)
        val result = PersonBboxMotionEstimator.estimate(base, movedAndResized)
        assertTrue(result.dx in 19f..21f)
        assertTrue(result.dy in 13f..15f)
    }
}
