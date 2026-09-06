package com.danceanon.native.diagnostics

import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer

/** Release no-op for tensor/artifact/signature diagnostics. */
class YoloTensorDiagnostics(
    jobId: String,
    artifactMaxPtsUs: Long = 450_000L,
    signatureMaxPtsUs: Long = artifactMaxPtsUs,
    maxSamplesPerTensor: Int = 4096
) {
    fun maybeCapture(
        ptsUs: Long,
        input: FloatArray,
        output0: FloatArray,
        output1: FloatArray,
        detections: List<PersonDetection>
    ) = Unit

    companion object {
        internal fun sampledFloatBytes(values: FloatArray, maxSamples: Int): ByteArray = ByteArray(0)
        internal fun detectionSignature(detections: List<PersonDetection>): List<Map<String, Any?>> = emptyList()
        internal fun recordGeometrySignature(jobId: String, ptsUs: Long, detections: List<PersonDetection>) = Unit
        internal fun geometryDetectionSignature(detections: List<PersonDetection>): List<Map<String, Any?>> = emptyList()

        internal data class AssociationMaskSummary(
            val sha256: String,
            val foregroundPixels: Int,
            val nearThresholdPixels: Int
        )

        internal fun associationMaskSummary(buffer: ByteBuffer): AssociationMaskSummary =
            AssociationMaskSummary("", 0, 0)
    }
}
