#!/usr/bin/env python3
"""Install/launch the Android debug-only M0 comparison experiment via adb."""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys

PKG = "art.gaoge.dance"
ACTIVITY = PKG + ".engine.comparison.ComparisonM0Activity"
REMOTE = "/sdcard/Movies/WoahM0"
REPORT = f"/sdcard/Android/data/{PKG}/files/Documents/comparison-m0/latest.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Already-built Android debug APK")
    parser.add_argument("--teacher", type=Path, help="Synthetic teacher.mp4 (optional)")
    parser.add_argument("--learner", type=Path, help="Synthetic learner.mp4 (optional)")
    parser.add_argument("--offset-us", type=int, default=3_000_000)
    parser.add_argument("--adb", default="adb", help="adb binary or absolute path")
    parser.add_argument("--serial", help="adb device serial, required if multiple")
    parser.add_argument("--pull-report", type=Path,
                        help="Only retrieve the last saved report; do not launch")
    args = parser.parse_args()

    def adb(*cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [args.adb, *(["-s", args.serial] if args.serial else []), *cmd],
            text=True, capture_output=True, check=check,
        )

    try:
        if args.pull_report is not None:
            args.pull_report.parent.mkdir(parents=True, exist_ok=True)
            result = adb("pull", REPORT, str(args.pull_report))
            print(result.stdout, result.stderr)
            print("Saved", args.pull_report)
            return 0

        if args.apk is not None:
            if not args.apk.is_file():
                parser.error(f"APK not found: {args.apk}")
            result = adb("install", "-r", str(args.apk))
            print(result.stdout)
        if (args.teacher is None) != (args.learner is None):
            parser.error("Provide both synthetic fixtures, or neither.")

        if args.teacher is not None and args.learner is not None:
            for file in (args.teacher, args.learner):
                if not file.is_file():
                    parser.error(f"Fixture not found: {file}")
            adb("shell", "mkdir", "-p", REMOTE)
            for role, file in (("teacher", args.teacher), ("learner", args.learner)):
                dest = f"{REMOTE}/{role}.mp4"
                print(adb("push", str(file), dest).stdout.strip())
                # Best-effort scan for visibility in system document picker.
                adb("shell", "am", "broadcast", "-a",
                    "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d", f"file://{dest}", check=False)

        print("Device:", adb("shell", "getprop", "ro.product.model").stdout.strip())
        print("API:", adb("shell", "getprop", "ro.build.version.sdk").stdout.strip())
        result = adb("shell", "am", "start", "-n", ACTIVITY,
                     "--el", "offset_us", str(args.offset_us))
        print(result.stdout)
        print("In the M0 screen pick Teacher and Learner via system picker.")
        print("Use Movies/WoahM0 for synthetic files (or browse your own videos).")
        print("After a playback run tap 保存诊断, then execute:")
        print(f"  python tools/comparison/run_m0_android.py --pull-report m0-latest.json")
        return 0
    except FileNotFoundError:
        print(f"adb not found: {args.adb}. Install Android platform-tools or use --adb.", file=sys.stderr)
    except subprocess.CalledProcessError as error:
        print(f"Command failed ({error.returncode}): {' '.join(error.cmd)}",
              file=sys.stderr)
        print(error.stdout, error.stderr, file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
