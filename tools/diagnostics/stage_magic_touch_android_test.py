#!/usr/bin/env python3
"""Provision a pinned public MagicTouch model for a single debug Android test.

Stage 3 ONLY. Binary model bytes stream over adb exec-in directly into the
target *debug application's private files directory*, never into production
assets, the test APK, diagnostic archives, or the repository. No media IO.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
from typing import Callable

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.diagnostics.verify_interactive_segmenter_asset import (  # noqa: E402
    ROOT as PROVENANCE_ROOT, validate_android_dependency_source, verify
)
from tools.diagnostics.analyze_magic_touch_stage3_smoke import (  # noqa: E402
    read_logcat, summarize
)

LOCK_FILE = ROOT / "tools/diagnostics/baselines/mediapipe_magictouch_int8_1.lock.json"
GRADLE_FILE = ROOT / "mobile/packages/dance_native/android/build.gradle.kts"
REMOTE_BASENAME = "woah_magic_touch_stage3.task"
TEST_CLASS = "art.gaoge.dance.engine.inference.MagicTouchSyntheticSmokeInstrumentedTest"
ENABLE_ARG = "woahMagicTouchStage3Enabled"
TOKEN_ARG = "woahMagicTouchStage3RunToken"
# AndroidTest library host package varies by the Gradle variant; the operator
# must read the actual target= package from "adb shell pm list instrumentation".
ACCEPTED_DEBUG_PACKAGES = frozenset({
    "art.gaoge.dance",
    "art.gaoge.dance.engine",
    "art.gaoge.dance.engine.test",
})


def validate_package(package: str) -> None:
    if package not in ACCEPTED_DEBUG_PACKAGES:
        raise ValueError("unrecognized Woah debug target package")


def check_public_asset(path: Path) -> str:
    lock = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    validate_android_dependency_source(GRADLE_FILE.read_text(encoding="utf-8"))
    result = verify(path, lock, repo_root=PROVENANCE_ROOT)
    if result["artifact_state"] != "PINNED_STRUCTURAL_INTEGRITY_ONLY":
        raise ValueError("model structural provenance check was not accepted")
    return lock["sha256"]


def adb_prefix(adb: str, serial: str | None) -> list[str]:
    if not adb or any(char in adb for char in ("\n", "\r", "\0")):
        raise ValueError("adb binary not valid")
    if serial and not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial):
        raise ValueError("invalid ADB device serial")
    return [adb] + (["-s", serial] if serial else [])


def _execute(
    command: list[str], *, timeout: int = 45,
    run: Callable = subprocess.run, stdin=None
) -> subprocess.CompletedProcess:
    completed = run(
        command, stdin=stdin, capture_output=True, text=stdin is None,
        timeout=timeout, check=False,
    )
    if completed.returncode != 0:
        # Never echo untrusted shell stdout/stderr or model bytes.
        raise RuntimeError("ADB command failed (exit %d)" % completed.returncode)
    return completed


def cleanup_model(
    package: str, *, adb: str = "adb", serial: str | None = None,
    run: Callable = subprocess.run
) -> None:
    validate_package(package)
    prefix = adb_prefix(adb, serial)
    _execute(
        prefix + ["shell", "run-as", package, "rm", "-f", "files/" + REMOTE_BASENAME],
        run=run,
    )


def stage_model(
    path: Path,
    package: str, *, adb: str = "adb", serial: str | None = None,
    run: Callable = subprocess.run,
    verifier: Callable[[Path], str] = check_public_asset,
) -> None:
    validate_package(package)
    # Perform all local security verification before any device command.
    expected_sha = verifier(path)
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
        raise ValueError("invalid model provenance digest")
    prefix = adb_prefix(adb, serial)
    _execute(prefix + ["shell", "run-as", package, "mkdir", "-p", "files"], run=run)
    try:
        with path.open("rb") as source:
            _execute(
                prefix + [
                    "exec-in", "run-as", package, "sh", "-c",
                    "cat > files/" + REMOTE_BASENAME,
                ],
                timeout=180, run=run, stdin=source,
            )
        answer = _execute(
            prefix + [
                "shell", "run-as", package, "sha256sum",
                "files/" + REMOTE_BASENAME,
            ],
            run=run,
        )
        parts = answer.stdout.split() if isinstance(answer.stdout, str) else []
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]):
            raise ValueError("device SHA response was missing or malformed")
        if parts[0].lower() != expected_sha or parts[1] != "files/" + REMOTE_BASENAME:
            raise ValueError("device-staged model SHA mismatch")
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired):
        cleanup_model(package, adb=adb, serial=serial, run=run)
        raise


def _instrumentation_component(package: str, prefix: list[str], *, run: Callable) -> str:
    response = _execute(prefix + ["shell", "pm", "list", "instrumentation"], run=run)
    matches = []
    for line in response.stdout.splitlines():
        found = re.fullmatch(
            r"instrumentation:(\S+)\s+\(target=([A-Za-z0-9_.]+)\)",
            line.strip(),
        )
        if found and found.group(2) == package and (
            "AndroidJUnitRunner" in found.group(1)
        ):
            matches.append(found.group(1))
    if len(matches) != 1:
        raise ValueError("Woah instrumentation target not uniquely installed; check pm list instrumentation")
    return matches[0]


def run_synthetic_smoke(
    path: Path, package: str, output: Path, *, repeats: int = 3,
    adb: str = "adb", serial: str | None = None,
    run: Callable = subprocess.run,
    verifier: Callable[[Path], str] = check_public_asset,
    token_generator: Callable[[int], str] = secrets.token_hex,
) -> dict:
    validate_package(package)
    if not 2 <= repeats <= 5:
        raise ValueError("stage3 CPU smoke requires 2–5 independent repeats")
    dest = output.resolve()
    if dest == ROOT or ROOT in dest.parents:
        raise ValueError("stage3 numeric report must be written outside Git tree")
    prefix = adb_prefix(adb, serial)
    component = _instrumentation_component(package, prefix, run=run)
    run_token = token_generator(8)
    if not re.fullmatch(r"[0-9a-f]{16}", run_token):
        raise ValueError("synthetic test run token malformed")
    stage_model(path, package, adb=adb, serial=serial, run=run, verifier=verifier)
    try:
        for _ in range(repeats):
            result = _execute(
                prefix + [
                    "shell", "am", "instrument", "-w", "-r",
                    "-e", "class", TEST_CLASS,
                    "-e", ENABLE_ARG, "1",
                    "-e", TOKEN_ARG, run_token,
                    component,
                ], timeout=360, run=run,
            )
            # Only this explicitly named test may count as a successful run.
            if (
                not isinstance(result.stdout, str) or
                not re.search(r"OK \(1 test\)", result.stdout) or
                "INSTRUMENTATION_CODE: -1" not in result.stdout
            ):
                raise RuntimeError("Stage 3 instrumentation did not pass exactly one test")
        logs = _execute(
            prefix + ["logcat", "-d", "-v", "brief", "-s", "WoahMagicTouchStage3:I", "*:S"],
            timeout=90, run=run,
        )
        records = read_logcat(logs.stdout, run_token=run_token)
        if len(records) != repeats:
            raise ValueError("tagged synthetic smoke records incomplete/duplicated")
        report = summarize(records, min_repeats=repeats)
        dest.parent.mkdir(parents=True, exist_ok=True)
        temporary = dest.with_name(dest.name + ".stage3tmp")
        temporary.write_text(
            json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(dest)
        return report
    finally:
        # Do not leave a 30 MB model lying in application-private files.
        cleanup_model(package, adb=adb, serial=serial, run=run)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stage", "cleanup", "run"))
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--package", required=True, choices=sorted(ACCEPTED_DEBUG_PACKAGES))
    parser.add_argument("--output", type=Path, help="Required for run; numeric-only JSON outside Git")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    try:
        if args.action == "stage":
            if args.artifact is None:
                raise ValueError("--artifact is required for stage")
            stage_model(args.artifact, args.package, adb=args.adb, serial=args.serial)
            print("WOAH_STAGE3_PUBLIC_MODEL_STAGED=SHA_VERIFIED_DEBUG_ONLY")
        elif args.action == "run":
            if args.artifact is None or args.output is None:
                raise ValueError("--artifact and --output are required for run")
            report = run_synthetic_smoke(
                args.artifact, args.package, args.output, repeats=args.repeats,
                adb=args.adb, serial=args.serial,
            )
            print("WOAH_STAGE3_CPU_SYNTHETIC=" + report["repeat_status"])
            print("PRIVACY_VISUAL_ACCEPTANCE=NOT_PROVEN")
        else:
            cleanup_model(args.package, adb=args.adb, serial=args.serial)
            print("WOAH_STAGE3_PUBLIC_MODEL_CLEANUP=COMPLETE")
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print("WOAH_STAGE3_MODEL_STAGING=FAIL: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
