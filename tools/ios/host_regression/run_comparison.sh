#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
CORE="$ROOT/crates/frame-kernel"
N="$ROOT/mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
OUT="${TMPDIR:-/tmp}/frame-kernel-comparison-host"
mkdir -p "$OUT"
swiftc -O -I "$CORE/include" -o "$OUT/composition" \
  "$N/IOSComparisonTimeCore.swift" "$N/IOSComparisonModels.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_composition/main.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/composition"
