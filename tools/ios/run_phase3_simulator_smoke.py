#!/usr/bin/env python3
"""Boot an available iPhone simulator and execute the Phase 3 Metal smoke app."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


BUNDLE_ID = "art.gaoge.dance"
PASS_MARKER = "WOAH_METAL_PHASE3_SMOKE=PASS"


def run(
    command: list[str],
    *,
    check: bool = True,
    timeout: int = 120,
) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(command), flush=True)
    return subprocess.run(
        command,
        check=check,
        text=True,
        capture_output=True,
        timeout=timeout,
    )


def choose_iphone() -> tuple[str, str, str]:
    result = run(["xcrun", "simctl", "list", "devices", "available", "-j"])
    payload = json.loads(result.stdout)
    candidates: list[tuple[str, str, str]] = []
    for runtime, devices in payload.get("devices", {}).items():
        if "iOS" not in runtime:
            continue
        for device in devices:
            if not device.get("isAvailable", True):
                continue
            name = str(device.get("name", ""))
            udid = str(device.get("udid", ""))
            if name.startswith("iPhone") and udid:
                candidates.append((runtime, name, udid))
    if not candidates:
        raise SystemExit("No available iPhone simulator was found on the runner")
    candidates.sort(reverse=True)
    return candidates[0]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Simulator app bundle does not exist: {app}")

    runtime, name, udid = choose_iphone()
    print(f"SIMULATOR_DEVICE={name}")
    print(f"SIMULATOR_RUNTIME={runtime}")
    print(f"SIMULATOR_UDID={udid}")
    try:
        boot = run(["xcrun", "simctl", "boot", udid], check=False)
        if boot.returncode != 0 and "current state: Booted" not in boot.stderr:
            print(boot.stdout, end="")
            print(boot.stderr, end="", file=sys.stderr)
            raise SystemExit(f"Failed to boot iOS simulator: exit={boot.returncode}")
        bootstatus = run(["xcrun", "simctl", "bootstatus", udid, "-b"], timeout=180)
        if bootstatus.stdout:
            print(bootstatus.stdout, end="")
        if bootstatus.stderr:
            print(bootstatus.stderr, end="", file=sys.stderr)

        run(["xcrun", "simctl", "install", udid, str(app)], timeout=120)
        launched = run(
            ["xcrun", "simctl", "launch", "--console", udid, BUNDLE_ID],
            check=False,
            timeout=90,
        )
        output = launched.stdout + launched.stderr
        print(output, end="")
        if PASS_MARKER not in output:
            raise SystemExit(
                f"Phase 3 simulator smoke did not emit {PASS_MARKER}; "
                f"launch_exit={launched.returncode}"
            )
        print("IOS_SIMULATOR_METAL_SMOKE=PASS")
        return 0
    finally:
        run(["xcrun", "simctl", "terminate", udid, BUNDLE_ID], check=False, timeout=30)
        run(["xcrun", "simctl", "shutdown", udid], check=False, timeout=60)


if __name__ == "__main__":
    raise SystemExit(main())
