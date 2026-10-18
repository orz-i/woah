package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowReservedLostRecoveryTest {
    private fun evidence(
        box: FloatRect,
        predictedIoU: Float = 0.82f,
        lastObservedIoU: Float = 0.85f,
        maskIoU: Float = 0.65f,
        otherTrackIoU: Float = 0.06f,
        otherDetectionIoU: Float = 0.0f,
        confidence: Float = 0.85f
    ) = FollowReservedLostRecovery.Evidence(
        detectionIndex = 2,
        box = box,
        confidence = confidence,
        predictedIoU = predictedIoU,
        lastObservedIoU = lastObservedIoU,
        maskIoU = maskIoU,
        strongestOtherTrackIoU = otherTrackIoU,
        strongestOtherDetectionIoU = otherDetectionIoU
    )

    @Test
    fun plk110RestoredFullBodyRequiresThreeContinuousUnambiguousDetections() {
        // ID 2 was last observed at 18.333s. 18.367s detector fragment
        // belongs to a different geometry: it cannot count as recovery.
        val gate = FollowReservedLostRecovery()
        val partial = FloatRect(540f, 335f, 600f, 514f)
        assertNull(gate.observe(
            evidence(partial, predictedIoU = 0.018f, lastObservedIoU = 0.018f),
            18_366_666L
        ))
        assertEquals(0, gate.pendingVotes)

        val restored1 = FloatRect(573f, 405f, 711f, 625f)
        val restored2 = FloatRect(572f, 408f, 708f, 624f)
        val restored3 = FloatRect(573f, 410f, 707f, 623f)
        assertNull(gate.observe(evidence(restored1, predictedIoU = 0.821f), 18_400_000L))
        assertEquals(1, gate.pendingVotes)
        assertNull(gate.observe(evidence(restored2, predictedIoU = 0.834f), 18_433_333L))
        assertEquals(2, gate.pendingVotes)
        assertEquals(
            2, gate.observe(evidence(restored3, predictedIoU = 0.798f), 18_466_666L)
        )
        assertEquals(0, gate.pendingVotes)
    }

    @Test
    fun overlappingBystanderOrDuplicateDetectorPreventsRecovery() {
        val full = FloatRect(572f, 405f, 710f, 625f)
        val gate = FollowReservedLostRecovery()
        assertFalse(FollowReservedLostRecovery.isStrictEvidence(
            evidence(full, otherTrackIoU = 0.20f)
        ))
        assertFalse(FollowReservedLostRecovery.isStrictEvidence(
            evidence(full, otherDetectionIoU = 0.25f)
        ))
        assertNull(gate.observe(evidence(full, otherTrackIoU = 0.42f), 18_400_000L))
        assertEquals(0, gate.pendingVotes)
        assertNull(gate.observe(evidence(full, otherDetectionIoU = 0.34f), 18_433_333L))
        assertEquals(0, gate.pendingVotes)
    }

    @Test
    fun weakMaskWeakGeometryAndLowConfidenceCannotBorrowIdentity() {
        val full = FloatRect(572f, 405f, 710f, 625f)
        for (bad in listOf(
            evidence(full, maskIoU = 0.0f),
            evidence(full, predictedIoU = 0.50f),
            evidence(full, lastObservedIoU = 0.50f),
            evidence(full, confidence = 0.58f)
        )) {
            assertFalse(FollowReservedLostRecovery.isStrictEvidence(bad))
        }
    }

    @Test
    fun voteGapOrBoxTeleportResetsPendingEvidence() {
        val full = FloatRect(572f, 405f, 710f, 625f)
        val distant = FloatRect(770f, 405f, 910f, 625f)
        val gate = FollowReservedLostRecovery()
        assertNull(gate.observe(evidence(full), 18_400_000L))
        assertNull(gate.observe(evidence(full), 18_600_000L))
        assertEquals(1, gate.pendingVotes)
        assertNull(gate.observe(evidence(distant), 18_633_333L))
        assertEquals(1, gate.pendingVotes)
        assertNull(gate.observe(null, 18_666_666L))
        assertEquals(0, gate.pendingVotes)
    }

    @Test
    fun onlyRecentUnprotectedUngroupedLostRootMayAttemptRecovery() {
        fun eligible(
            state: TrackState = TrackState.LOST,
            age: Int = 2,
            hasMask: Boolean = true,
            identityProtected: Boolean = false,
            privacySelected: Boolean = false,
            faceSelected: Boolean = false,
            groupMember: Boolean = false
        ) = FollowReservedLostRecovery.isEligibleTarget(
            state = state,
            framesSinceLastObservation = age,
            hasObservedMask = hasMask,
            identityProtected = identityProtected,
            privacySelected = privacySelected,
            facePrivacySelected = faceSelected,
            belongsToAnyGroup = groupMember
        )
        assertTrue(eligible())
        assertFalse(eligible(state = TrackState.ACTIVE))
        assertFalse(eligible(state = TrackState.REACQUIRING))
        assertFalse(eligible(age = 7))
        assertFalse(eligible(hasMask = false))
        assertFalse(eligible(identityProtected = true))
        assertFalse(eligible(privacySelected = true))
        assertFalse(eligible(faceSelected = true))
        assertFalse(eligible(groupMember = true))
    }
}
