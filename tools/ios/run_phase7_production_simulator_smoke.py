#!/usr/bin/env python3
"""Launch the production Release entrypoint and require short Simulator liveness."""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

from run_phase4_simulator_smoke import BUNDLE_ID, boot_iphone, safe_run


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--liveness-seconds", type=float, default=8.0)
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Production Simulator app bundle does not exist: {app}")

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
                "Production Release Simulator process exited before the liveness window: "
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

