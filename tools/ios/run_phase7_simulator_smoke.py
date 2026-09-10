#!/usr/bin/env python3
"""Execute the Phase 7 Apple-runtime regression app on an iPhone Simulator.

Flutter does not support Release mode on iOS Simulator. This runner therefore
provides Debug-Simulator runtime evidence only; Release compilation is proven by
the separate no-codesign iPhoneOS build and audited bundle.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from run_phase4_simulator_smoke import BUNDLE_ID, boot_iphone, run, safe_run


REQUIRED_MARKERS = (
    "WOAH_METAL_PHASE3_SMOKE=PASS",
    "WOAH_EXPORT_PHASE4_SMOKE=PASS",
    "WOAH_GOLDEN_TRACE_PHASE5_SMOKE=PASS",
    "WOAH_PRIVACY_CLASS_PHASE6_SMOKE=PASS",
    "WOAH_RELEASE_PHASE7_SMOKE=PASS",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Phase 7 Simulator app bundle does not exist: {app}")

    runtime, name, udid = boot_iphone(app)
    print(f"PHASE7_SIMULATOR_DEVICE={name}")
    print(f"PHASE7_SIMULATOR_RUNTIME={runtime}")
    print(f"PHASE7_SIMULATOR_UDID={udid}")
    try:
        launched = run(
            ["xcrun", "simctl", "launch", "--console", udid, BUNDLE_ID],
            check=False,
            timeout=900,
        )
        output = launched.stdout + launched.stderr
        print(output, end="")
        missing = [marker for marker in REQUIRED_MARKERS if marker not in output]
        if missing:
            raise SystemExit(
                "Phase 7 Simulator runtime smoke did not emit required markers "
                f"{missing}; launch_exit={launched.returncode}"
            )
        print("IOS_SIMULATOR_PHASE7_RUNTIME_REGRESSION=PASS")
        return 0
    finally:
        safe_run(["xcrun", "simctl", "terminate", udid, BUNDLE_ID], timeout=20)
        safe_run(["xcrun", "simctl", "shutdown", udid], timeout=45)


if __name__ == "__main__":
    raise SystemExit(main())
