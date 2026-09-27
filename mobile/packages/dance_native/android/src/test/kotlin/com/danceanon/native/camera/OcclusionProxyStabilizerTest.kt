package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcclusionProxyStabilizerTest {
    private fun box(x: Float, y: Float = 0.5f) =
        FloatRect(x - 0.05f, y - 0.20f, x + 0.05f, y + 0.20f)

    @Test
    fun alternatingMicroMotionStaysLocked() {
        val stabilizer = OcclusionProxyStabilizer()
        val initial = stabilizer.stabilize(box(0.25f), 0L)!!
        for (frame in 1..90) {
            val x = if (frame % 2 == 0) 0.263f else 0.237f
            val current = stabilizer.stabilize(box(x), frame * 33_333L)!!
            assertEquals(initial.centerX, current.centerX, 0.000001f)
        }
        assertFalse(stabilizer.state().trackingX)
    }

    @Test
    fun sustainedSmallDriftBreaksOutAfterConfirmation() {
        val stabilizer = OcclusionProxyStabilizer()
        val initial = stabilizer.stabilize(box(0.25f), 0L)!!
        var current = initial
        for (frame in 1..4) {
            current = stabilizer.stabilize(box(0.263f), frame * 33_333L)!!
            assertEquals(initial.centerX, current.centerX, 0.000001f)
        }
        for (frame in 5..15) {
            current = stabilizer.stabilize(box(0.263f), frame * 33_333L)!!
        }
        assertTrue(current.centerX > initial.centerX + 0.003f)
        assertTrue(current.centerX < 0.263f)
    }

    @Test
    fun largeRealMotionBreaksOutImmediatelyAndConverges() {
        val stabilizer = OcclusionProxyStabilizer()
        stabilizer.stabilize(box(0.25f), 0L)
        val first = stabilizer.stabilize(box(0.32f), 33_333L)!!
        assertTrue(first.centerX > 0.25f)

        var current = first
        for (frame in 2..20) {
            current = stabilizer.stabilize(box(0.32f), frame * 33_333L)!!
        }
        assertTrue(current.centerX > 0.31f)
        assertTrue(current.centerX < 0.32f)
    }

    @Test
    fun resetDoesNotCarryPreviousProxyComposition() {
        val stabilizer = OcclusionProxyStabilizer()
        stabilizer.stabilize(box(0.25f), 0L)
        for (frame in 1..10) {
            stabilizer.stabilize(box(0.35f), frame * 33_333L)
        }

        stabilizer.reset()
        val next = stabilizer.stabilize(box(0.70f), 500_000L)!!
        assertEquals(0.70f, next.centerX, 0.000001f)
        assertFalse(stabilizer.state().trackingX)
    }

    @Test
    fun sustainedMotionIsApproximatelyFrameRateIndependent() {
        fun finalCenter(fps: Int): Float {
            val stabilizer = OcclusionProxyStabilizer()
            stabilizer.stabilize(box(0.25f), 0L)
            var current = box(0.25f)
            for (frame in 1..fps) {
                current = stabilizer.stabilize(
                    box(0.29f),
                    frame * 1_000_000L / fps
                )!!
            }
            return current.centerX
        }

        assertEquals(finalCenter(30), finalCenter(60), 0.004f)
    }
}
