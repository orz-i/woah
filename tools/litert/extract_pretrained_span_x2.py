#!/usr/bin/env python3
"""Extract/reparameterize an official SPAN x2 checkpoint into a portable NPZ.

No training is performed. This mirrors upstream Conv3XC.update_params exactly so
all training-time 1x1/3x3/1x1 + skip branches become the single inference 3x3
convolution used by official SPAN eval mode. The PixelShuffle head is preserved
in PyTorch OIHW layout for the exact no-training rewrite implemented by
span_pretrained_x2_migration.py.

Only use checkpoints obtained from the official Apache-2.0 SPAN distribution and
pin their SHA-256 before promotion. PyTorch checkpoint loading can execute pickle
payloads on older versions; this tool requires a modern torch supporting
weights_only=True and should still be used only with trusted official weights.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import numpy as np

CONV3XC_PREFIXES = [
    "conv_1",
    *[
        f"block_{block}.{conv}_r"
        for block in range(1, 7)
        for conv in ("c1", "c2", "c3")
    ],
    "conv_2",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_torch():
    try:
        import torch  # type: ignore
        import torch.nn.functional as F  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit(
            "PyTorch is required only to extract the official checkpoint. Install "
            "tools/litert/span_pretrained_migration_requirements.txt in an isolated environment."
        ) from exc
    return torch, F


def _unwrap_state_dict(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("checkpoint root is not a mapping")
    for key in ("params_ema", "params", "state_dict"):
        candidate = payload.get(key)
        if isinstance(candidate, dict):
            payload = candidate
            break
    if not isinstance(payload, dict):
        raise ValueError("checkpoint does not contain a parameter mapping")
    result: dict[str, Any] = {}
    for raw_key, value in payload.items():
        key = str(raw_key)
        while key.startswith("module."):
            key = key[len("module.") :]
        result[key] = value
    return result


def _required(state: dict[str, Any], key: str):
    if key not in state:
        raise ValueError(f"official SPAN checkpoint is missing required tensor: {key}")
    return state[key]


def fuse_conv3xc(state: dict[str, Any], prefix: str):
    """Mirror upstream Conv3XC.update_params and return fused OIHW weight+bias."""
    _, F = _require_torch()
    w1 = _required(state, f"{prefix}.conv.0.weight").detach().cpu()
    b1 = _required(state, f"{prefix}.conv.0.bias").detach().cpu()
    w2 = _required(state, f"{prefix}.conv.1.weight").detach().cpu()
    b2 = _required(state, f"{prefix}.conv.1.bias").detach().cpu()
    w3 = _required(state, f"{prefix}.conv.2.weight").detach().cpu()
    b3 = _required(state, f"{prefix}.conv.2.bias").detach().cpu()
    sk_w = _required(state, f"{prefix}.sk.weight").detach().cpu()
    sk_b = _required(state, f"{prefix}.sk.bias").detach().cpu()

    w = F.conv2d(
        w1.flip(2, 3).permute(1, 0, 2, 3),
        w2,
        padding=2,
        stride=1,
    ).flip(2, 3).permute(1, 0, 2, 3)
    b = (w2 * b1.reshape(1, -1, 1, 1)).sum((1, 2, 3)) + b2
    fused_w = F.conv2d(
        w.flip(2, 3).permute(1, 0, 2, 3),
        w3,
        padding=0,
        stride=1,
    ).flip(2, 3).permute(1, 0, 2, 3)
    fused_b = (w3 * b.reshape(1, -1, 1, 1)).sum((1, 2, 3)) + b3
    fused_w = fused_w + F.pad(sk_w, [1, 1, 1, 1])
    fused_b = fused_b + sk_b
    return fused_w.numpy().astype(np.float32), fused_b.numpy().astype(np.float32)


def extract_checkpoint(checkpoint: Path, output: Path) -> dict[str, Any]:
    torch, _ = _require_torch()
    try:
        payload = torch.load(str(checkpoint), map_location="cpu", weights_only=True)
    except TypeError as exc:
        raise RuntimeError(
            "This extractor requires a PyTorch version with weights_only=True checkpoint loading."
        ) from exc
    state = _unwrap_state_dict(payload)

    archive: dict[str, np.ndarray] = {}
    for prefix in CONV3XC_PREFIXES:
        weight, bias = fuse_conv3xc(state, prefix)
        target = prefix.replace(".c1_r", ".c1").replace(".c2_r", ".c2").replace(".c3_r", ".c3")
        archive[f"{target}.weight"] = weight
        archive[f"{target}.bias"] = bias

    for source, target in (
        ("conv_cat.weight", "conv_cat.weight"),
        ("conv_cat.bias", "conv_cat.bias"),
        ("upsampler.0.weight", "upsampler.weight"),
        ("upsampler.0.bias", "upsampler.bias"),
    ):
        tensor = _required(state, source).detach().cpu().numpy().astype(np.float32)
        archive[target] = tensor

    feature_channels = int(archive["conv_1.weight"].shape[0])
    input_channels = int(archive["conv_1.weight"].shape[1])
    head_outputs = int(archive["upsampler.weight"].shape[0])
    if input_channels != 3:
        raise ValueError(f"Woah expects RGB SPAN checkpoint, got {input_channels} input channels")
    if head_outputs != 12:
        raise ValueError(
            f"Woah no-training route requires RGB x2 head with 12 pre-shuffle channels, got {head_outputs}"
        )
    if archive["upsampler.weight"].shape[1] != feature_channels:
        raise ValueError("SPAN head feature channel count does not match fused backbone")

    archive["__feature_channels"] = np.asarray([feature_channels], dtype=np.int32)
    archive["__scale"] = np.asarray([2], dtype=np.int32)
    archive["__input_channels"] = np.asarray([3], dtype=np.int32)
    archive["__output_channels"] = np.asarray([3], dtype=np.int32)

    output.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(output, **archive)
    report = {
        "checkpoint": checkpoint.name,
        "checkpoint_sha256": sha256(checkpoint),
        "archive": output.name,
        "feature_channels": feature_channels,
        "scale": 2,
        "tensors": len(archive),
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = extract_checkpoint(args.checkpoint, args.output)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"SPAN_PRETRAINED_EXTRACT=FAIL: {exc}")
        return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SPAN_PRETRAINED_EXTRACT=" + json.dumps(report, sort_keys=True))
    print("SPAN_PRETRAINED_EXTRACT=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
