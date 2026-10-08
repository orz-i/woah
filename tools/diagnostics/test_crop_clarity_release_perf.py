"""Contract tests: comparable release-only ON/OFF evidence, no invented GPU metrics."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from tools.diagnostics.crop_clarity_release_perf import compare, read_records


def record(mode: str, elapsed: float = 1000.0, **changes) -> dict:
    row = {
        "schema": 1, "state": "completed", "build_mode": "release", "git_commit": "0123456789abcdef",
        "device_model": "DeviceFixture", "source_width": 1280, "source_height": 720,
        "source_fps": 30.0, "trim_start_ms": 0, "trim_end_ms": 10_000,
        "target_width": 810, "target_height": 1440, "target_fps": 30.0,
        "video_bitrate": 4_000_000, "profile": "quality", "follow_enabled": True,
        "privacy_target_count": 2, "yolo_accelerator": "GPU", "yolo_fallback": None,
        "clarity_scale": 1.0 if mode == "off" else 2.0, "clarity_state": mode,
        "decoded_frames": 300, "rendered_frames": 300, "encoded_frames": 300,
        "elapsed_ms": elapsed, "throughput_fps": 300_000 / elapsed,
        "render_cpu_dispatch_count": 300, "render_cpu_dispatch_p50_ms": 2,
        "render_cpu_dispatch_p95_ms": 4, "pss_end_kb": 120_000,
        "thermal_status_end": 1, "ab_capture_possible": False,
    }
    row.update(changes)
    return row


class CropClarityReleasePerfTest(unittest.TestCase):
    def test_same_device_releases_are_comparable_but_not_auto_quality_pass(self):
        report = compare(
            [record("off", t) for t in (1000, 1010, 990)],
            [record("on", t) for t in (1050, 1060, 1040)],
            scenario="licensed-dance-fixture",
        )
        self.assertEqual(report["throughput_regression_review"], "WITHIN_PROVISIONAL_BUDGET")
        self.assertEqual(report["release_acceptance"], "REQUIRES_DEVICE_REVIEW")
        self.assertEqual(report["wall_elapsed_slowdown_percent"], 5.0)
        self.assertIn("NOT_MEASURED", report["measurement_limits"]["gpu_execution_time"])

    def test_one_run_is_not_enough(self):
        report = compare([record("off")], [record("on")], scenario="same-clip")
        self.assertEqual(report["throughput_regression_review"], "REPEAT_REQUIRED")

    def test_reject_debug_fallback_and_frame_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "log.txt"
            path.write_text("I/WoahExportPerf: " + json.dumps(record("on", build_mode="debug")))
            with self.assertRaisesRegex(ValueError, "Debug data"):
                read_records(path)
            path.write_text("I/WoahExportPerf: " + json.dumps(record("on", yolo_fallback="driver")))
            with self.assertRaisesRegex(ValueError, "fallback"):
                read_records(path)
            path.write_text("I/WoahExportPerf: " + json.dumps(record("on", encoded_frames=299)))
            with self.assertRaisesRegex(ValueError, "frame count"):
                read_records(path)

    def test_windows_utf16_logcat_is_decoded_as_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "off.log"
            path.write_bytes(("I/WoahExportPerf: " + json.dumps(record("off"))).encode("utf-16"))
            self.assertEqual(read_records(path)[0]["build_mode"], "release")

    def test_incompatible_geometry_revision_or_mode_rejected(self):
        for mutation in (
            {"target_width": 1080}, {"device_model": "OtherDevice"},
            {"git_commit": "other"}, {"privacy_target_count": 3},
            {"yolo_accelerator": "CPU"},
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaisesRegex(ValueError, "non-comparable"):
                    compare([record("off")], [record("on", **mutation)], scenario="clip")
        with self.assertRaisesRegex(ValueError, "never activated"):
            compare([record("off")], [record("off")], scenario="clip")

    def test_four_k_control_expects_both_paths_off(self):
        base = record("off", source_width=3840, source_height=2160)
        report = compare([base] * 3, [base] * 3, scenario="4k-control", control_noop=True)
        self.assertTrue(report["clarity_control_noop"])


if __name__ == "__main__":
    unittest.main()
