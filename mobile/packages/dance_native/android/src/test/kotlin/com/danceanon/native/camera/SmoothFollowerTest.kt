package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmoothFollowerTest {
    private fun box(x: Float, y: Float = 0.5f) = FloatRect(x - 0.04f, y - 0.2f, x + 0.04f, y + 0.2f)
    private fun crop(camera: SmoothFollower, x: Float?, time: Long, source: Float = 16f / 9f) =
        camera.cropForFrame(x?.let { box(it) }, time, source, 9f / 16f)

    @Test fun landscapeToPortraitPreservesFullHeightAndCentersSelectedSubject() {
        val result = crop(SmoothFollower(), 0.7f, 0)
        assertEquals(0.7f, result.centerX, 0.00001f)
        assertEquals(81f / 256f, result.width, 0.00001f)
        assertEquals(0f, result.top, 0.00001f)
        assertEquals(1f, result.bottom, 0.00001f)
        assertEquals(9f / 16f, result.width * (16f / 9f) / result.height, 0.00001f)
    }

    @Test fun edgesShiftRatherThanShrinkCropOrRevealBlackBars() {
        for (x in listOf(-0.1f, 0f, 0.02f, 0.98f, 1f, 1.1f)) {
            val result = crop(SmoothFollower(), x, 0)
            assertTrue(result.left >= 0f && result.right <= 1f)
            assertEquals(81f / 256f, result.width, 0.00001f)
        }
        assertEquals(0f, crop(SmoothFollower(), 0f, 0).left, 0.00001f)
        assertEquals(1f, crop(SmoothFollower(), 1f, 0).right, 0.00001f)
    }

    @Test fun portraitSourceIsNotNeedlesslyZoomed() {
        val result = crop(SmoothFollower(), 0.2f, 0, 9f / 16f)
        assertEquals(FloatRect(0f, 0f, 1f, 1f), result)
    }

    @Test fun narrowerPortraitCropsVerticallyInVisualTopLeftSpace() {
        val result = SmoothFollower().cropForFrame(box(0.5f, 0.2f), 0, 0.4f, 9f / 16f)
        assertEquals(1f, result.width, 0.00001f)
        assertEquals(0f, result.top, 0.00001f)
        assertEquals(0.4f / (9f / 16f), result.height, 0.00001f)
    }

    @Test fun panUsesElapsedTimeRatherThanFrameCount() {
        fun afterOneSecond(fps: Int): Float {
            val camera = SmoothFollower()
            crop(camera, 0.2f, 0)
            var result = crop(camera, 0.2f, 0)
            for (i in 1..fps) result = crop(camera, 0.75f, i * 1_000_000L / fps)
            return result.centerX
        }
        assertEquals(afterOneSecond(15), afterOneSecond(30), 0.012f)
        assertEquals(afterOneSecond(30), afterOneSecond(60), 0.012f)
    }

    @Test fun bodyBoxJitterInsideDeadZoneDoesNotMoveCamera() {
        val camera = SmoothFollower()
        val initial = crop(camera, 0.5f, 0)
        for (i in 1..60) {
            val current = crop(camera, 0.5f + if (i % 2 == 0) 0.003f else -0.003f, i * 33_333L)
            assertEquals(initial.centerX, current.centerX, 0.000001f)
        }
    }

    @Test fun missingTargetHoldsTrustedCompositionInsteadOfDrifting() {
        val camera = SmoothFollower()
        val initial = crop(camera, 0.7f, 0)
        for (i in 1..120) {
            assertEquals(initial.centerX, crop(camera, null, i * 33_333L).centerX, 0.000001f)
        }
    }

    @Test fun missingTargetDuringPanNeverMovesPastLastTrustedPosition() {
        val camera = SmoothFollower()
        crop(camera, 0.2f, 0)
        var previous = crop(camera, 0.7f, 33_333L).centerX
        for (i in 2..120) {
            val current = crop(camera, null, i * 33_333L).centerX
            assertTrue(current >= previous && current <= 0.7f)
            previous = current
        }
    }

    @Test fun reacquisitionIsSmoothedAndSpeedLimited() {
        val camera = SmoothFollower()
        crop(camera, 0.2f, 0)
        val result = crop(camera, 0.8f, 33_333L)
        assertTrue(result.centerX > 0.2f)
        assertTrue(result.centerX - 0.2f <= 0.8f / 30f + 0.00001f)
    }

    @Test fun backwardSeekAndResetDoNotReusePreviousCamera() {
        val camera = SmoothFollower()
        crop(camera, 0.2f, 1_000_000L)
        assertEquals(0.7f, crop(camera, 0.7f, 0).centerX, 0.00001f)
        camera.reset()
        assertEquals(0.3f, crop(camera, 0.3f, 0).centerX, 0.00001f)
    }

    @Test fun invalidObservationsAndDuplicateTimestampsCannotPoisonCamera() {
        val camera = SmoothFollower()
        val initial = crop(camera, 0.5f, 0)
        val result = camera.cropForFrame(box(Float.NaN), 33_333L, 16f / 9f, 9f / 16f)
        assertEquals(initial, result)
        assertEquals(result, crop(camera, 0.8f, 33_333L))
    }
}
