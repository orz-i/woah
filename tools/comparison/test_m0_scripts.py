"""M0 fixture/probe contract tests that require no Android SDK or FFmpeg."""
from __future__ import annotations

import json
import tempfile
import unittest
from unittest.mock import patch
import wave
from pathlib import Path

from evaluate_m0_report import evaluate
from analyze_m0_screenrecord import summarize, decode_strip
from evaluate_m0_frame_coverage import frame_intervals, evaluate_coverage
from generate_m0_fixtures import generate_music, write_audio, SAMPLE_RATE, main
from m0_pixels import render_frame, timecode, FRAME_CODE


class M0ScriptsTest(unittest.TestCase):
    def test_music_is_deterministic(self):
        music = generate_music()
        self.assertEqual(len(music), SAMPLE_RATE * 10 * 2)
        self.assertEqual(music, generate_music())
        self.assertNotEqual(len(set(music)), 1)

    def test_music_offsets_preserve_identical_payload(self):
        music = generate_music()
        with tempfile.TemporaryDirectory() as temp:
            a = Path(temp, "teacher.wav")
            b = Path(temp, "learner.wav")
            write_audio(a, 2000, music)
            write_audio(b, 3125, music)
            with wave.open(str(a)) as wa, wave.open(str(b)) as wb:
                teacher = wa.readframes(wa.getnframes())
                learner = wb.readframes(wb.getnframes())
                self.assertEqual(wa.getframerate(), 16000)
                self.assertEqual(wb.getframerate(), 16000)
            self.assertEqual(teacher[2000 * SAMPLE_RATE * 2 // 1000:],
                             learner[3125 * SAMPLE_RATE * 2 // 1000:])
            self.assertEqual(set(learner[:3125 * SAMPLE_RATE * 2 // 1000]), {0})

    def test_font_free_frame_code_survives_every_supported_size(self):
        for width, height in ((640, 360), (1280, 720), (1920, 1080)):
            frame = render_frame("LEARNER", "533a64", width, height,
                                 901, 30, 3_000_000)
            self.assertEqual(len(frame), width * height * 3)
            factor = width // 640
            decoded = 0
            for bit in range(FRAME_CODE["bits"]):
                x = (24 + bit * 26 + 13) * factor
                y = 264 * factor
                decoded = decoded * 2 + (frame[(y * width + x) * 3] > 128)
            self.assertEqual(decoded, 901)
        self.assertEqual(timecode(1_000_000 // 30), "+000.033333S")
        self.assertEqual(timecode(-2_000_000), "-002.000000S")

    def test_rendered_frames_are_deterministic_and_change_with_source_pts(self):
        args = ("TEACHER", "183c69", 640, 360)
        first = render_frame(*args, 0, 24, 2_000_000)
        self.assertEqual(first, render_frame(*args, 0, 24, 2_000_000))
        self.assertNotEqual(first, render_frame(*args, 1, 24, 2_000_000))
        self.assertNotEqual(first, render_frame(*args, 0, 24, 1_000_000))

    def test_failed_encoding_never_publishes_a_partial_pair(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp, "generated")
            out.mkdir()
            for name in ("teacher.mp4", "learner.mp4", "manifest.json"):
                (out / name).write_bytes(b"previous complete set")
            with patch("generate_m0_fixtures.render_video",
                       side_effect=RuntimeError("native encoder failed")):
                with self.assertRaisesRegex(RuntimeError, "native encoder failed"):
                    main(["--out", str(out)])
            self.assertEqual(sorted(p.name for p in Path(temp).iterdir()),
                             ["generated"])
            for file in out.iterdir():
                self.assertEqual(file.read_bytes(), b"previous complete set")

    def test_planned_frame_result_never_claims_physical_display_proof(self):
        report = {
            "schema": "woah.comparison.m0.probe.v1",
            "evidenceType": "pre_render_planned_release_metadata_only",
            "matchedPlannedReleaseFrames": 80,
            "offsetUs": 3000000,
            "p95AbsMediaErrorUs": 10000,
            "maxAbsMediaErrorUs": 30000,
        }
        result = evaluate(report, expected_offset_us=3000000, fps=30)
        self.assertEqual(result["status"], "PLANNED_METADATA_WITHIN_TARGET")
        self.assertEqual(result["actualDisplayAcceptance"], "NOT_PROVEN")
        report["matchedPlannedReleaseFrames"] = 4
        self.assertEqual(evaluate(report)["status"],
                         "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA")

    def test_full_frame_microsecond_rounding_and_pair_coverage(self):
        report = {"schema": "woah.comparison.m0.probe.v1",
                  "evidenceType": "pre_render_planned_release_metadata_only",
                  "matchedPlannedReleaseFrames": 90,
                  "usableTeacherFrames": 100,
                  "p95AbsMediaErrorUs": 33334, "maxAbsMediaErrorUs": 66667}
        self.assertEqual(evaluate(report)["status"], "PLANNED_METADATA_WITHIN_TARGET")
        report["usableTeacherFrames"] = 300
        self.assertEqual(evaluate(report)["status"],
                         "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA")
        report["usableTeacherFrames"] = 100
        report["sourceError"] = "decoder failed"
        self.assertEqual(evaluate(report)["status"],
                         "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA")

    def test_screen_capture_detects_visible_two_frame_skew(self):
        rows = [{"captureSeconds": i / 30, "teacherFrame": i,
                 "learnerFrame": i + 92} for i in range(90)]
        result = summarize(rows, 30, 3_000_000)
        self.assertEqual(result["status"], "SCREEN_CAPTURE_OUTSIDE_TARGET")
        self.assertEqual(result["p95AbsMediaErrorUs"], 66667)
        self.assertEqual(result["actualDisplayAcceptance"], "NOT_PROVEN_PHYSICAL_PANEL")
        for row in rows:
            row["learnerFrame"] -= 1
        self.assertEqual(summarize(rows, 30, 3_000_000)["status"],
                         "SCREEN_CAPTURE_WITHIN_TARGET")

    def test_joint_gl_metadata_requires_single_surface_and_covered_pts(self):
        report = {"schema": "woah.comparison.m0.gl.v1",
                  "evidenceType": "joint_gl_submission_source_pts_only",
                  "jointSubmissions": 100, "p95AbsMediaErrorUs": 0,
                  "maxAbsMediaErrorUs": 0, "audioPlayerCount": 1,
                  "videoWindowSurfaces": 1, "coverageMisses": 0}
        result = evaluate(report)
        self.assertEqual(result["status"], "JOINT_GL_METADATA_WITHIN_TARGET")
        self.assertEqual(result["actualDisplayAcceptance"], "NOT_PROVEN")
        report["coverageMisses"] = 1
        self.assertEqual(evaluate(report)["status"],
                         "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA")
        report["coverageMisses"] = 0
        report["videoWindowSurfaces"] = 2
        self.assertEqual(evaluate(report)["status"],
                         "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA")

    def test_mixed_cadences_use_actual_frame_intersections(self):
        teacher = frame_intervals([round(i*1e6/24) for i in range(120)],5_000_000,24)
        learner = frame_intervals([round(i*1e6/30) for i in range(240)],8_000_000,30)
        rows = [{"teacherFrame": int((i/60+.01)*24),
                 "learnerFrame": int((i/60+.01+3)*30)} for i in range(180)]
        self.assertEqual(evaluate_coverage(rows,teacher,learner,3_000_000)["status"],
                         "FRAME_COVERAGE_WITHIN_TARGET")
        for row in rows: row["learnerFrame"] += 4
        self.assertEqual(evaluate_coverage(rows,teacher,learner,3_000_000)["status"],
                         "FRAME_COVERAGE_OUTSIDE_TARGET")

    def test_sparse_vfr_retains_frame_until_actual_next_pts(self):
        teacher = frame_intervals([0,66667,100000],133333,30)
        learner = frame_intervals([3_000_000,3_033_333,3_066_667],3_100_000,30)
        rows = [{"teacherFrame": 0,"learnerFrame": 91}]*60
        result = evaluate_coverage(rows,teacher,learner,3_000_000)
        self.assertEqual(result["maxIntervalGapUs"],0)
        self.assertEqual(result["p95AbsFrameStartDeltaUs"],33333)
        self.assertEqual(result["status"],"FRAME_COVERAGE_WITHIN_TARGET")
        with self.assertRaises(ValueError):
            frame_intervals([0,45000],100000,30)

    def test_empty_screen_capture_has_no_fabricated_metrics(self):
        result = summarize([], 30, 3_000_000)
        self.assertEqual(result["status"], "INSUFFICIENT_SCREEN_CAPTURE")
        self.assertEqual(result["schema"], "woah.comparison.m0.screen.v1")
        self.assertIsNone(result["p95AbsMediaErrorUs"])
        self.assertIsNone(result["maxAbsMediaErrorUs"])
        self.assertEqual(result["actualDisplayAcceptance"], "NOT_PROVEN_PHYSICAL_PANEL")

    def test_screen_binary_decode_rejects_ambiguous_cells(self):
        raw = bytearray(1040 * 4)
        for side, code in enumerate((123, 213)):
            for bit in range(20):
                for y in range(4):
                    for x in range(26):
                        raw[y * 1040 + side * 520 + bit * 26 + x] = (
                            255 if code & (1 << (19 - bit)) else 0)
        self.assertEqual(decode_strip(raw), (123, 213))
        raw[:] = bytes([128]) * len(raw)
        self.assertIsNone(decode_strip(raw))

    def test_wrong_offset_cannot_pass_fixture(self):
        with self.assertRaises(ValueError):
            evaluate({
                "schema": "woah.comparison.m0.probe.v1",
                "evidenceType": "pre_render_planned_release_metadata_only",
                "offsetUs": -3000000,
            }, expected_offset_us=3000000)


if __name__ == "__main__":
    unittest.main()
