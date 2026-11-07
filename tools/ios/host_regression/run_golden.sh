#!/bin/bash
# Host-only temporal Golden cases. Actual Vision/Metal smokes run on iOS.
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
cd "$ROOT"
TEMP="$(mktemp -d "${TMPDIR:-/tmp}/woah-ios-golden.XXXXXX")"
trap 'rm -rf "$TEMP"' EXIT
N=mobile/packages/dance_native/ios/dance_native/Sources/dance_native
CORE=crates/frame-kernel
python3 tools/rust/run.py build
python3 - "$N/IOSGoldenTracePhase5Smoke.swift" "$N/Resources/GoldenTraces/phase5_tracking_golden.json" "$TEMP" <<'GOLDEN_PY'
import pathlib,sys,json
source=pathlib.Path(sys.argv[1]).read_text()
needle='IOSGoldenTraceResources.phase5TrackingURL()'
assert source.count(needle)==1
source=source.replace(needle,'Optional(URL(fileURLWithPath: '+json.dumps(str(pathlib.Path(sys.argv[2]).resolve()))+'))',1)
pathlib.Path(sys.argv[3],'IOSGoldenTracePhase5Smoke.swift').write_text(source)
GOLDEN_PY
cat > "$TEMP/main.swift" <<'SWIFT'
import Foundation
enum IOSYoloPreprocessor { static let inputSize = 640 }
enum IOSFacePrivacyPhase5Smoke {
  static func run() throws -> [String: Any] { ["status": "FACE_SCOPE_REQUIRES_IOS_RUNTIME"] }
}
do {
  let result = try IOSGoldenTracePhase5Smoke.run()
  if let cases = result["cases"] as? [[String: Any]] {
    for row in cases { print("CASE_PASS " + (row["name"] as? String ?? "")) }
  }
  print("IOS_GOLDEN_HOST_REGRESSION=PASS")
} catch {
  fputs("IOS_GOLDEN_HOST_REGRESSION=FAIL " + String(describing: error) + "\n", stderr)
  exit(1)
}
SWIFT
/usr/bin/swiftc -O -I "$CORE/include" -o "$TEMP/GoldenHost" \
 tools/ios/host_regression/tracker/ModelStubs.swift \
 "$N/IOSFrameKernel.swift" \
 "$N/IOSSharedTrackingSession.swift" \
 "$N/IOSOcclusionProxyStabilizer.swift" \
 "$N/IOSPreviewIdentityMatcher.swift" \
 "$N/IOSTemporalIdentityTracker.swift" \
 "$TEMP/IOSGoldenTracePhase5Smoke.swift" \
 "$TEMP/main.swift" "$CORE/target/release/libframe_kernel.a" -liconv
"$TEMP/GoldenHost"
