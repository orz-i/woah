#!/bin/bash
# Native-only identity safety regression suite, independent of Flutter/Xcode UI.
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$HERE" rev-parse --show-toplevel)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/woah-ios-tracker-host.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
CORE=crates/frame-kernel
python3 tools/rust/run.py build
/usr/bin/swiftc -I "$CORE/include" -o "$OUT/tracker_tests" "$HERE/tracker/ModelStubs.swift" "$N/IOSFrameKernel.swift" "$N/IOSSharedTrackingSession.swift" "$N/IOSOcclusionProxyStabilizer.swift" "$N/IOSPreviewIdentityMatcher.swift" "$N/IOSTemporalIdentityTracker.swift" "$N/IOSFacePrivacyPipeline.swift" "$HERE/tracker/main.swift" "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/tracker_tests"
