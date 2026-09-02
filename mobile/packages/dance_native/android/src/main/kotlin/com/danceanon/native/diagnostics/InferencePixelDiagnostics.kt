package com.danceanon.native.diagnostics

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/**
 * Debug-only lossless capture at the exact RGBA boundary consumed by YOLO.
 *
 * This intentionally does not transform, quantize, normalize, or otherwise mutate the input
 * buffer. It copies only the first few inference frames so two device diagnostic bundles can be
 * compared pixel-for-pixel offline at identical PTS values.
 */
class InferencePixelDiagnostics(
    private val jobId: String,
    private val width: Int,
    private val height: Int,
    private val maxCaptures: Int = 3
) {
    private var captured = 0

    fun maybeCapture(
        rgbaBuffer: ByteBuffer,
        ptsUs: Long,
        surfaceTransform: FloatArray,
        decoderFormatFields: Map<String, Any?> = emptyMap()
    ) {
        if (!com.danceanon.dance_native.BuildConfig.DEBUG || captured >= maxCaptures) return

        val expectedBytes = width * height * 4
        val duplicate = rgbaBuffer.duplicate()
        duplicate.rewind()
        if (duplicate.remaining() < expectedBytes) {
            NativeDiagnostics.event(
                level = "WARN",
                component = "InferencePixelDiagnostics",
                event = "INFERENCE_RGBA_CAPTURE_SKIPPED",
                fields = mapOf(
                    "job_id" to jobId,
                    "pts_us" to ptsUs,
                    "expected_bytes" to expectedBytes,
                    "available_bytes" to duplicate.remaining()
                )
            )
            return
        }

        val bytes = ByteArray(expectedBytes)
        duplicate.get(bytes)
        captured++

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = "inference_rgba_${safeJobId}_${captured}_${ptsUs}_${width}x${height}.rgba"
        NativeDiagnostics.writeArtifactAsync(fileName, bytes)

        NativeDiagnostics.event(
            level = "INFO",
            component = "InferencePixelDiagnostics",
            event = "INFERENCE_RGBA_CAPTURED",
            fields = buildMap {
                put("job_id", jobId)
                put("capture_ordinal", captured)
                put("pts_us", ptsUs)
                put("width", width)
                put("height", height)
                put("byte_count", bytes.size)
                put("rgba_sha256", sha256)
                put("artifact", fileName)
                put("surface_transform", surfaceTransform.toList())
                putAll(decoderFormatFields)
            }
        )
    }
}
