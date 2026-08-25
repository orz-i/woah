package com.danceanon.native.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class RawCandidate(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val maskCoeffs: FloatArray
)

object YoloPostprocessor {

    private const val PROTO_SIZE = 160
    private const val PROTO_CHANNELS = 32
    private const val ATTR_COUNT = 116
    private const val ANCHOR_COUNT = 8400

    fun postprocess(
        output0: Array<Array<FloatArray>>, // [1, 116, 8400] or flattened
        output1: Array<Array<Array<FloatArray>>>, // [1, 32, 160, 160]
        preprocess: PreprocessResult,
        confThreshold: Float = 0.35f,
        iouThreshold: Float = 0.50f
    ): List<PersonDetection> {
        val candidates = mutableListOf<RawCandidate>()

        // 1. Parse raw output0 [1, 116, 8400]
        val batch0 = output0[0]
        for (i in 0 until ANCHOR_COUNT) {
            val conf = batch0[4][i]
            if (conf >= confThreshold) {
                val cx = batch0[0][i]
                val cy = batch0[1][i]
                val w = batch0[2][i]
                val h = batch0[3][i]

                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f

                val coeffs = FloatArray(PROTO_CHANNELS) { k ->
                    batch0[84 + k][i]
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

        // 2. Perform Non-Maximum Suppression (NMS)
        val kept = nms(candidates, iouThreshold)

        // 3. Process kept detections: map coordinates and generate masks
        val detections = mutableListOf<PersonDetection>()
        val proto = output1[0] // [32, 160, 160]

        for (cand in kept) {
            // Map box back to original video dimensions (inverse letterbox)
            val srcX1 = ((cand.x1 - preprocess.padLeft) / preprocess.scale).coerceIn(0f, preprocess.srcWidth.toFloat())
            val srcY1 = ((cand.y1 - preprocess.padTop) / preprocess.scale).coerceIn(0f, preprocess.srcHeight.toFloat())
            val srcX2 = ((cand.x2 - preprocess.padLeft) / preprocess.scale).coerceIn(0f, preprocess.srcWidth.toFloat())
            val srcY2 = ((cand.y2 - preprocess.padTop) / preprocess.scale).coerceIn(0f, preprocess.srcHeight.toFloat())

            val bbox = FloatRect(left = srcX1, top = srcY1, right = srcX2, bottom = srcY2)

            // Generate R8 mask (1 byte per pixel) on proto resolution 160x160
            val maskBuffer = ByteBuffer.allocateDirect(PROTO_SIZE * PROTO_SIZE)
            maskBuffer.order(ByteOrder.nativeOrder())

            val protoX1 = ((cand.x1 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)
            val protoY1 = ((cand.y1 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)
            val protoX2 = ((cand.x2 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)
            val protoY2 = ((cand.y2 / preprocess.inputSize) * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE)

            for (py in 0 until PROTO_SIZE) {
                for (px in 0 until PROTO_SIZE) {
                    if (px in protoX1 until protoX2 && py in protoY1 until protoY2) {
                        var sum = 0f
                        for (c in 0 until PROTO_CHANNELS) {
                            sum += cand.maskCoeffs[c] * proto[c][py][px]
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

            val nativeMask = NativeMask(
                width = PROTO_SIZE,
                height = PROTO_SIZE,
                buffer = maskBuffer,
                originalWidth = preprocess.srcWidth,
                originalHeight = preprocess.srcHeight
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

        // 4. Sort strictly left-to-right by center X coordinate to match Python Golden Reference
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
