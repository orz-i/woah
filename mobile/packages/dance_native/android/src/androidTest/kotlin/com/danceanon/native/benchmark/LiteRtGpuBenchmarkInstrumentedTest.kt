package com.danceanon.native.benchmark

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.litert.LiteRtModelRunner
import com.danceanon.native.litert.LiteRtRunnerPolicy
import com.danceanon.native.sam2.Sam2TensorContract
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LiteRtGpuBenchmarkInstrumentedTest {

    data class BenchmarkStats(
        val modelName: String,
        val requested: LiteRtAccelerator,
        val effective: LiteRtAccelerator,
        val compileMs: Long,
        val warmupMs: Long,
        val latenciesMs: List<Double>,
        val meanMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val minMs: Double,
        val maxMs: Double
    ) {
        override fun toString(): String {
            return """
            |============================================================
            |[LiteRT Benchmark] Model: $modelName
            |Requested: $requested, Effective: $effective
            |Compile: ${compileMs}ms, Warmup: ${warmupMs}ms
            |Samples (${latenciesMs.size}): mean=${"%.2f".format(meanMs)}ms, p50=${"%.2f".format(p50Ms)}ms, p95=${"%.2f".format(p95Ms)}ms, min=${"%.2f".format(minMs)}ms, max=${"%.2f".format(maxMs)}ms
            |============================================================
            """.trimMargin()
        }
    }

    private fun calculateStats(
        modelName: String,
        requested: LiteRtAccelerator,
        effective: LiteRtAccelerator,
        compileMs: Long,
        warmupMs: Long,
        latencies: List<Double>
    ): BenchmarkStats {
        val sorted = latencies.sorted()
        val mean = if (sorted.isNotEmpty()) sorted.average() else 0.0
        val p50 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.50).toInt().coerceAtMost(sorted.size - 1)] else 0.0
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)] else 0.0
        val min = sorted.firstOrNull() ?: 0.0
        val max = sorted.lastOrNull() ?: 0.0
        return BenchmarkStats(
            modelName = modelName,
            requested = requested,
            effective = effective,
            compileMs = compileMs,
            warmupMs = warmupMs,
            latenciesMs = latencies,
            meanMs = mean,
            p50Ms = p50,
            p95Ms = p95,
            minMs = min,
            maxMs = max
        )
    }

    @Test
    fun benchmarkYoloGpuVsCpu() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyInput = FloatArray(1 * 3 * 640 * 640) { 0.5f }

        // 1. Benchmark YOLO STRICT_GPU
        var gpuStats: BenchmarkStats? = null
        try {
            val runnerGpu = LiteRtModelRunner.fromAsset(
                context = context,
                assetPath = YoloLiteRtSegmenter.DEFAULT_ASSET_PATH,
                policy = LiteRtRunnerPolicy.STRICT_GPU
            )
            runnerGpu.initialize()
            val inBufs = runnerGpu.getInputBuffers()
            val outBufs = runnerGpu.getOutputBuffers()
            inBufs[0].writeFloat(dummyInput)

            // Warmup 3
            for (i in 0 until 3) {
                runnerGpu.runInference()
            }

            // Measure 20
            val latencies = mutableListOf<Double>()
            for (i in 0 until 20) {
                val t0 = System.nanoTime()
                runnerGpu.runInference()
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                latencies.add(elapsedMs)
            }

            val compileMs = runnerGpu.runtimeInfo?.compileMs ?: 0L
            val warmupMs = runnerGpu.runtimeInfo?.warmupMs ?: 0L
            gpuStats = calculateStats(
                modelName = "yolo11n-seg-fp16.tflite",
                requested = LiteRtAccelerator.GPU,
                effective = runnerGpu.effectiveAccelerator,
                compileMs = compileMs,
                warmupMs = warmupMs,
                latencies = latencies
            )
            Log.i("GPU_BENCHMARK", gpuStats.toString())
            runnerGpu.close()
        } catch (t: Throwable) {
            Log.w("GPU_BENCHMARK", "YOLO STRICT_GPU failed: ${t.message}")
        }

        // 2. Benchmark YOLO STRICT_CPU
        val runnerCpu = LiteRtModelRunner.fromAsset(
            context = context,
            assetPath = YoloLiteRtSegmenter.DEFAULT_ASSET_PATH,
            policy = LiteRtRunnerPolicy.STRICT_CPU
        )
        runnerCpu.initialize()
        val inBufsCpu = runnerCpu.getInputBuffers()
        inBufsCpu[0].writeFloat(dummyInput)

        for (i in 0 until 3) {
            runnerCpu.runInference()
        }

        val cpuLatencies = mutableListOf<Double>()
        for (i in 0 until 20) {
            val t0 = System.nanoTime()
            runnerCpu.runInference()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            cpuLatencies.add(elapsedMs)
        }

        val cpuCompileMs = runnerCpu.runtimeInfo?.compileMs ?: 0L
        val cpuWarmupMs = runnerCpu.runtimeInfo?.warmupMs ?: 0L
        val cpuStats = calculateStats(
            modelName = "yolo11n-seg-fp16.tflite",
            requested = LiteRtAccelerator.CPU,
            effective = runnerCpu.effectiveAccelerator,
            compileMs = cpuCompileMs,
            warmupMs = cpuWarmupMs,
            latencies = cpuLatencies
        )
        Log.i("GPU_BENCHMARK", cpuStats.toString())
        runnerCpu.close()

        if (gpuStats != null) {
            val speedup = if (gpuStats.p50Ms > 0) cpuStats.p50Ms / gpuStats.p50Ms else 0.0
            Log.i("GPU_BENCHMARK", "[YOLO Speedup] CPU p50: ${cpuStats.p50Ms}ms / GPU p50: ${gpuStats.p50Ms}ms = ${"%.2f".format(speedup)}x")
        }
    }

    @Test
    fun benchmarkSam2ImageFeaturesGpuVsCpu() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyInput = FloatArray(1 * 3 * 1024 * 1024) { 0.5f }

        // 1. Benchmark SAM2 Image Features STRICT_GPU
        var gpuStats: BenchmarkStats? = null
        try {
            val runnerGpu = LiteRtModelRunner.fromAsset(
                context = context,
                assetPath = Sam2TensorContract.MODEL_IMAGE_FEATURES,
                policy = LiteRtRunnerPolicy.STRICT_GPU
            )
            runnerGpu.initialize()
            val inBufs = runnerGpu.getInputBuffers()
            inBufs[0].writeFloat(dummyInput)

            for (i in 0 until 2) {
                runnerGpu.runInference()
            }

            val latencies = mutableListOf<Double>()
            for (i in 0 until 10) {
                val t0 = System.nanoTime()
                runnerGpu.runInference()
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                latencies.add(elapsedMs)
            }

            val compileMs = runnerGpu.runtimeInfo?.compileMs ?: 0L
            val warmupMs = runnerGpu.runtimeInfo?.warmupMs ?: 0L
            gpuStats = calculateStats(
                modelName = "sam2_image_features.tflite",
                requested = LiteRtAccelerator.GPU,
                effective = runnerGpu.effectiveAccelerator,
                compileMs = compileMs,
                warmupMs = warmupMs,
                latencies = latencies
            )
            Log.i("GPU_BENCHMARK", gpuStats.toString())
            runnerGpu.close()
        } catch (t: Throwable) {
            Log.w("GPU_BENCHMARK", "SAM2 image_features STRICT_GPU failed: ${t.message}")
        }

        // 2. Benchmark SAM2 Image Features STRICT_CPU
        val runnerCpu = LiteRtModelRunner.fromAsset(
            context = context,
            assetPath = Sam2TensorContract.MODEL_IMAGE_FEATURES,
            policy = LiteRtRunnerPolicy.STRICT_CPU
        )
        runnerCpu.initialize()
        val inBufsCpu = runnerCpu.getInputBuffers()
        inBufsCpu[0].writeFloat(dummyInput)

        for (i in 0 until 2) {
            runnerCpu.runInference()
        }

        val cpuLatencies = mutableListOf<Double>()
        for (i in 0 until 10) {
            val t0 = System.nanoTime()
            runnerCpu.runInference()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            cpuLatencies.add(elapsedMs)
        }

        val cpuCompileMs = runnerCpu.runtimeInfo?.compileMs ?: 0L
        val cpuWarmupMs = runnerCpu.runtimeInfo?.warmupMs ?: 0L
        val cpuStats = calculateStats(
            modelName = "sam2_image_features.tflite",
            requested = LiteRtAccelerator.CPU,
            effective = runnerCpu.effectiveAccelerator,
            compileMs = cpuCompileMs,
            warmupMs = cpuWarmupMs,
            latencies = cpuLatencies
        )
        Log.i("GPU_BENCHMARK", cpuStats.toString())
        runnerCpu.close()

        if (gpuStats != null) {
            val speedup = if (gpuStats.p50Ms > 0) cpuStats.p50Ms / gpuStats.p50Ms else 0.0
            Log.i("GPU_BENCHMARK", "[SAM2 Image Speedup] CPU p50: ${cpuStats.p50Ms}ms / GPU p50: ${gpuStats.p50Ms}ms = ${"%.2f".format(speedup)}x")
        }
    }
}
