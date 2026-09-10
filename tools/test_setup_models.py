"""Filesystem staging tests; tiny TFL3 headers are NOT inference test models."""
import hashlib
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

from tools.setup_models import (
    ASSET_PATH, MODEL_NAMES, YOLO_CONTRACT, model_identity, stage_android_models,
)


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

    def test_yolo_only_copy_is_byte_identical_and_idempotent(self):
        self.assertEqual(MODEL_NAMES, ("yolo11n-seg-fp16.tflite",))
        report = stage_android_models(self.root)
        self.assertEqual(set(report["models"]), set(MODEL_NAMES))
        for name in MODEL_NAMES:
            self.assertEqual((self.source / name).read_bytes(), (self.root / ASSET_PATH / name).read_bytes())
        with patch("tools.setup_models.shutil.copyfile", side_effect=AssertionError("unexpected copy")):
            self.assertEqual(stage_android_models(self.root), report)

    def test_missing_yolo_fails_without_creating_assets(self):
        (self.source / MODEL_NAMES[0]).unlink()
        with self.assertRaisesRegex(ValueError, "Missing or empty required LiteRT model"):
            stage_android_models(self.root)
        self.assertFalse((self.root / ASSET_PATH).exists())

    def test_absent_sam2_and_onnx_are_not_prerequisites(self):
        self.assertEqual({p.name for p in self.source.iterdir()}, {MODEL_NAMES[0]})
        report = stage_android_models(self.root)
        self.assertEqual(set(report["models"]), {MODEL_NAMES[0]})

    def test_unavailable_and_obsolete_source_files_are_not_processed(self):
        unused = (
            "sam2_image_features.tflite", "sam2_init_step.tflite",
            "sam2_temporal_step.tflite", "yolo11n-seg.onnx",
        )
        for name in unused:
            (self.source / name).write_bytes(b"unavailable/obsolete: do not inspect or copy")
        with patch("tools.setup_models.model_identity", wraps=model_identity) as identity:
            stage_android_models(self.root)
        for call in identity.call_args_list:
            self.assertTrue(Path(call.args[0]).name.startswith(MODEL_NAMES[0]))
        self.assertEqual({p.name for p in (self.root / ASSET_PATH).iterdir()}, {MODEL_NAMES[0]})
        for name in unused:
            self.assertEqual((self.source / name).read_bytes(), b"unavailable/obsolete: do not inspect or copy")

    def test_empty_or_onnx_source_is_rejected(self):
        for data in (b"", b"not an onnx model either"):
            with self.subTest(data=data):
                (self.source / MODEL_NAMES[0]).write_bytes(data)
                with self.assertRaises(ValueError):
                    stage_android_models(self.root)
                self.assertFalse((self.root / ASSET_PATH).exists())

    def test_changed_yolo_is_rejected_even_if_old_target_is_valid(self):
        stage_android_models(self.root)
        (self.source / MODEL_NAMES[0]).write_bytes(b"\x08\x00\x00\x00TFL3drift")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            stage_android_models(self.root)

    def test_missing_or_invalid_pin_is_rejected(self):
        for pin in (None, "", "g" * 64, 1234):
            with self.subTest(pin=pin):
                (self.root / YOLO_CONTRACT).write_text(json.dumps({"expected_sha256": pin}), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "must be pinned"):
                    stage_android_models(self.root)

    def test_uppercase_pin_is_normalized(self):
        (self.root / YOLO_CONTRACT).write_text(json.dumps({"expected_sha256": self.sha.upper()}), encoding="utf-8")
        self.assertEqual(stage_android_models(self.root)["models"][MODEL_NAMES[0]]["sha256"], self.sha)

    def test_explicit_source_directory(self):
        source = self.root / "accepted-model-cache"
        self.source.rename(source)
        report = stage_android_models(self.root, source)
        self.assertEqual(Path(report["source"]), source.resolve())

    def test_interrupted_copy_preserves_previous_file_and_cleans_partial(self):
        stage_android_models(self.root)
        name = MODEL_NAMES[0]
        old_bytes = (self.root / ASSET_PATH / name).read_bytes()
        # Test-only pin update simulates replacing one validated model version.
        # No repository model or production pin is changed by this test.
        (self.source / name).write_bytes(b"\x08\x00\x00\x00TFL3changed")
        new_sha = hashlib.sha256((self.source / name).read_bytes()).hexdigest()
        (self.root / YOLO_CONTRACT).write_text(json.dumps({"expected_sha256": new_sha}), encoding="utf-8")
        with patch("tools.setup_models.shutil.copyfile", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                stage_android_models(self.root)
        self.assertEqual((self.root / ASSET_PATH / name).read_bytes(), old_bytes)
        self.assertEqual(list((self.root / ASSET_PATH).glob("*.partial")), [])

    def test_gradle_keeps_supported_model_pin_and_existing_quality_gates(self):
        root = Path(__file__).resolve().parents[1]
        gradle = (root / "mobile/packages/dance_native/android/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('val repoModelsDir = file("../../../../models/litert")', gradle)
        for name in MODEL_NAMES:
            self.assertIn('"models/litert/' + name + '"', gradle)
        synced = gradle.split("val litertFiles = listOf(", 1)[1].split(")", 1)[0]
        required = gradle.split("val requiredModels = listOf(", 1)[1].split(")", 1)[0]
        self.assertEqual(re.findall(r'"([^"]+)"', synced), list(MODEL_NAMES))
        self.assertEqual(re.findall(r'"([^"]+)"', required), ["models/litert/" + MODEL_NAMES[0]])
        self.assertIn('contract["expected_sha256"]', gradle)
        self.assertIn("Unexpected YOLO model hash", gradle)
        for gate in ("verifyLiteRtModelAssets", "verifyFaceDetectorAsset", "verifyNoOnnxRuntime", "verifyNoPlayServicesLiteRt"):
            self.assertIn(gate, gradle.split("tasks.matching", 1)[1])

    def test_ci_keeps_native_tests_apk_build_and_supported_bootstrap(self):
        root = Path(__file__).resolve().parents[1]
        ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("python -m unittest tools.test_setup_models -v", ci)
        self.assertIn("python tools/setup_models.py --android", ci)
        self.assertIn("./gradlew :dance_native:testDebugUnitTest", ci)
        self.assertIn("flutter build apk --debug", ci)
        self.assertNotIn("export_sam2", ci)
        self.assertNotIn("export_yolo.py", ci)
        bootstrap = (root / "tools/setup_models.py").read_text(encoding="utf-8")
        self.assertNotIn("import onnx", bootstrap)
        self.assertNotIn("export_single_model", bootstrap)


if __name__ == "__main__":
    unittest.main()
