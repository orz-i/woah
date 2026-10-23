"""M0 fixture/probe contract tests that require no Android SDK or FFmpeg."""
from __future__ import annotations

import json
import tempfile
import unittest
import wave
from pathlib import Path

from evaluate_m0_report import evaluate
from generate_m0_fixtures import generate_music, write_audio, SAMPLE_RATE


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

    def test_wrong_offset_cannot_pass_fixture(self):
        with self.assertRaises(ValueError):
            evaluate({
                "schema": "woah.comparison.m0.probe.v1",
                "evidenceType": "pre_render_planned_release_metadata_only",
                "offsetUs": -3000000,
            }, expected_offset_us=3000000)


if __name__ == "__main__":
    unittest.main()
