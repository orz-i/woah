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
    fun plk110ThirdRecoveryUsesStrongObservedAnchorWhenKalmanDrifts() {
        // Commit 1190aac: after the second real recovery at 18.533s, target
        // ID 2 was LOST again. The next three detector boxes were exclusive,
        // even as the UNOBSERVED predicted box drifted away from the anchor.
        val gate = FollowReservedLostRecovery()
        val frame567 = FloatRect(562.5f, 398.9f, 709.6f, 629.2f)
        val frame600 = FloatRect(560.4f, 397.5f, 707.4f, 629.8f)
        val frame633 = FloatRect(557.7f, 393.1f, 706.9f, 630.3f)
        assertNull(gate.observe(evidence(
            frame567, predictedIoU = .722f, lastObservedIoU = .950f,
            maskIoU = .650f, confidence = .836f, otherTrackIoU = .101f
        ), 18_566_666L))
        assertNull(gate.observe(evidence(
            frame600, predictedIoU = .701f, lastObservedIoU = .915f,
            maskIoU = .591f, confidence = .831f, otherTrackIoU = .108f
        ), 18_600_000L))
        assertEquals(2, gate.observe(evidence(
            frame633, predictedIoU = .656f, lastObservedIoU = .872f,
            maskIoU = .591f, confidence = .804f, otherTrackIoU = .119f
        ), 18_633_333L))
    }

    @Test
    fun strongAnchorCannotReplacePrivacySafeNegativeIdentityEvidence() {
        val box = FloatRect(558f, 393f, 707f, 630f)
        // The alternative predicted-box gate is only valid with independent
        // confirmed segmentation, an observed anchor and clear competitors.
        val safe = evidence(box, predictedIoU = .656f,
            lastObservedIoU = .872f, maskIoU = .591f,
            confidence = .804f, otherTrackIoU = .119f)
        assertTrue(FollowReservedLostRecovery.isStrictEvidence(safe))
        for (unsafe in listOf(
            evidence(box, predictedIoU = .599f, lastObservedIoU = .95f),
            evidence(box, predictedIoU = .656f, lastObservedIoU = .84f),
            evidence(box, predictedIoU = .656f, maskIoU = .54f),
            evidence(box, predictedIoU = .656f, confidence = .79f),
            evidence(box, predictedIoU = .656f, otherTrackIoU = .15f),
            evidence(box, predictedIoU = .656f, otherDetectionIoU = .20f)
        )) {
            assertFalse(FollowReservedLostRecovery.isStrictEvidence(unsafe))
        }
    }

    @Test
    fun immediateContinuationRequiresActualAdjacentObservedCommit() {
        val box = FloatRect(557f, 395f, 705f, 633f)
        val continuation = evidence(
            box, confidence = .676f, predictedIoU = .65f,
            lastObservedIoU = .86f, maskIoU = .52f,
            otherTrackIoU = .12f
        )
        assertFalse(FollowReservedLostRecovery.isStrictEvidence(continuation))
        assertTrue(FollowReservedLostRecovery.isContinuationEvidence(continuation))
        assertTrue(FollowReservedLostRecovery.isRecentVerifiedContinuation(
            lastConfirmedPtsUs = 18_633_333L,
            ptsUs = 18_666_666L,
            observedOnPreviousFrame = true
        ))
        assertFalse(FollowReservedLostRecovery.isRecentVerifiedContinuation(
            lastConfirmedPtsUs = 18_633_333L,
            ptsUs = 18_666_666L,
            observedOnPreviousFrame = false
        ))
        assertFalse(FollowReservedLostRecovery.isRecentVerifiedContinuation(
            lastConfirmedPtsUs = 18_633_333L,
            ptsUs = 18_766_666L,
            observedOnPreviousFrame = true
        ))
        assertFalse(FollowReservedLostRecovery.isRecentVerifiedContinuation(
            lastConfirmedPtsUs = 18_633_333L,
            ptsUs = 18_633_333L,
            observedOnPreviousFrame = true
        ))
        for (unsafe in listOf(
            evidence(box, confidence = .599f, predictedIoU = .65f,
                lastObservedIoU = .86f, maskIoU = .52f),
            evidence(box, confidence = .67f, predictedIoU = .57f,
                lastObservedIoU = .86f, maskIoU = .52f),
            evidence(box, confidence = .67f, predictedIoU = .65f,
                lastObservedIoU = .77f, maskIoU = .52f),
            evidence(box, confidence = .67f, predictedIoU = .65f,
                lastObservedIoU = .86f, maskIoU = .39f),
            evidence(box, confidence = .67f, predictedIoU = .65f,
                lastObservedIoU = .86f, maskIoU = .52f, otherTrackIoU = .15f),
            evidence(box, confidence = .67f, predictedIoU = .65f,
                lastObservedIoU = .86f, maskIoU = .52f, otherDetectionIoU = .16f)
        )) {
            assertFalse(FollowReservedLostRecovery.isContinuationEvidence(unsafe))
        }
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
