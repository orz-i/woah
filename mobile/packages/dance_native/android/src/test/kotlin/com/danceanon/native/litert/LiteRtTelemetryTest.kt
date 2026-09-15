package com.danceanon.native.litert

import com.danceanon.native.bridge.DanceNativeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteRtTelemetryTest {
    @Test
    fun testLiteRtRuntimeInfoFields() {
        val info = LiteRtRuntimeInfo(
            modelName = "yolo11n-seg-fp16.tflite",
            requestedAccelerator = LiteRtAccelerator.GPU,
            effectiveAccelerator = LiteRtAccelerator.GPU,
            compileMs = 120L,
            warmupMs = 45L,
            fallbackReason = null,
            cpuNumThreads = 4,
            inputShapes = listOf(listOf(1, 3, 640, 640)),
            outputShapes = listOf(listOf(1, 116, 8400), listOf(1, 32, 160, 160))
        )

        assertEquals("yolo11n-seg-fp16.tflite", info.modelName)
        assertEquals(LiteRtAccelerator.GPU, info.requestedAccelerator)
        assertEquals(LiteRtAccelerator.GPU, info.effectiveAccelerator)
        assertEquals(120L, info.compileMs)
        assertEquals(45L, info.warmupMs)
        assertEquals(null, info.fallbackReason)
        assertEquals(4, info.cpuNumThreads)
        assertEquals(1, info.inputShapes.size)
        assertEquals(2, info.outputShapes.size)
    }

    @Test
    fun testStrictGpuExceptionMessagePreservesModelNameAndDetails() {
        val cause = RuntimeException("clCreateContext failed with -1")
        val ex = DanceNativeException(
            DanceNativeException.MODEL_INIT_FAILED,
            "Failed strict GPU initialization for LiteRT model 'yolo11n-seg-fp16.tflite': ${cause.message}",
            cause
        )
        assertTrue(ex.message!!.contains("yolo11n-seg-fp16.tflite"))
        assertTrue(ex.message!!.contains("clCreateContext failed with -1"))
        assertTrue(ex.message!!.contains("MODEL_INIT_FAILED"))
    }
}
