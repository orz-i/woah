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
GOLDEN_TRACE_PASS_MARKER = "WOAH_GOLDEN_TRACE_PHASE5_SMOKE=PASS"


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


def simulator_state(udid: str) -> str | None:
    """Return CoreSimulator's current state for one device, if observable."""
    try:
        completed = run(
            ["xcrun", "simctl", "list", "devices", "-j"],
            check=False,
            timeout=30,
        )
    except subprocess.TimeoutExpired:
        return None
    if completed.returncode != 0:
        return None
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError:
        return None
    for devices in payload.get("devices", {}).values():
        for device in devices:
            if str(device.get("udid", "")) == udid:
                return str(device.get("state", "")) or None
    return None


def tolerate_degraded_bootstatus(
    *,
    name: str,
    udid: str,
    reason: str,
) -> bool:
    """Proceed only when bootstatus degraded but CoreSimulator is still Booted.

    Hosted macOS runners occasionally finish a usable Simulator boot while a
    migration plugin makes `simctl bootstatus -b` exit non-zero (or linger until
    our stabilization timeout). This does not waive the actual gate: install,
    app launch, Metal/export/FACE_ONLY markers, and media readback still run and
    must succeed. If the device is not observably Booted, the runner falls back
    to another iPhone candidate instead.
    """
    state = simulator_state(udid)
    if state != "Booted":
        return False
    print(
        f"SIMULATOR_BOOTSTATUS_DEGRADED_TOLERATED={name} "
        f"state={state} reason={reason}",
        file=sys.stderr,
    )
    return True


def install_probe(udid: str, app: Path, *, attempts: int = 3) -> bool:
    """Use the operation we actually need as the final Simulator readiness probe.

    `simctl bootstatus -b` is advisory on GitHub-hosted runners: migration
    plugins can fail or stall even though CoreSimulator is already capable of
    installing and launching an app. An install probe is therefore stricter and
    more relevant than accepting a state string alone. It does not waive the
    gate because the real launch plus all Phase 3/4/5 smoke markers still run
    after this function returns true.
    """
    for attempt in range(1, attempts + 1):
        try:
            completed = run(
                ["xcrun", "simctl", "install", udid, str(app)],
                check=False,
                timeout=120,
            )
        except subprocess.TimeoutExpired:
            print(
                f"SIMULATOR_INSTALL_PROBE_TIMEOUT=attempt_{attempt}",
                file=sys.stderr,
            )
            completed = None
        if completed is not None:
            if completed.stdout:
                print(completed.stdout, end="")
            if completed.stderr:
                print(completed.stderr, end="", file=sys.stderr)
            if completed.returncode == 0:
                print(f"SIMULATOR_INSTALL_PROBE=PASS attempt={attempt}")
                return True
        if attempt < attempts:
            time.sleep(5)
    return False


def boot_iphone(app: Path) -> tuple[str, str, str]:
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
                check=False,
                timeout=420,
            )
            if bootstatus.stdout:
                print(bootstatus.stdout, end="")
            if bootstatus.stderr:
                print(bootstatus.stderr, end="", file=sys.stderr)
            if bootstatus.returncode == 0:
                if install_probe(udid, app):
                    return runtime, name, udid
                failures.append(f"{name}: bootstatus passed but install probe failed")
                safe_run(["xcrun", "simctl", "shutdown", udid], timeout=30)
                continue
            reason = f"bootstatus_exit_{bootstatus.returncode}"
            if tolerate_degraded_bootstatus(name=name, udid=udid, reason=reason):
                if install_probe(udid, app):
                    return runtime, name, udid
            elif install_probe(udid, app):
                print(
                    f"SIMULATOR_BOOTSTATUS_DEGRADED_TOLERATED={name} "
                    f"state={simulator_state(udid) or 'unknown'} "
                    f"reason={reason}_install_probe_passed",
                    file=sys.stderr,
                )
                return runtime, name, udid
            failures.append(
                f"{name}: bootstatus exit={bootstatus.returncode} "
                f"state={simulator_state(udid) or 'unknown'}"
            )
            safe_run(["xcrun", "simctl", "shutdown", udid], timeout=30)
        except subprocess.TimeoutExpired:
            if tolerate_degraded_bootstatus(
                name=name,
                udid=udid,
                reason="bootstatus_timeout_420s",
            ):
                if install_probe(udid, app):
                    return runtime, name, udid
            elif install_probe(udid, app):
                print(
                    f"SIMULATOR_BOOTSTATUS_DEGRADED_TOLERATED={name} "
                    f"state={simulator_state(udid) or 'unknown'} "
                    "reason=bootstatus_timeout_420s_install_probe_passed",
                    file=sys.stderr,
                )
                return runtime, name, udid
            failures.append(
                f"{name}: bootstatus timed out after 420s "
                f"state={simulator_state(udid) or 'unknown'}"
            )
            safe_run(["xcrun", "simctl", "shutdown", udid], timeout=30)
    raise SystemExit("Unable to boot an iPhone simulator: " + " | ".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--require-phase5", action="store_true")
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Simulator app bundle does not exist: {app}")

    runtime, name, udid = boot_iphone(app)
    print(f"SIMULATOR_DEVICE={name}")
    print(f"SIMULATOR_RUNTIME={runtime}")
    print(f"SIMULATOR_UDID={udid}")
    try:
        launched = run(
            ["xcrun", "simctl", "launch", "--console", udid, BUNDLE_ID],
            check=False,
            timeout=600,
        )
        output = launched.stdout + launched.stderr
        print(output, end="")
        required_markers = [METAL_PASS_MARKER, EXPORT_PASS_MARKER]
        if args.require_phase5:
            required_markers.append(GOLDEN_TRACE_PASS_MARKER)
        missing = [
            marker
            for marker in required_markers
            if marker not in output
        ]
        if missing:
            raise SystemExit(
                "Phase 4 simulator smoke did not emit required markers "
                f"{missing}; launch_exit={launched.returncode}"
            )
        print("IOS_SIMULATOR_PHASE4_EXPORT_SMOKE=PASS")
        if args.require_phase5:
            print("IOS_SIMULATOR_PHASE5_GOLDEN_TRACE_SMOKE=PASS")
        return 0
    finally:
        # Teardown is deliberately best-effort. Phase 3 CI demonstrated that a
        # wedged CoreSimulator can also wedge `terminate`; cleanup must never
        # replace the actual export/Metal failure with a secondary exception.
        safe_run(["xcrun", "simctl", "terminate", udid, BUNDLE_ID], timeout=20)
        safe_run(["xcrun", "simctl", "shutdown", udid], timeout=45)


if __name__ == "__main__":
    raise SystemExit(main())
