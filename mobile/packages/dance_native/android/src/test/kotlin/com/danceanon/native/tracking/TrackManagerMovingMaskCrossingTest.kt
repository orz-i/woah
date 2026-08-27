package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackManagerMovingMaskCrossingTest {

    private fun createMovingMask(
        width: Int = 160,
        height: Int = 160,
        centerX: Float,
        centerY: Float,
        radius: Float = 15f
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val inCircle = (dx * dx + dy * dy) <= (radius * radius)
                buf.put(if (inCircle) 255.toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buf,
            originalWidth = 1920,
            originalHeight = 1080,
            mapper = null
        )
    }

    @Test
    fun testTwoPeopleCrossingPreservesIdContinuityWithMovingMasks() {
        val config = TrackingConfig(
            maxMissedFrames = 15,
            postOcclusionGraceFrames = 10,
            occlusionOverlapRatio = 0.30f
        )
        val tracker = TrackManager(config)

        val fps = 30.0
        val frameDurationUs = (1_000_000.0 / fps).toLong()
        var currentPtsUs = 100_000L

        // Initial positions: A at 200, B at 800
        val initA = PersonDetection(
            bbox = FloatRect(150f, 200f, 250f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 20f, 30f)
        )
        val initB = PersonDetection(
            bbox = FloatRect(750f, 200f, 850f, 400f),
            confidence = 0.95f,
            mask = createMovingMask(160, 160, 80f, 30f)
        )
        tracker.initializeWithAssignedIds(listOf(initA, initB), listOf(0, 1))

        // Frames 1..7: Approaching each other
        for (f in 1..7) {
            currentPtsUs += frameDurationUs
            val xA = 200f + f * 40f
            val xB = 800f - f * 40f
            val detA = PersonDetection(
                bbox = FloatRect(xA - 50f, 200f, xA + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xA / 10f, 30f)
            )
            val detB = PersonDetection(
                bbox = FloatRect(xB - 50f, 200f, xB + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xB / 10f, 30f)
            )
            val tracks = tracker.update(listOf(detA, detB), currentPtsUs)
            val tA = tracks.find { it.id == 0 }
            val tB = tracks.find { it.id == 1 }
            assertNotNull(tA)
            assertNotNull(tB)
        }

        // Frames 8..10: Overlap crossing (around x=500)
        for (f in 8..10) {
            currentPtsUs += frameDurationUs
            val xA = 200f + f * 40f // ~520..600
            val xB = 800f - f * 40f // ~480..400
            val detA = PersonDetection(
                bbox = FloatRect(xA - 50f, 200f, xA + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xA / 10f, 30f)
            )
            val detB = PersonDetection(
                bbox = FloatRect(xB - 50f, 200f, xB + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xB / 10f, 30f)
            )
            tracker.update(listOf(detA, detB), currentPtsUs)
        }

        // Frames 11..16: Separating on opposite sides.
        // Detection order is intentionally shuffled: [detB (on left, x~200..300), detA (on right, x~700..800)]
        for (f in 11..16) {
            currentPtsUs += frameDurationUs
            val xA = 200f + f * 40f // 640 -> 840 (Right side)
            val xB = 800f - f * 40f // 360 -> 160 (Left side)

            val detA = PersonDetection(
                bbox = FloatRect(xA - 50f, 200f, xA + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xA / 10f, 30f)
            )
            val detB = PersonDetection(
                bbox = FloatRect(xB - 50f, 200f, xB + 50f, 400f),
                confidence = 0.95f,
                mask = createMovingMask(160, 160, xB / 10f, 30f)
            )

            // Feed inverted list order: B first, A second
            val tracks = tracker.update(listOf(detB, detA), currentPtsUs)
            val tA = tracks.find { it.id == 0 }
            val tB = tracks.find { it.id == 1 }

            assertNotNull(tA, "Track 0 must exist")
            assertNotNull(tB, "Track 1 must exist")

            // Identity direction continuity: Track 0 must be on right (x > 600), Track 1 on left (x < 400)
            assertTrue(tA.bbox.centerX > 600f, "Track 0 must maintain motion to right (centerX=${tA.bbox.centerX})")
            assertTrue(tB.bbox.centerX < 400f, "Track 1 must maintain motion to left (centerX=${tB.bbox.centerX})")
        }
    }
}
