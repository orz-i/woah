import os
import sys

import torch


ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
VENDOR_PATH = os.path.join(ROOT_PATH, "desktop", "vendor")
if ROOT_PATH not in sys.path:
    sys.path.insert(0, ROOT_PATH)
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor
from tools.sam2_onnx.export.export_image_features import Sam2ImageFeaturesExporter


FORBIDDEN_TARGET_FRAGMENTS = (
    "aten.unbind",
    "aten.gather",
    "aten.index",
    "aten.repeat",
    "aten.expand",
    "aten.broadcast_to",
    "aten.sum",
    "aten.amax",
    "aten.stack",
)


def _tensor_shapes(value):
    shape = getattr(value, "shape", None)
    if shape is not None:
        return [tuple(shape)]
    if isinstance(value, (list, tuple)):
        result = []
        for item in value:
            result.extend(_tensor_shapes(item))
        return result
    return []


def _export_image_features_program():
    checkpoint = os.path.join(ROOT_PATH, "models", "pytorch", "sam2_hiera_tiny.pt")
    model = build_sam2_video_predictor(
        "sam2_hiera_t.yaml",
        ckpt_path=checkpoint,
        device="cpu",
    ).eval()

    trunk = model.image_encoder.trunk
    trunk.prepare_litert_export_pos_embed((256, 256))

    wrapper = Sam2ImageFeaturesExporter(model).eval()
    sample_input = torch.randn(1, 3, 1024, 1024, dtype=torch.float32)
    with torch.no_grad():
        return torch.export.export(wrapper, (sample_input,), strict=False)


def test_image_features_exported_program_is_gpu_clean_preflight():
    exported_program = _export_image_features_program()

    forbidden = []
    rank_violations = []
    reshape_without_shape = []

    for node in exported_program.graph_module.graph.nodes:
        if node.op != "call_function":
            continue

        target = str(node.target)
        if any(fragment in target for fragment in FORBIDDEN_TARGET_FRAGMENTS):
            forbidden.append((node.name, target))

        shapes = _tensor_shapes(node.meta.get("val"))
        for shape in shapes:
            if len(shape) > 4:
                rank_violations.append((node.name, target, shape))

        if "reshape" in target and not shapes:
            reshape_without_shape.append((node.name, target))

    assert not forbidden, f"forbidden LiteRT GPU-risk ops: {forbidden[:20]}"
    assert not rank_violations, f"rank > 4 exported intermediates: {rank_violations[:20]}"
    assert not reshape_without_shape, (
        f"reshape nodes without static shape metadata: {reshape_without_shape[:20]}"
    )


if __name__ == "__main__":
    test_image_features_exported_program_is_gpu_clean_preflight()
    print("PASS: SAM2 image_features torch.export GPU-clean preflight")
