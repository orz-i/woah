"""Privacy visual labels are coarse, offline, and never authorize mask carving."""
from __future__ import annotations

import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import evaluate_privacy_manual_labels as reviewer


SHA = "2cb113ea12ed200c60dcf50177061fe204cec155"
PTS = [39_733_333, 39_766_666]


def numeric_fixture() -> dict:
    def cells(pts: int):
        arr = {key: [0] * 16 for key in reviewer.RELEVANT}
        arr["selected_raw"][0] = 5
        arr["selected_raw"][1] = 4
        arr["selected_retained_effective"][0] = 3
        arr["selected_retained_effective"][1] = 4
        arr["selected_retained_and_fresh_foreground"][0] = 2
        arr["selected_and_fresh_foreground"][0] = 5
        arr["selected_retained_with_weak_foreground"][1] = 2
        arr["competing_selected"][1] = 4
        arr["final_merged"][0] = 3
        arr["final_merged"][1] = 4
        return arr
    return {
        "build_commit": SHA, "focus_frames": [
            {
                "pts_us": pts,
                "protected_mask_proto_bbox": [63.081, 70.049, 73.638, 97.608],
                "coarse_4x4_cells": cells(pts),
            }
            for pts in PTS
        ],
    }


def manual_fixture() -> dict:
    return {
        "schema": 1,
        "purpose": reviewer.PURPOSE,
        "evidence_scope": reviewer.EVIDENCE_SCOPE,
        "report_build_commit": SHA,
        "source_width": 1280,
        "source_height": 720,
        "source_duration_s": 46.366667,
        "manual_source_video_match": "OPERATOR_ASSERTED_NOT_CRYPTOGRAPHICALLY_VERIFIED",
        "visual_privacy_acceptance": "NOT_PROVEN",
        "pixel_carve_authorization": "NEVER",
        "label_definitions": {},
        "frames": [
            {
                "pts_us": pts,
                "selected_bbox_proto": [63.081, 70.049, 73.638, 97.608],
                "browser_media_time_s": pts / 1e6,
                "cells": ["PROTAGONIST_VISIBLE", "PROTECTED_VISIBLE",
                          "MIXED_UNCERTAIN", "BACKGROUND_VISIBLE"] +
                         ["UNREVIEWED"] * 12,
            }
            for pts in PTS
        ],
    }


class CoarseManualLabelTest(unittest.TestCase):
    def test_expected_coarse_counts_never_claim_final_pixel_proof(self):
        result = reviewer.evaluate(manual_fixture(), numeric_fixture())
        self.assertEqual(result["schema"], 1)
        self.assertEqual(result["privacy_acceptance"], "NOT_PROVEN")
        self.assertEqual(result["pixel_carve_authorization"], "NEVER")
        self.assertFalse(result["manual_review_complete"])
        self.assertTrue(result["decoded_video_time_verified_for_all_frames"])
        self.assertEqual(result["build_commit"], SHA)
        one = result["risk_frames"][0]
        triage = one["triage_candidates_not_proven_mask_errors"]
        self.assertEqual(triage["protected_pixels_in_cells_with_visible_protagonist"], 3)
        self.assertEqual(triage["protected_pixels_also_in_fresh_foreground_in_those_cells"], 2)
        self.assertEqual(triage["source_masks_missing_fresh_foreground_in_those_cells"], 1)
        self.assertEqual(triage["other_selected_mask_pixels_in_those_cells"], 0)
        self.assertEqual(one["manual_cell_labels"]["MIXED_UNCERTAIN"]["cell_count"], 1)

    def test_proto_bbox_maps_to_source_using_the_real_letterbox_geometry(self):
        converted = reviewer.proto_bbox_to_source(
            [63.081055, 70.04883, 73.637695, 97.60742]
        )
        self.assertAlmostEqual(converted[0], 504.648, delta=0.010)
        self.assertAlmostEqual(converted[1], 280.391, delta=0.010)
        self.assertAlmostEqual(converted[2], 589.102, delta=0.010)
        self.assertAlmostEqual(converted[3], 500.859, delta=0.010)
        result = reviewer.evaluate(manual_fixture(), numeric_fixture())
        self.assertEqual(len(result["risk_frames"][0]["target_bbox_source_px_approx"]), 4)

    def test_unknown_video_timing_never_claims_verified_alignment(self):
        labels = manual_fixture()
        labels["frames"][0]["browser_media_time_s"] = None
        labels["frames"][1]["browser_media_time_s"] = 39.4
        result = reviewer.evaluate(labels, numeric_fixture())
        self.assertEqual(
            [f["source_video_time_alignment"] for f in result["risk_frames"]],
            ["MEDIA_TIME_UNVERIFIED", "MEDIA_TIME_DIFFERENT_FROM_TARGET"]
        )
        self.assertFalse(result["decoded_video_time_verified_for_all_frames"])

    def test_rejects_invalid_schema_unknown_fields_and_unreviewed_export(self):
        labels = manual_fixture()
        for changes, pattern in (
            ({"pixel_carve_authorization": "GRANTED"}, "must not claim"),
            ({"source_width": 1920}, "source geometry"),
            ({"source_duration_s": 10.0}, "duration"),
            ({"source_original_file": "private_original.mp4"}, "unexpected manual"),
            ({"report_build_commit": "a" * 40}, "build commit"),
        ):
            with self.subTest(changes=changes):
                bad = copy.deepcopy(labels)
                bad.update(changes)
                with self.assertRaisesRegex(ValueError, pattern):
                    reviewer.evaluate(bad, numeric_fixture())
        labels["frames"][0]["cells"][0] = "PROTAGONIST_FACE_PIXELS"
        with self.assertRaisesRegex(ValueError, "sixteen allowed"):
            reviewer.evaluate(labels, numeric_fixture())
        labels = manual_fixture()
        for frame in labels["frames"]:
            frame["cells"] = ["UNREVIEWED"] * 16
        with self.assertRaisesRegex(ValueError, "manually reviewed"):
            reviewer.evaluate(labels, numeric_fixture())

    def test_rejects_changed_pts_bbox_or_missing_frame(self):
        for mutation, pattern in (
            ("pts", "unknown manual frame PTS"),
            ("bbox", "bbox does not match"),
            ("missing", "frames incomplete"),
        ):
            labels = manual_fixture()
            if mutation == "pts":
                labels["frames"][0]["pts_us"] = 33_000_000
            if mutation == "bbox":
                labels["frames"][0]["selected_bbox_proto"][1] = 72
            if mutation == "missing":
                labels["frames"] = labels["frames"][:1]
            with self.subTest(mutation=mutation):
                with self.assertRaisesRegex(ValueError, pattern):
                    reviewer.evaluate(labels, numeric_fixture())

    def test_abridged_real_device_json_never_invents_missing_cell_signals(self):
        numeric = numeric_fixture()
        for frame in numeric["focus_frames"]:
            frame["coarse_4x4_cells"].pop("selected_retained_effective")
            frame["coarse_4x4_cells"].pop("final_merged")
        result = reviewer.evaluate(manual_fixture(), numeric)
        first = result["risk_frames"][0]
        protagonist = first["manual_cell_labels"]["PROTAGONIST_VISIBLE"][
            "proto_counts_within_labeled_cells"
        ]
        self.assertIsNone(protagonist["selected_retained_effective"])
        self.assertIsNone(protagonist["final_merged"])
        self.assertEqual(protagonist["selected_retained_and_fresh_foreground"], 2)
        self.assertIsNone(
            first["triage_candidates_not_proven_mask_errors"][
                "source_masks_missing_fresh_foreground_in_those_cells"
            ]
        )

    def test_numeric_parity_format_also_supported(self):
        numeric = numeric_fixture()
        formatted = {
            "parity_status": "SOURCE_MASK_BIN_PARITY_ONLY_NOT_VISUAL_ACCEPTANCE",
            "landscape": {
                "build_commit": SHA,
                "focus_events": [
                    {
                        "pts_us": frame["pts_us"],
                        "selected_bbox_proto": frame["protected_mask_proto_bbox"],
                        "grid_by_signal": frame["coarse_4x4_cells"],
                    }
                    for frame in numeric["focus_frames"]
                ],
            }
        }
        self.assertEqual(len(reviewer.evaluate(manual_fixture(), formatted)["risk_frames"]), 2)
        formatted["parity_status"] = "FOCUS_BIN_COUNTS_MISMATCH"
        with self.assertRaisesRegex(ValueError, "does not establish"):
            reviewer.evaluate(manual_fixture(), formatted)

    def test_offline_browser_contract_has_no_media_export_or_network_requests(self):
        html = (
            Path(__file__).with_name("privacy_overlap_local_annotator.html")
            .read_text(encoding="utf-8")
        )
        self.assertIn("connect-src 'none'", html)
        self.assertIn("media-src blob:", html)
        self.assertIn("URL.createObjectURL(file)", html)
        self.assertIn("source_duration_s:video.duration", html)
        self.assertIn("browser_media_time_s:", html)
        self.assertIn("manual_source_video_match:", html)
        self.assertIn("pixel_carve_authorization:'NEVER'", html)
        self.assertIn("Array(16).fill('UNREVIEWED')", html)
        self.assertIn("REQUIRED_SIGNALS", html)
        self.assertIn("未提供逐格数据（不能当作 0）", html)
        self.assertNotIn("fetch(", html)
        self.assertNotIn("XMLHttpRequest", html)
        self.assertNotIn("toDataURL(", html)
        self.assertNotIn("captureStream(", html)

    def test_cli_rejects_sensitive_output_inside_git_repo(self):
        repo_root = Path(reviewer.__file__).resolve().parents[2]
        argv = [
            "evaluate_privacy_manual_labels.py",
            "--annotations", "/not-needed.json",
            "--numeric-report", "/not-needed.json",
            "--output", str(repo_root / "diagnostic-label-report.json"),
        ]
        with patch("sys.argv", argv), contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(reviewer.main(), 1)
        self.assertIn("outside the Git repository", error.getvalue())

    def test_cli_exports_numeric_only_report_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            labels = root / "user-labels.json"
            numeric = root / "numeric-report.json"
            target = root / "triage.json"
            labels.write_text(json.dumps(manual_fixture()), encoding="utf-8")
            numeric.write_text(json.dumps(numeric_fixture()), encoding="utf-8")
            argv = [
                "evaluate_privacy_manual_labels.py",
                "--annotations", str(labels),
                "--numeric-report", str(numeric),
                "--output", str(target),
            ]
            with patch("sys.argv", argv), contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(reviewer.main(), 0)
            self.assertIn("TRIAGE_ONLY_NOT_ACCEPTED", out.getvalue())
            export = target.read_text(encoding="utf-8")
            self.assertNotIn("mp4", export.lower())
            self.assertNotIn("source_original_file", export)
            self.assertNotIn("image_bytes", export.lower())
            self.assertNotIn("raw_video", export.lower())
            result = json.loads(export)
            self.assertEqual(result["privacy_acceptance"], "NOT_PROVEN")


if __name__ == "__main__":
    unittest.main()
