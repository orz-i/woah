package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackManagerPrivacySelectionSplitTest {
    @Test
    fun `identity protected face-only track remains unselected for full-body privacy class`() {
        val tracker = TrackManager()
        tracker.setIdentityProtectedTrackIds(setOf(10, 20))
        tracker.setPrivacySelectedTrackIds(setOf(10))

        tracker.initializeWithAssignedIds(
            detections = listOf(
                detection(40f, 60f, 140f, 320f),
                detection(240f, 60f, 340f, 320f)
            ),
            assignedIds = listOf(10, 20)
        )

        val hard = tracker.getHardPrivacyClassByDetectionIndex()
        assertEquals(PrivacySelectionClass.SELECTED, hard[0])
        assertEquals(PrivacySelectionClass.UNSELECTED, hard[1])
    }

    @Test
    fun `legacy protected setter keeps historical selected privacy semantics`() {
        val tracker = TrackManager()
        tracker.setProtectedTrackIds(setOf(20))
        tracker.initializeWithAssignedIds(
            detections = listOf(
                detection(40f, 60f, 140f, 320f),
                detection(240f, 60f, 340f, 320f)
            ),
            assignedIds = listOf(10, 20)
        )

        val hard = tracker.getHardPrivacyClassByDetectionIndex()
        assertEquals(PrivacySelectionClass.UNSELECTED, hard[0])
        assertEquals(PrivacySelectionClass.SELECTED, hard[1])
    }

    private fun detection(left: Float, top: Float, right: Float, bottom: Float): PersonDetection {
        val buffer = ByteBuffer.allocateDirect(16)
        repeat(16) { buffer.put(255.toByte()) }
        buffer.rewind()
        return PersonDetection(
            bbox = FloatRect(left, top, right, bottom),
            confidence = 0.95f,
            mask = NativeMask(
                width = 4,
                height = 4,
                buffer = buffer,
                originalWidth = 400,
                originalHeight = 400
            )
        )
    }
}
