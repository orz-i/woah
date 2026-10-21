"""Stage 4 six-frame paired-world falsification is synthetic and non-authorizing.

A perfect same-model score cannot reveal two physically different protected
people when the inputs and incorrectly 'complete' observed census are identical.
This test demonstrates a *specific ambiguity*, not overall model accuracy.
"""
from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
import subprocess
import sys
import unittest

from tools.diagnostics.independent_instance_mask_gate import evaluate
from tools.diagnostics.stage4_ownership_ambiguity_witness import (
    HEIGHT, RISK_PTS_US, REPORT_SCOPE, WIDTH,
    evaluate_ambiguity, synthetic_worlds,
)


class Stage4ObservationalAmbiguityWitnessTest(unittest.TestCase):
    def test_all_six_exact_risk_timestamps_have_indistinguishable_observables(self):
        self.assertEqual(RISK_PTS_US, (
            39_733_333, 39_766_666, 39_800_000,
            39_833_333, 39_866_666, 39_900_000,
        ))
        for pts in RISK_PTS_US:
            with self.subTest(pts_us=pts):
                agreed, missed = synthetic_worlds(pts)
                self.assertEqual(agreed.source_pts_us, missed.source_pts_us)
                self.assertEqual(agreed.coordinate_space, missed.coordinate_space)
                self.assertEqual(
                    replace(agreed, synthetic_truth_protected_mask=None),
                    replace(missed, synthetic_truth_protected_mask=None),
                    "observable input must be identical across physically different truths",
                )
                result_agreed = evaluate(agreed)
                result_missed = evaluate(missed)
                self.assertEqual(result_agreed.hypothetical_clear_pixels,
                                 result_missed.hypothetical_clear_pixels)
                self.assertGreater(result_agreed.hypothetical_clear_pixels, 0)
                self.assertEqual(result_agreed.synthetic_false_clear_pixels, 0)
                self.assertGreater(result_missed.synthetic_false_clear_pixels, 0)
                self.assertEqual(result_agreed.status,
                                 "SYNTHETIC_NO_FALSE_CLEAR_NOT_VIDEO_ACCEPTED")
                self.assertEqual(result_missed.status,
                                 "SYNTHETIC_FALSE_CLEAR_BLOCKS_MODEL")
                self.assertEqual(result_agreed.scalars()["pixel_carve_authorization"],
                                 "NEVER")
                self.assertEqual(result_missed.scalars()["pixel_carve_authorization"],
                                 "NEVER")

    def test_no_oracle_both_worlds_remain_not_proven(self):
        for pts in RISK_PTS_US:
            agreed, missed = synthetic_worlds(pts)
            # Remove inaccessible true occupancy: detector/model data alone
            # cannot separate which physical world produced the observation.
            unknown = replace(agreed, synthetic_truth_protected_mask=None)
            self.assertEqual(unknown,
                             replace(missed, synthetic_truth_protected_mask=None))
            result = evaluate(unknown)
            self.assertEqual(result.status,
                             "UNLABELED_HYPOTHESIS_NO_PRIVACY_ACCEPTANCE")
            self.assertIsNone(result.synthetic_false_clear_pixels)
            self.assertEqual(result.scalars()["privacy_visual_acceptance"],
                             "NOT_PROVEN")

    def test_falsifying_census_claim_rejects_all_candidate_holes(self):
        for pts in RISK_PTS_US:
            first, _ = synthetic_worlds(pts)
            rejected = replace(
                first, independently_verified_selected_census=False
            )
            result = evaluate(rejected)
            self.assertEqual(result.status,
                             "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")
            self.assertEqual(result.hypothetical_clear_pixels, 0)

    def test_report_is_only_safe_scalars_and_admits_no_real_video_proof(self):
        report = evaluate_ambiguity()
        self.assertEqual(report["schema"], 1)
        self.assertEqual(report["evidence_scope"], REPORT_SCOPE)
        self.assertEqual(report["paired_world_count"], 6)
        self.assertTrue(report["observational_equality_all_pairs"])
        self.assertEqual(report["synthetic_source_geometry"], f"{WIDTH}x{HEIGHT}")
        self.assertEqual(report["safe_world_false_clear_pixels"], 0)
        self.assertGreater(report["missed_protected_world_false_clear_pixels_min"], 0)
        self.assertEqual(report["missed_protected_world_false_clear_pixels_max"],
                         report["missed_protected_world_false_clear_pixels_min"])
        self.assertEqual(report["model_only_identity_ownership"],
                         "NOT_IDENTIFIABLE_FROM_AVAILABLE_EVIDENCE")
        self.assertFalse(report["real_video_ground_truth_available"])
        self.assertEqual(report["real_video_privacy_acceptance"], "NOT_PROVEN")
        self.assertEqual(report["pixel_carve_authorization"], "NEVER")
        self.assertFalse(report["production_runtime_changed"])
        self.assertEqual(len(set(report["hypothetical_clear_pixels_per_world"])), 1)
        self.assertEqual(set(report) - {
            "schema", "evidence_scope", "synthetic_source_geometry",
            "paired_world_count", "observational_equality_all_pairs",
            "hypothetical_clear_pixels_per_world",
            "safe_world_false_clear_pixels",
            "missed_protected_world_false_clear_pixels_min",
            "missed_protected_world_false_clear_pixels_max",
            "counterexample_interpretation", "model_only_identity_ownership",
            "real_video_ground_truth_available", "real_video_privacy_acceptance",
            "pixel_carve_authorization", "production_runtime_changed",
            "required_next_evidence",
        }, set())

    def test_unverified_pts_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "six explicitly reviewed risk PTS"):
            synthetic_worlds(39_799_999)

    def test_witness_can_be_reproduced_without_private_media_or_masks(self):
        script = Path(__file__).with_name("stage4_ownership_ambiguity_witness.py")
        text = script.read_text(encoding="utf-8")
        for forbidden in (
            "import cv2", "import torch", "import numpy", "from PIL",
            "import requests", "import urllib", "Bitmap", "MediaCodec",
            "PrivacyOcclusionResolver", "open(", "write_bytes(",
            "write_text(", "read_bytes(", "read_text(", "ffmpeg",
        ):
            self.assertNotIn(forbidden, text)
        result = subprocess.run(
            [sys.executable, str(script)], capture_output=True, text=True,
            timeout=10, check=True,
        )
        report = json.loads(result.stdout)
        self.assertEqual(report, evaluate_ambiguity())
        self.assertNotIn("risk_pts_us", report)
        self.assertNotIn("true_masks", report)
        self.assertNotIn("source_uri", report)
        self.assertNotIn("person_id", report)
        self.assertNotIn("mask_values", report)


if __name__ == "__main__":
    unittest.main()
