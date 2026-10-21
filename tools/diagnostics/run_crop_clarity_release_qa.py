#!/usr/bin/env python3
"""One-command Windows Release clarity A/B preparation and scalar capture.

Actions:
  build : build and SHA-pin signed OFF and ON Release APKs to an external folder.
  run   : verify the pinned pair, explicitly approved adb install -r in turn,
          collect 3 scalar exports for each and compare timings.
  all   : build then run (does not automate the six Woah UI exports).

No raw device Logcat capture, logcat clearing, app uninstallation, media read,
privacy-mask changes, model download, source-frame data or remote upload.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.diagnostics.capture_crop_clarity_release_perf import (  # noqa: E402
    adb_prefix, capture_device, ensure_output_outside_checkout,
)
from tools.diagnostics.crop_clarity_release_perf import (  # noqa: E402
    compare, read_records,
)
from tools.diagnostics.release_apk_preflight import (  # noqa: E402
    EXPECTED_APP_ID, inspect_release_apk,
)

APP_DIR = ROOT / "mobile/app"
GENERATED_APK = APP_DIR / "build/app/outputs/flutter-apk/app-release.apk"
MANIFEST = "release-qa-build.json"
OFF_APK = "release-clarity-off.apk"
ON_APK = "release-clarity-on.apk"
COMPARISON = "crop-clarity-release-comparison.json"
MAX_PAIRED_RUNS = 10
IDENTITY_KEYS = frozenset({"app_id", "version_code", "version_name", "signer_sha256"})
QA_SIGNING_DESCRIPTION = "QA_DEBUG_CERT_NOT_FOR_DISTRIBUTION"
RELEASE_SIGNING_DESCRIPTION = "EXPLICIT_RELEASE_CERT"


def _inspected_apk(
    apk: Path, *, qa_debug_signing: bool,
    inspector: Callable[[Path], dict],
) -> dict[str, Any]:
    # Source-inspection for the shipped helper enforces the exact signer class.
    # Injected inspectors exist only for synthetic/mock unit tests.
    if inspector is inspect_release_apk:
        result = inspector(apk, qa_debug_signing=qa_debug_signing)
    else:
        result = inspector(apk)
    return _validate_identity(result)


def _qa_debug_signing_environment(
    current: dict[str, str], *, home: Path | None = None
) -> dict[str, str]:
    """Use the workstation's EXISTING debug key for a QA-only Release build.

    Never generates, reads, copies or exports a signing key. Explicit opt-in
    only; the production Release Gradle signing guard stays unchanged.
    """
    location = Path(current.get("ANDROID_USER_HOME", "")).expanduser() if (
        current.get("ANDROID_USER_HOME", "").strip()
    ) else ((Path.home() if home is None else home) / ".android")
    keystore = location / "debug.keystore"
    if not keystore.is_file() or keystore.stat().st_size < 1:
        raise ValueError(
            "QA debug-signing keystore absent; first build an ordinary "
            "'flutter build apk --debug' on this machine. "
            "No new key has been created and no app data was touched"
        )
    if ROOT.resolve() == keystore.resolve() or ROOT.resolve() in keystore.resolve().parents:
        raise ValueError("QA signing key must remain outside the source checkout")
    updated = current.copy()
    updated.update({
        "WOAH_KEYSTORE_PATH": str(keystore.resolve()),
        "WOAH_KEYSTORE_PASSWORD": "android",  # Standard Android SDK debug keystore
        "WOAH_KEY_ALIAS": "androiddebugkey",
        "WOAH_KEY_PASSWORD": "android",
    })
    return updated


def _validate_identity(obj: object) -> dict[str, Any]:
    if (
        not isinstance(obj, dict) or set(obj) != IDENTITY_KEYS
        or obj.get("app_id") != EXPECTED_APP_ID
        or type(obj.get("version_code")) is not int
        or obj["version_code"] <= 0
        or type(obj.get("version_name")) is not str
        or not 1 <= len(obj["version_name"]) <= 64
        or not isinstance(obj.get("signer_sha256"), str)
        or not re.fullmatch(r"[0-9a-f]{64}", obj["signer_sha256"])
    ):
        raise ValueError("invalid signed APK package/version/certificate identity")
    return obj



def _exec(
    command: list[str], *, cwd: Path | None = None,
    env: dict[str, str] | None = None, runner: Callable = subprocess.run,
    timeout: int | None = None, stream_output: bool = False,
) -> subprocess.CompletedProcess:
    result = runner(
        command, cwd=str(cwd) if cwd is not None else None,
        env=env, timeout=timeout,
        check=False, capture_output=not stream_output, text=True,
    )
    if result.returncode:
        # Show Flutter build errors live, rather than hiding terminal progress.
        # For ADB installs, print only Android's standard failure category,
        # not arbitrary stdout that might contain local/private paths.
        detail = ""
        if "install" in command and not stream_output:
            raw = (result.stdout or "") + " " + (result.stderr or "")
            found = re.search(r"INSTALL_FAILED_[A-Z0-9_]+", raw)
            if found:
                detail = " (" + found.group(0) + ")"
        raise RuntimeError(
            f"{Path(command[0]).name} step failed (exit {result.returncode}){detail}; "
            "check the displayed build output or the Android signing configuration"
        )
    return result


def _safe_untracked_evidence(path: str) -> bool:
    """Untracked QA data outside build inputs is not source-code dirtiness.

    Only non-executable evidence in dedicated directories, narrowly named
    diagnosis ZIPs or exactly the standard Flutter debug-only manifests can
    be ignored. Never ignore untracked Dart/Kotlin/Gradle/Python, model
    assets, release/main manifests, SDK configs or unknown source resources.
    """
    normalized = path.replace("\\", "/")
    if normalized.startswith("/") or ".." in normalized.split("/"):
        return False
    # These two Flutter scaffold manifests belong to the *debug-only* Android
    # source set, never the Release build we benchmark. Standard Git output
    # normally collapses each parent to `?? src/debug/`; this verifier uses
    # `git status -z --untracked-files=all` to inspect the actual filenames.
    # Deliberately do NOT allow other files in those directories, including
    # Kotlin/Java, changed Gradle logic, manifests in src/main or src/release.
    if normalized in {
        "mobile/app/android/app/src/debug/AndroidManifest.xml",
        "mobile/packages/dance_native/example/android/app/src/debug/AndroidManifest.xml",
    }:
        return True
    # Flutter A/B QA archives stored beside the app project are already
    # protected-media evidence, not code or Flutter-declared app assets.
    # Keep this to known file names, never an entire mobile/app directory.
    if re.fullmatch(
        r"mobile/app/crop_clarity_ab(?:_raw)?(?:\(\d{1,3}\))?\.zip",
        normalized,
    ):
        return True
    if any(normalized.startswith(directory) for directory in (
        "logs/", "output/", "input/", "uploads/", "testdata/",
        "data/tasks/", "docs/session/", "artifacts/", "qa-output/",
    )):
        return not normalized.lower().endswith((
            ".py", ".dart", ".kt", ".java", ".gradle", ".kts", ".so",
            ".tflite", ".task", ".onnx", ".sh", ".ps1", ".exe",
        ))
    if "/" not in normalized:
        return bool(re.fullmatch(
            r"(?:woah_diag_|crop_clarity_ab|stage3-|release_(?:off|on))"
            r"[A-Za-z0-9_() .-]*\.(?:zip|log|json|jsonl|txt)", normalized,
            flags=re.I,
        ))
    return False


def git_identity(*, runner: Callable = subprocess.run) -> tuple[str, str]:
    head = _exec(["git", "rev-parse", "HEAD"], cwd=ROOT, runner=runner).stdout.strip()
    branch = _exec(["git", "branch", "--show-current"],
                   cwd=ROOT, runner=runner).stdout.strip()
    # -z disables Git's path quoting on Windows (including spaces and Unicode)
    # and preserves literal filenames without shell parsing.
    raw_status = _exec(["git", "status", "--porcelain", "-z", "--untracked-files=all"],
                       cwd=ROOT, runner=runner).stdout
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Release build cannot be tied to a valid full Git SHA")
    if not branch:
        raise ValueError("Release QA requires a named Git branch, not detached HEAD")
    tracked = 0
    unknown = 0
    allowed = 0
    for line in (raw_status.split("\0") if "\0" in raw_status else raw_status.splitlines()):
        if line.startswith("?? "):
            if _safe_untracked_evidence(line[3:]):
                allowed += 1
            else:
                unknown += 1
        elif line.strip():
            tracked += 1
    if tracked or unknown:
        raise ValueError(
            "Release QA source provenance blocked: "
            f"{tracked} tracked changes, {unknown} unrecognized untracked files "
            f"({allowed} isolated QA artifacts tolerated). "
            "Run git status --short --untracked-files=all for exact paths; "
            "nothing was deleted or reset"
        )
    if allowed:
        print(f"WOAH_RELEASE_QA=IGNORED_{allowed}_ISOLATED_UNTRACKED_ARTIFACTS", flush=True)
    return head, branch


def selected_adb_serial(
    *, adb: str, requested: str | None,
    runner: Callable = subprocess.run,
) -> str:
    """Pin the ADB session to one stable device without storing its ID."""
    raw = _exec(adb_prefix(adb, requested) + ["get-serialno"],
                runner=runner, timeout=20).stdout.strip()
    if (
        not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", raw)
        or raw.lower() in {"unknown", "offline", "unauthorized"}
        or (requested is not None and raw != requested)
    ):
        raise ValueError("ADB did not confirm the selected Android device identity")
    return raw


def _hash_file(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def _external_dir(path: Path, *, new: bool) -> Path:
    result = path.resolve()
    if result == ROOT or ROOT in result.parents:
        raise ValueError("Release test artifacts must stay outside the Git checkout")
    if new and result.exists() and any(result.iterdir()):
        raise ValueError("Release QA directory already contains files; choose a new empty folder")
    return result


def _save_atomic_json(path: Path, data: dict[str, Any]) -> None:
    ensure_output_outside_checkout(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".partial")
    temp.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def build_pair(
    output_dir: Path, *, flutter: str = "flutter",
    runner: Callable = subprocess.run,
    inspector: Callable[[Path], dict] = inspect_release_apk,
    qa_debug_signing: bool = False,
) -> dict[str, Any]:
    output = _external_dir(output_dir, new=True)
    head, branch = git_identity(runner=runner)
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    if qa_debug_signing:
        env = _qa_debug_signing_environment(env)
        print("WOAH_RELEASE_QA=ANDROID_DEBUG_CERT_FOR_LOCAL_PERF_ONLY_NOT_PUBLISHABLE", flush=True)
    env["GIT_COMMIT_SHA"] = head  # identical native BuildConfig for both variants
    _exec([flutter, "pub", "get"], cwd=APP_DIR, env=env,
          runner=runner, stream_output=True)

    artifacts: dict[str, dict[str, Any]] = {}
    for mode, args, filename in (
        ("off", ["--dart-define=WOAH_BENCHMARK_CROP_CLARITY_OFF=true"], OFF_APK),
        ("on", [], ON_APK),
    ):
        # Remove only Flutter's known generated release APK output, never a
        # user file or the installed application; rejects stale build reuse.
        if GENERATED_APK.exists():
            GENERATED_APK.unlink()
        _exec([flutter, "build", "apk", "--release", *args],
              cwd=APP_DIR, env=env, runner=runner, stream_output=True)
        if not GENERATED_APK.is_file() or GENERATED_APK.stat().st_size < 1024:
            raise ValueError("Flutter did not produce a usable new Release APK")
        target = output / filename
        shutil.copy2(GENERATED_APK, target)
        # Both installed artifacts must be real signed Release APKs, with
        # the *same* signing certificate, package ID and version. SHA-256 of
        # file bytes alone cannot establish those properties.
        identity = _inspected_apk(target, qa_debug_signing=qa_debug_signing, inspector=inspector)
        artifacts[mode] = {
            "filename": filename,
            "bytes": target.stat().st_size,
            "sha256": _hash_file(target),
            "apk_identity": identity,
        }
        print(f"WOAH_RELEASE_{mode.upper()}_BUILT=PINNED_SHA256", flush=True)

    if artifacts["off"]["sha256"] == artifacts["on"]["sha256"]:
        raise ValueError("OFF and ON APK hashes are identical; build-time mode not differentiated")
    if artifacts["off"]["apk_identity"] != artifacts["on"]["apk_identity"]:
        raise ValueError("OFF and ON APKs have different signing certificates or package versions")
    updated_head, updated_branch = git_identity(runner=runner)
    if (head, branch) != (updated_head, updated_branch):
        raise ValueError("Git checkout changed during Release pair build")

    manifest: dict[str, Any] = {
        "schema": 2,
        "purpose": "woah_two_variants_release_clarity_synthetic_setup_not_privacy_proof",
        "git_commit": head,
        "git_branch": branch,
        "build_mode": "release",
        "installed_app_id_expected": "art.gaoge.dance",
        "clarity_off_dart_define": True,
        "qa_debug_signing": qa_debug_signing,
        "signing_scope": (
            QA_SIGNING_DESCRIPTION if qa_debug_signing else RELEASE_SIGNING_DESCRIPTION
        ),
        "privacy_visual_acceptance": "NOT_PROVEN",
        "release_acceptance": "NOT_PROVEN",
        "apks": artifacts,
    }
    _save_atomic_json(output / MANIFEST, manifest)
    print("WOAH_RELEASE_APK_PAIR=BUILT_SIGNING_AND_ON_DEVICE_BEHAVIOR_NOT_YET_VERIFIED",
          flush=True)
    return manifest


def verify_pair(
    output_dir: Path, *, runner: Callable = subprocess.run,
    inspector: Callable[[Path], dict] = inspect_release_apk,
    qa_debug_signing: bool = False,
) -> dict[str, Any]:
    output = _external_dir(output_dir, new=False)
    path = output / MANIFEST
    if not path.is_file():
        raise ValueError("Release APK pair manifest missing; run build first")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    head, branch = git_identity(runner=runner)
    if (
        not isinstance(manifest, dict)
        or set(manifest) != {
            "schema", "purpose", "git_commit", "git_branch", "build_mode",
            "installed_app_id_expected", "clarity_off_dart_define",
            "qa_debug_signing", "signing_scope",
            "privacy_visual_acceptance", "release_acceptance", "apks",
        }
        or type(manifest["schema"]) is not int
        or manifest["schema"] != 2
        or manifest["purpose"] !=
            "woah_two_variants_release_clarity_synthetic_setup_not_privacy_proof"
        or manifest["git_commit"] != head or manifest["git_branch"] != branch
        or manifest["build_mode"] != "release"
        or manifest["installed_app_id_expected"] != "art.gaoge.dance"
        or manifest["clarity_off_dart_define"] is not True
        or manifest["qa_debug_signing"] is not qa_debug_signing
        or manifest["signing_scope"] != (
            QA_SIGNING_DESCRIPTION if qa_debug_signing else RELEASE_SIGNING_DESCRIPTION
        )
        or manifest["privacy_visual_acceptance"] != "NOT_PROVEN"
        or manifest["release_acceptance"] != "NOT_PROVEN"
        or not isinstance(manifest["apks"], dict)
        or set(manifest["apks"]) != {"off", "on"}
    ):
        raise ValueError("Release artifact provenance manifest invalid or wrong Git HEAD")
    for mode, name in (("off", OFF_APK), ("on", ON_APK)):
        obj = manifest["apks"][mode]
        if (
            not isinstance(obj, dict) or set(obj) != {"filename", "bytes", "sha256", "apk_identity"}
            or obj["filename"] != name or type(obj["bytes"]) is not int
            or obj["bytes"] < 1024 or not isinstance(obj["sha256"], str)
            or not re.fullmatch(r"[a-f0-9]{64}", obj["sha256"])
        ):
            raise ValueError(f"{mode}: invalid pinned APK manifest")
        apk = output / name
        if not apk.is_file() or apk.stat().st_size != obj["bytes"]:
            raise ValueError(f"{mode}: Release APK missing or size mismatch")
        if _hash_file(apk) != obj["sha256"]:
            raise ValueError(f"{mode}: Release APK SHA-256 mismatch")
        if _validate_identity(obj["apk_identity"]) != _inspected_apk(
            apk, qa_debug_signing=qa_debug_signing, inspector=inspector
        ):
            raise ValueError(f"{mode}: verified APK signing/package identity changed")
    if manifest["apks"]["off"]["sha256"] == manifest["apks"]["on"]["sha256"]:
        raise ValueError("OFF and ON APKs have identical hashes")
    if manifest["apks"]["off"]["apk_identity"] != manifest["apks"]["on"]["apk_identity"]:
        raise ValueError("OFF/ON signed APK certificate/package/version mismatch")
    return manifest


def run_pair(
    output_dir: Path, *, scenario: str, acknowledge_install: bool,
    repeats: int = 3, timeout_minutes: int = 90,
    adb: str = "adb", serial: str | None = None,
    max_slowdown_percent: float = 10.0,
    runner: Callable = subprocess.run,
    collect: Callable = capture_device,
    inspector: Callable[[Path], dict] = inspect_release_apk,
    qa_debug_signing: bool = False,
) -> dict[str, Any]:
    if acknowledge_install is not True:
        raise ValueError("set --acknowledge-test-install to explicitly allow replacing the installed Woah APK")
    if not 2 <= repeats <= MAX_PAIRED_RUNS:
        raise ValueError(f"paired Release QA requires 2-{MAX_PAIRED_RUNS} exports per variant")
    if not scenario or not scenario.strip() or len(scenario) > 100:
        raise ValueError("a short, non-identifying scenario label is required")
    if not 5 <= timeout_minutes <= 240:
        raise ValueError("capture timeout must be 5-240 minutes")
    if not math.isfinite(max_slowdown_percent) or not 0 <= max_slowdown_percent <= 100:
        raise ValueError("invalid provisional slowdown budget")
    output = _external_dir(output_dir, new=False)
    manifest = verify_pair(
        output, runner=runner, inspector=inspector,
        qa_debug_signing=qa_debug_signing,
    )
    # Resolve once and use -s for every further ADB/collector invocation.
    # A second phone of the same model cannot silently replace the first.
    pinned_serial = selected_adb_serial(adb=adb, requested=serial, runner=runner)
    prefix = adb_prefix(adb, pinned_serial)
    for mode, filename in (("off", OFF_APK), ("on", ON_APK)):
        expected_log = output / f"release_{mode}.log"
        ensure_output_outside_checkout(expected_log)
    result_path = output / COMPARISON
    ensure_output_outside_checkout(result_path)

    if qa_debug_signing:
        print("WOAH_RELEASE_QA=DEBUG_CERT_SIGNED_RELEASE_QA_NOT_FOR_DISTRIBUTION", flush=True)
    print("WOAH_RELEASE_QA=REPLACES_THE_INSTALLED_WOAH_APP_IN_TURN", flush=True)
    print("WOAH_RELEASE_QA=NEVER_UNINSTALLS_OR_CLEARS_APPLICATION_DATA", flush=True)
    for mode, filename in (("off", OFF_APK), ("on", ON_APK)):
        if selected_adb_serial(adb=adb, requested=pinned_serial, runner=runner) != pinned_serial:
            raise ValueError("Android device changed during paired Release QA")
        print(f"WOAH_RELEASE_INSTALLING={mode.upper()}_TEST_APK_REQUIRES_VALID_SIGNING", flush=True)
        _exec(prefix + ["install", "-r", str(output / filename)],
              runner=runner, timeout=240)
        print(
            f"WOAH_RELEASE_CAPTURE={mode.upper()}: "
            f"finish {repeats} matching exports after ARMED appears",
            flush=True,
        )
        collect(
            output / f"release_{mode}.log",
            mode=mode, repeats=repeats, timeout_minutes=timeout_minutes,
            adb=adb, serial=pinned_serial, expected_commit=manifest["git_commit"],
        )

    result = compare(
        read_records(output / "release_off.log"),
        read_records(output / "release_on.log"),
        scenario=scenario, repeats_required=repeats,
        max_slowdown_percent=max_slowdown_percent,
    )
    # Device measurements remain legitimate local QA of the Release codepath,
    # but debug-key signed APKs are categorically not publishing evidence.
    result["apk_signing_scope"] = manifest["signing_scope"]
    result["distribution_acceptance"] = "NOT_PROVEN"
    _save_atomic_json(result_path, result)
    print(f"WOAH_RELEASE_COMPARE=REVIEW_REQUIRED; output={result_path}", flush=True)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", choices=("build", "run", "all"), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--scenario", default="same-consented-video")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--timeout-minutes", type=int, default=90)
    parser.add_argument("--max-slowdown-percent", type=float, default=10.0)
    parser.add_argument("--acknowledge-test-install", action="store_true",
                        help="Allow adb install -r of both signed Release variants")
    parser.add_argument("--qa-debug-signing", action="store_true",
                        help="Opt-in: sign Release-code test APKs with existing local Android Debug key; NEVER publish")
    parser.add_argument("--flutter", default="flutter")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    try:
        # Fail before an expensive Release build if the eventual paired run
        # was not explicitly authorized to replace the installed Woah APK.
        if args.action in ("run", "all") and not args.acknowledge_test_install:
            raise ValueError("--acknowledge-test-install is required before build/install")
        if args.action in ("build", "all"):
            build_pair(
                args.output_dir, flutter=args.flutter,
                qa_debug_signing=args.qa_debug_signing,
            )
        if args.action in ("run", "all"):
            run_pair(
                args.output_dir, scenario=args.scenario,
                acknowledge_install=args.acknowledge_test_install,
                repeats=args.repeats, timeout_minutes=args.timeout_minutes,
                max_slowdown_percent=args.max_slowdown_percent,
                adb=args.adb, serial=args.serial,
                qa_debug_signing=args.qa_debug_signing,
            )
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError,
            subprocess.TimeoutExpired) as exc:
        print("WOAH_RELEASE_QA=FAIL: " + str(exc), file=sys.stderr)
        print("RELEASE_AND_PRIVACY_ACCEPTANCE=NOT_PROVEN", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("WOAH_RELEASE_QA=CANCELLED_NO_NEW_ACCEPTANCE", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
