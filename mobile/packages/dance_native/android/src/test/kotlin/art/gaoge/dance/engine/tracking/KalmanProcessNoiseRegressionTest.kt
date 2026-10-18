package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Detection bboxes and presentation cadence from the PLK110 570d6f9
 * full-body production trace, 17.000–18.833s. Only anonymous geometry is
 * encoded here; no source frames, masks or person images.
 *
 * This is a PREDICTION test, not a permission to reassign privacy identities.
 */
class KalmanProcessNoiseRegressionTest {
    @Test
    fun detectorBodySizeChangeMustNotLeaveStaleOverconfidentKalmanShape() {
        val heights = floatArrayOf(
            363.4f, 366.6f, 370.0f, 372.5f, 376.6f, 382.8f, 388.8f, 392.8f, 398.8f,
            406.9f, 411.9f, 410.0f, 408.8f, 406.9f, 405.3f, 405.3f, 400.0f, 395.3f,
            389.4f, 380.9f, 362.8f, 342.8f, 324.4f, 303.9f, 288.4f, 273.0f, 261.4f,
            256.9f, 246.4f, 242.5f, 237.5f, 236.4f, 233.0f, 233.0f, 230.9f, 229.4f,
            228.9f, 224.4f, 225.9f, 225.0f, 215.5f, 221.9f, 220.9f, 223.9f, 226.9f,
            230.0f, 231.9f, 237.5f, 235.0f, 219.4f, 219.4f, 240.9f, 270.5f, 290.9f
        )
        val centersY = floatArrayOf(
            440.0f, 440.6f, 441.2f, 440.6f, 443.8f, 443.1f, 443.1f, 443.8f, 443.1f,
            443.1f, 443.8f, 443.8f, 444.4f, 445.0f, 445.0f, 445.0f, 448.1f, 450.6f,
            451.9f, 456.2f, 463.8f, 471.9f, 478.1f, 485.0f, 490.6f, 496.9f, 502.5f,
            504.4f, 508.7f, 508.8f, 508.8f, 508.7f, 508.8f, 509.4f, 509.4f, 508.8f,
            508.1f, 510.0f, 511.2f, 511.2f, 516.9f, 515.0f, 516.2f, 515.0f, 515.0f,
            513.8f, 513.1f, 511.9f, 515.0f, 525.0f, 527.5f, 519.4f, 505.6f, 499.4f
        )
        fun box(i: Int) = FloatRect(
            638f - 75f, centersY[i] - heights[i] / 2f,
            638f + 75f, centersY[i] + heights[i] / 2f
        )
        val kalman = KalmanFilter()
        kalman.init(box(0), 17_000_000L)

        var heightErrorSquares = 0.0
        var centerErrorSquares = 0.0
        var lastPrediction = box(0)
        for (i in 1 until heights.size) {
            // Detector was not committed between 18.333 and 18.433s.
            val frameIndex = if (i <= 40) i else i + 2
            val pts = 17_000_000L + frameIndex * 33_333L
            lastPrediction = kalman.predict(pts)
            val observed = box(i)
            heightErrorSquares += (lastPrediction.height - observed.height).toDouble()
                .let { it * it }
            centerErrorSquares += (lastPrediction.centerY - observed.centerY).toDouble()
                .let { it * it }
            kalman.update(observed, pts)
        }
        val sampleCount = heights.size - 1
        val heightRmse = sqrt(heightErrorSquares / sampleCount)
        val centerRmse = sqrt(centerErrorSquares / sampleCount)
        // The original dt-scaled Q replay was ~37px height RMSE and only
        // ~211px predicted height against a 291px detector box at 18.833s.
        assertTrue(heightRmse < 13.0, "predicted height must follow real detections: $heightRmse")
        assertTrue(centerRmse < 8.0, "predicted center must not drift: $centerRmse")
        assertTrue(lastPrediction.height > 260f, "last predicted height ${lastPrediction.height}")
    }

    @Test
    fun staticNoisyBodyDoesNotChatterOrDivergeAfterProcessNoiseCorrection() {
        val kalman = KalmanFilter()
        val initial = FloatRect(100f, 80f, 220f, 380f)
        kalman.init(initial, 1_000_000L)
        var maxCenterError = 0f
        for (i in 1..180) {
            val pts = 1_000_000L + i * 33_333L
            val jitter = if (i % 2 == 0) 1.5f else -1.5f
            val measured = FloatRect(
                100f + jitter, 80f - jitter, 220f + jitter, 380f - jitter
            )
            val pred = kalman.predict(pts)
            maxCenterError = maxOf(maxCenterError, kotlin.math.abs(pred.centerX - 160f))
            kalman.update(measured, pts)
            assertTrue(pred.width.isFinite() && pred.height.isFinite())
            assertTrue(pred.width > 0f && pred.height > 0f)
        }
        assertTrue(maxCenterError < 10f, "stationary target filter jittered $maxCenterError px")
    }
}
