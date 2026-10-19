"""Privacy export-frame review must not mistake logs or sparse stills for proof."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import privacy_final_frame_review as review


def fake_meta(width: int = 1280, height: int = 720) -> dict:
    return {
        "width": width, "height": height, "fps": 30.0,
        "frame_count": 1391,
        "relative_pts_s": [i / 30 for i in range(1391)],
        "original_first_pts_s": 0.0,
    }


class PrivacyFinalFrameReviewTest(unittest.TestCase):
    def test_exact_risk_frames_and_neighboring_frames_are_selected(self):
        pts = fake_meta()["relative_pts_s"]
        early = review.window_frames(pts, review.WINDOWS[0])
        late = review.window_frames(pts, review.WINDOWS[1])
        self.assertIn(1192, early)    # 39.733333s
        self.assertIn(1379, late)     # 45.966667s
        self.assertIn(1387, late)     # 46.233333s
        self.assertIn(1390, late)     # 46.333333s / last frame
        self.assertLess(early[0], 1192)
        self.assertGreater(early[-1], 1192)
        self.assertEqual(late[-1], 1390)
        self.assertEqual((len(early), len(late)), (9, 16))
        self.assertEqual(early, list(range(early[0], early[-1] + 1)))

    def test_ffmpeg_select_is_index_exact_and_has_no_shell_quoting(self):
        self.assertEqual(
            review.make_select_filter([1191, 1192, 1390]),
            r"select=eq(n\,1191)+eq(n\,1192)+eq(n\,1390)",
        )
        with self.assertRaisesRegex(ValueError, "sorted"):
            review.make_select_filter([1192, 1191])
        with self.assertRaisesRegex(ValueError, "No selected"):
            review.make_select_filter([])

    def test_rejects_incompatible_video_from_another_job(self):
        review.validate_export(fake_meta(), "landscape")
        review.validate_export(fake_meta(810, 1440), "portrait")
        with self.assertRaisesRegex(ValueError, "expected"):
            review.validate_export(fake_meta(1920, 1080), "landscape")
        modified = fake_meta()
        modified["frame_count"] = 1390
        with self.assertRaisesRegex(ValueError, "1391"):
            review.validate_export(modified, "landscape")
        modified = fake_meta()
        modified["fps"] = 25.0
        with self.assertRaisesRegex(ValueError, "frame rate"):
            review.validate_export(modified, "landscape")
        modified = fake_meta()
        modified["relative_pts_s"] = [i / 29 for i in range(1391)]
        with self.assertRaisesRegex(ValueError, "final frame"):
            review.validate_export(modified, "landscape")

    def test_probe_normalizes_real_video_pts_instead_of_guessing_frame_offsets(self):
        payload = {
            "streams": [{"width": 1280, "height": 720, "avg_frame_rate": "30/1"}],
            "frames": [
                {"best_effort_timestamp_time": "7.000000"},
                {"best_effort_timestamp_time": "7.033333"},
                {"best_effort_timestamp_time": "7.066667"},
            ],
        }
        with patch.object(review, "run_command", return_value=json.dumps(payload)):
            meta = review.probe_video(Path("fixture.mp4"), "ffprobe")
        self.assertAlmostEqual(meta["relative_pts_s"][1], 1 / 30, places=5)
        self.assertAlmostEqual(meta["relative_pts_s"][2], 2 / 30, places=5)
        self.assertEqual(meta["original_first_pts_s"], 7.0)

    def test_ffmpeg_extraction_rejects_a_missing_output_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "fixture.mp4"
            video.write_bytes(b"fixture")
            captured = []

            def simulated_ffmpeg(command: list[str]) -> str:
                captured.append(command)
                target = Path(command[-1].replace("%04d", "0000"))
                target.write_bytes(b"PNG fixture")
                return ""

            with patch.object(review, "run_command", side_effect=simulated_ffmpeg):
                with self.assertRaisesRegex(ValueError, "Decoded 1"):
                    review.extract_window(
                        video, ffmpeg="ffmpeg",
                        destination=root / "output", indices=[1189, 1190]
                    )
            self.assertIn("select=eq(n\\,1189)+eq(n\\,1190)", captured[0])

    def test_local_review_remains_manual_and_keeps_exact_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = {
                "landscape": root / "full-landscape.mp4",
                "portrait": root / "full-portrait.mp4",
            }
            for source in inputs.values():
                source.write_bytes(b"fake-video-fixture")

            def fake_probe(video: Path, tool: str) -> dict:
                mode = "landscape" if "landscape" in video.name else "portrait"
                return fake_meta(*review.EXPECTED_DIMENSIONS[mode])

            def fake_extract(video: Path, *, ffmpeg: str, destination: Path,
                             indices: list[int]) -> list[Path]:
                destination.mkdir(parents=True, exist_ok=False)
                frames = []
                for ordinal, _ in enumerate(indices):
                    image = destination / f"frame_{ordinal:04d}.png"
                    image.write_bytes(b"PNG fixture")
                    frames.append(image)
                return frames

            out = root / "private-review"
            with patch.object(review, "probe_video", side_effect=fake_probe), \
                 patch.object(review, "extract_window", side_effect=fake_extract), \
                 patch.object(review.shutil, "which", return_value="/usr/bin/ffmpeg"):
                result = review.prepare_review(
                    inputs, out, ffmpeg="ffmpeg", ffprobe="ffprobe"
                )
            self.assertEqual(result["mode_coverage"], "BOTH")
            self.assertEqual(result["visual_acceptance"], "NOT_REVIEWED")
            self.assertEqual(set(result["videos"]), {"landscape", "portrait"})
            self.assertTrue((out / "index.html").exists())
            checklist = (out / "review-checklist.md").read_text(encoding="utf-8")
            self.assertIn("待核验", checklist)
            self.assertIn("39.733", checklist)
            self.assertIn("46.333", checklist)
            manifest = json.loads((out / "manifest.json").read_text())
            self.assertEqual(manifest["visual_acceptance"], "NOT_REVIEWED")
            self.assertEqual(
                manifest["videos"]["portrait"]["frame_count"], review.EXPECTED_FRAMES
            )
            self.assertTrue(
                any(frame["index"] == 1192
                    for frame in manifest["videos"]["landscape"]["windows"][0]["frames"])
            )
            self.assertNotIn(str(root), (out / "manifest.json").read_text())
            with self.assertRaisesRegex(ValueError, "new or empty"):
                review.validate_inputs(inputs, out)

    def test_missing_ffmpeg_never_claims_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / "out.mp4"
            file.write_bytes(b"not-a-real-video")
            with patch.object(review.shutil, "which", return_value=None):
                with self.assertRaisesRegex(ValueError, "required on PATH"):
                    review.prepare_review(
                        {"landscape": file}, root / "review",
                        ffmpeg="ffmpeg", ffprobe="ffprobe",
                    )
            self.assertFalse((root / "review").exists())

    def test_prevents_accidentally_committing_sensitive_review_pngs(self):
        from pathlib import Path
        repo_root = Path(review.__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "full-export.mp4"
            source.write_bytes(b"video")
            with self.assertRaisesRegex(ValueError, "outside the Git repository"):
                review.validate_inputs(
                    {"landscape": source},
                    repo_root / "private-review-pngs",
                )

    def test_same_file_cannot_pretend_to_be_both_modes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "same.mp4"
            video.write_bytes(b"video")
            with self.assertRaisesRegex(ValueError, "different exported"):
                review.validate_inputs(
                    {"landscape": video, "portrait": video},
                    root / "review",
                )


if __name__ == "__main__":
    unittest.main()
