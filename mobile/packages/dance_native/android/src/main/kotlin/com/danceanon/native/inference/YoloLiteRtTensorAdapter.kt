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
        val candidates = mutableListOf<RawCandidate>()
        val inputSize = preprocess.inputSize.toFloat()

        if (!isOut0Transposed) {
            // [1, 116, 8400] layout
            for (i in 0 until ANCHOR_COUNT) {
                val conf = output0Buffer.get(4 * ANCHOR_COUNT + i)
                if (conf >= confThreshold) {
                    var cx = output0Buffer.get(0 * ANCHOR_COUNT + i)
                    var cy = output0Buffer.get(1 * ANCHOR_COUNT + i)
                    var w = output0Buffer.get(2 * ANCHOR_COUNT + i)
                    var h = output0Buffer.get(3 * ANCHOR_COUNT + i)

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
                        output0Buffer.get((84 + k) * ANCHOR_COUNT + i)
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
                val conf = output0Buffer.get(anchorOffset + 4)
                if (conf >= confThreshold) {
                    var cx = output0Buffer.get(anchorOffset + 0)
                    var cy = output0Buffer.get(anchorOffset + 1)
                    var w = output0Buffer.get(anchorOffset + 2)
                    var h = output0Buffer.get(anchorOffset + 3)

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
                        output0Buffer.get(anchorOffset + 84 + k)
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

        // NMS
        val kept = nms(candidates, iouThreshold)

        // Process kept detections
        val detections = mutableListOf<PersonDetection>()
        val protoPixels = PROTO_SIZE * PROTO_SIZE

        for (cand in kept) {
            val srcX1 = ((cand.x1 - preprocess.padLeft) / preprocess.scale).coerceIn(0f, preprocess.srcWidth.toFloat())
            val srcY1 = ((cand.y1 - preprocess.padTop) / preprocess.scale).coerceIn(0f, preprocess.srcHeight.toFloat())
            val srcX2 = ((cand.x2 - preprocess.padLeft) / preprocess.scale).coerceIn(0f, preprocess.srcWidth.toFloat())
            val srcY2 = ((cand.y2 - preprocess.padTop) / preprocess.scale).coerceIn(0f, preprocess.srcHeight.toFloat())

            val bbox = FloatRect(left = srcX1, top = srcY1, right = srcX2, bottom = srcY2)

            val maskBuffer = ByteBuffer.allocateDirect(PROTO_SIZE * PROTO_SIZE).apply {
                order(ByteOrder.nativeOrder())
            }

            val protoX1 = ((cand.x1 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)
            val protoY1 = ((cand.y1 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)
            val protoX2 = ((cand.x2 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)
            val protoY2 = ((cand.y2 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)

            for (py in 0 until PROTO_SIZE) {
                for (px in 0 until PROTO_SIZE) {
                    if (px in protoX1 until protoX2 && py in protoY1 until protoY2) {
                        var sum = 0f
                        if (!isOut1Nhwc) {
                            // [1, 32, 160, 160]
                            val pixelOffset = py * PROTO_SIZE + px
                            for (c in 0 until PROTO_CHANNELS) {
                                sum += cand.maskCoeffs[c] * output1Buffer.get(c * protoPixels + pixelOffset)
                            }
                        } else {
                            // [1, 160, 160, 32]
                            val pixelOffset = (py * PROTO_SIZE + px) * PROTO_CHANNELS
                            for (c in 0 until PROTO_CHANNELS) {
                                sum += cand.maskCoeffs[c] * output1Buffer.get(pixelOffset + c)
                            }
                        }
                        val prob = 1f / (1f + exp(-sum))
                        val byteVal = if (prob > 0.5f) 255.toByte() else 0.toByte()
                        maskBuffer.put(byteVal)
                    } else {
                        maskBuffer.put(0.toByte())
                    }
                }
            }
            maskBuffer.rewind()

            val mapper = ModelCoordinateMapper(
                srcWidth = preprocess.srcWidth,
                srcHeight = preprocess.srcHeight,
                modelInputSize = preprocess.inputSize,
                protoSize = PROTO_SIZE
            )

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
        return detections
    }

    private fun nms(candidates: List<RawCandidate>, iouThresh: Float): List<RawCandidate> {
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<RawCandidate>()

        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            selected.add(current)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(current, next) > iouThresh) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun calculateIoU(a: RawCandidate, b: RawCandidate): Float {
        val interX1 = max(a.x1, b.x1)
        val interY1 = max(a.y1, b.y1)
        val interX2 = min(a.x2, b.x2)
        val interY2 = min(a.y2, b.y2)

        val interW = max(0f, interX2 - interX1)
        val interH = max(0f, interY2 - interY1)
        val interArea = interW * interH

        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}
