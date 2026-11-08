#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$HERE" rev-parse --show-toplevel)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/woah-observation-replay.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
swiftc -o "$OUT/tests" "$HERE/tracker/ModelStubs.swift" "$N/IOSQaObservationReplay.swift" "$HERE/observation_replay/main.swift"
"$OUT/tests"

