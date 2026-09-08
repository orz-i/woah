#!/usr/bin/env python3
"""GitHub macOS Phase 3 gate: offline Metal compile + simulator GPU smoke."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "mobile/app"
METAL_ROOT = Path(os.environ.get("RUNNER_TEMP", ROOT / "tmp")) / "woah-phase3-metal"


def run(command: list[str], *, cwd: Path = ROOT, timeout: int = 1200) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True, timeout=timeout)


def main() -> int:
    if sys.platform != "darwin":
        raise SystemExit("Phase 3 macOS gate must run on macOS")

    run([sys.executable, "tools/release/verify_ios_phase2.py"])
    run([sys.executable, "tools/release/verify_ios_phase3.py"])
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
        "tools/ios/run_phase3_simulator_smoke.py",
        "--app",
        "mobile/app/build/ios/iphonesimulator/Runner.app",
    ], timeout=300)
    print("IOS_PHASE3_MACOS_GATE=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
