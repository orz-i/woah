package com.danceanon.native.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder

object YoloPostprocessor {

    private const val PROTO_SIZE = 160
    private const val PROTO_CHANNELS = 32
    private const val ATTR_COUNT = 116
    private const val ANCHOR_COUNT = 8400

    fun postprocessBuffer(
        output0Buffer: java.nio.FloatBuffer,
        output1Buffer: java.nio.FloatBuffer,
        preprocess: PreprocessResult,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.50f
    ): List<PersonDetection> {
        val candidates = mutableListOf<RawCandidate>()

        // 1. Parse raw output0 [1, 116, 8400]
        for (i in 0 until ANCHOR_COUNT) {
            val conf = output0Buffer.get(4 * ANCHOR_COUNT + i)
            if (conf >= confThreshold) {
                val cx = output0Buffer.get(0 * ANCHOR_COUNT + i)
                val cy = output0Buffer.get(1 * ANCHOR_COUNT + i)
                val w = output0Buffer.get(2 * ANCHOR_COUNT + i)
                val h = output0Buffer.get(3 * ANCHOR_COUNT + i)

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

        // 2. Perform Mask-Aware Non-Maximum Suppression (NMS)
        val protoView = NchwBufferProtoView(output1Buffer, PROTO_CHANNELS, PROTO_SIZE)
        val (kept, maskCache) = YoloMaskDecoder.maskAwareNms(
            candidates = candidates,
            protoView = protoView,
            bboxIouThreshold = iouThreshold,
            maskIouThreshold = YoloMaskDecoder.DEFAULT_MASK_IOU_THRESHOLD,
            inputSize = preprocess.inputSize,
            protoSize = PROTO_SIZE
        )

        // 3. Process kept detections: map coordinates and generate masks
        val detections = mutableListOf<PersonDetection>()

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

            val mapper = com.danceanon.native.geometry.ModelCoordinateMapper(
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

        // 2. Perform Mask-Aware Non-Maximum Suppression (NMS)
        val proto = output1[0] // [32, 160, 160]
        val protoView = ArrayProtoView(proto, PROTO_CHANNELS)
        val (kept, maskCache) = YoloMaskDecoder.maskAwareNms(
            candidates = candidates,
            protoView = protoView,
            bboxIouThreshold = iouThreshold,
            maskIouThreshold = YoloMaskDecoder.DEFAULT_MASK_IOU_THRESHOLD,
            inputSize = preprocess.inputSize,
            protoSize = PROTO_SIZE
        )

        // 3. Process kept detections: map coordinates and generate masks
        val detections = mutableListOf<PersonDetection>()

        for (cand in kept) {
            // Map box back to original video dimensions (inverse letterbox)
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

            val mapper = com.danceanon.native.geometry.ModelCoordinateMapper(
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

        // 4. Sort strictly left-to-right by center X coordinate to match Python Golden Reference
        detections.sortBy { it.bbox.centerX }
        return detections
    }
}

