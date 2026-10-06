import json
from pathlib import Path
import unittest

import numpy as np

from tools.litert import benchmark_crop_clarity_model as benchmark_tool
from tools.litert import crop_clarity_span_x2 as model_tool
from tools.litert import extract_crop_clarity_frames as frame_tool
from tools.litert import extract_pretrained_span_x2 as pretrained_extractor
from tools.litert import span_pretrained_x2_migration as pretrained_migration
from tools.litert import verify_crop_clarity_model as verifier


ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "models/litert/crop-clarity-span-x2.spec.json"
PRETRAINED_SPEC = ROOT / "models/litert/crop-clarity-span-pretrained-x2.spec.json"


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
        self.assertEqual(self.spec["status"], "fallback_training_candidate_not_promoted")
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("models/litert/*", gitignore)
        self.assertNotIn("!models/litert/crop-clarity-span-x2.tflite", gitignore)

    def test_pretrained_route_is_no_training_and_transpose_conv_allowlisted(self):
        spec = json.loads(PRETRAINED_SPEC.read_text(encoding="utf-8"))
        self.assertEqual(spec["status"], "no_training_reference_candidate_performance_risk")
        self.assertFalse(spec["migration"]["training_required"])
        self.assertIn("TRANSPOSE_CONV", verifier.GPU_OP_ALLOWLIST)
        self.assertEqual(verifier.GPU_OP_VERSION_MAX["TRANSPOSE_CONV"], 1)
        self.assertEqual(spec["migration"]["forbidden_runtime_op"], "DEPTH_TO_SPACE")
        self.assertGreater(spec["host_proof"]["mac_per_192_tile"], 10_000_000_000)
        self.assertEqual(spec["host_proof"]["representative_608x1080_tiles"], 28)

    def test_pretrained_checkpoint_extractor_covers_all_inference_conv3xc_modules(self):
        self.assertEqual(len(pretrained_extractor.CONV3XC_PREFIXES), 20)
        self.assertEqual(pretrained_extractor.CONV3XC_PREFIXES[0], "conv_1")
        self.assertEqual(pretrained_extractor.CONV3XC_PREFIXES[-1], "conv_2")
        self.assertIn("block_6.c3_r", pretrained_extractor.CONV3XC_PREFIXES)

    def test_pretrained_pixelshuffle_head_rewrite_is_numerically_exact(self):
        rng = np.random.default_rng(42)
        source = pretrained_migration.PixelShuffleHead(
            weight=rng.normal(0, 0.1, (12, 4, 3, 3)).astype(np.float32),
            bias=rng.normal(0, 0.1, (12,)).astype(np.float32),
        )
        feature = rng.normal(0, 1, (1, 5, 7, 4)).astype(np.float32)
        expected = pretrained_migration.pytorch_pixelshuffle_reference(feature, source)
        converted = pretrained_migration.convert_pixelshuffle_head(source)
        actual = pretrained_migration.transpose_head_reference_numpy(feature, converted)
        delta = np.abs(expected - actual)
        self.assertLess(float(delta.max()), 1e-4)
        self.assertEqual(converted.main_kernel.shape, (6, 6, 3, 4))
        self.assertEqual(converted.phase_bias_kernel.shape, (2, 2, 3, 1))

    def test_benchmark_tile_count_matches_android_planner_examples(self):
        self.assertEqual(benchmark_tool.tile_count(608, 1080), 28)
        self.assertEqual(benchmark_tool.tile_count(405, 720), 15)
        self.assertEqual(benchmark_tool.axis_starts(120), [0])

    def test_video_frame_sampling_is_temporally_distributed(self):
        self.assertEqual(
            frame_tool.sample_frame_indices(
                frame_count=300,
                source_fps=30.0,
                sample_fps=1.0,
                max_frames=None,
            ),
            list(range(0, 300, 30)),
        )
        limited = frame_tool.sample_frame_indices(
            frame_count=300,
            source_fps=30.0,
            sample_fps=5.0,
            max_frames=4,
        )
        self.assertEqual(len(limited), 4)
        self.assertEqual(limited[0], 0)
        self.assertGreater(limited[-1], 250)
        self.assertEqual(limited, sorted(set(limited)))

    def test_invalid_video_metadata_has_safe_sampling_fallback(self):
        self.assertEqual(
            frame_tool.sample_frame_indices(
                frame_count=91,
                source_fps=0.0,
                sample_fps=1.0,
                max_frames=None,
            ),
            [0, 30, 60, 90],
        )
        self.assertEqual(
            frame_tool.sample_frame_indices(
                frame_count=0,
                source_fps=30.0,
                sample_fps=1.0,
                max_frames=None,
            ),
            [],
        )


if __name__ == "__main__":
    unittest.main()
