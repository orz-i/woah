package com.danceanon.native.tracking

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LostTrackPrivacySafetyTest {

    private fun createSyntheticMask(
        protoW: Int = 160,
        protoH: Int = 160,
        srcW: Int = 1920,
        srcH: Int = 1080,
        fillBox: FloatRect
    ): NativeMask {
        val mapper = ModelCoordinateMapper(srcW, srcH, 640, protoW)
        val buf = ByteBuffer.allocateDirect(protoW * protoH)
        val pX1 = mapper.sourceToProtoX(fillBox.left).roundToInt().coerceIn(0, protoW)
        val pY1 = mapper.sourceToProtoY(fillBox.top).roundToInt().coerceIn(0, protoH)
        val pX2 = mapper.sourceToProtoX(fillBox.right).roundToInt().coerceIn(0, protoW)
        val pY2 = mapper.sourceToProtoY(fillBox.bottom).roundToInt().coerceIn(0, protoH)

        for (y in 0 until protoH) {
            for (x in 0 until protoW) {
                if (x in pX1 until pX2 && y in pY1 until pY2) {
                    buf.put(255.toByte())
                } else {
                    buf.put(0.toByte())
                }
            }
        }
        buf.rewind()
        return NativeMask(
            width = protoW,
            height = protoH,
            buffer = buf,
            originalWidth = srcW,
            originalHeight = srcH,
            mapper = mapper
        )
    }

    @Test
    fun testProtectedSelectedIdentitySurvivesRemovalWindowAndReacquiresOriginalId() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 15))
        tracker.setProtectedTrackIds(setOf(42))

        val initialBox = FloatRect(760f, 350f, 980f, 875f)
        val initialMask = createSyntheticMask(fillBox = initialBox)
        tracker.initializeWithAssignedIds(
            listOf(PersonDetection(initialBox, 0.95f, initialMask)),
            listOf(42)
        )

        var pts = 33_333L
        repeat(16) {
            val tracks = tracker.update(emptyList(), pts)
            pts += 33_333L
            assertNotNull(tracks.find { it.id == 42 }, "selected identity must not be removed at miss ${it + 1}")
        }

        val tombstone = tracker.predictWithoutObservation(pts).find { it.id == 42 }
        assertNotNull(tombstone)
        assertEquals(TrackState.LOST, tombstone.state)
        assertEquals(null, tombstone.mask, "stale selected mask must stop rendering after normal LOST window")

        val returnBox = FloatRect(675f, 415f, 950f, 880f)
        val returnMask = createSyntheticMask(fillBox = returnBox)
        val recovered = tracker.update(
            listOf(PersonDetection(returnBox, 0.95f, returnMask)),
            pts + 33_333L
        )

        val selected = recovered.find { it.id == 42 }
        assertNotNull(selected, "returning selected person must recover the protected identity")
        assertEquals(TrackState.ACTIVE, selected.state)
        assertTrue(selected.observedThisFrame)
        assertEquals(1, recovered.size, "reappearance must not mint a replacement identity")
    }

    private fun computeMaskCenter(mask: NativeMask): Pair<Float, Float> {
        val buf = mask.buffer
        buf.rewind()
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                val b = buf.get(y * mask.width + x).toInt() and 0xFF
                if (b > 128) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        buf.rewind()
        if (count == 0) return Pair(0f, 0f)
        return Pair((sumX / count).toFloat(), (sumY / count).toFloat())
    }

    private fun countMaskPixels(mask: NativeMask): Int {
        val buf = mask.buffer
        buf.rewind()
        var count = 0
        for (i in 0 until mask.width * mask.height) {
            val b = buf.get(i).toInt() and 0xFF
            if (b > 128) count++
        }
        buf.rewind()
        return count
    }

    @Test
    fun testLostFrame4DoesNotUseStaleMask() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        val initialBox = FloatRect(100f, 100f, 200f, 400f)
        val initialMask = createSyntheticMask(fillBox = initialBox)
        val det0 = PersonDetection(bbox = initialBox, confidence = 0.95f, mask = initialMask)

        tracker.initialize(listOf(det0))

        // Moving right: frame 1 (dx=50)
        val det1 = PersonDetection(bbox = FloatRect(150f, 100f, 250f, 400f), confidence = 0.95f, mask = createSyntheticMask(fillBox = FloatRect(150f, 100f, 250f, 400f)))
        tracker.update(listOf(det1), 33_333L)

        // Frames 2..4: LOST (no detections)
        var lastTrack: TrackedPerson? = null
        for (f in 2..4) {
            val res = tracker.update(emptyList(), f * 33_333L)
            assertEquals(1, res.size)
            lastTrack = res[0]
        }

        assertNotNull(lastTrack)
        assertEquals(TrackState.LOST, lastTrack.state)
        assertEquals(3, lastTrack.missedFrames)

        // Now frame 5 (missedFrames = 4)
        val res5 = tracker.update(emptyList(), 5 * 33_333L)
        assertEquals(1, res5.size)
        val frame4Track = res5[0]
        assertEquals(4, frame4Track.missedFrames)
        assertNotNull(frame4Track.mask)

        // Mask center on frame 4/5 must NOT be stuck at initial location (x=100) or frame 1 location (x=150)
        val center = computeMaskCenter(frame4Track.mask!!)
        val mapper = frame4Track.mask!!.mapper!!
        val initialProtoX = mapper.sourceToProtoX(150f)
        assertTrue(center.first > initialProtoX, "Lost frame 4 mask center () must advance past initial location ()")
    }

    @Test
    fun testLostFrame10PrivacyMaskFollowsPredictedBBox() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        val dtUs = 33_333L

        // Feed 5 frames of moving right at 15px per frame (450 px/s)
        val b0 = FloatRect(200f, 200f, 300f, 600f)
        tracker.initialize(listOf(PersonDetection(bbox = b0, confidence = 0.95f, mask = createSyntheticMask(fillBox = b0))))

        for (i in 1..5) {
            val curX = 200f + i * 20f
            val curBox = FloatRect(curX, 200f, curX + 100f, 600f)
            tracker.update(listOf(PersonDetection(bbox = curBox, confidence = 0.95f, mask = createSyntheticMask(fillBox = curBox))), i * dtUs)
        }

        // Run 10 missed frames (predict only)
        var currentTrack: TrackedPerson? = null
        var previousCenterX = 0f

        for (f in 6..15) {
            val res = tracker.predict(f * dtUs)
            assertEquals(1, res.size)
            currentTrack = res[0]
            val maskCenter = computeMaskCenter(currentTrack.mask!!)
            if (previousCenterX > 0f) {
                assertTrue(maskCenter.first >= previousCenterX - 0.5f, "Frame $f mask center (${maskCenter.first}) must track predicted motion (prev: $previousCenterX)")
            }
            previousCenterX = maskCenter.first
        }

        assertNotNull(currentTrack)
        assertEquals(10, currentTrack.missedFrames)
        assertEquals(TrackState.LOST, currentTrack.state)

        // Bbox should have moved significantly to the right (> 400px in source coordinates)
        assertTrue(currentTrack.bbox.centerX > 400f, "Predicted bbox center (${currentTrack.bbox.centerX}) must have advanced to > 400px")
    }

    @Test
    fun testLostTrackFallbackMaskExpandsConservatively() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        val box = FloatRect(300f, 200f, 500f, 600f)
        val mask = createSyntheticMask(fillBox = box)

        tracker.initialize(listOf(PersonDetection(bbox = box, confidence = 0.95f, mask = mask)))

        // Missed frames 1..4
        for (f in 1..4) {
            tracker.predict(f * 33_333L)
        }

        // Frame 5 (missedFrames = 5, tier 1 margin 15%)
        val resTier1 = tracker.predict(5 * 33_333L)[0]
        val countTier1 = countMaskPixels(resTier1.mask!!)

        // Missed frames up to 12 (missedFrames = 12, tier 2 margin 25%)
        for (f in 6..11) {
            tracker.predict(f * 33_333L)
        }
        val resTier2 = tracker.predict(12 * 33_333L)[0]
        val countTier2 = countMaskPixels(resTier2.mask!!)

        assertTrue(countTier2 >= countTier1, "Tier 2 (>10 missed frames) fallback mask must be >= Tier 1 mask in coverage")
    }

    @Test
    fun testReDetectionReplacesFallbackWithRealSegmentationMask() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        val box0 = FloatRect(100f, 100f, 200f, 400f)
        tracker.initialize(listOf(PersonDetection(bbox = box0, confidence = 0.95f, mask = createSyntheticMask(fillBox = box0))))

        // Lose detection for 6 frames -> enters fallback
        for (f in 1..6) {
            tracker.predict(f * 33_333L)
        }
        val lostPerson = tracker.predict(7 * 33_333L)[0]
        assertEquals(TrackState.LOST, lostPerson.state)

        // Custom asymmetric real mask at re-detection
        val reDetectBox = lostPerson.bbox
        val customRealMask = createSyntheticMask(fillBox = FloatRect(reDetectBox.left, reDetectBox.top, reDetectBox.left + 20f, reDetectBox.top + 20f))
        val reDet = PersonDetection(bbox = reDetectBox, confidence = 0.95f, mask = customRealMask)

        val updated = tracker.update(listOf(reDet), 8 * 33_333L)
        assertEquals(1, updated.size)
        val recovered = updated[0]

        assertEquals(0, recovered.id, "Track ID must remain stable across recovery")
        assertEquals(TrackState.ACTIVE, recovered.state)
        assertEquals(0, recovered.missedFrames)
        assertEquals(countMaskPixels(customRealMask), countMaskPixels(recovered.mask!!), "Real segmentation mask must immediately replace fallback mask")
    }

    @Test
    fun testLostTrackEventuallyRemoved() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 15))
        val box = FloatRect(100f, 100f, 200f, 400f)
        tracker.initialize(listOf(PersonDetection(bbox = box, confidence = 0.95f, mask = createSyntheticMask(fillBox = box))))

        for (f in 1..15) {
            val res = tracker.predict(f * 33_333L)
            assertEquals(1, res.size, "Track should remain active or lost up to maxMissedFrames")
        }

        // Frame 16 (> maxMissedFrames)
        val res16 = tracker.predict(16 * 33_333L)
        assertTrue(res16.isEmpty(), "Track must be completely removed after exceeding maxMissedFrames")
    }

    @Test
    fun testLostTrackPrivacyMaskRespectsPortraitLetterbox() {
        val srcW = 1080
        val srcH = 1920
        val mapper = ModelCoordinateMapper(srcW, srcH, 640, 160)
        val box = FloatRect(200f, 400f, 600f, 1400f)
        val mask = createSyntheticMask(srcW = srcW, srcH = srcH, fillBox = box)

        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        tracker.initialize(listOf(PersonDetection(bbox = box, confidence = 0.95f, mask = mask)))

        for (f in 1..5) {
            tracker.predict(f * 33_333L)
        }

        val lostPerson = tracker.predict(6 * 33_333L)[0]
        assertNotNull(lostPerson.mask)
        assertEquals(srcW, lostPerson.mask!!.originalWidth)
        assertEquals(srcH, lostPerson.mask!!.originalHeight)
        assertNotNull(lostPerson.mask!!.mapper)

        // Ensure mask pixels are strictly within valid portrait letterbox range in Proto space
        val minValidProtoX = mapper.sourceToProtoX(0f).toInt()
        val maxValidProtoX = mapper.sourceToProtoX(srcW.toFloat()).toInt()

        val buf = lostPerson.mask!!.buffer
        buf.rewind()
        for (y in 0 until 160) {
            for (x in 0 until 160) {
                val b = buf.get(y * 160 + x).toInt() and 0xFF
                if (b > 0) {
                    assertTrue(x >= minValidProtoX - 1 && x <= maxValidProtoX + 1, "Mask x () must respect portrait letterbox boundaries [, ]")
                }
            }
        }
    }

    @Test
    fun testLostTrackPrivacyMaskRespectsLandscapeLetterbox() {
        val srcW = 1920
        val srcH = 1080
        val mapper = ModelCoordinateMapper(srcW, srcH, 640, 160)
        val box = FloatRect(400f, 200f, 1400f, 800f)
        val mask = createSyntheticMask(srcW = srcW, srcH = srcH, fillBox = box)

        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        tracker.initialize(listOf(PersonDetection(bbox = box, confidence = 0.95f, mask = mask)))

        for (f in 1..5) {
            tracker.predict(f * 33_333L)
        }

        val lostPerson = tracker.predict(6 * 33_333L)[0]
        assertNotNull(lostPerson.mask)
        assertEquals(srcW, lostPerson.mask!!.originalWidth)
        assertEquals(srcH, lostPerson.mask!!.originalHeight)

        val minValidProtoY = mapper.sourceToProtoY(0f).toInt()
        val maxValidProtoY = mapper.sourceToProtoY(srcH.toFloat()).toInt()

        val buf = lostPerson.mask!!.buffer
        buf.rewind()
        for (y in 0 until 160) {
            for (x in 0 until 160) {
                val b = buf.get(y * 160 + x).toInt() and 0xFF
                if (b > 0) {
                    assertTrue(y >= minValidProtoY - 1 && y <= maxValidProtoY + 1, "Mask y () must respect landscape letterbox boundaries [, ]")
                }
            }
        }
    }

    @Test
    fun testPrivacyCoverageThresholdOnMovingLostPerson() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        val b0 = FloatRect(100f, 200f, 300f, 700f)
        val b1 = FloatRect(140f, 200f, 340f, 700f)

        tracker.initialize(listOf(PersonDetection(bbox = b0, confidence = 0.95f, mask = createSyntheticMask(fillBox = b0))))
        tracker.update(listOf(PersonDetection(bbox = b1, confidence = 0.95f, mask = createSyntheticMask(fillBox = b1))), 33_333L)

        // Predict 8 frames into the future
        var lastTrack: TrackedPerson? = null
        for (f in 2..9) {
            val res = tracker.predict(f * 33_333L)
            lastTrack = res[0]
        }

        assertNotNull(lastTrack)
        val predBox = lastTrack.bbox
        val mapper = lastTrack.mask!!.mapper!!

        // Ground truth proxy target region in Proto coordinates
        val pX1 = mapper.sourceToProtoX(predBox.left).roundToInt().coerceIn(0, 160)
        val pY1 = mapper.sourceToProtoY(predBox.top).roundToInt().coerceIn(0, 160)
        val pX2 = mapper.sourceToProtoX(predBox.right).roundToInt().coerceIn(0, 160)
        val pY2 = mapper.sourceToProtoY(predBox.bottom).roundToInt().coerceIn(0, 160)

        val targetPixels = (pX2 - pX1) * (pY2 - pY1)
        assertTrue(targetPixels > 0, "Target region must be non-empty")

        val buf = lastTrack.mask!!.buffer
        buf.rewind()
        var coveredCount = 0
        for (y in pY1 until pY2) {
            for (x in pX1 until pX2) {
                val b = buf.get(y * 160 + x).toInt() and 0xFF
                if (b > 128) coveredCount++
            }
        }

        val coverageRatio = coveredCount.toDouble() / targetPixels.toDouble()
        assertTrue(coverageRatio >= 0.95, "Conservative privacy fallback coverage must cover >= 95% of predicted target region (actual: )")
    }

    @Test
    fun testCanonicalMaskIsNotOverwrittenByWarpOrFallback() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 30))
        val box0 = FloatRect(100f, 100f, 200f, 400f)
        val initialMask = createSyntheticMask(fillBox = box0)
        val initialCount = countMaskPixels(initialMask)

        tracker.initialize(listOf(PersonDetection(bbox = box0, confidence = 0.95f, mask = initialMask)))

        // Predict 10 frames into the future (frames 1..3 warp, frames 4..10 fallback rectangle)
        for (f in 1..10) {
            val res = tracker.predict(f * 33_333L)
            assertEquals(1, res.size)
            assertNotNull(res[0].mask)
        }

        // Now inject a new detection at frame 11 to simulate reacquisition
        val reDetectBox = FloatRect(250f, 100f, 350f, 400f)
        val newCanonicalMask = createSyntheticMask(fillBox = reDetectBox)
        val newCount = countMaskPixels(newCanonicalMask)

        val updated = tracker.update(listOf(PersonDetection(bbox = reDetectBox, confidence = 0.95f, mask = newCanonicalMask)), 11 * 33_333L)
        assertEquals(1, updated.size)
        val recovered = updated[0]

        assertEquals(TrackState.ACTIVE, recovered.state)
        assertEquals(0, recovered.missedFrames)
        assertEquals(newCount, countMaskPixels(recovered.mask!!), "Canonical mask must be restored on reacquisition without rectangular corruption")
    }
}
