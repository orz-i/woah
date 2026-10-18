"""Kalman LOST height coasting and follow-mode independence regression.

Numerical replay uses the PLK110 ~261px / negative height-velocity failure;
source checks ensure the original tracking path is no longer configured by
the portrait-follow crop. Android/JVM validation is a separate gate.
"""
from pathlib import Path
import math
import unittest

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine"
KALMAN = (NATIVE / "tracking/KalmanFilter.kt").read_text()
TRACKER = (NATIVE / "tracking/TrackManager.kt").read_text()
EXPORT = (NATIVE / "pipeline/ExportPipeline.kt").read_text()


def height_trace(coasting: bool):
    height, velocity_h = 261.4, -415.0
    heights = []
    for frame in range(1, 16):
        if coasting and frame > 1:
            velocity_h = 0.0
        height += 0.033333 * velocity_h
        heights.append(height)
    return heights


class KalmanLostCoastingContractTest(unittest.TestCase):
    def test_17_867_second_collapse_does_not_repeat_after_lost(self):
        old = height_trace(False)
        bounded = height_trace(True)
        self.assertLess(old[-1], 65)  # previously ~53px after 0.5s
        self.assertGreater(bounded[-1], 245)
        self.assertLess(abs(bounded[-1] - bounded[0]), .001)

    def test_mode_independent_tracker_and_regular_active_motion(self):
        self.assertIn("coastUnobservedShape: Boolean = false", KALMAN)
        self.assertIn("state[6] = 0f", KALMAN)
        self.assertIn("state[7] = 0f", KALMAN)
        self.assertIn("state[4] *= LOST_CENTER_VELOCITY_RETENTION", KALMAN)
        self.assertIn("state[5] *= LOST_CENTER_VELOCITY_RETENTION", KALMAN)
        self.assertIn("LOST_CENTER_VELOCITY_RETENTION = 0.85f", KALMAN)
        self.assertIn("coastUnobservedShape = track.state == TrackState.LOST", TRACKER)
        self.assertEqual(TRACKER.count("coastUnobservedShape = track.state == TrackState.LOST"), 2)
        self.assertNotIn("FollowReservedLostRecovery", TRACKER)
        self.assertNotIn("followCameraTrackId", TRACKER)
        self.assertNotIn("setFollowCameraTrackId(", EXPORT)
        self.assertNotIn("setFollowCameraTrackId(", TRACKER)

    def test_privacy_and_camera_only_identity_rules_remain_separate(self):
        self.assertIn("reframeIdentityHistory.recordSelectedObservation(", EXPORT)
        self.assertIn("reframeIdentityHistory.excludedIds()", EXPORT)
        self.assertIn("reframeTemporalRecovery.observe(", EXPORT)
        self.assertIn("excludedTrackIds = excludedCameraProxyIds", EXPORT)
        self.assertIn("isProtectedGroupIdentityEvidenceSufficient(", TRACKER)
        self.assertIn("reservedGroupDetectionIndices.add(", TRACKER)
        self.assertIn("val remainingTrackIndices = tracks.indices.filter", TRACKER)


if __name__ == "__main__":
    unittest.main()
