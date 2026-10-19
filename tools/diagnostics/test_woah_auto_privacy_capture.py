"""Offline collector contract: no full source video, no remote upload, exact PTS."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import woah_auto_privacy_capture as capture


COMMIT = "2cb113ea12ed200c60dcf50177061fe204cec155"


def numeric_fixture() -> dict:
    risk = [
        (39733333, [63.081055, 70.04883, 73.637695, 97.60742]),
        (39766666, [62.890625, 70.078125, 73.828125, 97.890625]),
        (39800000, [62.924805, 70.17578, 73.950195, 98.10547]),
        (39833333, [62.939453, 70.17578, 73.7793, 98.10547]),
        (39866666, [62.993164, 70.06836, 74.11621, 98.05664]),
        (39900000, [62.86621, 70.20508, 74.086914, 98.07617]),
    ]
    return {
        "build_commit": COMMIT,
        "focus_frames": [
            {"pts_us": pts, "protected_mask_proto_bbox": box}
            for pts, box in risk
        ],
    }


def meta(fps: float = 30., duration: float = 46.366667) -> dict:
    count = int(round(duration * fps)) + 1
    return {
        "fps": fps,
        "duration_s": (count - 1) / fps,
        "pts": [index / fps for index in range(count)],
        "num_frames": count,
        "dimensions": (1280, 720),
    }


class WoahAutomaticPrivacyCollectorTest(unittest.TestCase):
    def test_actual_six_risk_pts_map_to_model_source_coordinates(self):
        with tempfile.TemporaryDirectory() as directory:
            sample = Path(directory) / "evidence.json"
            sample.write_text(json.dumps(numeric_fixture()), encoding="utf-8")
            loaded = capture.read_numeric_report(sample)
            self.assertEqual(loaded["numeric_report_commit"], COMMIT)
            self.assertEqual(len(loaded["focus"]), 6)
            first = capture.bbox_to_source(
                loaded["focus"][0]["selected_bbox_proto"]
            )
            for measured, expected in zip(
                first, [504.648, 280.391, 589.102, 500.859]
            ):
                self.assertAlmostEqual(measured, expected, delta=0.02)
            self.assertEqual(
                capture.choose_roi(loaded["focus"]),
                (412, 205, 361, 455),
            )

    def test_selects_all_six_risk_frames_and_adjacent_context_at_30fps(self):
        focus = [
            {"pts_us": item["pts_us"], "selected_bbox_proto": item["protected_mask_proto_bbox"]}
            for item in numeric_fixture()["focus_frames"]
        ]
        samples = capture.select_indices(meta(), focus, offset=0)
        self.assertEqual(samples, list(range(1189, 1200)))
        self.assertTrue(all(index in samples for index in range(1192, 1198)))
        self.assertEqual(
            capture.make_filter([1192, 1193], (412, 205, 361, 455)),
            r"select=eq(n\,1192)+eq(n\,1193),crop=361:455:412:205",
        )
        high_fps = meta(60.)
        indexes = capture.select_indices(high_fps, focus, offset=0)
        self.assertLessEqual(len(indexes), capture.MAX_TOTAL_CAPTURE_FRAMES)
        for item in focus:
            nearest, _, _ = capture.nearest_frame(
                high_fps["pts"], item["pts_us"] / 1e6, 60.
            )
            self.assertIn(nearest, indexes)

    def test_frame_mismatch_or_timing_mismatch_fails_without_guessing(self):
        with self.assertRaisesRegex(ValueError, "No source frame"):
            capture.nearest_frame([0., 1., 2.], .5, 30.)
        focus = [
            {"pts_us": item["pts_us"], "selected_bbox_proto": item["protected_mask_proto_bbox"]}
            for item in numeric_fixture()["focus_frames"]
        ]
        with self.assertRaisesRegex(ValueError, "does not extend"):
            capture.select_indices(meta(duration=35.), focus, offset=0.)
        with self.assertRaisesRegex(ValueError, "nonnegative"):
            capture.select_indices(meta(), focus, offset=-5.)
        with self.assertRaisesRegex(ValueError, "sorted"):
            capture.make_filter([1193, 1192], None)

    def test_rejects_malformed_numeric_report_and_unverified_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "numeric.json"
            sample = numeric_fixture()
            sample["focus_frames"][0]["protected_mask_proto_bbox"][1] = 175.
            path.write_text(json.dumps(sample), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "bbox"):
                capture.read_numeric_report(path)
            path.write_text(json.dumps(numeric_fixture()), encoding="utf-8")
            loaded = capture.read_numeric_report(path)
            self.assertEqual(len(loaded["numeric_report_sha256"]), 64)
            video = Path(directory) / "source.mp4"
            video.write_bytes(b"synthetic placeholder")
            with self.assertRaisesRegex(ValueError, "different files"):
                capture.prepare(
                    video, video, None, path, Path(directory) / "new.zip",
                    0., "ffmpeg", "ffprobe",
                )
            with self.assertRaisesRegex(ValueError, "Output ZIP exists"):
                output = Path(directory) / "exists.zip"
                output.write_bytes(b"not zipped")
                capture.prepare(video, None, None, path, output, 0., "ffmpeg", "ffprobe")

    def test_ffmpeg_uses_dense_output_pts_without_legacy_vsync(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / 'fixture.mp4'
            video.write_bytes(b'placeholder video')
            commands = []
            indices = [1189, 1192, 1197]

            def simulate_ffmpeg(command):
                commands.append(command)
                # Reproduce a Windows FFmpeg build without -vsync support:
                # if the old option is present, fail rather than writing PNGs.
                if '-vsync' in command or '-fps_mode' in command:
                    raise ValueError("Unrecognized option 'vsync'")
                pattern = command[-1]
                for number in range(len(indices)):
                    Path(pattern.replace('%03d', f'{number:03d}')).write_bytes(
                        b'\x89PNG\r\n\x1a\n' + b'test frame payload' * 5
                    )
                return ''

            with patch.object(capture, 'run', side_effect=simulate_ffmpeg):
                paths = capture.ffmpeg_extract(
                    video, indices, root / 'review', 'ffmpeg.EXE',
                    (412, 205, 361, 455),
                )
            self.assertEqual(len(paths), len(indices))
            cmd = commands[0]
            self.assertNotIn('-vsync', cmd)
            self.assertNotIn('-fps_mode', cmd)
            self.assertEqual(cmd[cmd.index('-r') + 1], '1')
            self.assertEqual(
                cmd[cmd.index('-vf') + 1],
                capture.make_filter(indices, (412, 205, 361, 455)) + ',setpts=N/TB'
            )
            self.assertTrue(all(p.stat().st_size >= 60 for p in paths))

    def test_ffmpeg_refuses_misaligned_frame_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / 'fixture.mp4'
            video.write_bytes(b'placeholder video')
            def emit_two_frames(command):
                for number in range(2):
                    Path(command[-1].replace('%03d', f'{number:03d}')).write_bytes(
                        b'\x89PNG\r\n\x1a\n' + b'payload' * 15
                    )
                return ''
            with patch.object(capture, 'run', side_effect=emit_two_frames):
                with self.assertRaisesRegex(ValueError, 'extracted 2 frames, expected 3'):
                    capture.ffmpeg_extract(
                        video, [1189, 1192, 1197], root / 'review',
                        'ffmpeg.EXE', None,
                    )

    def test_generated_review_html_references_only_captured_relative_images(self):
        fragment = capture.generate_index({
            "frames": [{
                "target_pts_us": 39733333,
                "images": {
                    "source": {
                        "file": "source/frame_003.png", "frame_index": 1192,
                        "source_timeline_s": 39.733333,
                    }
                },
            }]
        })
        self.assertIn('src="source/frame_003.png"', fragment)
        self.assertIn('39.733333', fragment)
        self.assertIn('未自动判断隐私通过', fragment)
        self.assertNotIn('<video', fragment)

    def test_automatic_collector_does_not_upload_or_include_original_video(self):
        script = Path(capture.__file__).read_text(encoding="utf-8")
        self.assertIn("'full_original_video_included':False", script)
        self.assertIn("'audio_included':False", script)
        self.assertIn("'raw_mask_included':False", script)
        self.assertIn("'privacy_acceptance':'NOT_REVIEWED'", script)
        self.assertIn("with tempfile.TemporaryDirectory(", script)
        self.assertIn("ffmpeg_extract(file,indices", script)
        self.assertNotIn("requests.post", script)
        self.assertNotIn("urllib.request", script)
        self.assertNotIn("multipart/form-data", script)


if __name__ == "__main__":
    unittest.main()
