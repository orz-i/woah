package com.danceanon.native.diagnostics

import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Debug-only sparse tensor capture for cross-device YOLO analysis.
 *
 * Captures deterministic samples from the exact NCHW model input and both LiteRT output tensors
 * for the early portion of an export. The live tensors are never modified.
 */
class YoloTensorDiagnostics(
    private val jobId: String,
    private val maxPtsUs: Long = 450_000L,
    private val maxSamplesPerTensor: Int = 4096
) {
    private val capturedPts = mutableSetOf<Long>()
    private val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun maybeCapture(
        ptsUs: Long,
        input: FloatArray,
        output0: FloatArray,
        output1: FloatArray,
        detections: List<PersonDetection>
    ) {
        if (!com.danceanon.dance_native.BuildConfig.DEBUG || ptsUs < 0L || ptsUs > maxPtsUs) return
        if (!capturedPts.add(ptsUs)) return

        val inputArtifact = artifactName(ptsUs, "input")
        val output0Artifact = artifactName(ptsUs, "output0")
        val output1Artifact = artifactName(ptsUs, "output1")

        NativeDiagnostics.writeArtifactAsync(inputArtifact, sampledFloatBytes(input, maxSamplesPerTensor))
        NativeDiagnostics.writeArtifactAsync(output0Artifact, sampledFloatBytes(output0, maxSamplesPerTensor))
        NativeDiagnostics.writeArtifactAsync(output1Artifact, sampledFloatBytes(output1, maxSamplesPerTensor))

        NativeDiagnostics.event(
            level = "INFO",
            component = "YoloTensorDiagnostics",
            event = "YOLO_TENSOR_CAPTURED",
            fields = mapOf(
                "job_id" to jobId,
                "pts_us" to ptsUs,
                "sample_count" to maxSamplesPerTensor,
                "input_count" to input.size,
                "output0_count" to output0.size,
                "output1_count" to output1.size,
                "input_artifact" to inputArtifact,
                "output0_artifact" to output0Artifact,
                "output1_artifact" to output1Artifact,
                "detection_count" to detections.size,
                "detections" to detectionSignature(detections)
            )
        )
    }

    private fun artifactName(ptsUs: Long, tensorName: String): String =
        "yolo_tensor_${safeJobId}_${ptsUs}_${tensorName}.f32s"

    companion object {
        internal fun sampledFloatBytes(values: FloatArray, maxSamples: Int): ByteArray {
            if (values.isEmpty() || maxSamples <= 0) return ByteArray(0)
            val samples = minOf(maxSamples, values.size)
            val out = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in 0 until samples) {
                val index = sampleIndex(sample, samples, values.size)
                out.putFloat(values[index])
            }
            return out.array()
        }

        private fun detectionSignature(detections: List<PersonDetection>): List<Map<String, Any?>> =
            detections.mapIndexed { index, detection ->
                mapOf(
                    "index" to index,
                    "confidence_q1e4" to (detection.confidence * 10_000f).roundToInt(),
                    "bbox_q0_0625px" to listOf(
                        (detection.bbox.left * 16f).roundToInt(),
                        (detection.bbox.top * 16f).roundToInt(),
                        (detection.bbox.right * 16f).roundToInt(),
                        (detection.bbox.bottom * 16f).roundToInt()
                    )
                )
            }

        private fun sampleIndex(sample: Int, samples: Int, size: Int): Int {
            if (samples <= 1 || size <= 1) return 0
            return ((sample.toLong() * (size - 1).toLong()) / (samples - 1).toLong()).toInt()
        }
    }
}
