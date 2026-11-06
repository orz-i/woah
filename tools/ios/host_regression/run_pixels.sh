#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../../.."
OUT="$(mktemp -d "${TMPDIR:-/tmp}/frame-kernel-pixels.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
CORE=crates/frame-kernel
python3 tools/rust/run.py build
swiftc -O -I "$CORE/include" -o "$OUT/tests" "$HERE/pixels/PixelStubs.swift" "$N/IOSFrameKernel.swift" "$N/IOSYoloPostprocessor.swift" "$N/IOSYoloPreprocessor.swift" "$HERE/pixels/main.swift" "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/tests"
