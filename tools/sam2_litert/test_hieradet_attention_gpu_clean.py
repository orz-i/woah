import os
import sys

import torch
import torch.nn.functional as F


ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
VENDOR_PATH = os.path.join(ROOT_PATH, "desktop", "vendor")
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.modeling.backbones.hieradet import Hiera, MultiScaleAttention, do_pool
from sam2.modeling.backbones.utils import window_partition, window_unpartition


def reference_forward(module: MultiScaleAttention, x: torch.Tensor) -> torch.Tensor:
    """Original rank-5 qkv/unbind implementation used as a numerical oracle."""
    b, h, w, _ = x.shape
    qkv = module.qkv(x).reshape(b, h * w, 3, module.num_heads, -1)
    q, k, v = torch.unbind(qkv, dim=2)

    if module.q_pool:
        q = do_pool(q.reshape(b, h, w, -1), module.q_pool)
        q = q.reshape(b, q.shape[1] * q.shape[2], module.num_heads, -1)

    q_h = h // 2 if module.q_pool else h
    q_w = w // 2 if module.q_pool else w
    q = q.transpose(1, 2)
    k = k.transpose(1, 2)
    v = v.transpose(1, 2)
    out = F.scaled_dot_product_attention(q, k, v)
    out = out.transpose(1, 2).reshape(b, q_h, q_w, -1)
    return module.proj(out)


def _run_case(q_pool: bool) -> None:
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    pool = torch.nn.MaxPool2d(kernel_size=(2, 2), stride=(2, 2)) if q_pool else None
    module = MultiScaleAttention(
        dim=32,
        dim_out=64,
        num_heads=4,
        q_pool=pool,
    ).to(device).eval()
    x = torch.randn(1, 8, 8, 32, device=device)

    with torch.no_grad():
        expected = reference_forward(module, x)
        actual = module(x)

    max_diff = (actual - expected).abs().max().item()
    assert max_diff <= 1e-5, f"q_pool={q_pool} max_abs_diff={max_diff}"


def test_gpu_clean_attention_matches_rank5_reference():
    _run_case(q_pool=False)
    _run_case(q_pool=True)


def test_litert_export_pos_embed_cache_is_exact():
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = Hiera(
        embed_dim=8,
        num_heads=1,
        q_pool=0,
        stages=(1, 1, 1, 1),
        window_pos_embed_bkg_spatial_size=(2, 2),
        window_spec=(2, 2, 2, 2),
        global_att_blocks=(),
        return_interm_layers=True,
    ).to(device).eval()
    with torch.no_grad():
        model.pos_embed.normal_()
        model.pos_embed_window.normal_()
        expected = model._compute_pos_embed((8, 8)).clone()
        cached = model.prepare_litert_export_pos_embed((8, 8))
        actual = model._get_pos_embed((8, 8))

    assert torch.equal(cached, expected)
    assert torch.equal(actual, expected)


def reference_window_partition(x: torch.Tensor, window_size: int):
    b, h, w, c = x.shape
    pad_h = (window_size - h % window_size) % window_size
    pad_w = (window_size - w % window_size) % window_size
    if pad_h > 0 or pad_w > 0:
        x = F.pad(x, (0, 0, 0, pad_w, 0, pad_h))
    hp, wp = h + pad_h, w + pad_w
    x = x.view(b, hp // window_size, window_size, wp // window_size, window_size, c)
    windows = x.permute(0, 1, 3, 2, 4, 5).reshape(-1, window_size, window_size, c)
    return windows, (hp, wp)


def reference_window_unpartition(windows, window_size, pad_hw, hw):
    hp, wp = pad_hw
    h, w = hw
    b = windows.shape[0] // (hp * wp // window_size // window_size)
    x = windows.reshape(
        b, hp // window_size, wp // window_size, window_size, window_size, -1
    )
    x = x.permute(0, 1, 3, 2, 4, 5).reshape(b, hp, wp, -1)
    if hp > h or wp > w:
        x = x[:, :h, :w, :]
    return x


def test_rank4_window_partition_matches_rank6_reference():
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    for h, w, window_size in ((16, 16, 8), (15, 17, 8), (14, 14, 7)):
        x = torch.randn(2, h, w, 12, device=device)
        expected_windows, expected_pad = reference_window_partition(x, window_size)
        actual_windows, actual_pad = window_partition(x, window_size)
        assert actual_pad == expected_pad
        assert torch.equal(actual_windows, expected_windows)

        expected_roundtrip = reference_window_unpartition(
            expected_windows, window_size, expected_pad, (h, w)
        )
        actual_roundtrip = window_unpartition(
            actual_windows, window_size, actual_pad, (h, w)
        )
        assert torch.equal(actual_roundtrip, expected_roundtrip)
        assert torch.equal(actual_roundtrip, x)


if __name__ == "__main__":
    test_gpu_clean_attention_matches_rank5_reference()
    test_litert_export_pos_embed_cache_is_exact()
    test_rank4_window_partition_matches_rank6_reference()
    print("PASS: GPU-clean MultiScaleAttention matches rank-5 reference")
