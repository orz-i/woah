#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$HERE" rev-parse --show-toplevel)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/frame-kernel-face.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
CORE=crates/frame-kernel
python3 tools/rust/run.py build
swiftc -O -I "$CORE/include" -o "$OUT/tests" "$HERE/tracker/ModelStubs.swift" "$N/IOSFrameKernel.swift" "$N/IOSSharedTrackingSession.swift" "$N/IOSOcclusionProxyStabilizer.swift" "$N/IOSPreviewIdentityMatcher.swift" "$N/IOSTemporalIdentityTracker.swift" "$N/IOSFacePrivacyPipeline.swift" "$N/IOSFaceObservationRecorder.swift" "$N/IOSGridFaceLocator.swift" "$N/IOSFrameKernelFaceGeometry.swift" "$HERE/face_geometry/CanonicalObservationGeometry.swift" "$HERE/face_geometry/main.swift" "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/tests"
