"""Source-contract checks for numeric-only YOLO decoder-gap diagnostics.

Native Kotlin test execution still requires JDK/Android SDK on a build host.
These checks guard the observation-only boundary on the current Mac.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = (ROOT / "mobile/packages/dance_native/android/src/main/kotlin"
           / "art/gaoge/dance/engine")


class YoloRawTopSupportContractTest(unittest.TestCase):
    def test_probe_never_enters_export_privacy_decision(self):
        probe = (ANDROID / "inference/YoloRawTopSupportProbe.kt").read_text()
        adapter = (ANDROID / "inference/YoloLiteRtTensorAdapter.kt").read_text()
        segmenter = (ANDROID / "inference/YoloLiteRtSegmenter.kt").read_text()
        resolver = (ANDROID / "privacy/PrivacyOcclusionResolver.kt").read_text()
        self.assertIn("captureRawTopSupportAudit: Boolean = false", adapter)
        self.assertIn("if (auditOverlappingCandidate)", adapter)
        self.assertIn("BuildConfig.DEBUG &&", segmenter)
        self.assertIn("diagnosticJobId != null", segmenter)
        self.assertIn("PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT", resolver)
        self.assertIn("target_yolo_raw_top_support", resolver)
        self.assertIn("candidate_yolo_raw_top_support", resolver)
        self.assertEqual(resolver.count("probe.targetRawTopSupportAudit?.scalarFields()"), 1)
        self.assertEqual(resolver.count("probe.foregroundRawTopSupportAudit?.scalarFields()"), 1)
        # Only numeric sample buckets; never images/arrays in export telemetry.
        self.assertIn("Map<String, Int>", probe)
        for prohibited in ("Bitmap", "writeBytes", "FileOutputStream", "MediaCodec"):
            self.assertNotIn(prohibited, probe)

    def test_existing_selected_fallback_and_renderer_veto_remain_in_place(self):
        resolver = (ANDROID / "privacy/PrivacyOcclusionResolver.kt").read_text()
        self.assertIn("person.framesSinceLastObservation <= maxFallbackObservationAgeFrames", resolver)
        self.assertIn("mergeMasks(effectiveSelectedMasks)", resolver)
        self.assertIn("after == 0", resolver)
        self.assertIn("buildSafeRenderOccluderMask(", resolver)
        self.assertIn("val conservativeUnknownTarget", resolver)


if __name__ == "__main__":
    unittest.main()
