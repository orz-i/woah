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


def _instrumentation_component(
    package: str, prefix: list[str], *, run: Callable
) -> tuple[str, str]:
    # Auto-select ONLY from the narrow Woah debug target allowlist. Ambiguous
    # instrumentation targets fail closed; never stage a model for a random app.
    if package != "auto":
        validate_package(package)
    response = _execute(prefix + ["shell", "pm", "list", "instrumentation"], run=run)
    matches: list[tuple[str, str]] = []
    for line in response.stdout.splitlines():
        found = re.fullmatch(
            r"instrumentation:(\S+)\s+\(target=([A-Za-z0-9_.]+)\)",
            line.strip(),
        )
        if found and found.group(2) in ACCEPTED_DEBUG_PACKAGES and (
            package == "auto" or found.group(2) == package
        ) and found.group(1).endswith("/androidx.test.runner.AndroidJUnitRunner"):
            matches.append((found.group(2), found.group(1)))
    if len(matches) != 1:
        raise ValueError("Woah instrumentation target not uniquely installed; check pm list instrumentation")
    return matches[0]


def _instrumentation_result_verdict(stdout: str, returncode: int) -> tuple[bool, str]:
    """Recognize formatted and AndroidJUnitRunner -r raw output without guesswork.

    Android 'am instrument -w -r' may report per-test status codes but omit
    the human-readable 'OK (1 test)' footer. A successful *individual* test
    must still be distinguishable from a skipped/failed/crashed invocation.
    """
    if not isinstance(stdout, str) or not stdout.strip():
        return False, "EMPTY_INSTRUMENTATION_OUTPUT"
    if returncode != 0:
        return False, "ADB_INSTRUMENTATION_NONZERO_EXIT"
    terminal_codes = re.findall(
        r"(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", stdout
    )
    if terminal_codes != ["-1"]:
        return False, "MISSING_SUCCESSFUL_FINAL_INSTRUMENTATION_CODE"
    if re.search(
        r"(?im)^\s*(?:INSTRUMENTATION_FAILED:|FAILURES!!!|"
        r"INSTRUMENTATION_RESULT:\s*shortMsg=)", stdout
    ):
        return False, "ANDROID_RUNNER_REPORTED_FAILURE"
    statuses = [
        int(status) for status in re.findall(
            r"(?m)^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", stdout
        )
    ]
    if any(status < 0 for status in statuses):
        return False, "TEST_FAILED_SKIPPED_OR_ABORTED"
    formatted_one = bool(re.search(r"\bOK\s*\(\s*1\s+tests?\s*\)", stdout))
    raw_one = (
        statuses.count(1) == 1
        and statuses.count(0) == 1
        and len(statuses) == 2
        and "INSTRUMENTATION_STATUS: class=" + TEST_CLASS in stdout
        and "INSTRUMENTATION_STATUS: test=testPinnedMagicTouchOnSyntheticShapesOnly"
        in stdout
    )
    if statuses and not raw_one:
        return False, "PER_TEST_STATUS_COUNT_OR_CLASS_MISMATCH"
    if not (formatted_one or raw_one):
        return False, "NO_VERIFIED_SINGLE_TEST_SUCCESS_RECORD"
    return True, ("ANDROID_RAW_PER_TEST_SUCCESS" if raw_one else "FORMATTED_SINGLE_TEST_SUCCESS")


def _safe_instrumentation_excerpt(stdout: str, *, max_lines: int = 85) -> str:
    """Bounded, targeted AndroidTest failure evidence; never dump device logcat.

    This output originates only from our one synthetic instrumentation class.
    Remove path/URL-like tokens and control chars before showing or saving it.
    """
    if not isinstance(stdout, str):
        return "NO_TEXT_FROM_ADB"
    kept: list[str] = []
    in_stack = False
    for line in stdout.replace("\r", "").splitlines():
        clean = "".join(ch for ch in line if ch == "\t" or ord(ch) >= 32)
        if re.match(
            r"^(?:INSTRUMENTATION_(?:STATUS|RESULT|CODE|FAILED)"
            r"|FAILURES!!!|Process crashed|No tests found|Tests run:|Error:)", clean
        ):
            in_stack = clean.startswith("INSTRUMENTATION_STATUS: stack=")
        elif in_stack and (
            clean.lstrip().startswith(("at ", "Caused by:", "... ", "Suppressed:"))
            or re.match(r"^[A-Za-z_$][\w.$]*Error:", clean)
            or re.match(r"^[A-Za-z_$][\w.$]*Exception:", clean)
        ):
            pass
        else:
            in_stack = False
            continue
        # Instrumentation errors are from synthetic Stage3 only, but even
        # their stack trace should not expose an unrelated file location.
        clean = re.sub(r"(?:content|file|https?)://\S+", "<redacted-uri>", clean)
        clean = re.sub(r"/(?:data|storage|sdcard|mnt)/\S+", "<redacted-device-path>", clean)
        clean = re.sub(r"\b[A-Za-z]:\\[^\s]+", "<redacted-host-path>", clean)
        kept.append(clean[:300])
        if len(kept) >= max_lines:
            kept.append("TRUNCATED_STAGE3_INSTRUMENTATION_LINES")
            break
    return "\n".join(kept) if kept else "NO_PARSEABLE_SYNTHETIC_INSTRUMENTATION_STATUS"


def _instrumentation_failure_evidence(result: subprocess.CompletedProcess) -> str:
    stdout = result.stdout if isinstance(result.stdout, str) else ""
    stderr = result.stderr if isinstance(result.stderr, str) else ""
    # Only the targeted synthetic test's stdout/stderr, never device logcat.
    # Prefix stderr lines so the bounded sanitizer retains ADB failure text.
    return stdout + "\n" + "\n".join(
        "Error: " + line for line in stderr.splitlines()[:12]
    )


def _write_stage3_instrument_failure(
    dest: Path, *, attempt: int, repeats: int, package: str,
    reason: str, result: subprocess.CompletedProcess
) -> Path:
    failure_path = dest.with_name(dest.stem + ".failure.txt")
    lines = [
        "WOAH_STAGE3_SYNTHETIC_INSTRUMENTATION=FAIL",
        f"run={attempt}/{repeats}",
        f"target_package={package}",
        f"reason={reason}",
        f"adb_exit={result.returncode}",
        "privacy_visual_acceptance=NOT_PROVEN",
        "pixel_carve_authorization=NEVER",
        "source_video_used=false",
        "--- bounded targeted instrumentation details ---",
        _safe_instrumentation_excerpt(_instrumentation_failure_evidence(result)),
    ]
    failure_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = failure_path.with_name(failure_path.name + ".stage3tmp")
    temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
    temporary.replace(failure_path)
    return failure_path


def run_synthetic_smoke(
    path: Path, package: str, output: Path, *, repeats: int = 3,
    adb: str = "adb", serial: str | None = None,
    run: Callable = subprocess.run,
    verifier: Callable[[Path], str] = check_public_asset,
    token_generator: Callable[[int], str] = secrets.token_hex,
) -> dict:
    if package != "auto":
        validate_package(package)
    if not 2 <= repeats <= 5:
        raise ValueError("stage3 CPU smoke requires 2–5 independent repeats")
    dest = output.resolve()
    if dest == ROOT or ROOT in dest.parents:
        raise ValueError("stage3 numeric report must be written outside Git tree")
    prefix = adb_prefix(adb, serial)
    resolved_package, component = _instrumentation_component(package, prefix, run=run)
    run_token = token_generator(8)
    if not re.fullmatch(r"[0-9a-f]{16}", run_token):
        raise ValueError("synthetic test run token malformed")
    stage_model(path, resolved_package, adb=adb, serial=serial, run=run, verifier=verifier)
    try:
        for attempt in range(1, repeats + 1):
            command = prefix + [
                "shell", "am", "instrument", "-w", "-r",
                "-e", "class", TEST_CLASS,
                "-e", ENABLE_ARG, "1",
                "-e", TOKEN_ARG, run_token,
                component,
            ]
            # Instrumentation sometimes returns shell exit=0 *even when a
            # specific test has failed*. Inspect its per-test status separately.
            result = run(
                command, capture_output=True, text=True, timeout=360,
                check=False,
            )
            accepted, reason = _instrumentation_result_verdict(
                result.stdout, result.returncode
            )
            if not accepted:
                diagnostic = _write_stage3_instrument_failure(
                    dest, attempt=attempt, repeats=repeats,
                    package=resolved_package, reason=reason, result=result,
                )
                raise RuntimeError(
                    "Stage 3 instrumentation did not pass exactly one test "
                    f"(run {attempt}/{repeats}; {reason}). "
                    f"Synthetic test diagnostic: {diagnostic}\n"
                    + _safe_instrumentation_excerpt(
                        _instrumentation_failure_evidence(result), max_lines=25
                    )
                )
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
        cleanup_model(resolved_package, adb=adb, serial=serial, run=run)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stage", "cleanup", "run"))
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--package", default="auto", choices=sorted(ACCEPTED_DEBUG_PACKAGES | {"auto"}),
                        help="Auto-detect a unique Woah instrumentation target for run")
    parser.add_argument("--output", type=Path, help="Required for run; numeric-only JSON outside Git")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    try:
        if args.action == "stage":
            if args.artifact is None or args.package == "auto":
                raise ValueError("--artifact and an explicit --package are required for stage")
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
            if args.package == "auto":
                raise ValueError("cleanup requires an explicit --package")
            cleanup_model(args.package, adb=args.adb, serial=args.serial)
            print("WOAH_STAGE3_PUBLIC_MODEL_CLEANUP=COMPLETE")
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print("WOAH_STAGE3_MODEL_STAGING=FAIL: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
