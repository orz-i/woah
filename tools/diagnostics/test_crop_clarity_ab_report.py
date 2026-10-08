"""Contract tests for protected, same-geometry crop clarity A/B reports."""
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools.diagnostics import crop_clarity_ab_report as qa


class CropClarityAbReportTest(unittest.TestCase):
    def setUp(self):
        self.sample = {
            "frame": 325,
            "pts_us": 10_800_000,
            "baseline": "frame_000325_off.png",
            "enhanced": "frame_000325_on.png",
            "crop": [0.42, 0.0, 0.73, 1.0],
            "same_decoded_frame": True,
            "same_privacy_state": True,
            "same_crop_matrix": True,
        }
        self.manifest = {
            "schema": 1,
            "job_id": "fixture",
            "capture_mode": "same_frame_same_crop_two_pass",
            "privacy_composited": True,
            "source_material_included": False,
            "output_width": 8,
            "output_height": 12,
            "strength_off": 0.0,
            "strength_on": 0.55,
            "expected_frames": [325],
            "samples": [self.sample],
        }

    def test_rejects_unprotected_and_wrong_crop(self):
        self.manifest["privacy_composited"] = False
        with self.assertRaisesRegex(ValueError, "protected"):
            qa.validate_manifest(self.manifest)
        self.manifest["privacy_composited"] = True
        self.sample["crop"] = [0.75, 0.0, 0.50, 1.0]
        with self.assertRaisesRegex(ValueError, "crop"):
            qa.validate_manifest(self.manifest)

    def test_rejects_traversal_duplicate_or_different_frame(self):
        self.sample["baseline"] = "../raw.png"
        with self.assertRaisesRegex(ValueError, "unsafe"):
            qa.validate_manifest(self.manifest)
        self.sample["baseline"] = "frame_000325_off.png"
        self.manifest["samples"] = [self.sample, dict(self.sample)]
        with self.assertRaisesRegex(ValueError, "increasing"):
            qa.validate_manifest(self.manifest)

    def test_review_generates_artifacts_without_claiming_quality_pass(self):
        try:
            import numpy as np
            from PIL import Image
        except ImportError:
            self.skipTest("numpy/pillow not installed in plain system Python; CI uses uv run")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive_path = root / "ab.zip"
            pixels = np.zeros((12, 8, 3), dtype=np.uint8)
            pixels[:, 4:] = 120
            on = pixels.copy()
            on[:, 3] = 30
            def png(image):
                output = BytesIO()
                Image.fromarray(image, "RGB").save(output, format="PNG")
                return output.getvalue()
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("manifest.json", json.dumps(self.manifest))
                archive.writestr(self.sample["baseline"], png(pixels))
                archive.writestr(self.sample["enhanced"], png(on))
            result = qa.review_bundle(archive_path, root / "report")
            self.assertEqual(result["quality_acceptance"], "MANUAL_REVIEW_REQUIRED")
            self.assertGreater(result["image_pairs"][0]["pixels_changed_gt2_percent"], 0)
            self.assertTrue((root / "report" / "crop_clarity_ab_contact_sheet.png").exists())
            self.assertTrue((root / "report" / "crop_clarity_ab_report.json").exists())
            self.assertEqual(result["missing_expected_frames"], [])

    def test_phase2_scene_selection_and_five_consecutive_frames(self):
        try:
            import numpy as np
            from PIL import Image
        except ImportError:
            self.skipTest("numpy/pillow not installed in plain system Python; CI uses uv run")
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            manifest = dict(self.manifest)
            manifest.update({
                "schema": 2, "capture_mode": "same_frame_same_crop_two_pass_scene_driven",
                "selection_policy": "online_scene_heuristics_v2",
            })
            samples = []
            for offset in range(5):
                frame = 201 + offset
                item = dict(self.sample)
                item.update({
                    "frame": frame, "pts_us": offset * 33_333,
                    "baseline": f"frame_{frame:06d}_off.png",
                    "enhanced": f"frame_{frame:06d}_on.png",
                    "scene_kind": "temporal_burst", "burst_index": offset,
                    "luma_mean": 100.0, "contrast": 150,
                    "protected_overlap": 0.2, "protagonist_motion": 0.03,
                    "protected_count": 1,
                })
                samples.append(item)
            manifest["samples"] = samples
            manifest["expected_frames"] = [item["frame"] for item in samples]
            bundle = directory_path / "phase2.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
                for index, item in enumerate(samples):
                    off = np.zeros((12, 8, 3), dtype=np.uint8)
                    off[:, 4:] = 100
                    on = off.copy()
                    on[:, 3] = 20 + index
                    for field, image in (("baseline", off), ("enhanced", on)):
                        output = BytesIO()
                        Image.fromarray(image, "RGB").save(output, format="PNG")
                        archive.writestr(item[field], output.getvalue())
            result = qa.review_bundle(bundle, directory_path / "report")
            self.assertEqual(result["schema"], 2)
            self.assertEqual(result["scene_counts"]["temporal_burst"], 5)
            self.assertTrue(result["scene_coverage"]["complete_five_frame_burst"])
            self.assertEqual(result["temporal_runs"], [[201, 202, 203, 204, 205]])
            self.assertEqual(len(result["adjacent_frame_proxy"]), 4)
            self.assertEqual(result["quality_acceptance"], "MANUAL_REVIEW_REQUIRED")

    def test_phase2_rejects_bad_scene_evidence(self):
        self.manifest.update({
            "schema": 2, "capture_mode": "same_frame_same_crop_two_pass_scene_driven",
            "selection_policy": "online_scene_heuristics_v2",
        })
        with self.assertRaisesRegex(ValueError, "scene kind"):
            qa.validate_manifest(self.manifest)
        self.sample.update({
            "scene_kind": "temporal_burst", "burst_index": 0,
            "luma_mean": 95.0, "contrast": 132,
            "protected_overlap": 0.1, "protagonist_motion": 0.02,
            "protected_count": 1,
        })
        self.assertEqual(len(qa.validate_manifest(self.manifest)), 1)
        self.sample["burst_index"] = 15
        with self.assertRaisesRegex(ValueError, "burst index"):
            qa.validate_manifest(self.manifest)

    def test_adjacent_midframe_proxy_is_explicitly_non_motion_compensated(self):
        try:
            import numpy as np
            from PIL import Image
        except ImportError:
            self.skipTest("numpy/pillow not installed in plain system Python; CI uses uv run")
        next_sample = dict(self.sample)
        next_sample.update({
            "frame": 326, "pts_us": 10_833_333,
            "baseline": "frame_000326_off.png", "enhanced": "frame_000326_on.png",
        })
        self.manifest["samples"].append(next_sample)
        self.manifest["expected_frames"] = [325, 326, 327]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive_path = root / "ab.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("manifest.json", json.dumps(self.manifest))
                for sample in self.manifest["samples"]:
                    base = np.zeros((12, 8, 3), dtype=np.uint8)
                    base[:, 4:] = 120
                    enhanced = base.copy()
                    enhanced[:, 3] = 20 if sample["frame"] == 325 else 35
                    for key, arr in (("baseline", base), ("enhanced", enhanced)):
                        stream = BytesIO()
                        Image.fromarray(arr, "RGB").save(stream, format="PNG")
                        archive.writestr(sample[key], stream.getvalue())
            result = qa.review_bundle(archive_path, root / "report")
            self.assertEqual(len(result["adjacent_frame_proxy"]), 1)
            self.assertEqual(result["missing_expected_frames"], [327])
            self.assertEqual(result["temporal_flicker_acceptance"], "NOT_PROVEN_BY_UNCOMPENSATED_PROXY")


if __name__ == "__main__":
    unittest.main()
