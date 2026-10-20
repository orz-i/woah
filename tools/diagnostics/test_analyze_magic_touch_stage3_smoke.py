"""Safety-oriented parser tests for CPU-only synthetic MagicTouch logs."""
from __future__ import annotations

import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import analyze_magic_touch_stage3_smoke as audit


def record(**overrides) -> dict:
    fields = {
        "schema": 1,
        "scope": audit.SCOPE,
        "run_token": "f" * 16,
        "git_commit": "a" * 40,
        "model_sha256": audit.EXPECTED_MODEL_SHA,
        "device_model": "synthetic-mock-device",
        "android_api": 37,
        "source_video_used": False,
        "person_identity_proven": False,
        "protected_person_occupancy_verified": False,
        "pixel_carve_authorization": "NEVER",
        "privacy_visual_acceptance": "NOT_PROVEN",
        "delegate": "CPU",
        "model_sha256_verified": True,
        "image_width": 384,
        "image_height": 384,
        "warmups": 1,
        "measured_runs": 5,
        "init_wall_ms": 121,
        "set_image_median_ms": 178.3,
        "set_image_p95_ms": 192.4,
        "segment_median_ms": 82.5,
        "segment_p95_ms": 87.2,
        "last_positive_mask_pixels_ge_0_5": 7104,
        "last_confidence_min": 0.02,
        "last_confidence_max": 0.99,
        "wide_source_width": 512,
        "wide_source_height": 288,
        "wide_source_output_geometry_verified": True,
        "wide_set_image_ms": 203.3,
        "wide_segment_ms": 90.1,
        "pss_start_kb": 120_000,
        "pss_end_kb": 155_000,
        "thermal_start": 0,
        "thermal_end": 1,
        "peak_ram_measured": False,
        "gpu_latency_measured": False,
        "final_video_verified": False,
    }
    fields.update(overrides)
    return fields


def logcat(*rows: dict) -> str:
    return "\n".join([
        "I/WoahMagicTouchStage3(313): " + json.dumps(row) for row in rows
    ])


class Stage3SyntheticSmokeReportTest(unittest.TestCase):
    def test_single_completed_run_does_not_claim_performance_acceptance(self):
        result = audit.summarize(audit.read_logcat(logcat(record())))
        self.assertEqual(result["runs"], 1)
        self.assertEqual(result["repeat_status"], "REPEAT_REQUIRED")
        self.assertEqual(result["android_api_smoke"], "CPU_CODE_PATH_EXECUTED_ONLY")
        self.assertEqual(result["privacy_visual_acceptance"], "NOT_PROVEN")
        self.assertEqual(result["pixel_carve_authorization"], "NEVER")
        self.assertEqual(result["release_acceptance"], "NOT_PROVEN")

    def test_run_token_filters_existing_logcat_without_global_log_clear(self):
        other = record(run_token="1" * 16, device_model="other-device")
        current = record(run_token="f" * 16)
        selected = audit.read_logcat(logcat(other, current), run_token="f" * 16)
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]["device_model"], "synthetic-mock-device")
        with self.assertRaisesRegex(ValueError, "no valid completed"):
            audit.read_logcat(logcat(other), run_token="f" * 16)

    def test_three_repeated_runs_have_sampled_cost_without_real_video_claim(self):
        events = [record(
            init_wall_ms=121 + i * 7,
            segment_median_ms=82.5 + i,
            segment_p95_ms=87.2 + i,
        ) for i in range(3)]
        summary = audit.summarize(audit.read_logcat(logcat(*events)))
        self.assertEqual(summary["runs"], 3)
        self.assertEqual(summary["repeat_status"],
                         "REPEATED_CPU_SMOKE_NOT_PRODUCTION_BENCHMARK")
        self.assertAlmostEqual(summary["cpu_init_median_ms"], 128)
        self.assertAlmostEqual(summary["segment_median_ms"], 83.5)
        self.assertAlmostEqual(summary["segment_p95_over_runs_ms"], 89.2)
        self.assertIn("NO_REAL_VIDEO_OR_OCCLUSION", summary["performance_limits"])

    def test_unknown_fields_including_raw_masks_fail_closed(self):
        for suspicious in (
            {"raw_mask": [0, 1, 255]},
            {"rgba": "abc"},
            {"source_uri": "file:///private.mp4"},
            {"privacy_pass": True},
            {"root_person_id": 2},
        ):
            with self.assertRaisesRegex(ValueError, "forbidden mask/image data"):
                audit.validate(record(**suspicious))

    def test_privacy_provenance_flags_cannot_upgrade_status(self):
        for changes in (
            {"person_identity_proven": True},
            {"protected_person_occupancy_verified": True},
            {"pixel_carve_authorization": "YES"},
            {"privacy_visual_acceptance": "PASS"},
            {"release_acceptance": True},
            {"source_video_used": True},
            {"delegate": "GPU"},
            {"gpu_latency_measured": True},
            {"peak_ram_measured": True},
            {"final_video_verified": True},
        ):
            with self.assertRaises(ValueError):
                audit.validate(record(**changes))

    def test_mismatched_model_commit_device_and_geometry_cannot_compare(self):
        for changes in (
            {"model_sha256": "f" * 64},
            {"device_model": "other"},
            {"git_commit": "c" * 40},
            {"android_api": 36},
            {"image_width": 768},
            {"wide_source_height": 384},
            {"wide_source_output_geometry_verified": False},
        ):
            edited = record(**changes)
            if changes.keys() & {"device_model", "git_commit", "android_api"}:
                with self.assertRaisesRegex(ValueError, "cannot be compared"):
                    audit.summarize([record(), edited])
            else:
                with self.assertRaises(ValueError):
                    audit.validate(edited)

    def test_nan_negative_infinite_timing_and_p95_order_rejected(self):
        for change in (
            {"init_wall_ms": float("nan")},
            {"set_image_median_ms": -1},
            {"wide_segment_ms": float("inf")},
            {"segment_median_ms": 99, "segment_p95_ms": 10},
            {"set_image_median_ms": 99, "set_image_p95_ms": 10},
            {"thermal_end": 999},
            {"pss_end_kb": -20},
        ):
            with self.assertRaises(ValueError):
                audit.validate(record(**change))

    def test_bounded_mask_count_and_confidence_range(self):
        for change in (
            {"last_positive_mask_pixels_ge_0_5": 384 * 384 + 1},
            {"last_positive_mask_pixels_ge_0_5": -1},
            {"last_positive_mask_pixels_ge_0_5": True},
            {"last_confidence_min": float("nan")},
            {"last_confidence_max": float("inf")},
            {"last_confidence_min": 0.9, "last_confidence_max": 0.1},
        ):
            with self.assertRaises(ValueError):
                audit.validate(record(**change))

    def test_bad_json_and_older_log_lines_are_not_inferred_as_pass(self):
        sample = "\n".join((
            "D/SomeOtherTag(100): {\"privacy_visual_acceptance\":\"PASS\"}",
            "I/WoahMagicTouchStage3(100): {not json}",
        ))
        with self.assertRaisesRegex(ValueError, "no valid completed"):
            audit.read_logcat(sample)

    def test_main_refuses_output_inside_repository(self):
        with tempfile.TemporaryDirectory() as dirname:
            log_file = Path(dirname) / "run.log"
            log_file.write_text(logcat(record()), encoding="utf-8")
            report_path = audit.ROOT / "stage3-private-report.json"
            with patch("sys.argv", [
                "analyze_magic_touch_stage3_smoke.py",
                "--log", str(log_file),
                "--output", str(report_path),
            ]), contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(audit.main(), 1)
                self.assertIn("outside Git tree", errors.getvalue())
            self.assertFalse(report_path.exists())

    def test_main_writes_only_scalar_json_outside_repository(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            source = root / "synthetic.log"
            dest = root / "numeric-report.json"
            source.write_text(logcat(record()), encoding="utf-8")
            with patch("sys.argv", [
                "analyze_magic_touch_stage3_smoke.py",
                "--log", str(source),
                "--output", str(dest),
            ]), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(audit.main(), 0)
                self.assertIn("REPEAT_REQUIRED", output.getvalue())
            data = json.loads(dest.read_text(encoding="utf-8"))
            self.assertNotIn("mask_bytes", data)
            self.assertNotIn("mask", [k for k in data if k.endswith("pixels")])
            self.assertEqual(data["pixel_carve_authorization"], "NEVER")
            self.assertEqual(data["privacy_visual_acceptance"], "NOT_PROVEN")


if __name__ == "__main__":
    unittest.main()
