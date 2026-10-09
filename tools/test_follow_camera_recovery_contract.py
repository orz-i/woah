"""Guard camera-only handoff boundaries without an Android/JDK build."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PIPELINE = ROOT / 'mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/ExportPipeline.kt'
RECOVERY = ROOT / 'mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/camera/FollowCameraTemporalRecovery.kt'


class FollowCameraRecoveryContractTest(unittest.TestCase):
    def test_trackmanager_identity_and_privacy_not_reassigned_by_camera_recovery(self):
        source = PIPELINE.read_text()
        start = source.index('val followTargetId = requireNotNull(request.follow.targetPersonId)')
        end = source.index('val currentIdentityTrackId = reframeIdentityTrackId', start)
        block = source[start:end]
        self.assertIn('FollowCameraTemporalRecovery.MAX_AGE_US', block)
        self.assertIn('reframeTemporalRecovery.observe(', block)
        self.assertIn('excludedTrackIds = allPrivacyTargetIds +', block)
        self.assertIn('reframeDistinctObservedBeforeLoss.keys', block)
        self.assertIn('reframeIdentityTrackId = recovery.track.id', block)
        for forbidden in ('trackManager.setPrivacySelectedTrackIds(', 'trackManager.setIdentityProtectedTrackIds(', 'selectedIds = ', 'privacyModeByTrackId ='):
            self.assertNotIn(forbidden, block)

    def test_temporal_voting_require_three_spaced_unique_observations(self):
        source = RECOVERY.read_text()
        for expected in ('const val MIN_OBSERVATIONS = 3', 'const val MAX_AGE_US = 2_600_000L',
                         'const val MIN_OBSERVATION_SPAN_US = 250_000L',
                         'best.score - next.score < MIN_SCORE_MARGIN',
                         'track.id !in excludedTrackIds', 'track.observedThisFrame',
                         'track.confidence >= MIN_TRACK_CONFIDENCE'):
            self.assertIn(expected, source)


if __name__ == '__main__':
    unittest.main()
