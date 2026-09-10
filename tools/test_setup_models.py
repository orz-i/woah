"""Filesystem staging tests; tiny TFL3 headers are NOT inference test models."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.setup_models import ASSET_PATH, MODEL_NAMES, YOLO_CONTRACT, stage_android_models


class AndroidModelStagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "models/litert"
        self.source.mkdir(parents=True)
        for name in MODEL_NAMES:
            (self.source / name).write_bytes(b"\x08\x00\x00\x00TFL3" + name.encode())
        contract = self.root / YOLO_CONTRACT
        contract.parent.mkdir(parents=True)
        self.sha = hashlib.sha256((self.source / MODEL_NAMES[0]).read_bytes()).hexdigest()
        contract.write_text(json.dumps({"expected_sha256": self.sha}), encoding="utf-8")

    def test_all_four_copies_are_byte_identical_and_idempotent(self):
        report = stage_android_models(self.root)
        self.assertEqual(set(report["models"]), set(MODEL_NAMES))
        for name in MODEL_NAMES:
            self.assertEqual((self.source / name).read_bytes(), (self.root / ASSET_PATH / name).read_bytes())
        with patch("tools.setup_models.shutil.copyfile", side_effect=AssertionError("unexpected copy")):
            self.assertEqual(stage_android_models(self.root), report)

    def test_missing_sam2_lists_every_missing_file_without_staging_yolo(self):
        for name in MODEL_NAMES[1:]:
            (self.source / name).unlink()
        with self.assertRaises(ValueError) as error:
            stage_android_models(self.root)
        for name in MODEL_NAMES[1:]:
            self.assertIn(name, str(error.exception))
        self.assertFalse((self.root / ASSET_PATH).exists())

    def test_empty_or_onnx_source_is_rejected(self):
        for data in (b"", b"not an onnx model either"):
            with self.subTest(data=data):
                (self.source / MODEL_NAMES[2]).write_bytes(data)
                with self.assertRaises(ValueError):
                    stage_android_models(self.root)
                self.assertFalse((self.root / ASSET_PATH).exists())

    def test_changed_yolo_is_rejected_even_if_old_target_is_valid(self):
        stage_android_models(self.root)
        (self.source / MODEL_NAMES[0]).write_bytes(b"\x08\x00\x00\x00TFL3drift")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            stage_android_models(self.root)

    def test_missing_pin_is_rejected(self):
        (self.root / YOLO_CONTRACT).write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "must be pinned"):
            stage_android_models(self.root)

    def test_explicit_source_directory(self):
        source = self.root / "accepted-model-cache"
        self.source.rename(source)
        report = stage_android_models(self.root, source)
        self.assertEqual(Path(report["source"]), source.resolve())

    def test_interrupted_copy_preserves_previous_file_and_cleans_partial(self):
        stage_android_models(self.root)
        name = MODEL_NAMES[1]
        old_bytes = (self.root / ASSET_PATH / name).read_bytes()
        (self.source / name).write_bytes(b"\x08\x00\x00\x00TFL3changed")
        with patch("tools.setup_models.shutil.copyfile", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                stage_android_models(self.root)
        self.assertEqual((self.root / ASSET_PATH / name).read_bytes(), old_bytes)
        self.assertEqual(list((self.root / ASSET_PATH).glob("*.partial")), [])

    def test_gradle_keeps_all_four_assets_and_uses_plugin_relative_root(self):
        root = Path(__file__).resolve().parents[1]
        gradle = (root / "mobile/packages/dance_native/android/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('val repoModelsDir = file("../../../../models/litert")', gradle)
        for name in MODEL_NAMES:
            self.assertIn('"models/litert/' + name + '"', gradle)
        self.assertIn("verifyLiteRtModelAssets,", gradle)


if __name__ == "__main__":
    unittest.main()
