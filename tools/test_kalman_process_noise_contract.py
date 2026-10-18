"""An executable 1D slice of the Android Kalman height/velocity covariance.

Uses anonymous observed bbox heights from the PLK110 diagnostic (17.0-18.833s).
It is a mathematically exact slice for the h/vh channel of XYAH; it does not
exercise Android tracking, identity assignment, or privacy rendering.
"""
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "mobile/packages/dance_native/android/src/main/kotlin/"
          "art/gaoge/dance/engine/tracking/KalmanFilter.kt").read_text()
TRACKER = (ROOT / "mobile/packages/dance_native/android/src/main/kotlin/"
           "art/gaoge/dance/engine/tracking/TrackManager.kt").read_text()
EXPORT = (ROOT / "mobile/packages/dance_native/android/src/main/kotlin/"
          "art/gaoge/dance/engine/pipeline/ExportPipeline.kt").read_text()

HEIGHTS = (
    363.4,366.6,370.0,372.5,376.6,382.8,388.8,392.8,398.8,406.9,
    411.9,410.0,408.8,406.9,405.3,405.3,400.0,395.3,389.4,380.9,
    362.8,342.8,324.4,303.9,288.4,273.0,261.4,256.9,246.4,242.5,
    237.5,236.4,233.0,233.0,230.9,229.4,228.9,224.4,225.9,225.0,
    215.5,221.9,220.9,223.9,226.9,230.0,231.9,237.5,235.0,219.4,
    219.4,240.9,270.5,290.9,
)


def replay(adjusted):
    h = HEIGHTS[0]
    vh = 0.0
    # Initial P[3,3], P[3,7], P[7,7] from KalmanFilter.init
    p_hh = (0.1 * h)**2
    p_hv = 0.0
    p_vv = h*h
    last_t = 17_000_000
    predicted = []
    for i, observed in enumerate(HEIGHTS[1:], start=1):
        frame_number = i if i <= 40 else i + 2  # 18.367/18.400 no commit
        pts = 17_000_000 + frame_number * 33333
        dt = max(0.001, min(0.2, (pts-last_t)/1e6))
        last_t = pts
        h += dt*vh
        if adjusted:
            scale = 0.50 * math.sqrt(dt*30.0)
            q_h = (.05*h*scale)**2
            q_v = (.1*h*30.0*scale)**2
        else:
            q_h = (.05*h*dt)**2
            q_v = (.1*h*dt)**2
        new_hh = p_hh + 2*dt*p_hv + dt*dt*p_vv + q_h
        new_hv = p_hv + dt*p_vv
        new_vv = p_vv + q_v
        p_hh, p_hv, p_vv = new_hh, new_hv, new_vv
        predicted.append(h)
        s = p_hh + (.05*observed)**2
        k_h, k_v = p_hh/s, p_hv/s
        innovation = observed - h
        h += k_h*innovation
        vh += k_v*innovation
        old_hh, old_hv = p_hh, p_hv
        p_hh -= k_h*old_hh
        p_hv -= k_h*old_hv
        p_vv -= k_v*old_hv
    rmse = math.sqrt(sum((a-b)**2 for a,b in zip(predicted,HEIGHTS[1:]))/len(predicted))
    return rmse, predicted[-1]


class KalmanProcessNoiseContractTest(unittest.TestCase):
    def test_real_bbox_height_replay_detects_dt_squared_underweighting(self):
        original_rmse, original_last = replay(adjusted=False)
        fixed_rmse, fixed_last = replay(adjusted=True)
        self.assertGreater(original_rmse, 25)
        self.assertLess(original_last, 235)
        self.assertLess(fixed_rmse, 13)
        self.assertGreater(fixed_last, 260)

    def test_production_process_covariance_has_frame_normalized_variance(self):
        self.assertIn("val noiseScale = 0.50f * sqrt(dt * 30f)", SOURCE)
        self.assertIn("stdPos * noiseScale", SOURCE)
        self.assertIn("stdVel * 30f * noiseScale", SOURCE)
        self.assertNotIn("stdPos * dt,", SOURCE)
        self.assertNotIn("stdVel * dt,", SOURCE)
        self.assertIn("covariance[i][j] = newP[i][j]", SOURCE)

    def test_retired_camera_shortcuts_are_absent_while_privacy_guards_remain(self):
        for removed in ("FollowCameraWeakGroupOwnership",
                        "weakGroupYieldOwnerByDetection",
                        "isOcclusionPairConnected(",
                        "OCCLUSION_GROUP_EXIT_MARGIN"):
            self.assertNotIn(removed, TRACKER)
        self.assertIn("if (overlapRatio >= config.occlusionOverlapRatio)", TRACKER)
        self.assertNotIn("FollowReservedLostRecovery", TRACKER)
        self.assertNotIn("setFollowCameraTrackId(", EXPORT)
        self.assertIn("reframeIdentityHistory.excludedIds()", EXPORT)
        self.assertIn("reframeIdentityHistory.recordSelectedObservation(", EXPORT)


if __name__ == "__main__":
    unittest.main()
