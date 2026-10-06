import json
from pathlib import Path
import unittest

from tools.litert import benchmark_crop_clarity_model as benchmark_tool
from tools.litert import crop_clarity_span_x2 as model_tool
from tools.litert import verify_crop_clarity_model as verifier


ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "models/litert/crop-clarity-span-x2.spec.json"


class CropClarityModelToolContractTest(unittest.TestCase):
    def setUp(self):
        self.spec = json.loads(SPEC.read_text(encoding="utf-8"))

    def test_static_spec_matches_authoring_constants(self):
        self.assertEqual(self.spec["scale"], model_tool.UPSCALE)
        self.assertEqual(self.spec["tile_size"], model_tool.TILE_SIZE)
        self.assertEqual(self.spec["input"]["shape"], [1, model_tool.TILE_SIZE, model_tool.TILE_SIZE, 3])
        self.assertEqual(
            self.spec["output"]["shape"],
            [1, model_tool.OUTPUT_SIZE, model_tool.OUTPUT_SIZE, 3],
        )
        self.assertEqual(self.spec["architecture"]["feature_channels"], model_tool.FEATURE_CHANNELS)
        self.assertEqual(self.spec["architecture"]["attention_blocks"], model_tool.BLOCK_COUNT)

    def test_verifier_contract_matches_spec(self):
        self.assertEqual(verifier.EXPECTED_INPUT_SHAPE, self.spec["input"]["shape"])
        self.assertEqual(verifier.EXPECTED_OUTPUT_SHAPE, self.spec["output"]["shape"])
        self.assertEqual(verifier.GPU_OP_ALLOWLIST, set(self.spec["allowed_litert_gpu_ops"]))
        self.assertEqual(verifier.GPU_OP_VERSION_MAX, self.spec["litert_gpu_max_op_versions"])
        self.assertEqual(verifier.FORBIDDEN_OPS, set(self.spec["forbidden_ops"]))

    def test_pixelshuffle_is_explicitly_prohibited(self):
        self.assertIn("DEPTH_TO_SPACE", verifier.FORBIDDEN_OPS)
        self.assertNotIn("DEPTH_TO_SPACE", verifier.GPU_OP_ALLOWLIST)
        self.assertEqual(self.spec["architecture"]["upsample_head"], "RESIZE_BILINEAR + CONV_2D")

    def test_prototype_is_not_promoted_or_required(self):
        self.assertEqual(self.spec["status"], "prototype_not_promoted")
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("models/litert/*", gitignore)
        self.assertNotIn("!models/litert/crop-clarity-span-x2.tflite", gitignore)

    def test_benchmark_tile_count_matches_android_planner_examples(self):
        self.assertEqual(benchmark_tool.tile_count(608, 1080), 28)
        self.assertEqual(benchmark_tool.tile_count(405, 720), 15)
        self.assertEqual(benchmark_tool.axis_starts(120), [0])


if __name__ == "__main__":
    unittest.main()
