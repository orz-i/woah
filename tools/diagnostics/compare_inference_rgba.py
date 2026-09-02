#!/usr/bin/env python3
"""Compare lossless 640x640 RGBA inference captures from two Woah diagnostic bundles."""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import zipfile
from dataclasses import dataclass
from pathlib import Path


CAPTURE_RE = re.compile(
    r"(?:^|/)inference_rgba_.+?_(?P<ordinal>\d+)_(?P<pts>-?\d+)_(?P<width>\d+)x(?P<height>\d+)\.rgba$"
)


@dataclass(frozen=True)
class Capture:
    name: str
    ordinal: int
    pts_us: int
    width: int
    height: int
    data: bytes


def load_captures(bundle: Path) -> dict[int, Capture]:
    captures: dict[int, Capture] = {}
    with zipfile.ZipFile(bundle) as zf:
        for name in zf.namelist():
            match = CAPTURE_RE.search(name)
            if not match:
                continue
            width = int(match.group("width"))
            height = int(match.group("height"))
            data = zf.read(name)
            expected = width * height * 4
            if len(data) != expected:
                raise ValueError(f"{bundle}: {name} has {len(data)} bytes, expected {expected}")
            capture = Capture(
                name=name,
                ordinal=int(match.group("ordinal")),
                pts_us=int(match.group("pts")),
                width=width,
                height=height,
                data=data,
            )
            captures[capture.pts_us] = capture
    return captures


def percentile(values: list[int], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    pos = (len(ordered) - 1) * p
    lower = math.floor(pos)
    upper = math.ceil(pos)
    if lower == upper:
        return float(ordered[lower])
    frac = pos - lower
    return ordered[lower] * (1.0 - frac) + ordered[upper] * frac


def summarize(values: list[int]) -> dict[str, float | int]:
    return {
        "mean": statistics.fmean(values) if values else 0.0,
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values, default=0),
    }


def compare(a: Capture, b: Capture, tile_count: int = 8) -> dict[str, object]:
    if (a.width, a.height) != (b.width, b.height):
        raise ValueError(f"Dimension mismatch: {a.width}x{a.height} vs {b.width}x{b.height}")

    channels = {"r": [], "g": [], "b": [], "a": []}
    pixel_max_delta: list[int] = []
    changed_pixels = 0
    threshold_counts = {1: 0, 2: 0, 4: 0, 8: 0, 16: 0}
    tiles = [
        {"sum": 0, "count": 0, "changed": 0, "max": 0}
        for _ in range(tile_count * tile_count)
    ]

    for pixel in range(a.width * a.height):
        base = pixel * 4
        deltas = [abs(a.data[base + c] - b.data[base + c]) for c in range(4)]
        for name, delta in zip(("r", "g", "b", "a"), deltas):
            channels[name].append(delta)
        max_delta = max(deltas[:3])
        pixel_max_delta.append(max_delta)
        if max_delta > 0:
            changed_pixels += 1
        for threshold in threshold_counts:
            if max_delta > threshold:
                threshold_counts[threshold] += 1

        x = pixel % a.width
        y = pixel // a.width
        tx = min(tile_count - 1, x * tile_count // a.width)
        ty = min(tile_count - 1, y * tile_count // a.height)
        tile = tiles[ty * tile_count + tx]
        tile["sum"] += max_delta
        tile["count"] += 1
        tile["changed"] += int(max_delta > 0)
        tile["max"] = max(tile["max"], max_delta)

    total_pixels = a.width * a.height
    spatial = []
    for ty in range(tile_count):
        row = []
        for tx in range(tile_count):
            tile = tiles[ty * tile_count + tx]
            count = tile["count"] or 1
            row.append(
                {
                    "mean_max_rgb_delta": tile["sum"] / count,
                    "changed_pixel_ratio": tile["changed"] / count,
                    "max_rgb_delta": tile["max"],
                }
            )
        spatial.append(row)

    sample_coords = [
        (0, 0),
        (a.width // 4, a.height // 4),
        (a.width // 2, a.height // 2),
        (3 * a.width // 4, 3 * a.height // 4),
        (a.width - 1, a.height - 1),
    ]
    samples = []
    for x, y in sample_coords:
        base = (y * a.width + x) * 4
        av = list(a.data[base : base + 4])
        bv = list(b.data[base : base + 4])
        samples.append({"x": x, "y": y, "a_rgba": av, "b_rgba": bv, "abs_delta": [abs(x - y) for x, y in zip(av, bv)]})

    return {
        "pts_us": a.pts_us,
        "dimensions": [a.width, a.height],
        "channel_abs_delta": {name: summarize(values) for name, values in channels.items()},
        "pixel_max_rgb_abs_delta": summarize(pixel_max_delta),
        "different_pixel_ratio": changed_pixels / total_pixels,
        "pixel_ratio_above_delta": {
            str(threshold): count / total_pixels for threshold, count in threshold_counts.items()
        },
        "spatial_8x8": spatial,
        "deterministic_samples": samples,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle_a", type=Path)
    parser.add_argument("bundle_b", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    captures_a = load_captures(args.bundle_a)
    captures_b = load_captures(args.bundle_b)
    common_pts = sorted(set(captures_a) & set(captures_b))
    if not common_pts:
        raise SystemExit(
            "No exact matching PTS captures found. "
            f"A={sorted(captures_a)}, B={sorted(captures_b)}"
        )

    result = {
        "bundle_a": str(args.bundle_a),
        "bundle_b": str(args.bundle_b),
        "common_pts_us": common_pts,
        "frames": [compare(captures_a[pts], captures_b[pts]) for pts in common_pts],
    }
    rendered = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
