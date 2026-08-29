package com.danceanon.native.benchmark

import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer

internal data class BenchmarkFaceDetection(
    val bboxInModelPixels: FloatRect,
    val confidence: Float
)

internal data class FaceBackendResult(
    val detections: List<BenchmarkFaceDetection>,
    val inferenceMs: Double
)

/**
 * Instrumentation-only adapter boundary so MediaPipe and ML Kit can be compared
 * on identical RGBA frames without introducing either detector into production.
 */
internal interface FaceBenchmarkBackend : AutoCloseable {
    val name: String

    fun detect(
        rgbaTopDown: ByteBuffer,
        width: Int,
        height: Int,
        timestampMs: Long
    ): FaceBackendResult
}
