"""No-JDK production-wiring guard for the narrow camera-follow reservation fix.

The executable geometry/evidence boundary tests are in the Kotlin JVM suite.
These assertions ensure the special release cannot become ordinary unreserved
global matching, a new ID, or a privacy-selected identity association.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/tracking"
TRACK = (BASE / "TrackManager.kt").read_text()
POLICY = (BASE / "FollowCameraWeakGroupOwnership.kt").read_text()
FOLLOW_CAMERA = (ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/ExportPipeline.kt").read_text()


class FollowWeakGroupOwnershipContractTest(unittest.TestCase):
    def test_weak_group_claim_requires_recent_original_unprotected_root(self):
        start = TRACK.index("val yieldedToFollow = if (")
        end = TRACK.index("if (!yieldedToFollow &&", start)
        fragment = TRACK[start:end]
        for key in (
            "lastFollowReservedCommitPtsUs >= 0L",
            "followRoot.state == TrackState.ACTIVE",
            "followRoot.observedOnPreviousFrame",
            "rootId !in protectedTrackIds",
            "rootId !in privacySelectedTrackIds",
            "rootId !in facePrivacySelectedTrackIds",
            "rootOutsideGroup = occlusionGroups.none",
            "unassignedByGroupHungarian =",
            "matchResult.matches.none { it.second == c }",
            "groupStrongestIoU = groupSupportIoU",
            "FollowCameraWeakGroupOwnership.canYield(",
        ):
            self.assertIn(key, fragment)
        self.assertIn("if (!yieldedToFollow && (overlapsGroupMember || hungarianDetectionSupport != null))", TRACK)

    def test_global_does_not_reassign_unreserved_detection(self):
        section = TRACK[TRACK.index("// 4. Global Hungarian Matching"):TRACK.index("// 5. Handle Unmatched Tracks")]
        for key in (
            "val exclusiveFollowOwner = weakGroupYieldOwnerByDetection[dIdx]",
            "val rootHasYieldedDetection =",
            "(exclusiveFollowOwner != null && track.id != exclusiveFollowOwner)",
            "(rootHasYieldedDetection && exclusiveFollowOwner != track.id)",
            "0f",
            "computeMatchScore(track, dIdx)",
            "if (weakGroupYieldOwnerByDetection[dIdx] == track.id)",
            "lastFollowReservedCommitPtsUs = timestampUs",
            "FOLLOW_WEAK_GROUP_OWNERSHIP_COMMIT",
            "if (dIdx !in matchedDetectionIndices)",
            "reservedGlobalDetectionIndices.add(dIdx)",
        ):
            self.assertIn(key, section)
        self.assertNotIn("reservedGroupDetectionIndices.remove(", section)
        self.assertNotIn("privacySelectedTrackIds.add", section)
        self.assertNotIn("protectedTrackIds.add", section)

    def test_policy_never_lets_weak_evidence_override_group_claim(self):
        for key in (
            "MAX_WEAK_GROUP_IOU = 0.18f",
            "MAX_OTHER_TRACK_IOU = 0.18f",
            "MAX_OTHER_DETECTION_IOU = 0.15f",
            "e.unassignedByGroupHungarian",
            "e.detectorHasMask",
            "e.rootNotPrivacyProtected",
            "e.groupStrongestIoU >= MAX_WEAK_GROUP_IOU",
            "e.otherTrackStrongestIoU >= MAX_OTHER_TRACK_IOU",
            "e.otherDetectionStrongestIoU >= MAX_OTHER_DETECTION_IOU",
            "e.rootLastObservedIoU >= 0.85f",
            "e.rootMaskIoU >= 0.55f",
            "e.rootLastObservedIoU >= 0.72f",
            "e.rootMaskIoU >= 0.50f",
            "return nearExactAnchor || resizedBody",
        ):
            self.assertIn(key, POLICY)

    def test_original_camera_and_privacy_guards_remain(self):
        self.assertIn("reframeIdentityHistory.excludedIds()", FOLLOW_CAMERA)
        self.assertIn("reframeIdentityHistory.recordSelectedObservation(", FOLLOW_CAMERA)
        self.assertIn("reframeTemporalRecovery.observe(", FOLLOW_CAMERA)
        self.assertIn("excludedTrackIds = excludedCameraProxyIds", FOLLOW_CAMERA)
        self.assertIn("trackManager.setFollowCameraTrackId(", FOLLOW_CAMERA)


if __name__ == "__main__":
    unittest.main()
