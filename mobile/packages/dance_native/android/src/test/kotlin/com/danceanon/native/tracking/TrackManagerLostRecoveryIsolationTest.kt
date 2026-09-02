package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class TrackManagerLostRecoveryIsolationTest {

    private fun solidMask(): NativeMask {
        val size = 64
        val buffer = ByteBuffer.allocateDirect(size * size)
        repeat(size * size) { buffer.put(255.toByte()) }
        buffer.rewind()
        return NativeMask(size, size, buffer, 640, 640)
    }

    @Test
    fun protectedLostAnchorOcclusionRequiresCurrentPredictionToRemainNearAnchor() {
        assertTrue(
            TrackManager.isProtectedLostAnchorOcclusionSupported(
                trackState = TrackState.LOST,
                identityProtected = true,
                predictionAnchorIoU = 0.80f,
                anchorFreshOverlapRatio = 0.41f,
                overlapThreshold = 0.30f
            )
        )
        assertTrue(
            !TrackManager.isProtectedLostAnchorOcclusionSupported(
                trackState = TrackState.LOST,
                identityProtected = true,
                predictionAnchorIoU = 0.29f,
                anchorFreshOverlapRatio = 0.90f,
                overlapThreshold = 0.30f
            ),
            "a stale last-observed anchor must not revive a LOST identity after its prediction has moved away"
        )
        assertTrue(
            !TrackManager.isProtectedLostAnchorOcclusionSupported(
                trackState = TrackState.ACTIVE,
                identityProtected = true,
                predictionAnchorIoU = 0.80f,
                anchorFreshOverlapRatio = 0.41f,
                overlapThreshold = 0.30f
            )
        )
        assertTrue(
            !TrackManager.isProtectedLostAnchorOcclusionSupported(
                trackState = TrackState.LOST,
                identityProtected = false,
                predictionAnchorIoU = 0.80f,
                anchorFreshOverlapRatio = 0.41f,
                overlapThreshold = 0.30f
            )
        )
        assertTrue(
            !TrackManager.isProtectedLostAnchorOcclusionSupported(
                trackState = TrackState.LOST,
                identityProtected = true,
                predictionAnchorIoU = 0.80f,
                anchorFreshOverlapRatio = 0.41f,
                overlapThreshold = 0.30f,
                occluderReliable = false
            ),
            "an ordinary winner disputed by another protected identity must not anchor-confirm a LOST identity"
        )
    }

    @Test
    fun testLostTrackIsNotGloballyReboundToPersonAtStaleLastObservedPosition() {
        val tracker = TrackManager(TrackingConfig())
        val mask = solidMask()

        val initial = tracker.initialize(
            listOf(PersonDetection(FloatRect(100f, 100f, 160f, 300f), 0.95f, mask))
        )
        val originalId = initial.single().id

        // Establish rightward motion so the predicted position continues beyond
        // the last observed box when the target disappears.
        tracker.update(
            listOf(PersonDetection(FloatRect(200f, 100f, 260f, 300f), 0.95f, mask)),
            timestampUs = 33_333L
        )
        tracker.update(
            listOf(PersonDetection(FloatRect(300f, 100f, 360f, 300f), 0.95f, mask)),
            timestampUs = 66_666L
        )
        tracker.update(
            listOf(PersonDetection(FloatRect(400f, 100f, 460f, 300f), 0.95f, mask)),
            timestampUs = 99_999L
        )

        val lostFrame = tracker.update(emptyList(), timestampUs = 133_332L)
        val lost = lostFrame.single { it.id == originalId }
        assertEquals(TrackState.LOST, lost.state)
        assertTrue(
            lost.bbox.left > 400f,
            "reproduction requires the motion prediction to have advanced beyond lastObserved; actual=${lost.bbox.left}"
        )

        // A different person now occupies the stale last-observed position while
        // the original target's motion prediction has advanced. The LOST identity
        // must not be rebound merely because lastObserved distance is zero.
        val passer = PersonDetection(FloatRect(400f, 100f, 460f, 300f), 0.95f, mask)
        val afterPasser = tracker.update(listOf(passer), timestampUs = 166_665L)

        val original = afterPasser.single { it.id == originalId }
        assertEquals(TrackState.LOST, original.state)
        assertTrue(!original.observedThisFrame)
        assertTrue(
            afterPasser.any { it.id != originalId && it.observedThisFrame },
            "a passer at stale lastObserved position must get a new identity instead of stealing the LOST target ID"
        )
    }
}
