#!/usr/bin/env python3
"""Permanent Apple native regression: locked models, contracts, Metal and simulator."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "mobile/app"
METAL_ROOT = Path(os.environ.get("RUNNER_TEMP", ROOT / "tmp")) / "woah-preview-metal"
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.privacy.build_qa import verify_ios_model



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
        if isinstance(tail, bytes):
            tail = tail.decode("utf-8", errors="replace")
        print(tail, end="" if tail.endswith("\n") else "\n")
        print(
            "::error title=Woah iOS native regression timeout::"
            + annotation_escape(f"{rendered} timed out after {timeout}s\n{tail}")
        )
        raise

    output = completed.stdout or ""
    if output:
        print(output, end="" if output.endswith("\n") else "\n")
    if completed.returncode != 0:
        tail = output[-8000:]
        print(
            "::error title=Woah iOS native regression failed::"
            + annotation_escape(
                f"command={rendered}\nexit={completed.returncode}\n{tail}"
            )
        )
        raise SystemExit(completed.returncode)


def main() -> int:
    if sys.platform != "darwin":
        raise SystemExit("iOS native regression must run on macOS")

    # CocoaPods expands resource inputs during dependency resolution. Stage the
    # pinned YOLO and face binaries before that graph is generated on clean CI.
    run([sys.executable, "tools/release/sync_ios_yolo_model.py"])
    run([sys.executable, "tools/release/sync_ios_yolo_model.py", "--verify-only"])
    run(["flutter", "config", "--enable-swift-package-manager"], cwd=APP)
    run([sys.executable, "tools/ci/resolve_ios_dependencies.py"])

    for verifier in (
        "tools/release/verify_export_media_contract.py",
        "tools/release/verify_ios_analysis.py",
        "tools/release/verify_ios_preview.py",
        "tools/release/verify_ios_export.py",
        "tools/release/verify_ios_tracking.py",
        "tools/release/verify_ios_privacy_class.py",
    ):
        run([sys.executable, verifier])

    for sdk in ("iphoneos", "iphonesimulator"):
        run([
            sys.executable,
            "tools/ios/compile_preview_metal.py",
            "--sdk",
            sdk,
            "--output-dir",
            str(METAL_ROOT / sdk),
        ])

    run([
        "flutter",
        "build",
        "ios",
        "--simulator",
        "--debug",
        "--no-pub",
        "--target",
        "lib/ios_native_regression_main.dart",
    ], cwd=APP, timeout=1800)
    # Use the same byte/size verifier as the device regression builder. The
    # compiled app, not only the source asset directory, must contain both models.
    verify_ios_model(APP / "build/ios/iphonesimulator/Runner.app/dance_native_models.bundle")
    run([
        sys.executable,
        "tools/ios/run_simulator_regression.py",
        "--app",
        "mobile/app/build/ios/iphonesimulator/Runner.app",
    ], timeout=1200)
    print("IOS_NATIVE_REGRESSION=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
