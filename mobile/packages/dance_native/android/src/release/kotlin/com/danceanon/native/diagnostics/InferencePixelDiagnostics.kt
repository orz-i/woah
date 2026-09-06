package com.danceanon.native.diagnostics

import java.nio.ByteBuffer

/** Release no-op for debug-only RGBA artifact capture. */
class InferencePixelDiagnostics(
    jobId: String,
    width: Int,
    height: Int,
    maxCaptures: Int = 3
) {
    fun maybeCapture(
        rgbaBuffer: ByteBuffer,
        ptsUs: Long,
        surfaceTransform: FloatArray,
        decoderFormatFields: Map<String, Any?> = emptyMap()
    ) = Unit
}
