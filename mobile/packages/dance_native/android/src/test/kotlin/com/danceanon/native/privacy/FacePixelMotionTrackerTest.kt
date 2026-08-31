package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import kotlin.math.abs
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
    fun `pixel motion never survives beyond short detector seed age`() {
        val tracker = FacePixelMotionTracker(maxSeedAgeUs = 150_000L)
        val first = frameWithPatch(240, 180)
        val detected = FacePrivacyEllipse(240f, 180f, 18f, 20f, FacePrivacyRegionSource.DETECTED_FACE)
        assertTrue(tracker.seed(5, first, mapper, detected, 0L))

        val moved = frameWithPatch(244, 181)
        assertNull(
            tracker.match(
                trackId = 5,
                rgbaBottomUp = moved,
                mapper = mapper,
                ptsUs = 150_001L,
                personBbox = FloatRect(160f, 100f, 340f, 420f)
            )
        )
        assertTrue(!tracker.hasUsableState(5, 150_001L))
    }

    private fun frameWithPatch(centerX: Int, centerY: Int): ByteBuffer = blankFrame().also {
        drawPatch(it, centerX, centerY)
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
