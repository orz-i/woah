"""Verify Stage 3 remains test-only and cannot alter Woah privacy output.

Read-only structural assertions; Android native compilation is separately
requested in CI and mandatory before calling this ABI smoke tested.
"""
from __future__ import annotations

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
TEST = ROOT / (
    "mobile/packages/dance_native/android/src/androidTest/kotlin/"
    "art/gaoge/dance/engine/inference/MagicTouchSyntheticSmokeInstrumentedTest.kt"
)
STAGER = ROOT / "tools/diagnostics/stage_magic_touch_android_test.py"
NATIVE_ROOT = ROOT / "mobile/packages/dance_native/android/src/main/kotlin"
CI = ROOT / ".github/workflows/ci.yml"


class MagicTouchStage3IsolationContractTest(unittest.TestCase):
    def test_smoke_is_in_android_test_and_opt_in(self):
        self.assertTrue(TEST.is_file())
        src = TEST.read_text(encoding="utf-8")
        self.assertIn("src/androidTest/", TEST.as_posix())
        for required in (
            "MagicTouchSyntheticSmokeInstrumentedTest",
            'const val ENABLE_ARG = "woahMagicTouchStage3Enabled"',
            'instrumentation.arguments.getString(ENABLE_ARG) == "1"',
            "assumeTrue(",
            'const val TEST_MODEL_FILE = "woah_magic_touch_stage3.task"',
            "File(context.filesDir, TEST_MODEL_FILE)",
            'const val MODEL_BYTES = 30_525_312L',
            "setModelAssetBuffer(mapped)",
            "setDelegate(Delegate.CPU)",
            "InteractiveSegmenter.createFromOptions",
            "segmenter.setImage(mpImage)",
            "segmenter.segment(foregroundStrokes)",
            "segmenter.segment(alternativeStrokes)",
            "ByteBufferExtractor.extract(image).asFloatBuffer()",
            "JSONObject()",
            '"pixel_carve_authorization", "NEVER"',
            '"privacy_visual_acceptance", "NOT_PROVEN"',
        ):
            self.assertIn(required, src)

    def test_can_never_embed_model_or_call_production_export(self):
        src = TEST.read_text(encoding="utf-8")
        main_code = "\n".join(
            path.read_text(encoding="utf-8") for path in
            NATIVE_ROOT.rglob("*.kt")
        )
        for forbidden in (
            "GlRenderer", "PrivacyOcclusionResolver", "ExportPipeline",
            "AssetManager.open", "BitmapFactory.decode", "FileOutputStream",
            "Context.MODE_WORLD_READABLE", "MediaCodec",
            "writeBytes(", "imwrite", "framesource",
        ):
            self.assertNotIn(forbidden, src)
        self.assertNotIn("MagicTouchSyntheticSmokeInstrumentedTest", main_code)
        self.assertNotIn("woahMagicTouchStage3Enabled", main_code)
        self.assertNotIn("InteractiveSegmenter.createFromOptions", main_code)
        self.assertNotIn("woah_magic_touch_stage3.task", main_code)

    def test_stager_and_instrumentation_share_exact_model_identity(self):
        smoke = TEST.read_text(encoding="utf-8")
        stager = STAGER.read_text(encoding="utf-8")
        self.assertIn('REMOTE_BASENAME = "woah_magic_touch_stage3.task"', stager)
        self.assertIn('const val TEST_MODEL_FILE = "woah_magic_touch_stage3.task"', smoke)
        self.assertIn(
            '"38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03"',
            smoke,
        )
        self.assertIn("check_public_asset", stager)
        self.assertIn("run-as", stager)
        self.assertIn("exec-in", stager)

    def test_ci_compiles_but_does_not_execute_optional_model_smoke(self):
        yaml = CI.read_text(encoding="utf-8")
        self.assertIn(":dance_native:compileDebugAndroidTestKotlin", yaml)
        self.assertIn("Compile Android Instrumentation ABI Smoke", yaml)
        self.assertNotIn(":dance_native:connectedDebugAndroidTest", yaml)
        self.assertNotIn("woahMagicTouchStage3Enabled=1", yaml)

    def test_model_weights_are_not_bundled_in_any_app_assets(self):
        for root in (
            ROOT / "mobile/app",
            ROOT / "mobile/packages/dance_native",
        ):
            for candidate in root.rglob("*"):
                if candidate.is_file() and (
                    candidate.name.startswith("interactive_segmentation.")
                    or candidate.name == "woah_magic_touch_stage3.task"
                    or candidate.name == "magictouch-int8-v1.task"
                ):
                    self.fail(f"Research model was bundled into mobile tree: {candidate}")

    def test_there_is_no_source_media_and_no_image_artifact_write(self):
        src = TEST.read_text(encoding="utf-8")
        self.assertIn("Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)", src)
        self.assertIn("canvas.drawColor(", src)
        self.assertIn("canvas.drawRect(", src)
        self.assertIn("canvas.drawCircle(", src)
        for word in ("ContentResolver", "sourceUri", "FileInputStream(sourceVideo)",
                     "Picture", "MediaStore", ".compress(", "ImageWriter"):
            self.assertNotIn(word, src)


if __name__ == "__main__":
    unittest.main()
