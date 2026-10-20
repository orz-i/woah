"""Source contracts for retiring exhausted YOLO raw top-support diagnostics.

Historical anonymous ZIP analyzers stay usable; native privacy/rendering keeps
its pre-experiment behavior. These tests do NOT establish visual privacy PASS.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = (ROOT / "mobile/packages/dance_native/android/src/main/kotlin"
           / "art/gaoge/dance/engine")


class YoloRawTopSupportRetirementContractTest(unittest.TestCase):
    def test_live_raw_proto_sampler_is_fully_retired(self):
        probe = ANDROID / "inference/YoloRawTopSupportProbe.kt"
        self.assertFalse(probe.exists(), "Unused debug raw-prototype sampler must not be compiled")
        adapter = (ANDROID / "inference/YoloLiteRtTensorAdapter.kt").read_text()
        segmenter = (ANDROID / "inference/YoloLiteRtSegmenter.kt").read_text()
        detector = (ANDROID / "inference/Segmenter.kt").read_text()
        resolver = (ANDROID / "privacy/PrivacyOcclusionResolver.kt").read_text()
        for source in (adapter, segmenter, detector, resolver):
            for forbidden in (
                "rawTopSupportAudit", "YoloRawTopSupportProbe",
                "YoloRawTopSupportAudit", "captureRawTopSupportAudit",
                "target_yolo_raw_top_support",
                "candidate_yolo_raw_top_support",
            ):
                self.assertNotIn(forbidden, source)
        self.assertIn("PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT", resolver)
        self.assertIn("val (kept, maskCache) = YoloMaskDecoder.maskAwareNms(", adapter)

    def test_historical_numeric_comparator_remains_non_visual(self):
        analyzer = (ROOT / "tools/diagnostics/analyze_privacy_overlap_evidence.py").read_text()
        for token in (
            "RAW_TOP_SUPPORT_FIELDS",
            "target_yolo_raw_top_support",
            "candidate_yolo_raw_top_support",
            '"visual_privacy_acceptance": "NOT_PROVEN"',
        ):
            self.assertIn(token, analyzer)
        for forbidden in ("cv2.imread", "PIL.Image.open", "mask_png_bytes"):
            self.assertNotIn(forbidden, analyzer)

    def test_existing_selected_fallback_and_renderer_veto_remain_in_place(self):
        resolver = (ANDROID / "privacy/PrivacyOcclusionResolver.kt").read_text()
        self.assertIn("person.framesSinceLastObservation <= maxFallbackObservationAgeFrames", resolver)
        self.assertIn("mergeMasks(effectiveSelectedMasks)", resolver)
        self.assertIn("after == 0", resolver)
        self.assertIn("buildSafeRenderOccluderMask(", resolver)
        self.assertIn("val conservativeUnknownTarget", resolver)

    def test_nms_control_case_restored_without_active_threshold_change(self):
        decoder = (ANDROID / "inference/YoloMaskDecoder.kt").read_text()
        adapter = (ANDROID / "inference/YoloLiteRtTensorAdapter.kt").read_text()
        self.assertIn("if (a != 0 && b != 0) intersection++", decoder)
        self.assertIn("if ((a or b) != 0) union++", decoder)
        self.assertNotIn("activeByteThreshold", decoder)
        self.assertNotIn("nmsShadowStats", adapter)


if __name__ == "__main__":
    unittest.main()
