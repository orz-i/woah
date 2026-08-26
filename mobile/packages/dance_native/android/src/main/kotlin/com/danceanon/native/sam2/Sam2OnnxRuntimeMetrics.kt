package com.danceanon.native.sam2

import android.os.Debug

/**
 * Runtime telemetry and performance metrics for Android SAM2 ONNX execution.
 */
class Sam2OnnxRuntimeMetrics {
    var modelLoadMs: Long = 0L

    val imageEncoderTimesMs = mutableListOf<Long>()
    val temporalStepTimesMs = mutableListOf<Long>()
    val totalFrameTimesMs = mutableListOf<Long>()

    var peakPssKb: Long = 0L
    var javaHeapKb: Long = 0L
    var nativeHeapKb: Long = 0L
    var stateMemoryBytes: Long = 0L

    fun recordFrame(imgMs: Long, stepMs: Long) {
        imageEncoderTimesMs.add(imgMs)
        temporalStepTimesMs.add(stepMs)
        totalFrameTimesMs.add(imgMs + stepMs)
        sampleMemory()
    }

    fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L

        val pss = Debug.getPss()
        if (pss > peakPssKb) {
            peakPssKb = pss
        }
    }

    fun getPercentile(times: List<Long>, p: Double): Double {
        if (times.isEmpty()) return 0.0
        val sorted = times.sorted()
        val index = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }

    fun summaryJson(): String {
        val imgP50 = getPercentile(imageEncoderTimesMs, 0.50)
        val imgP95 = getPercentile(imageEncoderTimesMs, 0.95)
        val stepP50 = getPercentile(temporalStepTimesMs, 0.50)
        val stepP95 = getPercentile(temporalStepTimesMs, 0.95)
        val totP50 = getPercentile(totalFrameTimesMs, 0.50)
        val totP95 = getPercentile(totalFrameTimesMs, 0.95)
        val totMax = totalFrameTimesMs.maxOrNull()?.toDouble() ?: 0.0

        return """
        {
          "model_load_ms": $modelLoadMs,
          "frames_recorded": ${totalFrameTimesMs.size},
          "image_encoder_p50_ms": $imgP50,
          "image_encoder_p95_ms": $imgP95,
          "temporal_step_p50_ms": $stepP50,
          "temporal_step_p95_ms": $stepP95,
          "total_frame_p50_ms": $totP50,
          "total_frame_p95_ms": $totP95,
          "total_frame_max_ms": $totMax,
          "peak_pss_kb": $peakPssKb,
          "java_heap_kb": $javaHeapKb,
          "native_heap_kb": $nativeHeapKb,
          "state_memory_bytes": $stateMemoryBytes
        }
        """.trimIndent()
    }
}
