"""Static wiring guard for the PLK110 group-exit fix (no Android JDK required).

The executable numeric contract lives in TrackManagerOcclusionEdgeHysteresisTest.kt.
This test ensures that production grouping calls that helper without changing the
privacy identity matching or the follow-camera identity recovery lane.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TRACKER = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/tracking/TrackManager.kt"
PIPELINE = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/ExportPipeline.kt"


class TrackingOcclusionHysteresisContractTest(unittest.TestCase):
    def test_active_recent_group_pair_is_the_only_exit_grace(self):
        source = TRACKER.read_text()
        group = source[source.index('private fun updateOcclusionGroups('):source.index('override fun update(')]
        helper = source[source.index('internal fun isOcclusionPairConnected('):source.index('internal fun isStrictFaceOnlyReservationRescueEligible(')]
        self.assertIn('group.state == OcclusionGroupState.ACTIVE_OVERLAP', group)
        self.assertIn('trackA.observedOnPreviousFrame || trackB.observedOnPreviousFrame', group)
        self.assertIn('val connected = isOcclusionPairConnected(', group)
        self.assertIn('if (connected) {', group)
        self.assertIn('if (overlapRatio >= entryThreshold) return true', helper)
        self.assertIn('if (!activeGroupPair || !recentlyObservedMember) return false', helper)
        self.assertIn('val exitThreshold = (entryThreshold - OCCLUSION_GROUP_EXIT_MARGIN)', helper)
        self.assertIn('return overlapRatio >= exitThreshold', helper)
        self.assertIn('private const val OCCLUSION_GROUP_EXIT_MARGIN = 0.05f', source)

    def test_no_reassignment_in_camera_or_privacy_lane(self):
        source = TRACKER.read_text()
        group = source[source.index('private fun updateOcclusionGroups('):source.index('override fun update(')]
        for forbidden in ('setIdentityProtectedTrackIds(', 'setPrivacySelectedTrackIds(', 'reframeIdentityTrackId ='):
            self.assertNotIn(forbidden, group)
        follow = PIPELINE.read_text()
        self.assertIn('reframeIdentityHistory.recordSelectedObservation(', follow)
        self.assertIn('reframeTemporalRecovery.observe(', follow)
        self.assertIn('excludedTrackIds = excludedCameraProxyIds', follow)


if __name__ == '__main__':
    unittest.main()
