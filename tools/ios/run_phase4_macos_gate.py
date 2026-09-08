#!/usr/bin/env python3
"""GitHub macOS Phase 4 gate: contracts + Metal compile + real-video Simulator export."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "mobile/app"
METAL_ROOT = Path(os.environ.get("RUNNER_TEMP", ROOT / "tmp")) / "woah-phase4-metal"


def annotation_escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def run(command: list[str], *, cwd: Path = ROOT, timeout: int = 1200) -> None:
    rendered = " ".join(command)
    print("+", rendered, flush=True)
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            check=False,
            timeout=timeout,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    except subprocess.TimeoutExpired as exc:
        tail = (exc.stdout or "")[-8000:]
        print(tail, end="" if tail.endswith("\n") else "\n")
        print(
            "::error title=Woah Phase 4 macOS gate timeout::"
            + annotation_escape(f"{rendered} timed out after {timeout}s\n{tail}")
        )
        raise

    output = completed.stdout or ""
    if output:
        print(output, end="" if output.endswith("\n") else "\n")
    if completed.returncode != 0:
        tail = output[-8000:]
        print(
            "::error title=Woah Phase 4 macOS gate failed::"
            + annotation_escape(
                f"command={rendered}\nexit={completed.returncode}\n{tail}"
            )
        )
        raise SystemExit(completed.returncode)


def main() -> int:
    if sys.platform != "darwin":
        raise SystemExit("Phase 4 macOS gate must run on macOS")

    for verifier in (
        "tools/release/verify_ios_phase2.py",
        "tools/release/verify_ios_phase3.py",
        "tools/release/verify_ios_phase4.py",
    ):
        run([sys.executable, verifier])

    # Preserve Phase 3's exact production-kernel offline compile evidence.
    for sdk in ("iphoneos", "iphonesimulator"):
        run([
            sys.executable,
            "tools/ios/compile_phase3_metal.py",
            "--sdk",
            sdk,
            "--output-dir",
            str(METAL_ROOT / sdk),
        ])

    run(["flutter", "config", "--enable-swift-package-manager"], cwd=APP)
    run(["flutter", "pub", "get"], cwd=APP)
    run([
        "flutter",
        "build",
        "ios",
        "--simulator",
        "--debug",
        "--target",
        "lib/ios_metal_smoke_main.dart",
    ], cwd=APP, timeout=1800)
    run([
        sys.executable,
        "tools/ios/run_phase4_simulator_smoke.py",
        "--app",
        "mobile/app/build/ios/iphonesimulator/Runner.app",
    ], timeout=1200)
    print("IOS_PHASE4_MACOS_GATE=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
