package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacePixelMotionTrackerTest {
    private val mapper = ModelCoordinateMapper(640, 640, 640, 160)

    @Test
    fun `unique face texture follows current pixels and preserves source size`() {
        val tracker = FacePixelMotionTracker()
        val first = frameWithPatch(centerX = 240, centerY = 180)
        val detected = FacePrivacyEllipse(
            centerX = 240f,
            centerY = 180f,
            radiusX = 18f,
            radiusY = 20f,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )
        assertTrue(tracker.seed(7, first, mapper, detected, 0L))

        val second = frameWithPatch(centerX = 249, centerY = 174)
        val match = assertNotNull(
            tracker.match(
                trackId = 7,
                rgbaBottomUp = second,
                mapper = mapper,
                ptsUs = 16_666L,
                personBbox = FloatRect(170f, 100f, 330f, 420f)
            )
        )
        assertTrue(abs(match.region.centerX - 249f) <= 1f)
        assertTrue(abs(match.region.centerY - 174f) <= 1f)
        assertEquals(18f, match.region.radiusX)
        assertEquals(20f, match.region.radiusY)
        assertEquals(FacePrivacyRegionSource.PREDICTED_FACE, match.region.source)
    }

    @Test
    fun `high resolution roi tracker follows a small source face without full frame downscale`() {
        val tracker = FacePixelMotionTracker()
        val firstPlan = FaceHeadRoiPlan(
            sourceRect = FloatRect(140f, 80f, 340f, 280f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = 256
        )
        val detected = FacePrivacyEllipse(
            centerX = 240f,
            centerY = 180f,
            radiusX = 14f,
            radiusY = 16f,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )
        val first = roiFrameWithPatch(firstPlan, 240f, 180f)
        assertTrue(
            tracker.seedRoi(
                trackId = 21,
                rgbaTopDown = first,
                roiPlan = firstPlan,
                detected = detected,
                personBbox = FloatRect(180f, 100f, 300f, 500f),
                ptsUs = 0L
            )
        )

        val secondPlan = FaceHeadRoiPlan(
            sourceRect = FloatRect(150f, 74f, 350f, 274f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = 256
        )
        val second = roiFrameWithPatch(secondPlan, 252f, 173f)
        val match = assertNotNull(
            tracker.matchRoi(
                trackId = 21,
                rgbaTopDown = second,
                roiPlan = secondPlan,
                personBbox = FloatRect(192f, 93f, 312f, 493f),
                personObservedThisFrame = true,
                ptsUs = 16_666L
            )
        )
        assertTrue(abs(match.region.centerX - 252f) <= 2f)
        assertTrue(abs(match.region.centerY - 173f) <= 2f)
        assertEquals(14f, match.region.radiusX)
        assertEquals(16f, match.region.radiusY)
    }

    @Test
    fun `roi partial occlusion keeps current pixel evidence from two agreeing quadrants`() {
        val tracker = FacePixelMotionTracker()
        val plan = FaceHeadRoiPlan(
            sourceRect = FloatRect(140f, 80f, 340f, 280f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = 256
        )
        val detected = FacePrivacyEllipse(
            centerX = 240f,
            centerY = 180f,
            radiusX = 14f,
            radiusY = 16f,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )
        assertTrue(
            tracker.seedRoi(
                trackId = 31,
                rgbaTopDown = roiFrameWithPatch(plan, 240f, 180f),
                roiPlan = plan,
                detected = detected,
                personBbox = FloatRect(180f, 100f, 300f, 500f),
                ptsUs = 0L
            )
        )

        val occluded = roiFrameWithPatch(plan, 252f, 173f)
        occludeRoiRightSide(
            buffer = occluded,
            plan = plan,
            sourceCenterX = 252f,
            sourceCenterY = 173f
        )
        val outcome = tracker.matchRoiDetailed(
            trackId = 31,
            rgbaTopDown = occluded,
            roiPlan = plan,
            personBbox = FloatRect(180f, 100f, 300f, 500f),
            personObservedThisFrame = false,
            ptsUs = 16_666L
        )
        val match = assertNotNull(outcome.match)
        assertTrue(match.partialOcclusion)
        assertTrue(abs(match.region.centerX - 252f) <= 2f)
        assertTrue(abs(match.region.centerY - 173f) <= 2f)
        assertEquals(14f, match.region.radiusX)
        assertEquals(16f, match.region.radiusY)
    }

    @Test
    fun `roi partial occlusion fallback still rejects repeated lookalike peaks`() {
        val tracker = FacePixelMotionTracker()
        val plan = FaceHeadRoiPlan(
            sourceRect = FloatRect(140f, 80f, 340f, 280f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = 256
        )
        val detected = FacePrivacyEllipse(240f, 180f, 14f, 16f, FacePrivacyRegionSource.DETECTED_FACE)
        assertTrue(
            tracker.seedRoi(
                trackId = 32,
                rgbaTopDown = roiFrameWithPatch(plan, 240f, 180f),
                roiPlan = plan,
                detected = detected,
                personBbox = FloatRect(180f, 100f, 300f, 500f),
                ptsUs = 0L
            )
        )

        val ambiguous = blankRoiFrame(plan.outputSize).also { frame ->
            drawRoiPatch(frame, plan, 224f, 180f)
            drawRoiPatch(frame, plan, 256f, 180f)
            occludeRoiRightSide(frame, plan, 224f, 180f)
            occludeRoiRightSide(frame, plan, 256f, 180f)
        }
        val outcome = tracker.matchRoiDetailed(
            trackId = 32,
            rgbaTopDown = ambiguous,
            roiPlan = plan,
            personBbox = FloatRect(180f, 100f, 300f, 500f),
            personObservedThisFrame = false,
            ptsUs = 16_666L
        )
        assertNull(outcome.match)
        assertTrue(
            outcome.rejectReason == FacePixelMotionTracker.RoiRejectReason.LOW_CORRELATION ||
                outcome.rejectReason == FacePixelMotionTracker.RoiRejectReason.AMBIGUOUS_PEAK
        )
    }

    @Test
    fun `roi tracker cannot renew forever without a detector refresh`() {
        val tracker = FacePixelMotionTracker()
        val plan = FaceHeadRoiPlan(
            sourceRect = FloatRect(140f, 80f, 340f, 280f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = 256
        )
        val detected = FacePrivacyEllipse(240f, 180f, 14f, 16f, FacePrivacyRegionSource.DETECTED_FACE)
        val first = roiFrameWithPatch(plan, 240f, 180f)
        assertTrue(
            tracker.seedRoi(
                trackId = 22,
                rgbaTopDown = first,
                roiPlan = plan,
                detected = detected,
                personBbox = FloatRect(180f, 100f, 300f, 500f),
                ptsUs = 0L
            )
        )

        var pts = 16_666L
        while (pts <= FacePixelMotionTracker.ROI_MAX_DETECTOR_SEED_AGE_US) {
            val frame = roiFrameWithPatch(plan, 240f, 180f)
            assertNotNull(
                tracker.matchRoi(
                    trackId = 22,
                    rgbaTopDown = frame,
                    roiPlan = plan,
                    personBbox = FloatRect(180f, 100f, 300f, 500f),
                    personObservedThisFrame = true,
                    ptsUs = pts
                )
            )
            pts += 16_666L
        }
        val expiredPts = FacePixelMotionTracker.ROI_MAX_DETECTOR_SEED_AGE_US + 16_666L
        val expired = tracker.matchRoiDetailed(
            trackId = 22,
            rgbaTopDown = roiFrameWithPatch(plan, 240f, 180f),
            roiPlan = plan,
            personBbox = FloatRect(180f, 100f, 300f, 500f),
            personObservedThisFrame = true,
            ptsUs = expiredPts
        )
        assertNull(expired.match)
        assertEquals(FacePixelMotionTracker.RoiRejectReason.DETECTOR_SEED_EXPIRED, expired.rejectReason)
    }

    @Test
    fun `repeated lookalike peaks are rejected as ambiguous`() {
        val tracker = FacePixelMotionTracker(minUniquenessGap = 0.08f)
        val first = frameWithPatch(centerX = 240, centerY = 180)
        val detected = FacePrivacyEllipse(240f, 180f, 18f, 20f, FacePrivacyRegionSource.DETECTED_FACE)
        assertTrue(tracker.seed(3, first, mapper, detected, 0L))

        val ambiguous = blankFrame().also { frame ->
            drawPatch(frame, 220, 180)
            drawPatch(frame, 260, 180)
        }
        assertNull(
            tracker.match(
                trackId = 3,
                rgbaBottomUp = ambiguous,
                mapper = mapper,
                ptsUs = 16_666L,
                personBbox = FloatRect(160f, 100f, 340f, 420f)
            )
        )
    }

    @Test
    fun `continuous current pixel evidence renews the tracklet but a real gap expires it`() {
        val tracker = FacePixelMotionTracker(maxEvidenceGapUs = 150_000L)
        val first = frameWithPatch(240, 180)
        val detected = FacePrivacyEllipse(240f, 180f, 18f, 20f, FacePrivacyRegionSource.DETECTED_FACE)
        assertTrue(tracker.seed(5, first, mapper, detected, 0L))

        val moved1 = frameWithPatch(244, 181)
        assertNotNull(
            tracker.match(
                trackId = 5,
                rgbaBottomUp = moved1,
                mapper = mapper,
                ptsUs = 140_000L,
                personBbox = FloatRect(160f, 100f, 340f, 420f)
            )
        )

        // The detector seed is now 280 ms old, but current pixel evidence has
        // remained continuous, so the immutable detector-seeded template may
        // keep localizing the same face.
        val moved2 = frameWithPatch(248, 182)
        assertNotNull(
            tracker.match(
                trackId = 5,
                rgbaBottomUp = moved2,
                mapper = mapper,
                ptsUs = 280_000L,
                personBbox = FloatRect(160f, 100f, 340f, 420f)
            )
        )

        val afterGap = frameWithPatch(252, 183)
        assertNull(
            tracker.match(
                trackId = 5,
                rgbaBottomUp = afterGap,
                mapper = mapper,
                ptsUs = 430_001L,
                personBbox = FloatRect(160f, 100f, 340f, 420f)
            )
        )
        assertTrue(!tracker.hasUsableState(5, 430_001L))
    }

    private fun frameWithPatch(centerX: Int, centerY: Int): ByteBuffer = blankFrame().also {
        drawPatch(it, centerX, centerY)
    }

    private fun roiFrameWithPatch(plan: FaceHeadRoiPlan, sourceCenterX: Float, sourceCenterY: Float): ByteBuffer {
        val buffer = blankRoiFrame(plan.outputSize)
        drawRoiPatch(buffer, plan, sourceCenterX, sourceCenterY)
        return buffer
    }

    private fun blankRoiFrame(size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size * size * 4)
        for (i in 0 until size * size) {
            val offset = i * 4
            buffer.put(offset, 24)
            buffer.put(offset + 1, 24)
            buffer.put(offset + 2, 24)
            buffer.put(offset + 3, 255.toByte())
        }
        return buffer
    }

    private fun drawRoiPatch(
        buffer: ByteBuffer,
        plan: FaceHeadRoiPlan,
        sourceCenterX: Float,
        sourceCenterY: Float
    ) {
        val size = plan.outputSize
        val localX = (((sourceCenterX - plan.sourceRect.left) / plan.sourceRect.width) * size).roundToInt()
        val localY = (((sourceCenterY - plan.sourceRect.top) / plan.sourceRect.height) * size).roundToInt()
        for (dy in -18..18) {
            for (dx in -18..18) {
                val x = localX + dx
                val y = localY + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val r = (80 + (dx * 17 + dy * 7 + dx * dy * 3)).and(0xFF)
                val g = (60 + (dx * 5 - dy * 19 + dx * dx)).and(0xFF)
                val b = (40 + (dy * 13 - dx * 11 + dy * dy)).and(0xFF)
                val offset = (y * size + x) * 4
                buffer.put(offset, r.toByte())
                buffer.put(offset + 1, g.toByte())
                buffer.put(offset + 2, b.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
    }

    private fun occludeRoiRightSide(
        buffer: ByteBuffer,
        plan: FaceHeadRoiPlan,
        sourceCenterX: Float,
        sourceCenterY: Float
    ) {
        val size = plan.outputSize
        val localX = (((sourceCenterX - plan.sourceRect.left) / plan.sourceRect.width) * size).roundToInt()
        val localY = (((sourceCenterY - plan.sourceRect.top) / plan.sourceRect.height) * size).roundToInt()
        for (dy in -22..22) {
            for (dx in -1..22) {
                val x = localX + dx
                val y = localY + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val offset = (y * size + x) * 4
                buffer.put(offset, 188.toByte())
                buffer.put(offset + 1, 142.toByte())
                buffer.put(offset + 2, 116.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
    }

    private fun blankFrame(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(640 * 640 * 4)
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                setVisualPixel(buffer, x, y, 24, 24, 24)
            }
        }
        return buffer
    }

    private fun drawPatch(buffer: ByteBuffer, centerX: Int, centerY: Int) {
        for (dy in -14..14) {
            for (dx in -14..14) {
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until 640 || y !in 0 until 640) continue
                // Deterministic non-symmetric high-texture face-like patch.
                val r = (80 + (dx * 17 + dy * 7 + dx * dy * 3)).and(0xFF)
                val g = (60 + (dx * 5 - dy * 19 + dx * dx)).and(0xFF)
                val b = (40 + (dy * 13 - dx * 11 + dy * dy)).and(0xFF)
                setVisualPixel(buffer, x, y, r, g, b)
            }
        }
    }

    private fun setVisualPixel(buffer: ByteBuffer, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val bufferY = 639 - y
        val offset = (bufferY * 640 + x) * 4
        buffer.put(offset, r.toByte())
        buffer.put(offset + 1, g.toByte())
        buffer.put(offset + 2, b.toByte())
        buffer.put(offset + 3, 255.toByte())
    }
}
