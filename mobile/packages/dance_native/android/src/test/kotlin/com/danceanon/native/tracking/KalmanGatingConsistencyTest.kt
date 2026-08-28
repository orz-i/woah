package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KalmanGatingConsistencyTest {

    @Test
    fun testKalmanPredictedStateEqualsAssociationPredictedBbox() {
        val kalman = KalmanFilter()
        val initialBbox = FloatRect(100f, 100f, 200f, 300f)
        kalman.init(initialBbox)

        // Predict at t = 33ms
        val predictedBbox = kalman.predict(33_333L)
        assertEquals(initialBbox.centerX, predictedBbox.centerX, 1e-3f)
        assertEquals(initialBbox.centerY, predictedBbox.centerY, 1e-3f)
        assertEquals(initialBbox.width, predictedBbox.width, 1e-3f)
        assertEquals(initialBbox.height, predictedBbox.height, 1e-3f)

        // Gating distance for exact same bbox is 0
        val distExact = kalman.gatingDistance(initialBbox)
        assertEquals(0f, distExact, 1e-3f)

        // Displaced detection has non-zero gating distance
        val displacedBbox = FloatRect(120f, 100f, 220f, 300f)
        val distDisplaced = kalman.gatingDistance(displacedBbox)
        assertTrue(distDisplaced > 0f)
    }
}
