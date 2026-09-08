#!/usr/bin/env python3
"""Boot an iPhone simulator and execute the combined Phase 3/4 media smoke app."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
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


def choose_iphones() -> list[tuple[str, str, str]]:
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
    # Prefer the newest runtime, then conventional Pro/standard devices before
    # Air. Phase 3 CI showed that a single hosted-runner device can occasionally
    # wedge in CoreSimulator boot; trying another compatible iPhone is a gate
    # robustness measure, not a relaxation of the media/GPU assertions.
    def rank(candidate: tuple[str, str, str]) -> tuple[str, int, str]:
        runtime, name, _ = candidate
        if "Pro Max" in name:
            device_rank = 4
        elif "Pro" in name:
            device_rank = 3
        elif "Air" in name:
            device_rank = 1
        else:
            device_rank = 2
        return runtime, device_rank, name

    candidates.sort(key=rank, reverse=True)
    return candidates


def safe_run(command: list[str], *, timeout: int) -> None:
    try:
        completed = run(command, check=False, timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"CLEANUP_TIMEOUT={' '.join(command)}", file=sys.stderr)
        return
    if completed.stdout:
        print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)


def boot_iphone() -> tuple[str, str, str]:
    failures: list[str] = []
    for runtime, name, udid in choose_iphones()[:4]:
        print(f"SIMULATOR_BOOT_ATTEMPT={name} runtime={runtime} udid={udid}")
        safe_run(["xcrun", "simctl", "shutdown", udid], timeout=20)
        time.sleep(1)
        try:
            boot = run(["xcrun", "simctl", "boot", udid], check=False, timeout=30)
            if boot.returncode != 0 and "current state: Booted" not in boot.stderr:
                failures.append(f"{name}: boot exit={boot.returncode} {boot.stderr.strip()}")
                continue
            bootstatus = run(
                ["xcrun", "simctl", "bootstatus", udid, "-b"],
                timeout=420,
            )
            if bootstatus.stdout:
                print(bootstatus.stdout, end="")
            if bootstatus.stderr:
                print(bootstatus.stderr, end="", file=sys.stderr)
            return runtime, name, udid
        except subprocess.TimeoutExpired:
            failures.append(f"{name}: bootstatus timed out after 420s")
            safe_run(["xcrun", "simctl", "shutdown", udid], timeout=30)
    raise SystemExit("Unable to boot an iPhone simulator: " + " | ".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Simulator app bundle does not exist: {app}")

    runtime, name, udid = boot_iphone()
    print(f"SIMULATOR_DEVICE={name}")
    print(f"SIMULATOR_RUNTIME={runtime}")
    print(f"SIMULATOR_UDID={udid}")
    try:
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
        # Teardown is deliberately best-effort. Phase 3 CI demonstrated that a
        # wedged CoreSimulator can also wedge `terminate`; cleanup must never
        # replace the actual export/Metal failure with a secondary exception.
        safe_run(["xcrun", "simctl", "terminate", udid, BUNDLE_ID], timeout=20)
        safe_run(["xcrun", "simctl", "shutdown", udid], timeout=45)


if __name__ == "__main__":
    raise SystemExit(main())
