#!/usr/bin/env python3
"""Benchmark a verified crop-clarity x2 model with LiteRT CompiledModel.

This is a host-side direction check, not an Android/iPhone promotion gate. It
measures model-only tile inference and reports how many overlapping 192px tiles
a representative crop would require. Production promotion still requires real
mobile devices and the zero-copy graphics-buffer handoff.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import statistics
import time
from typing import Any

TILE_SIZE = 192
OVERLAP = 12
INPUT_FLOAT_COUNT = TILE_SIZE * TILE_SIZE * 3
OUTPUT_FLOAT_COUNT = (TILE_SIZE * 2) * (TILE_SIZE * 2) * 3


def axis_starts(length: int, tile_size: int = TILE_SIZE, overlap: int = OVERLAP) -> list[int]:
    if length <= 0:
        raise ValueError("length must be positive")
    if tile_size <= 0 or overlap < 0 or overlap * 2 >= tile_size:
        raise ValueError("invalid tile geometry")
    if length <= tile_size:
        return [0]
    stride = tile_size - overlap * 2
    starts: list[int] = []
    start = 0
    while True:
        starts.append(start)
        if start + tile_size >= length:
            break
        next_start = min(start + stride, max(0, length - tile_size))
        if next_start <= start:
            break
        start = next_start
    return starts


def tile_count(width: int, height: int) -> int:
    return len(axis_starts(width)) * len(axis_starts(height))


def _require_runtime() -> tuple[Any, Any, Any]:
    try:
        import numpy as np  # type: ignore
        from ai_edge_litert.compiled_model import CompiledModel, HardwareAccelerator  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit(
            "Install tools/litert/crop_clarity_requirements.txt in an isolated Python 3.12 environment."
        ) from exc
    return np, CompiledModel, HardwareAccelerator


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return ordered[index]


def benchmark(
    model_path: Path,
    *,
    accelerator: str,
    warmup: int,
    runs: int,
    crop_width: int,
    crop_height: int,
) -> dict[str, Any]:
    np, CompiledModel, HardwareAccelerator = _require_runtime()
    hardware = {
        "cpu": HardwareAccelerator.CPU,
        "gpu": HardwareAccelerator.GPU,
    }[accelerator]

    started = time.perf_counter()
    model = CompiledModel.from_file(str(model_path), hardware)
    initialization_ms = (time.perf_counter() - started) * 1000.0
    inputs = model.create_input_buffers(0)
    outputs = model.create_output_buffers(0)
    if len(inputs) != 1 or len(outputs) != 1:
        raise RuntimeError(f"expected one input/output buffer, got {len(inputs)}/{len(outputs)}")

    # A deterministic gradient catches ordering/finite-value issues while making
    # the benchmark independent from user media.
    input_data = np.linspace(0.0, 1.0, INPUT_FLOAT_COUNT, dtype=np.float32).reshape(
        (1, TILE_SIZE, TILE_SIZE, 3)
    )
    inputs[0].write(input_data)

    for _ in range(max(0, warmup)):
        model.run_by_index(0, inputs, outputs)

    timings: list[float] = []
    for _ in range(max(1, runs)):
        started = time.perf_counter()
        model.run_by_index(0, inputs, outputs)
        timings.append((time.perf_counter() - started) * 1000.0)

    output = outputs[0].read(OUTPUT_FLOAT_COUNT, np.float32)
    if output.size != OUTPUT_FLOAT_COUNT or not bool(np.isfinite(output).all()):
        raise RuntimeError("model output contract/finite check failed")

    count = tile_count(crop_width, crop_height)
    median_ms = statistics.median(timings)
    return {
        "accelerator": accelerator,
        "initialization_ms": initialization_ms,
        "tile_ms": {
            "min": min(timings),
            "median": median_ms,
            "mean": statistics.fmean(timings),
            "p95": percentile(timings, 0.95),
            "runs": len(timings),
        },
        "representative_crop": {
            "width": crop_width,
            "height": crop_height,
            "tiles": count,
            "serial_model_only_ms_at_median": count * median_ms,
        },
        "output": {
            "finite": True,
            "min": float(np.min(output)),
            "max": float(np.max(output)),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--accelerator", choices=("cpu", "gpu"), default="gpu")
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--crop-width", type=int, default=608)
    parser.add_argument("--crop-height", type=int, default=1080)
    args = parser.parse_args()

    report = benchmark(
        args.model,
        accelerator=args.accelerator,
        warmup=args.warmup,
        runs=args.runs,
        crop_width=args.crop_width,
        crop_height=args.crop_height,
    )
    print("CROP_CLARITY_BENCHMARK=" + json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
