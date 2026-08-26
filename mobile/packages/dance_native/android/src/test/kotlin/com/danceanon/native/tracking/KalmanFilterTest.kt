package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalmanFilterTest {

    @Test
    fun testStaticObjectStaysStable() {
        val kalman = KalmanFilter()
        val initialBox = FloatRect(100f, 100f, 200f, 300f)
        kalman.init(initialBox, 0L)

        for (i in 1..20) {
            val pts = i * 33333L // ~30 fps (33.33ms)
            kalman.predict(pts)
            kalman.update(initialBox, pts)
        }

        val pred = kalman.toBBox()
        assertEquals(150f, pred.centerX, 5f)
        assertEquals(200f, pred.centerY, 5f)
        assertEquals(100f, pred.width, 5f)
        assertEquals(200f, pred.height, 5f)
        assertTrue(kotlin.math.abs(kalman.state[4]) < 5f, "Static vx should be close to 0")
        assertTrue(kotlin.math.abs(kalman.state[5]) < 5f, "Static vy should be close to 0")
    }

    @Test
    fun testLearnsHorizontalVelocity() {
        val kalman = KalmanFilter()
        val startBox = FloatRect(100f, 100f, 200f, 300f)
        kalman.init(startBox, 0L)

        // Move horizontally at 300 pixels per second (10 px per 33.33ms frame)
        val speedPxPerSec = 300f
        val dtUs = 33333L
        for (i in 1..30) {
            val pts = i * dtUs
            val currentX = 100f + (i * dtUs / 1_000_000f) * speedPxPerSec
            val box = FloatRect(currentX, 100f, currentX + 100f, 300f)
            kalman.predict(pts)
            kalman.update(box, pts)
        }

        val learnedVx = kalman.state[4]
        // Velocity should converge near 300 px/sec
        assertTrue(learnedVx > 200f, "Learned horizontal velocity should be positive and close to 300 px/s, got: $learnedVx")

        // Now simulate 5 missing detection frames: predict only
        var lastPred = kalman.toBBox()
        for (k in 1..5) {
            val pts = (30 + k) * dtUs
            val nextPred = kalman.predict(pts)
            assertTrue(nextPred.centerX > lastPred.centerX, "Prediction during missing frames should continue in positive X direction")
            lastPred = nextPred
        }
    }

    @Test
    fun testLearnsVerticalVelocity() {
        val kalman = KalmanFilter()
        val startBox = FloatRect(100f, 100f, 200f, 300f)
        kalman.init(startBox, 0L)

        val speedY = 400f // 400 px/sec downwards
        val dtUs = 33333L
        for (i in 1..30) {
            val pts = i * dtUs
            val currentY = 100f + (i * dtUs / 1_000_000f) * speedY
            val box = FloatRect(100f, currentY, 200f, currentY + 200f)
            kalman.predict(pts)
            kalman.update(box, pts)
        }

        val learnedVy = kalman.state[5]
        assertTrue(learnedVy > 250f, "Learned vertical velocity should be positive and close to 400 px/s, got: $learnedVy")
    }

    @Test
    fun testMissingDetectionsAndResume() {
        val kalman = KalmanFilter()
        val startBox = FloatRect(100f, 100f, 200f, 300f)
        kalman.init(startBox, 0L)

        val dtUs = 33333L
        for (i in 1..10) {
            val pts = i * dtUs
            kalman.predict(pts)
            kalman.update(startBox, pts)
        }

        // Drop detections for 5 frames
        for (i in 11..15) {
            val pts = i * dtUs
            kalman.predict(pts)
        }

        // Resume detections
        val newBox = FloatRect(120f, 100f, 220f, 300f)
        for (i in 16..20) {
            val pts = i * dtUs
            kalman.predict(pts)
            kalman.update(newBox, pts)
        }

        val finalBox = kalman.toBBox()
        assertEquals(170f, finalBox.centerX, 10f)
    }
}
