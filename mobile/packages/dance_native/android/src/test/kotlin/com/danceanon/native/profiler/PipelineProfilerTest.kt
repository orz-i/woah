package com.danceanon.native.profiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineProfilerTest {
    @Test
    fun snapshotSummaryExposesStructuredStageTimings() {
        val profiler = PipelineProfiler()
        repeat(3) {
            profiler.recordStage("unitStage") {
                Thread.sleep(1)
            }
        }

        val stage = profiler.snapshotSummary()["unitStage"]
        requireNotNull(stage)
        assertEquals(3, stage["count"])
        assertTrue((stage["max_ms"] as Long) >= 0L)
        assertTrue((stage["avg_ms"] as Double) >= 0.0)
    }
}
