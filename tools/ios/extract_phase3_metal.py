#!/usr/bin/env python3
"""Extract the exact Phase 3 runtime Metal kernel into a standalone .metal file.

The production renderer intentionally keeps the kernel embedded in Swift so the
plugin has no runtime shader-resource lookup dependency. CI uses this helper to
offline-compile the exact same source with Apple's `metal`/`metallib` tools.
"""

from __future__ import annotations

import argparse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RENDERER = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
    / "IOSMetalPreviewRenderer.swift"
)
START = '  private static let kernelSource = #"""\n'
END = '\n"""#'


def extract() -> str:
    source = RENDERER.read_text(encoding="utf-8")
    start = source.find(START)
    if start < 0:
        raise SystemExit("Phase 3 Metal kernel start marker was not found")
    start += len(START)
    end = source.find(END, start)
    if end < 0:
        raise SystemExit("Phase 3 Metal kernel end marker was not found")
    shader = source[start:end]
    if "kernel void woahPreviewKernel" not in shader:
        raise SystemExit("Phase 3 Metal entrypoint is missing from extracted source")
    return shader.rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shader = extract()
    args.output.write_text(shader, encoding="utf-8", newline="\n")
    print(f"METAL_SOURCE={args.output}")
    print(f"METAL_SOURCE_BYTES={len(shader.encode('utf-8'))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
