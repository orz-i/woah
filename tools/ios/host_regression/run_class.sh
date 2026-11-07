#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$HERE" rev-parse --show-toplevel)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/frame-kernel-class.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
CORE=crates/frame-kernel
python3 tools/rust/run.py build
swiftc -O -I "$CORE/include" -o "$OUT/tests" "$HERE/tracker/ModelStubs.swift" "$N/IOSSharedTrackingSession.swift" "$N/IOSPrivacyClassTemporalTracker.swift" "$HERE/class/main.swift" "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/tests"
