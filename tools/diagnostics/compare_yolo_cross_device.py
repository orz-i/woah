#!/usr/bin/env python3
"""Compare debug YUV and sparse YOLO tensor artifacts from two diagnostic bundles."""

from __future__ import annotations

import json
import math
import re
import struct
import sys
import zipfile
from pathlib import Path


YUV_RE = re.compile(r"decoder_yuv_.*?_\d+_(\d+)_64x64_32x32\.yuvs$")
TENSOR_RE = re.compile(r"yolo_tensor_.*?_(\d+)_(input|output0|output1)\.f32s$")


def quantile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(q * len(ordered)) - 1))
    return ordered[index]


def stats(values: list[float]) -> dict[str, float]:
    return {
        "mean": sum(values) / len(values) if values else 0.0,
        "p50": quantile(values, 0.50),
        "p95": quantile(values, 0.95),
        "p99": quantile(values, 0.99),
        "max": max(values) if values else 0.0,
    }


def read_bundle(path: Path):
    z = zipfile.ZipFile(path)
    current_job_id = None
    if "manifest.json" in z.namelist():
        manifest = json.loads(z.read("manifest.json"))
        current_job_id = manifest.get("pipeline_lifecycle_job_id")
    yuv: dict[int, bytes] = {}
    tensors: dict[tuple[str, int, str], bytes] = {}
    for name in z.namelist():
        if current_job_id and current_job_id not in name:
            continue
        m = YUV_RE.search(name)
        if m:
            yuv[int(m.group(1))] = z.read(name)
            continue
        m = TENSOR_RE.search(name)
        if m:
            backend = (
                "cpu_mt4_probe"
                if current_job_id and f"{current_job_id}_cpu_mt4_probe_" in name
                else "cpu_probe"
                if current_job_id and f"{current_job_id}_cpu_probe_" in name
                else "gpu"
            )
            tensors[(backend, int(m.group(1)), m.group(2))] = z.read(name)
    return z, current_job_id, yuv, tensors


def compare_bytes(a: bytes, b: bytes) -> dict[str, object]:
    n = min(len(a), len(b))
    deltas = [abs(a[i] - b[i]) for i in range(n)]
    return {
        "count": n,
        "different_ratio": sum(d != 0 for d in deltas) / n if n else 0.0,
        "abs_delta": stats([float(d) for d in deltas]),
    }


def unpack_floats(raw: bytes) -> tuple[float, ...]:
    usable = len(raw) - (len(raw) % 4)
    return struct.unpack("<" + "f" * (usable // 4), raw[:usable])


def compare_floats(a_raw: bytes, b_raw: bytes) -> dict[str, object]:
    a = unpack_floats(a_raw)
    b = unpack_floats(b_raw)
    n = min(len(a), len(b))
    deltas = [abs(a[i] - b[i]) for i in range(n)]
    return {
        "sample_count": n,
        "different_ratio": sum(d != 0.0 for d in deltas) / n if n else 0.0,
        "abs_delta": stats(deltas),
    }


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: compare_yolo_cross_device.py <bundle_a.zip> <bundle_b.zip>", file=sys.stderr)
        return 2

    a_path, b_path = Path(sys.argv[1]), Path(sys.argv[2])
    za, a_job_id, a_yuv, a_tensors = read_bundle(a_path)
    zb, b_job_id, b_yuv, b_tensors = read_bundle(b_path)
    try:
        yuv_pts = sorted(set(a_yuv) & set(b_yuv))
        tensor_keys = sorted(set(a_tensors) & set(b_tensors))
        result = {
            "bundle_a": str(a_path),
            "bundle_b": str(b_path),
            "job_id_a": a_job_id,
            "job_id_b": b_job_id,
            "yuv": [
                {"pts_us": pts, **compare_bytes(a_yuv[pts], b_yuv[pts])}
                for pts in yuv_pts
            ],
            "tensors": [
                {
                    "backend": backend,
                    "pts_us": pts,
                    "tensor": tensor,
                    **compare_floats(
                        a_tensors[(backend, pts, tensor)],
                        b_tensors[(backend, pts, tensor)],
                    ),
                }
                for backend, pts, tensor in tensor_keys
            ],
        }
        print(json.dumps(result, indent=2))
        return 0
    finally:
        za.close()
        zb.close()


if __name__ == "__main__":
    raise SystemExit(main())
