import os
import sys

import torch


ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
VENDOR_PATH = os.path.join(ROOT_PATH, "desktop", "vendor")
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.modeling.position_encoding import PositionEmbeddingSine


def reference_pe(module: PositionEmbeddingSine, batch: int, device: torch.device, h: int, w: int):
    y_embed = (
        torch.arange(1, h + 1, dtype=torch.float32, device=device)
        .view(1, -1, 1)
        .repeat(batch, 1, w)
    )
    x_embed = (
        torch.arange(1, w + 1, dtype=torch.float32, device=device)
        .view(1, 1, -1)
        .repeat(batch, h, 1)
    )
    eps = 1e-6
    y_embed = y_embed / (y_embed[:, -1:, :] + eps) * module.scale
    x_embed = x_embed / (x_embed[:, :, -1:] + eps) * module.scale

    dim_t = torch.arange(module.num_pos_feats, dtype=torch.float32, device=device)
    dim_t = module.temperature ** (2 * (dim_t // 2) / module.num_pos_feats)
    pos_x = x_embed[:, :, :, None] / dim_t
    pos_y = y_embed[:, :, :, None] / dim_t
    pos_x = torch.stack(
        (pos_x[:, :, :, 0::2].sin(), pos_x[:, :, :, 1::2].cos()), dim=4
    ).flatten(3)
    pos_y = torch.stack(
        (pos_y[:, :, :, 0::2].sin(), pos_y[:, :, :, 1::2].cos()), dim=4
    ).flatten(3)
    return torch.cat((pos_y, pos_x), dim=3).permute(0, 3, 1, 2)


def test_gpu_clean_position_encoding_matches_reference():
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    module = PositionEmbeddingSine(
        num_pos_feats=256,
        normalize=True,
        warmup_cache=False,
    ).to(device)

    for h, w in ((8, 8), (7, 11)):
        actual = module._pe(1, device, h, w)
        expected = reference_pe(module, 1, device, h, w)
        max_diff = (actual - expected).abs().max().item()
        assert max_diff <= 1e-6, f"shape={(h, w)} max_abs_diff={max_diff}"


if __name__ == "__main__":
    test_gpu_clean_position_encoding_matches_reference()
    print("PASS: GPU-clean PositionEmbeddingSine matches rank-5 reference")
