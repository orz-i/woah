package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowCameraWeakGroupOwnershipTest {
    private fun proof(
        verified: Boolean = true,
        observedRoot: Boolean = true,
        outsideGroup: Boolean = true,
        privacySafe: Boolean = true,
        unassignedByGroup: Boolean = true,
        hasMask: Boolean = true,
        confidence: Float = .5908203f,
        predictedIoU: Float = .6455898f,
        observedIoU: Float = .89989233f,
        maskIoU: Float = .60f,
        groupIoU: Float = .1248f,
        competingTrackIoU: Float = .1248f,
        competingDetectionIoU: Float = .0168f
    ) = FollowCameraWeakGroupOwnership.Evidence(
        recentlyVerifiedFollowCommit = verified,
        rootActiveAndObservedPreviousFrame = observedRoot,
        rootOutsideGroup = outsideGroup,
        rootNotPrivacyProtected = privacySafe,
        unassignedByGroupHungarian = unassignedByGroup,
        detectorHasMask = hasMask,
        confidence = confidence,
        rootPredictedIoU = predictedIoU,
        rootLastObservedIoU = observedIoU,
        rootMaskIoU = maskIoU,
        groupStrongestIoU = groupIoU,
        otherTrackStrongestIoU = competingTrackIoU,
        otherDetectionStrongestIoU = competingDetectionIoU
    )

    @Test
    fun plk110StaleGroupEdgeCannotReserveJustVerifiedProtagonist() {
        // Production 1190aac, 18.767s. The group [1,8,10] reserved detection
        // 2 even though ID1 had no observation for >50 frames, ID8 for >150,
        // and the sole fresh member ID10 only grazed it at the bbox edge.
        // Box data are from the uploaded detector/tracking signatures (pixels).
        val detector = FloatRect(553.375f, 396.6875f, 702.3125f, 640.0625f)
        val groupBoxes = listOf(
            FloatRect(481.38672f, 299.0625f, 558.6133f, 555.9375f), // 1 anchor
            FloatRect(535.2539f, 328.90625f, 597.2461f, 502.34375f), // 8 anchor
            FloatRect(455.46875f, 424.2578f, 560.15625f, 575.7422f), // 10 anchor
            FloatRect(477.875f, 325.875f, 562.1875f, 575.5f), // 1 predicted
            FloatRect(539.25f, 335.75f, 597.625f, 513.5625f), // 8 predicted
            FloatRect(481.73825f, 438.5719f, 546.34784f, 569.33466f) // 10 predicted
        )
        val groupMax = groupBoxes.maxOf { TrackManager.computeBBoxIoU(it, detector) }
        assertTrue(groupMax >= 0.10f && groupMax <= 0.15f)
        assertTrue(FollowCameraWeakGroupOwnership.canYield(proof(
            groupIoU = groupMax,
            competingTrackIoU = groupMax
        )))
    }

    @Test
    fun followRootMayContinueOnlyWhileStillUniquelyOwned() {
        // A body box grows as the detector becomes less certain. High
        // confidence, independent mask/geometry and exclusive group absence
        // are required; this is not general weakening of identity matching.
        assertTrue(FollowCameraWeakGroupOwnership.canYield(proof(
            confidence = .7444f, predictedIoU = .554f,
            observedIoU = .783f, maskIoU = .56f,
            groupIoU = .15f, competingTrackIoU = .151f,
            competingDetectionIoU = .026f
        )))
        assertTrue(FollowCameraWeakGroupOwnership.canYield(proof(
            confidence = .7641f, predictedIoU = .498f,
            observedIoU = .728f, maskIoU = .518f,
            groupIoU = .162f, competingTrackIoU = .162f,
            competingDetectionIoU = .030f
        )))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(
            confidence = .4599f, predictedIoU = .460f,
            observedIoU = .707f, maskIoU = .467f,
            groupIoU = .166f, competingTrackIoU = .166f,
            competingDetectionIoU = .475f
        ))) // 18.9s overlapping duplicate detections: fail closed.
    }

    @Test
    fun protectedOrAmbiguousGroupAlwaysRetainsItsReservation() {
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(verified = false)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(observedRoot = false)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(outsideGroup = false)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(privacySafe = false)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(unassignedByGroup = false)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(hasMask = false)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(groupIoU = .18f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(competingTrackIoU = .18f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(competingDetectionIoU = .15f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(predictedIoU = .47f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(observedIoU = .71f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(maskIoU = .49f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(confidence = .54f)))
        assertFalse(FollowCameraWeakGroupOwnership.canYield(proof(confidence = Float.NaN)))
    }
}
