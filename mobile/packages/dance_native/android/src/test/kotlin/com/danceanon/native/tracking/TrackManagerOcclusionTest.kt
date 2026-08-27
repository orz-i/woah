package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackManagerOcclusionTest {

    private fun createTestMask(width: Int = 160, height: Int = 160, fillRect: Pair<IntRange, IntRange>): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val byteVal = if (x in fillRect.first && y in fillRect.second) 255.toByte() else 0.toByte()
                buf.put(byteVal)
            }
        }
        buf.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buf,
            originalWidth = 640,
            originalHeight = 640,
            mapper = null
        )
    }

    private fun isFullRectangleMask(mask: NativeMask?): Boolean {
        if (mask == null) return false
        val buf = mask.buffer
        buf.rewind()
        var count255 = 0
        val total = mask.width * mask.height
        for (i in 0 until total) {
            if ((buf.get(i).toInt() and 0xFF) == 255) {
                count255++
            }
        }
        buf.rewind()
        // If a very high percentage of the whole proto resolution is filled with 255, it's a fallback block
        return count255 > (total * 0.40)
    }

    @Test
    fun testTwoPeopleCrossingMaintainsIdWithoutRectangleFallback() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 15, occlusionOverlapRatio = 0.30f))

        // Frame 0: Person A moving right, Person B moving left
        val maskA = createTestMask(160, 160, Pair(10..30, 20..100))
        val maskB = createTestMask(160, 160, Pair(130..150, 20..100))

        val detA0 = PersonDetection(FloatRect(100f, 100f, 200f, 400f), 0.9f, maskA, 400f)
        val detB0 = PersonDetection(FloatRect(400f, 100f, 500f, 400f), 0.9f, maskB, 400f)

        val tracks0 = tracker.initialize(listOf(detA0, detB0))
        assertEquals(2, tracks0.size)
        val idA = tracks0[0].id
        val idB = tracks0[1].id
        assertNotEquals(idA, idB)

        // Frame 1-3: Approaching each other
        var tracks = tracks0
        var tUs = 33333L
        for (frame in 1..3) {
            val ax = 100f + frame * 40f // 140, 180, 220
            val bx = 400f - frame * 40f // 360, 320, 280
            val detA = PersonDetection(FloatRect(ax, 100f, ax + 100f, 400f), 0.9f, maskA, 400f)
            val detB = PersonDetection(FloatRect(bx, 100f, bx + 100f, 400f), 0.9f, maskB, 400f)
            tracks = tracker.update(listOf(detA, detB), tUs)
            tUs += 33333L
        }

        // Frame 4-7: Heavy Occlusion! A and B overlap spatially (240..280), only A is detected by YOLO
        val occlusionPositionsA = listOf(240f, 250f, 260f, 280f)
        for (ax in occlusionPositionsA) {
            val detA = PersonDetection(FloatRect(ax, 100f, ax + 100f, 400f), 0.9f, maskA, 400f)
            tracks = tracker.update(listOf(detA), tUs)
            tUs += 33333L

            // Verify B is OCCLUDED, NOT REMOVED, and NOT converted to full rectangle fallback!
            val trackB = tracks.find { it.id == idB }
            assertNotNull(trackB, "Occluded track B must remain in tracked list during occlusion")
            assertEquals(TrackState.OCCLUDED, trackB.state, "Track B should be marked OCCLUDED during crossing")
            assertTrue(!isFullRectangleMask(trackB.mask), "Occluded target MUST NOT generate full rectangle fallback")
        }

        // Frame 8-10: Separation! Person A continues right, Person B continues left
        val separationPositions = listOf(
            Pair(320f, 180f),
            Pair(360f, 140f),
            Pair(400f, 100f)
        )
        for ((ax, bx) in separationPositions) {
            val detA = PersonDetection(FloatRect(ax, 100f, ax + 100f, 400f), 0.9f, maskA, 400f)
            val detB = PersonDetection(FloatRect(bx, 100f, bx + 100f, 400f), 0.9f, maskB, 400f)
            tracks = tracker.update(listOf(detB, detA), tUs) // intentionally reversed detection order
            tUs += 33333L
        }

        // After separation, both A and B are ACTIVE with stable preserved IDs!
        assertEquals(2, tracks.size)
        val finalA = tracks.find { it.id == idA }
        val finalB = tracks.find { it.id == idB }

        assertNotNull(finalA, "Track A must survive with original ID")
        assertNotNull(finalB, "Track B must survive with original ID")
        assertEquals(TrackState.ACTIVE, finalA.state)
        assertEquals(TrackState.ACTIVE, finalB.state)
        assertTrue(finalA.bbox.centerX > finalB.bbox.centerX, "Track A (moving right) must be to the right of Track B (moving left)")
    }

    @Test
    fun testOccludedTargetNotRemovedQuicklyDuringOcclusion() {
        val tracker = TrackManager(TrackingConfig(maxMissedFrames = 5, occlusionMaxDurationFrames = 50, occlusionOverlapRatio = 0.30f))

        val mask = createTestMask(160, 160, Pair(10..40, 10..40))
        val detA = PersonDetection(FloatRect(200f, 200f, 300f, 400f), 0.9f, mask, 400f)
        val detB = PersonDetection(FloatRect(210f, 200f, 310f, 400f), 0.9f, mask, 400f)

        tracker.initialize(listOf(detA, detB))

        var tUs = 0L
        var tracks = emptyList<TrackedPerson>()

        // 8 frames of occlusion (more than maxMissedFrames = 5)
        for (i in 1..8) {
            tUs += 33333L
            val det = PersonDetection(FloatRect(200f, 200f, 300f, 400f), 0.9f, mask, 400f)
            tracks = tracker.update(listOf(det), tUs)
        }

        // Track B must still survive in OCCLUDED state, NOT REMOVED
        assertEquals(2, tracks.size, "Occluded track must not be removed by standard maxMissedFrames")
        val occluded = tracks.find { it.state == TrackState.OCCLUDED }
        assertNotNull(occluded, "Must have an OCCLUDED track")
    }
}
