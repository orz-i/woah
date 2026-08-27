package com.danceanon.native.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YoloMaskAwareNmsTest {

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
