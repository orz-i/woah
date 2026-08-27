package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PrivacyRawMaskOwnershipTest {

    private fun createBinaryMask(
        width: Int = 10,
        height: Int = 10,
        xRange: IntRange,
        yRange: IntRange
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val byteVal = if (x in xRange && y in yRange) 255.toByte() else 0.toByte()
                buf.put(byteVal)
            }
        }
        buf.rewind()
        return NativeMask(
            width = width,
            height = height,
            buffer = buf,
            originalWidth = 100,
            originalHeight = 100,
            mapper = null
        )
    }

    @Test
    fun testCanonicalRawMaskAreaIsNotAlteredByTracking() {
        val rawMask = createBinaryMask(10, 10, 3..5, 3..5) // 3x3 = 9 pixels
        val rawPixelCount = PrivacyOcclusionResolver.countMaskPixels(rawMask)
        assertEquals(9, rawPixelCount)

        val det = PersonDetection(
            bbox = FloatRect(30f, 30f, 60f, 60f),
            confidence = 0.95f,
            mask = rawMask
        )

        val tracker = TrackManager()
        val tracked = tracker.initialize(listOf(det))
        assertEquals(1, tracked.size)

        val person = tracked[0]
        val trackedMask = person.mask
        assertNotNull(trackedMask)

        // The canonical mask stored in track must match raw mask pixel count exactly (no pre-dilation)
        val trackedPixelCount = PrivacyOcclusionResolver.countMaskPixels(trackedMask)
        assertEquals(9, trackedPixelCount, "Tracking canonical mask must NOT be pre-dilated")

        // Single dilation applied during privacy resolution
        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = tracked,
            selectedPersonIds = setOf(person.id),
            applyDilationToPrivacyTargets = true,
            dilationRadius = 1
        )

        assertNotNull(resolved.privacyMask)
        val resolvedPixelCount = PrivacyOcclusionResolver.countMaskPixels(resolved.privacyMask)
        // 3x3 dilated by radius 1 -> 5x5 = 25 pixels
        assertEquals(25, resolvedPixelCount, "Privacy resolution must apply exact single dilation")
    }
}
