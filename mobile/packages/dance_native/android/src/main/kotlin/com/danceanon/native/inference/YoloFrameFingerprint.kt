package com.danceanon.native.inference

import java.nio.ByteBuffer
import kotlin.math.roundToLong

/**
 * Behavior-neutral, sparse fingerprints for cross-device YOLO diagnostics.
 *
 * The goal is not cryptographic identity.  The same fixed sample positions are
 * quantized at a few useful precisions so logs can tell whether divergence first
 * appears in decoder/GL pixels, preprocessing, LiteRT output, or final detections
 * without copying full tensors into diagnostics.
 */
internal object YoloFrameFingerprint {
    private const val SAMPLE_COUNT = 1024
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    fun sampledByteHash(buffer: ByteBuffer, shiftRight: Int = 0): String {
        val size = buffer.capacity()
        if (size <= 0) return hex(FNV_OFFSET_BASIS)

        var hash = FNV_OFFSET_BASIS
        val samples = minOf(SAMPLE_COUNT, size)
        for (sample in 0 until samples) {
            val index = sampleIndex(sample, samples, size)
            val value = (buffer.get(index).toInt() and 0xFF) ushr shiftRight
            hash = mixInt(hash, value)
        }
        return hex(hash)
    }

    fun sampledFloatHash(values: FloatArray, scale: Double): String {
        if (values.isEmpty()) return hex(FNV_OFFSET_BASIS)

        var hash = FNV_OFFSET_BASIS
        val samples = minOf(SAMPLE_COUNT, values.size)
        for (sample in 0 until samples) {
            val index = sampleIndex(sample, samples, values.size)
            val value = quantize(values[index], scale)
            hash = mixInt(hash, value)
        }
        return hex(hash)
    }

    fun detectionSignature(detections: List<PersonDetection>): List<Map<String, Any?>> =
        detections.mapIndexed { index, detection ->
            mapOf(
                "index" to index,
                "confidence_q1e3" to quantize(detection.confidence, 1_000.0),
                "bbox_q0_25px" to listOf(
                    quantize(detection.bbox.left, 4.0),
                    quantize(detection.bbox.top, 4.0),
                    quantize(detection.bbox.right, 4.0),
                    quantize(detection.bbox.bottom, 4.0)
                ),
                "mask_q4_sample_hash" to detection.mask?.let { sampledByteHash(it.buffer, shiftRight = 4) }
            )
        }

    private fun sampleIndex(sample: Int, samples: Int, size: Int): Int {
        if (samples <= 1 || size <= 1) return 0
        return ((sample.toLong() * (size - 1).toLong()) / (samples - 1).toLong()).toInt()
    }

    private fun quantize(value: Float, scale: Double): Int {
        if (value.isNaN()) return Int.MIN_VALUE
        if (value == Float.POSITIVE_INFINITY) return Int.MAX_VALUE
        if (value == Float.NEGATIVE_INFINITY) return Int.MIN_VALUE + 1
        return (value.toDouble() * scale)
            .roundToLong()
            .coerceIn(Int.MIN_VALUE.toLong() + 2L, Int.MAX_VALUE.toLong() - 1L)
            .toInt()
    }

    private fun mixInt(initial: Long, value: Int): Long {
        var hash = initial
        for (shift in 0..24 step 8) {
            hash = (hash xor ((value ushr shift) and 0xFF).toLong()) * FNV_PRIME
        }
        return hash
    }

    private fun hex(value: Long): String =
        java.lang.Long.toUnsignedString(value, 16).padStart(16, '0')
}
