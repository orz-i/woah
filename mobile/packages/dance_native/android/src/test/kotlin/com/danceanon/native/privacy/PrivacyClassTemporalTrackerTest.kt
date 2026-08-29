package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.PrivacySelectionClass
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivacyClassTemporalTrackerTest {
    private fun maskRect(left: Int, top: Int, right: Int, bottom: Int): NativeMask {
        val size = 64
        val buffer = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                buffer.put(if (x in left until right && y in top until bottom) 255.toByte() else 0.toByte())
            }
        }
        buffer.rewind()
        return NativeMask(size, size, buffer, 640, 640)
    }

    private fun detection(
        left: Float,
        right: Float,
        maskLeft: Int,
        maskRight: Int,
        maskTop: Int = 12,
        maskBottom: Int = 50
    ): PersonDetection = PersonDetection(
        bbox = FloatRect(left, 100f, right, 300f),
        confidence = 0.95f,
        mask = maskRect(maskLeft, maskTop, maskRight, maskBottom)
    )

    @Test
    fun hardSeedsAllowFreshClassInferenceOnFollowingFrame() {
        val tracker = PrivacyClassTemporalTracker()
        val initial = listOf(
            detection(100f, 260f, 10, 26),
            detection(380f, 540f, 38, 54)
        )
        val seeded = tracker.update(
            detections = initial,
            hardClassByDetectionIndex = mapOf(
                0 to PrivacySelectionClass.SELECTED,
                1 to PrivacySelectionClass.UNSELECTED
            ),
            ptsUs = 0L
        )
        assertTrue(seeded.isEmpty(), "hard evidence is already represented by fresh tracked persons")

        val next = listOf(
            detection(130f, 290f, 13, 29),
            detection(350f, 510f, 35, 51)
        )
        val inferred = tracker.update(next, emptyMap(), 16_667L)

        assertEquals(2, inferred.size)
        assertEquals(PrivacySelectionClass.SELECTED, inferred.single { it.detectionIndex == 0 }.selectionClass)
        assertEquals(PrivacySelectionClass.UNSELECTED, inferred.single { it.detectionIndex == 1 }.selectionClass)
    }

    @Test
    fun selectedAndUnselectedCrossWithoutExactIdentityCommits() {
        val tracker = PrivacyClassTemporalTracker()
        tracker.update(
            listOf(
                detection(100f, 260f, 10, 26),
                detection(380f, 540f, 38, 54)
            ),
            mapOf(0 to PrivacySelectionClass.SELECTED, 1 to PrivacySelectionClass.UNSELECTED),
            0L
        )

        val frame1 = tracker.update(
            listOf(
                detection(160f, 320f, 16, 32),
                detection(320f, 480f, 32, 48)
            ),
            emptyMap(),
            16_667L
        )
        assertEquals(2, frame1.size, "frame1 must classify both detections: $frame1")
        assertEquals(PrivacySelectionClass.SELECTED, frame1.single { it.detectionIndex == 0 }.selectionClass)
        assertEquals(PrivacySelectionClass.UNSELECTED, frame1.single { it.detectionIndex == 1 }.selectionClass)

        val frame2 = tracker.update(
            listOf(
                detection(220f, 380f, 22, 38),
                detection(260f, 420f, 26, 42)
            ),
            emptyMap(),
            33_334L
        )
        assertEquals(2, frame2.size, "frame2 must classify both detections: $frame2")
        assertEquals(PrivacySelectionClass.SELECTED, frame2.single { it.detectionIndex == 0 }.selectionClass)
        assertEquals(PrivacySelectionClass.UNSELECTED, frame2.single { it.detectionIndex == 1 }.selectionClass)

        val frame3 = tracker.update(
            listOf(
                detection(280f, 440f, 28, 44),
                detection(200f, 360f, 20, 36)
            ),
            emptyMap(),
            50_001L
        )
        assertEquals(2, frame3.size, "frame3 must classify both detections: $frame3")
        assertEquals(PrivacySelectionClass.SELECTED, frame3.single { it.detectionIndex == 0 }.selectionClass)
        assertEquals(PrivacySelectionClass.UNSELECTED, frame3.single { it.detectionIndex == 1 }.selectionClass)
    }

    @Test
    fun mergedDetectionBetweenClassesRemainsUnknown() {
        val tracker = PrivacyClassTemporalTracker(minClassMargin = 0.20f)
        tracker.update(
            listOf(
                detection(100f, 260f, 10, 26),
                detection(300f, 460f, 30, 46)
            ),
            mapOf(0 to PrivacySelectionClass.SELECTED, 1 to PrivacySelectionClass.UNSELECTED),
            0L
        )

        val mergedMask = maskRect(20, 12, 36, 50)
        val merged = PersonDetection(
            bbox = FloatRect(200f, 100f, 360f, 300f),
            confidence = 0.95f,
            mask = mergedMask
        )
        val inferred = tracker.update(listOf(merged), emptyMap(), 16_667L)
        assertTrue(inferred.isEmpty(), "selected/unselected merged evidence must stay UNKNOWN")
    }

    @Test
    fun farNewEntrantWithoutHardEvidenceStaysUnknown() {
        val tracker = PrivacyClassTemporalTracker()
        tracker.update(
            listOf(detection(100f, 260f, 10, 26)),
            mapOf(0 to PrivacySelectionClass.SELECTED),
            0L
        )

        val entrant = detection(500f, 620f, 50, 62, maskTop = 2, maskBottom = 20)
        val inferred = tracker.update(listOf(entrant), emptyMap(), 16_667L)
        assertTrue(inferred.isEmpty(), "a new far-away person must not inherit the selected class")
    }

    @Test
    fun oneFrameOcclusionRetainsUnselectedClassOnReturn() {
        val tracker = PrivacyClassTemporalTracker()
        tracker.update(
            listOf(
                detection(100f, 260f, 10, 26),
                detection(380f, 540f, 38, 54)
            ),
            mapOf(0 to PrivacySelectionClass.SELECTED, 1 to PrivacySelectionClass.UNSELECTED),
            0L
        )

        val moving = tracker.update(
            listOf(
                detection(130f, 290f, 13, 29),
                detection(350f, 510f, 35, 51)
            ),
            emptyMap(),
            16_667L
        )
        assertEquals(2, moving.size)

        val occluded = tracker.update(
            listOf(detection(160f, 320f, 16, 32)),
            emptyMap(),
            33_334L
        )
        assertEquals(1, occluded.size)
        assertEquals(PrivacySelectionClass.SELECTED, occluded.single().selectionClass)

        val returned = tracker.update(
            listOf(
                detection(190f, 350f, 19, 35),
                detection(290f, 450f, 29, 45)
            ),
            emptyMap(),
            50_001L
        )
        assertEquals(2, returned.size)
        assertEquals(PrivacySelectionClass.SELECTED, returned.single { it.detectionIndex == 0 }.selectionClass)
        assertEquals(PrivacySelectionClass.UNSELECTED, returned.single { it.detectionIndex == 1 }.selectionClass)
    }
}
