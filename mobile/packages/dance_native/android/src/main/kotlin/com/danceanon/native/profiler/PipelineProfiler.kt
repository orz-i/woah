package com.danceanon.native.profiler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PipelineProfiler {

    val stageTimes = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()

    fun <T> recordStage(stage: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            stageTimes.getOrPut(stage) { CopyOnWriteArrayList() }.add(elapsedMs)
        }
    }

    fun printSummary(jobId: String) {
        val sb = StringBuilder()
        sb.appendLine("================= [Pipeline Profiler Report: ] =================")
        for ((stage, times) in stageTimes.entries.sortedBy { it.key }) {
            if (times.isEmpty()) continue
            val sorted = times.sorted()
            val count = sorted.size
            val avg = sorted.average()
            val p50 = sorted[count / 2]
            val p95 = sorted[(count * 0.95).toInt().coerceAtMost(count - 1)]
            val max = sorted.last()
            sb.appendLine(String.format("  - %-16s | count: %4d | avg: %5.1fms | p50: %3dms | p95: %3dms | max: %3dms", stage, count, avg, p50, p95, max))
        }
        sb.appendLine("=======================================================================")
        android.util.Log.i("PipelineProfiler", sb.toString())
    }

    fun snapshotSummary(): Map<String, Map<String, Number>> {
        return stageTimes.entries.sortedBy { it.key }.associate { (stage, times) ->
            val sorted = times.sorted()
            if (sorted.isEmpty()) {
                stage to mapOf(
                    "count" to 0,
                    "avg_ms" to 0.0,
                    "p50_ms" to 0L,
                    "p95_ms" to 0L,
                    "max_ms" to 0L
                )
            } else {
                val count = sorted.size
                stage to mapOf(
                    "count" to count,
                    "avg_ms" to sorted.average(),
                    "p50_ms" to sorted[count / 2],
                    "p95_ms" to sorted[(count * 0.95).toInt().coerceAtMost(count - 1)],
                    "max_ms" to sorted.last()
                )
            }
        }
    }
}
