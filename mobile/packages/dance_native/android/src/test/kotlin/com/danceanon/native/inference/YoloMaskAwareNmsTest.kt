package com.danceanon.native.inference

import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

class YoloMaskAwareNmsTest {

    @Test
    fun channelMajorNchwDecodeMatchesPerPixelReferenceAcrossCandidateBounds() {
        val protoSize = 160
        val channels = 32
        val random = Random(20260831)
        val proto = FloatArray(channels * protoSize * protoSize) { random.nextFloat() * 2f - 1f }
        val view = NchwArrayProtoView(proto, channels, protoSize)
        val scratch = FloatArray(protoSize * protoSize)
        val candidates = listOf(
            RawCandidate(0f, 0f, 640f, 640f, 0.9f, FloatArray(channels) { random.nextFloat() * 2f - 1f }),
            RawCandidate(97.5f, 31.25f, 412.75f, 601.5f, 0.8f, FloatArray(channels) { random.nextFloat() * 2f - 1f }),
            RawCandidate(610f, 590f, 680f, 700f, 0.7f, FloatArray(channels) { random.nextFloat() * 2f - 1f })
        )

        for (candidate in candidates) {
            val reference = YoloMaskDecoder.decodeCandidateMask(candidate, view, 640, protoSize)
            val optimized = view.decodeCandidateMask(candidate, 640, scratch)
            assertTrue(reference.contentEquals(optimized), "channel-major NCHW decode must be byte-exact")
        }
    }

    @Test
    fun arrayBackedProtoViewsMatchBufferBackedMaskBytesExactly() {
        val protoSize = 8
        val channels = 4
        val coeffs = floatArrayOf(0.75f, -0.25f, 0.5f, 0.125f)
        val nchw = FloatArray(channels * protoSize * protoSize) { index ->
            ((index * 37 % 101) - 50) / 17f
        }
        val nhwc = FloatArray(channels * protoSize * protoSize)
        for (y in 0 until protoSize) {
            for (x in 0 until protoSize) {
                val pixel = y * protoSize + x
                for (c in 0 until channels) {
                    nhwc[pixel * channels + c] = nchw[c * protoSize * protoSize + pixel]
                }
            }
        }

        val candidate = RawCandidate(
            x1 = 80f,
            y1 = 80f,
            x2 = 560f,
            y2 = 560f,
            confidence = 0.9f,
            maskCoeffs = coeffs
        )

        val nchwBufferMask = YoloMaskDecoder.decodeCandidateMask(
            candidate,
            NchwBufferProtoView(FloatBuffer.wrap(nchw), channels, protoSize),
            inputSize = 640,
            protoSize = protoSize
        )
        val nchwArrayMask = YoloMaskDecoder.decodeCandidateMask(
            candidate,
            NchwArrayProtoView(nchw, channels, protoSize),
            inputSize = 640,
            protoSize = protoSize
        )
        val nhwcBufferMask = YoloMaskDecoder.decodeCandidateMask(
            candidate,
            NhwcBufferProtoView(FloatBuffer.wrap(nhwc), channels, protoSize),
            inputSize = 640,
            protoSize = protoSize
        )
        val nhwcArrayMask = YoloMaskDecoder.decodeCandidateMask(
            candidate,
            NhwcArrayProtoView(nhwc, channels, protoSize),
            inputSize = 640,
            protoSize = protoSize
        )

        assertTrue(nchwBufferMask.contentEquals(nchwArrayMask))
        assertTrue(nchwBufferMask.contentEquals(nhwcBufferMask))
        assertTrue(nchwBufferMask.contentEquals(nhwcArrayMask))
    }

    private fun createSyntheticMask(width: Int = 160, height: Int = 160, activeRect: Pair<IntRange, IntRange>): ByteArray {
        val mask = ByteArray(width * height)
        for (y in activeRect.second) {
            for (x in activeRect.first) {
                mask[y * width + x] = 255.toByte()
            }
        }
        return mask
    }

    @Test
    fun testCaseA_TwoOverlappingPersonsBothSurvive() {
        // Person A: left [100, 100, 300, 500], mask occupies left half of bbox
        // Person B: right [150, 100, 350, 500], mask occupies right half of bbox
        // High bbox overlap, but distinct masks (low mask IoU)
        val maskA = createSyntheticMask(160, 160, Pair(25..50, 25..125))
        val maskB = createSyntheticMask(160, 160, Pair(55..80, 25..125))

        val candA = RawCandidate(
            x1 = 100f, y1 = 100f, x2 = 300f, y2 = 500f,
            confidence = 0.88f,
            maskCoeffs = FloatArray(32),
            syntheticMask = maskA
        )
        val candB = RawCandidate(
            x1 = 150f, y1 = 100f, x2 = 350f, y2 = 500f,
            confidence = 0.82f,
            maskCoeffs = FloatArray(32),
            syntheticMask = maskB
        )

        val bboxIoU = YoloMaskDecoder.calculateBboxIoU(candA, candB)
        val maskIoU = YoloMaskDecoder.calculateMaskIoU(maskA, maskB)

        assertTrue(bboxIoU > 0.50f, "Bbox IoU should be high (overlapping people): $bboxIoU")
        assertTrue(maskIoU < 0.20f, "Mask IoU should be low: $maskIoU")

        val (kept, _) = YoloMaskDecoder.maskAwareNms(
            candidates = listOf(candA, candB),
            protoView = null,
            bboxIouThreshold = 0.50f,
            maskIouThreshold = 0.50f
        )

        assertEquals(2, kept.size, "Both overlapping persons must survive mask-aware NMS")
    }

    @Test
    fun testCaseB_DuplicateDetectionIsSuppressed() {
        // High bbox overlap, high mask overlap
        val maskA = createSyntheticMask(160, 160, Pair(30..70, 30..120))
        val maskB = createSyntheticMask(160, 160, Pair(32..72, 30..120))

        val candA = RawCandidate(
            x1 = 100f, y1 = 100f, x2 = 300f, y2 = 500f,
            confidence = 0.90f,
            maskCoeffs = FloatArray(32),
            syntheticMask = maskA
        )
        val candB = RawCandidate(
            x1 = 105f, y1 = 102f, x2 = 305f, y2 = 502f,
            confidence = 0.70f,
            maskCoeffs = FloatArray(32),
            syntheticMask = maskB
        )

        val bboxIoU = YoloMaskDecoder.calculateBboxIoU(candA, candB)
        val maskIoU = YoloMaskDecoder.calculateMaskIoU(maskA, maskB)

        assertTrue(bboxIoU > 0.70f, "Bbox IoU should be high: $bboxIoU")
        assertTrue(maskIoU > 0.80f, "Mask IoU should be high: $maskIoU")

        val (kept, _) = YoloMaskDecoder.maskAwareNms(
            candidates = listOf(candA, candB),
            protoView = null,
            bboxIouThreshold = 0.50f,
            maskIouThreshold = 0.50f
        )

        assertEquals(1, kept.size, "Duplicate candidate must be suppressed")
        assertEquals(candA, kept[0], "Higher confidence candidate must be kept")
    }

    @Test
    fun testCaseC_NonOverlappingBboxesBothSurvive() {
        val maskA = createSyntheticMask(160, 160, Pair(10..30, 10..50))
        val maskB = createSyntheticMask(160, 160, Pair(100..120, 10..50))

        val candA = RawCandidate(
            x1 = 50f, y1 = 50f, x2 = 150f, y2 = 250f,
            confidence = 0.85f,
            maskCoeffs = FloatArray(32),
            syntheticMask = maskA
        )
        val candB = RawCandidate(
            x1 = 400f, y1 = 50f, x2 = 500f, y2 = 250f,
            confidence = 0.80f,
            maskCoeffs = FloatArray(32),
            syntheticMask = maskB
        )

        val bboxIoU = YoloMaskDecoder.calculateBboxIoU(candA, candB)
        assertEquals(0f, bboxIoU)

        val (kept, _) = YoloMaskDecoder.maskAwareNms(
            candidates = listOf(candA, candB),
            protoView = null,
            bboxIouThreshold = 0.50f,
            maskIouThreshold = 0.50f
        )

        assertEquals(2, kept.size, "Non-overlapping candidates must both survive")
    }
}
