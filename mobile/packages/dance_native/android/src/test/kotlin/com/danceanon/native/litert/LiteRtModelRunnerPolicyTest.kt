package com.danceanon.native.litert

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteRtModelRunnerPolicyTest {

    @Test
    fun testPolicyDefaultsAndPresets() {
        val strictGpu = LiteRtRunnerPolicy.STRICT_GPU
        assertEquals(LiteRtAccelerator.GPU, strictGpu.requestedAccelerator)
        assertFalse(strictGpu.allowCpuFallback)
        assertFalse(strictGpu.requireWarmupSuccess)

        val gpuFallback = LiteRtRunnerPolicy.GPU_WITH_CPU_FALLBACK
        assertEquals(LiteRtAccelerator.GPU, gpuFallback.requestedAccelerator)
        assertTrue(gpuFallback.allowCpuFallback)
        assertFalse(gpuFallback.requireWarmupSuccess)

        val strictCpu = LiteRtRunnerPolicy.STRICT_CPU
        assertEquals(LiteRtAccelerator.CPU, strictCpu.requestedAccelerator)
        assertFalse(strictCpu.allowCpuFallback)
        assertFalse(strictCpu.requireWarmupSuccess)
    }

    @Test
    fun testRunnerCloseIsIdempotent() {
        val runner = LiteRtModelRunner(
            modelName = "test_model",
            modelFile = File("non_existent.tflite"),
            policy = LiteRtRunnerPolicy.STRICT_GPU
        )
        // Close once
        runner.close()
        // Close again - should not throw
        runner.close()
    }
}
