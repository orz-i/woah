#!/usr/bin/env python3
"""Launch the production app entrypoint and require short Simulator liveness.

Flutter supports only Debug runtime builds on iOS Simulator. Release-specific
smoke-hook fail-closed behavior is therefore audited on the iPhoneOS Release
bundle, not inferred from this Simulator launch.
"""

from __future__ import annotations

import argparse
import plistlib
import subprocess
import sys
import time
from pathlib import Path

from run_phase4_simulator_smoke import BUNDLE_ID, boot_iphone, safe_run


def verify_production_bundle_contract(app: Path) -> None:
    info_path = app / "Info.plist"
    if not info_path.is_file():
        raise SystemExit(f"Production Simulator Info.plist is missing: {info_path}")
    with info_path.open("rb") as stream:
        info = plistlib.load(stream)
    if str(info.get("CFBundleIdentifier", "")) != BUNDLE_ID:
        raise SystemExit("Production Simulator bundle ID does not match art.gaoge.dance")
    print("IOS_PHASE7_PRODUCTION_SIMULATOR_ENTRYPOINT=VERIFIED")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--liveness-seconds", type=float, default=8.0)
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Production Simulator app bundle does not exist: {app}")
    verify_production_bundle_contract(app)

    runtime, name, udid = boot_iphone(app)
    print(f"PHASE7_PRODUCTION_SIMULATOR_DEVICE={name}")
    print(f"PHASE7_PRODUCTION_SIMULATOR_RUNTIME={runtime}")
    try:
        command = ["xcrun", "simctl", "launch", "--console", udid, BUNDLE_ID]
        print("+", " ".join(command), flush=True)
        process = subprocess.Popen(
            command,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        try:
            stdout, _ = process.communicate(timeout=max(1.0, args.liveness_seconds))
        except subprocess.TimeoutExpired:
            # `simctl launch --console` remains attached while the Flutter app is
            # alive. Surviving the liveness window is the production-startup
            # assertion; cleanup below terminates the app and attached simctl.
            print(f"IOS_PHASE7_PRODUCTION_SIMULATOR_LIVENESS=PASS seconds={args.liveness_seconds}")
            return 0
        else:
            if stdout:
                print(stdout, end="" if stdout.endswith("\n") else "\n")
            raise SystemExit(
                "Production Simulator process exited before the liveness window: "
                f"launch_exit={process.returncode}"
            )
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    finally:
        safe_run(["xcrun", "simctl", "terminate", udid, BUNDLE_ID], timeout=20)
        safe_run(["xcrun", "simctl", "shutdown", udid], timeout=45)


if __name__ == "__main__":
    sys.exit(main())

