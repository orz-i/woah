package com.danceanon.native.diagnostics

import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Debug-only sparse tensor capture for cross-device YOLO analysis.
 *
 * Captures deterministic samples from the exact NCHW model input and both LiteRT output tensors
 * for the early portion of an export. The live tensors are never modified.
 */
class YoloTensorDiagnostics(
    private val jobId: String,
    private val artifactMaxPtsUs: Long = 450_000L,
    private val signatureMaxPtsUs: Long = artifactMaxPtsUs,
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
        if (!com.danceanon.dance_native.BuildConfig.DEBUG || ptsUs < 0L || ptsUs > signatureMaxPtsUs) return
        if (!capturedPts.add(ptsUs)) return

        if (ptsUs > artifactMaxPtsUs) {
            NativeDiagnostics.event(
                level = "INFO",
                component = "YoloTensorDiagnostics",
                event = "YOLO_DETECTION_SIGNATURE_CAPTURED",
                fields = mapOf(
                    "job_id" to jobId,
                    "pts_us" to ptsUs,
                    "detection_count" to detections.size,
                    "detections" to associationDetectionSignature(detections)
                )
            )
            return
        }

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
                val maskBuffer = detection.mask?.buffer
                val associationMask = maskBuffer?.let(::associationMaskSummary)
                mapOf(
                    "index" to index,
                    "confidence_q1e4" to (detection.confidence * 10_000f).roundToInt(),
                    "bbox_q0_0625px" to listOf(
                        (detection.bbox.left * 16f).roundToInt(),
                        (detection.bbox.top * 16f).roundToInt(),
                        (detection.bbox.right * 16f).roundToInt(),
                        (detection.bbox.bottom * 16f).roundToInt()
                    ),
                    "mask_width" to detection.mask?.width,
                    "mask_height" to detection.mask?.height,
                    "mask_sha256" to maskBuffer?.let(::sha256),
                    "mask_assoc_binary_sha256" to associationMask?.sha256,
                    "mask_assoc_foreground_pixels" to associationMask?.foregroundPixels,
                    "mask_assoc_near_threshold_pixels" to associationMask?.nearThresholdPixels
                )
            }

        private fun associationDetectionSignature(detections: List<PersonDetection>): List<Map<String, Any?>> =
            detections.mapIndexed { index, detection ->
                val associationMask = detection.mask?.buffer?.let(::associationMaskSummary)
                mapOf(
                    "index" to index,
                    "confidence_q1e4" to (detection.confidence * 10_000f).roundToInt(),
                    "bbox_q0_0625px" to listOf(
                        (detection.bbox.left * 16f).roundToInt(),
                        (detection.bbox.top * 16f).roundToInt(),
                        (detection.bbox.right * 16f).roundToInt(),
                        (detection.bbox.bottom * 16f).roundToInt()
                    ),
                    "mask_width" to detection.mask?.width,
                    "mask_height" to detection.mask?.height,
                    "mask_assoc_binary_sha256" to associationMask?.sha256,
                    "mask_assoc_foreground_pixels" to associationMask?.foregroundPixels,
                    "mask_assoc_near_threshold_pixels" to associationMask?.nearThresholdPixels
                )
            }

        internal data class AssociationMaskSummary(
            val sha256: String,
            val foregroundPixels: Int,
            val nearThresholdPixels: Int
        )

        /**
         * Mirrors TrackManager's association-mask semantics exactly: a mask byte is foreground
         * only when its unsigned value is > 128. The source buffer is duplicated so diagnostics
         * cannot change the live mask position or contents.
         */
        internal fun associationMaskSummary(buffer: ByteBuffer): AssociationMaskSummary {
            val digest = MessageDigest.getInstance("SHA-256")
            val duplicate = buffer.duplicate().apply { rewind() }
            val binaryChunk = ByteArray(4096)
            var foregroundPixels = 0
            var nearThresholdPixels = 0

            while (duplicate.hasRemaining()) {
                val count = minOf(binaryChunk.size, duplicate.remaining())
                for (i in 0 until count) {
                    val value = duplicate.get().toInt() and 0xff
                    if (value > 128) {
                        binaryChunk[i] = 0xff.toByte()
                        foregroundPixels++
                    } else {
                        binaryChunk[i] = 0
                    }
                    if (value in 127..130) {
                        nearThresholdPixels++
                    }
                }
                digest.update(binaryChunk, 0, count)
            }

            return AssociationMaskSummary(
                sha256 = digest.digest().joinToString("") {
                    "%02x".format(Locale.US, it.toInt() and 0xff)
                },
                foregroundPixels = foregroundPixels,
                nearThresholdPixels = nearThresholdPixels
            )
        }

        private fun sha256(buffer: ByteBuffer): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val duplicate = buffer.duplicate().apply { rewind() }
            val chunk = ByteArray(4096)
            while (duplicate.hasRemaining()) {
                val count = minOf(chunk.size, duplicate.remaining())
                duplicate.get(chunk, 0, count)
                digest.update(chunk, 0, count)
            }
            return digest.digest().joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            }
        }

        private fun sampleIndex(sample: Int, samples: Int, size: Int): Int {
            if (samples <= 1 || size <= 1) return 0
            return ((sample.toLong() * (size - 1).toLong()) / (samples - 1).toLong()).toInt()
        }
    }
}
