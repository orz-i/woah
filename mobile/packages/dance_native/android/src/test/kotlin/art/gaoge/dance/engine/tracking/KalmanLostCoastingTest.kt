package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalmanLostCoastingTest {
    @Test
    fun lostRootStopsShrinkingWhileDetectorIsStillUncommitted() {
        // PLK110 at 17.867s: a recent full body was ~261px high.
        // Under the former constant-velocity LOST projection, the negative
        // height velocity kept shrinking the box to ~53px by 18.367s.
        val observed = FloatRect(563f, 378f, 713f, 639.4f)
        val kalman = KalmanFilter()
        kalman.init(observed, 17_867_000L)
        kalman.state[5] = 185f  // downwards center motion, still plausible
        kalman.state[6] = -0.15f
        kalman.state[7] = -415f // recent detector truncation created negative vh

        // First frame has not yet been classified as LOST. Regular prediction
        // must remain unchanged for observed/global identity matching.
        val firstMissing = kalman.predict(17_900_333L)
        val heldHeight = firstMissing.height
        val heldWidth = firstMissing.width
        assertTrue(heldHeight < observed.height)

        var last = firstMissing
        for (frame in 2..15) {
            val pts = 17_867_000L + frame * 33_333L
            last = kalman.predict(pts, coastUnobservedShape = true)
            assertEquals(heldHeight, last.height, 0.02f)
            assertEquals(heldWidth, last.width, 0.02f)
            assertTrue(last.height > observed.height * 0.90f)
            assertTrue(last.centerX.isFinite() && last.centerY.isFinite())
        }
        assertEquals(0f, kalman.state[6])
        assertEquals(0f, kalman.state[7])
        assertTrue(
            abs(last.centerY - firstMissing.centerY) < 80f,
            "a LOST target must not drift indefinitely on stale center velocity"
        )
    }

    @Test
    fun observedTrackingStillLearnsChangingHeightAndResumesAfterLost() {
        val initial = FloatRect(560f, 370f, 710f, 630f)
        val kalman = KalmanFilter()
        kalman.init(initial, 1_000_000L)
        for (i in 1..12) {
            val pts = 1_000_000L + i * 33_333L
            val height = 260f - i * 3f
            val detection = FloatRect(560f, 500f - height / 2f, 710f, 500f + height / 2f)
            kalman.predict(pts)
            kalman.update(detection, pts)
        }
        assertTrue(kalman.state[7] < 0f, "real observed shape velocity remains learnable")

        val firstMiss = kalman.predict(1_433_329L)
        for (i in 1..8) {
            kalman.predict(1_433_329L + i * 33_333L, coastUnobservedShape = true)
        }
        assertEquals(0f, kalman.state[7])
        val newObservation = FloatRect(560f, 355f, 710f, 645f)
        val before = kalman.toBBox()
        kalman.update(newObservation, 1_733_326L)
        val after = kalman.toBBox()
        assertTrue(abs(after.height - newObservation.height) < abs(before.height - newObservation.height))
        assertTrue(after.height > firstMiss.height)
    }

    @Test
    fun skippedInferenceKeepsOriginalMotionModelWhenNotLost() {
        val initial = FloatRect(100f, 100f, 180f, 300f)
        val kalman = KalmanFilter()
        kalman.init(initial, 1_000_000L)
        kalman.state[4] = 150f
        kalman.state[7] = 75f
        val pred = kalman.predict(1_033_333L)
        assertTrue(pred.centerX > initial.centerX)
        assertTrue(pred.height > initial.height)
        assertEquals(75f, kalman.state[7])
    }
}
