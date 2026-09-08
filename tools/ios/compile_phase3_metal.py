#!/usr/bin/env python3
"""Offline-compile the exact Phase 3 runtime Metal source with Xcode tools."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from extract_phase3_metal import extract


def run(command: list[str]) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", choices=("iphoneos", "iphonesimulator"), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    metal = args.output_dir / "WoahPhase3Preview.metal"
    air = args.output_dir / "WoahPhase3Preview.air"
    metallib = args.output_dir / "WoahPhase3Preview.metallib"
    metal.write_text(extract(), encoding="utf-8", newline="\n")

    run([
        "xcrun",
        "-sdk",
        args.sdk,
        "metal",
        "-c",
        str(metal),
        "-o",
        str(air),
    ])
    run([
        "xcrun",
        "-sdk",
        args.sdk,
        "metallib",
        str(air),
        "-o",
        str(metallib),
    ])
    if not air.is_file() or air.stat().st_size == 0:
        raise SystemExit("Metal compiler did not produce a non-empty AIR artifact")
    if not metallib.is_file() or metallib.stat().st_size == 0:
        raise SystemExit("metallib did not produce a non-empty library")
    print(f"METAL_OFFLINE_COMPILE=PASS sdk={args.sdk}")
    print(f"METALLIB={metallib} bytes={metallib.stat().st_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
