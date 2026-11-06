#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$HERE" rev-parse --show-toplevel)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/woah-ios-highres.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
swiftc -O -o "$OUT/tests" "$HERE/tracker/ModelStubs.swift" "$HERE/high_resolution/InferenceStubs.swift" "$N/IOSHighResolutionPrivacyCoverage.swift" "$N/IOSPrivacyMaskProjector.swift" "$HERE/high_resolution/main.swift"
"$OUT/tests"
