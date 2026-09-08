#!/usr/bin/env python3
"""Boot an iPhone simulator and execute the combined Phase 3/4 media smoke app."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


BUNDLE_ID = "art.gaoge.dance"
METAL_PASS_MARKER = "WOAH_METAL_PHASE3_SMOKE=PASS"
EXPORT_PASS_MARKER = "WOAH_EXPORT_PHASE4_SMOKE=PASS"


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
            timeout=600,
        )
        output = launched.stdout + launched.stderr
        print(output, end="")
        missing = [
            marker
            for marker in (METAL_PASS_MARKER, EXPORT_PASS_MARKER)
            if marker not in output
        ]
        if missing:
            raise SystemExit(
                "Phase 4 simulator smoke did not emit required markers "
                f"{missing}; launch_exit={launched.returncode}"
            )
        print("IOS_SIMULATOR_PHASE4_EXPORT_SMOKE=PASS")
        return 0
    finally:
        run(["xcrun", "simctl", "terminate", udid, BUNDLE_ID], check=False, timeout=30)
        run(["xcrun", "simctl", "shutdown", udid], check=False, timeout=60)


if __name__ == "__main__":
    raise SystemExit(main())
