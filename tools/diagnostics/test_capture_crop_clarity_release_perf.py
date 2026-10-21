"""Host-only Release telemetry watch: no emulator, user media, APK, or ADB required."""
from __future__ import annotations

from io import StringIO
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import capture_crop_clarity_release_perf as capture
from tools.diagnostics.crop_clarity_release_perf import (
    compare, read_records,
)
from tools.diagnostics.test_crop_clarity_release_perf import record as fixture


TOKENS = "f" * 24
COMMIT = "a" * 40


def event(mode: str = "off", *, elapsed: float = 1000.0, **changes) -> str:
    row = fixture(mode, elapsed=elapsed, **changes)
    row["git_commit"] = changes.get("git_commit", COMMIT)
    return "I/WoahExportPerf(4343): " + json.dumps(row) + "\n"


def marker(token: str = TOKENS) -> str:
    return "I/WoahPerfMarker(900): " + capture.MARKER_PREFIX + token + "\n"


class CaptureCropClarityReleasePerfTest(unittest.TestCase):
    def test_marker_ignores_preexisting_unrelated_records_without_logcat_clear(self):
        session = capture.CaptureSession("off", TOKENS, repeats=3)
        self.assertEqual(session.ingest(event("off", elapsed=955)), "ignored")
        self.assertEqual(session.ingest(marker("e" * 24)), "ignored")
        self.assertEqual(session.ingest(marker()), "armed")
        for time_ms in (1000, 1050, 1100):
            self.assertEqual(session.ingest(event("off", elapsed=time_ms)), "sample")
        self.assertTrue(session.complete)
        self.assertEqual(len(session.records), 3)
        self.assertNotIn("WoahPerfMarker", session.scalar_log())
        self.assertNotIn("payload://", session.scalar_log())

    def test_recorded_log_is_consumable_by_existing_release_pair_comparator(self):
        off = capture.CaptureSession("off", TOKENS)
        on = capture.CaptureSession("on", "e" * 24)
        self.assertEqual(off.ingest(marker()), "armed")
        self.assertEqual(on.ingest(marker("e" * 24)), "armed")
        for i in range(3):
            self.assertEqual(off.ingest(event("off", elapsed=1000 + i * 10)), "sample")
            self.assertEqual(on.ingest(event("on", elapsed=1050 + i * 10)), "sample")
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            f_off, f_on = root / "off.log", root / "on.log"
            f_off.write_text(off.scalar_log(), encoding="utf-8")
            f_on.write_text(on.scalar_log(), encoding="utf-8")
            summary = compare(
                read_records(f_off), read_records(f_on),
                scenario="operator-asserted-same-source",
            )
            self.assertEqual(summary["wall_elapsed_slowdown_percent"], 4.95)
            self.assertEqual(summary["release_acceptance"], "REQUIRES_DEVICE_REVIEW")
            self.assertEqual(summary["throughput_regression_review"],
                             "WITHIN_PROVISIONAL_BUDGET")

    def test_wrong_variant_and_invalid_release_data_never_count(self):
        session = capture.CaptureSession("off", TOKENS)
        session.ingest(marker())
        for text in (
            event("on"),
            event("off", build_mode="debug"),
            event("off", render_cpu_dispatch_count=299),
            event("off", throughput_fps=999999.0),
        ):
            with self.subTest(record=text[:80]), self.assertRaises(ValueError):
                session.ingest(text)
        self.assertEqual(session.records, [])

    def test_unknown_fields_are_rejected_before_any_output(self):
        session = capture.CaptureSession("on", TOKENS)
        session.ingest(marker())
        for extras in (
            {"source_file": "private_user_video.mp4"},
            {"image_data": "iVBORw0"},
            {"raw_mask": [0, 2, 3]},
            {"session_secret": "credential"},
        ):
            with self.subTest(extra=extras), self.assertRaisesRegex(
                ValueError, "private Release payload fields"
            ):
                session.ingest(event("on", **extras))
        self.assertEqual(session.records, [])

    def test_same_mode_runs_must_match_source_critical_fields(self):
        for modification in (
            {"source_width": 1920}, {"target_height": 1080},
            {"trim_end_ms": 12_000}, {"git_commit": "b" * 40},
            {"privacy_target_count": 3}, {"clarity_scale": 1.7},
            {"rendered_frames": 301, "decoded_frames": 301,
             "encoded_frames": 301, "render_cpu_dispatch_count": 301,
             "throughput_fps": 301.0},
        ):
            with self.subTest(modification=modification):
                session = capture.CaptureSession("on", TOKENS)
                session.ingest(marker())
                session.ingest(event("on", elapsed=1000))
                with self.assertRaises(ValueError):
                    session.ingest(event("on", elapsed=1050, **modification))
                self.assertEqual(len(session.records), 1)

    def test_replayed_identical_record_cannot_count_as_another_export(self):
        session = capture.CaptureSession("off", TOKENS)
        session.ingest(marker())
        raw = event("off", elapsed=1000)
        session.ingest(raw)
        with self.assertRaisesRegex(ValueError, "duplicate identical"):
            session.ingest(raw)
        self.assertFalse(session.complete)

    def test_expected_build_sha_is_enforced_even_if_release_fields_match(self):
        session = capture.CaptureSession("off", TOKENS, expected_commit="b" * 40)
        session.ingest(marker())
        with self.assertRaisesRegex(ValueError, "requested Git commit"):
            session.ingest(event("off"))
        self.assertEqual(session.records, [])

    def test_bad_arguments_and_unfinished_capture_are_rejected(self):
        for invalid in ("OFF", "debug", ""):
            with self.assertRaises(ValueError):
                capture.CaptureSession(invalid, TOKENS)
        for invalid in ("INVALID", "a" * 10, "g" * 24):
            with self.assertRaises(ValueError):
                capture.CaptureSession("off", invalid)
        with self.assertRaises(ValueError):
            capture.CaptureSession("off", TOKENS, repeats=1)
        with self.assertRaises(ValueError):
            capture.CaptureSession("off", TOKENS, expected_commit="12345")
        with self.assertRaises(ValueError):
            capture.CaptureSession("off", TOKENS).scalar_log()
        with self.assertRaises(ValueError):
            capture.adb_prefix("adb", "serial; rm -rf /")

    def test_refuses_output_inside_git_or_overwriting_existing_log(self):
        root = capture.ROOT
        with self.assertRaisesRegex(ValueError, "outside Git"):
            capture.ensure_output_outside_checkout(root / "private-release.log")
        with tempfile.TemporaryDirectory() as dirname:
            file = Path(dirname) / "release_off.log"
            capture.ensure_output_outside_checkout(file)
            file.write_text("existing")
            with self.assertRaisesRegex(ValueError, "exists"):
                capture.ensure_output_outside_checkout(file)
            file.unlink()
            file.with_name(file.name + ".partial").write_text("partial")
            with self.assertRaisesRegex(ValueError, "exists"):
                capture.ensure_output_outside_checkout(file)

    def test_streamed_marker_arms_then_captures_only_current_tagged_records(self):
        # Simulates the entire safe live host flow, including ADB marker,
        # event stream, output atomic rename and subprocess cleanup.
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release_off.log"
            class FakeProcess:
                def __init__(self, command):
                    self.command = command
                    self.stdout = StringIO(
                        event("off", elapsed=840) + marker() +
                        event("off", elapsed=1000) +
                        event("off", elapsed=1010) +
                        event("off", elapsed=1020)
                    )
                    self.terminated = False
                def poll(self):
                    return 0 if self.terminated else None
                def terminate(self):
                    self.terminated = True
                def wait(self, timeout=5):
                    return 0
                def kill(self):
                    self.terminated = True

            fake_processes = []
            marker_commands = []
            def fake_popen(command, **_):
                process = FakeProcess(command)
                fake_processes.append(process)
                return process
            def fake_run(command, **_):
                marker_commands.append(command)
                return subprocess.CompletedProcess(command, 0, "", "")
            with patch.object(capture.subprocess, "Popen", side_effect=fake_popen), \
                 patch.object(capture.subprocess, "run", side_effect=fake_run), \
                 patch.object(capture.secrets, "token_hex", return_value=TOKENS), \
                 patch.object(capture.time, "sleep", return_value=None):
                capture.capture_device(
                    output, mode="off", repeats=3, timeout_minutes=5,
                    adb="adb", serial=None, expected_commit=COMMIT,
                )
            self.assertEqual(len(fake_processes), 1)
            self.assertTrue(fake_processes[0].terminated)
            self.assertIn("WoahExportPerf:I", fake_processes[0].command)
            self.assertIn("WoahPerfMarker:I", fake_processes[0].command)
            self.assertEqual(marker_commands[0][-6:], [
                "log", "-p", "i", "-t", "WoahPerfMarker",
                capture.MARKER_PREFIX + TOKENS,
            ])
            self.assertNotIn("logcat -c", str(fake_processes[0].command))
            self.assertEqual(len(read_records(output)), 3)
            self.assertNotIn('"elapsed_ms":840', output.read_text())

    def test_live_capture_refuses_wrong_variant_and_never_writes_report(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release_off.log"
            class WrongProcess:
                def __init__(self, _):
                    self.stdout = StringIO(marker() + event("on"))
                    self.terminated = False
                def poll(self):
                    return 0 if self.terminated else None
                def terminate(self):
                    self.terminated = True
                def wait(self, timeout=5):
                    return 0
                def kill(self):
                    self.terminated = True
            process = WrongProcess(None)
            def fake_run(command, **_):
                return subprocess.CompletedProcess(command, 0, "", "")
            with patch.object(capture.subprocess, "Popen", return_value=process), \
                 patch.object(capture.subprocess, "run", side_effect=fake_run), \
                 patch.object(capture.secrets, "token_hex", return_value=TOKENS), \
                 patch.object(capture.time, "sleep", return_value=None):
                with self.assertRaisesRegex(ValueError, "wrong Release clarity variant"):
                    capture.capture_device(
                        output, mode="off", repeats=3, timeout_minutes=5,
                        adb="adb", serial=None, expected_commit=COMMIT,
                    )
            self.assertTrue(process.terminated)
            self.assertFalse(output.exists())

    def test_live_capture_fails_if_marker_cannot_be_sent(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release_on.log"
            class FakeProcess:
                def __init__(self):
                    self.stdout = StringIO("")
                    self.terminated = False
                def poll(self):
                    return 0 if self.terminated else None
                def terminate(self):
                    self.terminated = True
                def wait(self, timeout=5):
                    return 0
                def kill(self):
                    self.terminated = True
            proc = FakeProcess()
            with patch.object(capture.subprocess, "Popen", return_value=proc), \
                 patch.object(capture.subprocess, "run", return_value=(
                     subprocess.CompletedProcess([], 1, "", "no marker utility")
                 )), patch.object(capture.time, "sleep", return_value=None):
                with self.assertRaisesRegex(RuntimeError, "cannot emit Release capture marker"):
                    capture.capture_device(
                        output, mode="on", repeats=3, timeout_minutes=5,
                        adb="adb", serial=None, expected_commit=None,
                    )
            self.assertTrue(proc.terminated)
            self.assertFalse(output.exists())

    def test_live_capture_refuses_missing_marker_even_with_three_old_logs(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release_on.log"
            class FakeProcess:
                def __init__(self):
                    self.stdout = StringIO(
                        event("on", elapsed=1000) +
                        event("on", elapsed=1010) +
                        event("on", elapsed=1020)
                    )
                    self.terminated = False
                def poll(self):
                    return 0 if self.terminated else None
                def terminate(self):
                    self.terminated = True
                def wait(self, timeout=5):
                    return 0
                def kill(self):
                    self.terminated = True
            proc = FakeProcess()
            with patch.object(capture.subprocess, "Popen", return_value=proc), \
                 patch.object(capture.subprocess, "run", return_value=(
                     subprocess.CompletedProcess([], 0, "", "")
                 )), patch.object(capture.time, "sleep", return_value=None):
                with self.assertRaisesRegex(RuntimeError, "logcat closed"):
                    capture.capture_device(
                        output, mode="on", repeats=3, timeout_minutes=5,
                        adb="adb", serial=None, expected_commit=COMMIT,
                    )
            self.assertTrue(proc.terminated)
            self.assertFalse(output.exists())

    def test_native_export_log_has_exact_reviewed_scalar_field_allowlist(self):
        # If native release logging evolves, explicitly review any new value
        # before the host tool is permitted to serialize it to a report.
        native = (capture.ROOT / "mobile/packages/dance_native/android/src/main/kotlin"
                  "/art/gaoge/dance/engine/pipeline/ExportPipeline.kt").read_text()
        start = native.index("val payload = org.json.JSONObject().apply {")
        end = native.index('android.util.Log.i("WoahExportPerf", payload.toString())', start)
        import re
        keys = set(re.findall(r'put\("(\w+)"', native[start:end]))
        self.assertEqual(keys, capture.ALLOWED_FIELDS)

    def test_no_audio_video_source_or_network_calls_in_collector(self):
        source = Path(capture.__file__).read_text(encoding="utf-8")
        for forbidden in (
            "import cv2", "import numpy", "from PIL", "import torch",
            "import requests", "urllib.request", "MediaCodec",
            "GlRenderer", "ffmpeg", "logcat -c", "adb install", ".mp4",
        ):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
