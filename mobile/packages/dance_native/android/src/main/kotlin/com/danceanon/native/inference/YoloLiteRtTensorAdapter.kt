package com.danceanon.native.inference

import com.danceanon.native.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Adapter for YOLO11 segmentation LiteRT output tensor layouts.
 * Bridges raw TFLite outputs (NHWC or NCHW / transposed detection attributes)
 * into canonical PersonDetection instances, preserving exact geometry semantics.
 */
class YoloLiteRtTensorAdapter(
    val output0Shape: List<Int> = emptyList(),
    val output1Shape: List<Int> = emptyList()
) {
    companion object {
        const val PROTO_SIZE = 160
        const val PROTO_CHANNELS = 32
        const val ATTR_COUNT = 116
        const val ANCHOR_COUNT = 8400
        const val PERSON_CLASS_INDEX = 0
    }

    // Determine if output0 is [1, 116, 8400] or [1, 8400, 116]
    private val isOut0Transposed: Boolean = run {
        if (output0Shape.size == 3) {
            output0Shape[1] == ANCHOR_COUNT && output0Shape[2] == ATTR_COUNT
        } else false
    }

    // Determine if output1 is [1, 160, 160, 32] (NHWC) or [1, 32, 160, 160] (NCHW)
    private val isOut1Nhwc: Boolean = run {
        if (output1Shape.size == 4) {
            output1Shape[3] == PROTO_CHANNELS && output1Shape[1] == PROTO_SIZE && output1Shape[2] == PROTO_SIZE
        } else false
    }

    fun parseDetections(
        output0Buffer: FloatBuffer,
        output1Buffer: FloatBuffer,
        preprocess: PreprocessResult,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.50f
    ): List<PersonDetection> {
        val candidates = collectCandidates(
            preprocess = preprocess,
            confThreshold = confThreshold,
            getOutput0 = output0Buffer::get
        )

        val protoView: ProtoTensorView = if (!isOut1Nhwc) {
            NchwBufferProtoView(output1Buffer, PROTO_CHANNELS, PROTO_SIZE)
        } else {
            NhwcBufferProtoView(output1Buffer, PROTO_CHANNELS, PROTO_SIZE)
        }
        return finishDetections(candidates, protoView, preprocess, iouThreshold)
    }

    /**
     * Production fast path. LiteRT TensorBuffer.readFloat() already returns
     * FloatArray, so keeping both outputs as arrays avoids wrapping them in
     * FloatBuffer and then performing absolute FloatBuffer.get() calls in the
     * mask-decoder hot loop. Geometry/NMS/mask arithmetic is otherwise shared
     * with the compatibility FloatBuffer path above.
     */
    fun parseDetections(
        output0: FloatArray,
        output1: FloatArray,
        preprocess: PreprocessResult,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.50f,
        stageTimingsMs: MutableMap<String, Long>? = null
    ): List<PersonDetection> {
        val candidateStartNs = System.nanoTime()
        val candidates = collectCandidates(
            preprocess = preprocess,
            confThreshold = confThreshold,
            getOutput0 = { index -> output0[index] }
        )
        stageTimingsMs?.set(
            "yoloCandidateScan",
            (System.nanoTime() - candidateStartNs) / 1_000_000
        )

        val protoView: ProtoTensorView = if (!isOut1Nhwc) {
            NchwArrayProtoView(output1, PROTO_CHANNELS, PROTO_SIZE)
        } else {
            NhwcArrayProtoView(output1, PROTO_CHANNELS, PROTO_SIZE)
        }
        return finishDetections(
            candidates = candidates,
            protoView = protoView,
            preprocess = preprocess,
            iouThreshold = iouThreshold,
            stageTimingsMs = stageTimingsMs
        )
    }

    private inline fun collectCandidates(
        preprocess: PreprocessResult,
        confThreshold: Float,
        getOutput0: (Int) -> Float
    ): MutableList<RawCandidate> {
        val candidates = mutableListOf<RawCandidate>()
        val inputSize = preprocess.inputSize.toFloat()

        if (!isOut0Transposed) {
            // [1, 116, 8400] layout
            for (i in 0 until ANCHOR_COUNT) {
                val conf = getOutput0(4 * ANCHOR_COUNT + i)
                if (conf >= confThreshold) {
                    var cx = getOutput0(0 * ANCHOR_COUNT + i)
                    var cy = getOutput0(1 * ANCHOR_COUNT + i)
                    var w = getOutput0(2 * ANCHOR_COUNT + i)
                    var h = getOutput0(3 * ANCHOR_COUNT + i)

                    // LiteRT exported models output coordinates normalized in [0, 1]
                    if (cx <= 2.0f && w <= 2.0f) {
                        cx *= inputSize
                        cy *= inputSize
                        w *= inputSize
                        h *= inputSize
                    }

                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    val x2 = cx + w / 2f
                    val y2 = cy + h / 2f

                    val coeffs = FloatArray(PROTO_CHANNELS) { k ->
                        getOutput0((84 + k) * ANCHOR_COUNT + i)
                    }

                    candidates.add(
                        RawCandidate(
                            x1 = x1,
                            y1 = y1,
                            x2 = x2,
                            y2 = y2,
                            confidence = conf,
                            maskCoeffs = coeffs
                        )
                    )
                }
            }
        } else {
            // [1, 8400, 116] layout
            for (i in 0 until ANCHOR_COUNT) {
                val anchorOffset = i * ATTR_COUNT
                val conf = getOutput0(anchorOffset + 4)
                if (conf >= confThreshold) {
                    var cx = getOutput0(anchorOffset + 0)
                    var cy = getOutput0(anchorOffset + 1)
                    var w = getOutput0(anchorOffset + 2)
                    var h = getOutput0(anchorOffset + 3)

                    if (cx <= 2.0f && w <= 2.0f) {
                        cx *= inputSize
                        cy *= inputSize
                        w *= inputSize
                        h *= inputSize
                    }

                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    val x2 = cx + w / 2f
                    val y2 = cy + h / 2f

                    val coeffs = FloatArray(PROTO_CHANNELS) { k ->
                        getOutput0(anchorOffset + 84 + k)
                    }

                    candidates.add(
                        RawCandidate(
                            x1 = x1,
                            y1 = y1,
                            x2 = x2,
                            y2 = y2,
                            confidence = conf,
                            maskCoeffs = coeffs
                        )
                    )
                }
            }
        }
        return candidates
    }

    private fun finishDetections(
        candidates: List<RawCandidate>,
        protoView: ProtoTensorView,
        preprocess: PreprocessResult,
        iouThreshold: Float,
        stageTimingsMs: MutableMap<String, Long>? = null
    ): List<PersonDetection> {
        // 2. Perform Mask-Aware Non-Maximum Suppression (NMS)
        val nmsStartNs = System.nanoTime()
        val nmsTimings = YoloMaskDecoder.MaskAwareNmsTimings()
        val (kept, maskCache) = YoloMaskDecoder.maskAwareNms(
            candidates = candidates,
            protoView = protoView,
            bboxIouThreshold = iouThreshold,
            maskIouThreshold = YoloMaskDecoder.DEFAULT_MASK_IOU_THRESHOLD,
            inputSize = preprocess.inputSize,
            protoSize = PROTO_SIZE,
            timings = nmsTimings
        )
        stageTimingsMs?.set(
            "yoloMaskAwareNms",
            (System.nanoTime() - nmsStartNs) / 1_000_000
        )

        // 3. Process kept detections
        val materializeStartNs = System.nanoTime()
        val detections = ArrayList<PersonDetection>(kept.size)
        val mapper = ModelCoordinateMapper(
            srcWidth = preprocess.srcWidth,
            srcHeight = preprocess.srcHeight,
            modelInputSize = preprocess.inputSize,
            protoSize = PROTO_SIZE
        )

        for (cand in kept) {
            val srcX1 = ((cand.x1 - preprocess.padLeft) / preprocess.scale).coerceIn(0f, preprocess.srcWidth.toFloat())
            val srcY1 = ((cand.y1 - preprocess.padTop) / preprocess.scale).coerceIn(0f, preprocess.srcHeight.toFloat())
            val srcX2 = ((cand.x2 - preprocess.padLeft) / preprocess.scale).coerceIn(0f, preprocess.srcWidth.toFloat())
            val srcY2 = ((cand.y2 - preprocess.padTop) / preprocess.scale).coerceIn(0f, preprocess.srcHeight.toFloat())

            val bbox = FloatRect(left = srcX1, top = srcY1, right = srcX2, bottom = srcY2)

            val maskBytes = maskCache.getMask(cand)
            val maskBuffer = ByteBuffer.allocateDirect(PROTO_SIZE * PROTO_SIZE).apply {
                order(ByteOrder.nativeOrder())
                put(maskBytes)
                rewind()
            }

            val nativeMask = NativeMask(
                width = PROTO_SIZE,
                height = PROTO_SIZE,
                buffer = maskBuffer,
                originalWidth = preprocess.srcWidth,
                originalHeight = preprocess.srcHeight,
                mapper = mapper
            )

            detections.add(
                PersonDetection(
                    bbox = bbox,
                    confidence = cand.confidence,
                    mask = nativeMask,
                    footY = bbox.bottom
                )
            )
        }

        detections.sortBy { it.bbox.centerX }
        stageTimingsMs?.set("yoloMaskDecode", nmsTimings.maskDecodeNs / 1_000_000)
        stageTimingsMs?.set("yoloMaskLogitDecode", nmsTimings.maskLogitDecodeNs / 1_000_000)
        stageTimingsMs?.set("yoloMaskSoftMaterialize", nmsTimings.maskSoftMaterializeNs / 1_000_000)
        stageTimingsMs?.set("yoloMaskIouScan", nmsTimings.maskIouNs / 1_000_000)
        stageTimingsMs?.set(
            "yoloMaskMaterialize",
            (System.nanoTime() - materializeStartNs) / 1_000_000
        )
        return detections
    }
}
