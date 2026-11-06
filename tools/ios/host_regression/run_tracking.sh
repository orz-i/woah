#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../../.."
OUT="$(mktemp -d "${TMPDIR:-/tmp}/frame-kernel-tracking.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
CORE=crates/frame-kernel
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
python3 tools/rust/run.py build
swiftc -O -I "$CORE/include" -o "$OUT/tests" "$N/IOSSharedTrackingSession.swift" "$HERE/TrackingSessionConformance.swift" "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/tests" "$CORE/testdata/tracking-update-reference-v1.bin"
