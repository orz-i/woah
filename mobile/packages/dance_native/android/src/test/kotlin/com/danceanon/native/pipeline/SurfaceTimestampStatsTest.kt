package com.danceanon.native.pipeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurfaceTimestampStatsTest {

    @Test
    fun testSurfaceTimestampMetricsComputation() {
        val decoderPtsList = listOf(0L, 33_333L, 66_666L, 100_000L, 133_333L)
        val surfaceTimestampNsList = listOf(0L, 33_330_000L, 66_666_000L, 66_666_000L, 120_000_000L)

        var duplicateCount = 0L
        var nonMonotonicCount = 0L
        var maxDeltaUs = 0L
        val deltas = mutableListOf<Long>()
        var lastSurfaceNs = -1L

        for (i in decoderPtsList.indices) {
            val decPtsUs = decoderPtsList[i]
            val surfNs = surfaceTimestampNsList[i]

            if (lastSurfaceNs > 0L) {
                if (surfNs == lastSurfaceNs) {
                    duplicateCount++
                } else if (surfNs < lastSurfaceNs) {
                    nonMonotonicCount++
                }
            }
            lastSurfaceNs = surfNs

            val surfPtsUs = surfNs / 1000L
            val deltaUs = abs(surfPtsUs - decPtsUs)
            if (deltaUs > maxDeltaUs) {
                maxDeltaUs = deltaUs
            }
            deltas.add(deltaUs)
        }

        assertEquals(1L, duplicateCount, "Expected 1 duplicate timestamp")
        assertEquals(0L, nonMonotonicCount, "Expected 0 non-monotonic timestamp")
        assertTrue(maxDeltaUs >= 13333L, "Max delta must capture frame 4 discrepancy ($maxDeltaUs us)")

        val sorted = deltas.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        assertTrue(p95 > 0L)
    }
}
