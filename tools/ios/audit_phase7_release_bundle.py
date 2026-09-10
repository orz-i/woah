#!/usr/bin/env python3
"""Audit a built Woah iOS Release app and emit reproducible release identity."""

from __future__ import annotations

import argparse
import hashlib
import json
import plistlib
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP_ROOT = ROOT / "mobile/app"
CONTRACT_PATH = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "yolo11n-seg-fp16.contract.json"
)
EXPECTED_BUNDLE_ID = "art.gaoge.dance"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def model_core(path: Path) -> bytes:
    data = path.read_bytes()
    search_start = max(8, len(data) - 64 * 1024)
    offset = data.find(b"PK\x03\x04", search_start)
    if offset < 0:
        raise SystemExit(f"Packaged model has no appended metadata ZIP boundary: {path}")
    return data[:offset]


def git_head() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return completed.stdout.strip()


def source_version() -> tuple[str, str]:
    pubspec = (APP_ROOT / "pubspec.yaml").read_text(encoding="utf-8")
    match = re.search(r"(?m)^version:\s*([^+\s]+)\+(\d+)\s*$", pubspec)
    if match is None:
        raise SystemExit("mobile/app/pubspec.yaml does not contain a release version+build")
    return match.group(1), match.group(2)


def relative_files(root: Path, name: str) -> list[str]:
    return sorted(path.relative_to(root).as_posix() for path in root.rglob(name))


def truthy_plist_value(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    app = args.app.resolve()
    if not app.is_dir():
        raise SystemExit(f"Release app bundle does not exist: {app}")
    info_path = app / "Info.plist"
    if not info_path.is_file():
        raise SystemExit(f"Release app Info.plist is missing: {info_path}")
    with info_path.open("rb") as stream:
        info = plistlib.load(stream)

    source_name, source_build = source_version()
    bundle_id = str(info.get("CFBundleIdentifier", ""))
    bundle_name = str(info.get("CFBundleShortVersionString", ""))
    bundle_build = str(info.get("CFBundleVersion", ""))
    smoke_hooks_enabled = truthy_plist_value(info.get("WoahEnableSmokeHooks"))
    if bundle_id != EXPECTED_BUNDLE_ID:
        raise SystemExit(f"Unexpected Release bundle ID: {bundle_id!r}")
    if bundle_name != source_name or bundle_build != source_build:
        raise SystemExit(
            "Built Release version does not match pubspec.yaml: "
            f"built={bundle_name}+{bundle_build} source={source_name}+{source_build}"
        )
    if smoke_hooks_enabled:
        raise SystemExit("Production Release bundle must not enable iOS smoke MethodChannel hooks")

    executable_name = str(info.get("CFBundleExecutable", "Runner"))
    executable = app / executable_name
    if not executable.is_file():
        raise SystemExit(f"Release executable is missing: {executable}")

    lock = APP_ROOT / "pubspec.lock"
    if not lock.is_file():
        raise SystemExit("Tracked mobile/app/pubspec.lock is missing from the Release checkout")

    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    expected_model_hash = contract.get("expected_sha256")
    expected_model_size = int(contract["expected_size_bytes"])
    model_name = str(contract["model"])
    model_candidates = sorted(app.rglob(model_name))
    if len(model_candidates) != 1:
        raise SystemExit(
            f"Expected exactly one packaged {model_name}, found {len(model_candidates)}: "
            + ", ".join(path.relative_to(app).as_posix() for path in model_candidates)
        )
    model = model_candidates[0]
    if model.stat().st_size != expected_model_size:
        raise SystemExit(
            f"Packaged model size drifted: expected={expected_model_size} actual={model.stat().st_size}"
        )
    packaged_model_hash = sha256(model)
    if not isinstance(expected_model_hash, str) or len(expected_model_hash) != 64:
        raise SystemExit("Phase 7 Release audit requires expected_sha256 to be pinned in the model contract")
    if packaged_model_hash != expected_model_hash:
        raise SystemExit(
            f"Packaged model hash drifted: expected={expected_model_hash} actual={packaged_model_hash}"
        )
    reproducibility = contract.get("reproducibility") or {}
    packaged_core = model_core(model)
    packaged_core_hash = sha256_bytes(packaged_core)
    expected_core_size = int(reproducibility.get("expected_core_size_bytes", 0))
    expected_core_hash = str(reproducibility.get("expected_core_sha256", ""))
    if len(packaged_core) != expected_core_size or packaged_core_hash != expected_core_hash:
        raise SystemExit(
            "Packaged model FlatBuffer core drifted: "
            f"expected_size={expected_core_size} actual_size={len(packaged_core)} "
            f"expected_sha256={expected_core_hash} actual_sha256={packaged_core_hash}"
        )

    privacy_manifests = relative_files(app, "PrivacyInfo.xcprivacy")
    if not privacy_manifests:
        raise SystemExit("Release app bundle contains no PrivacyInfo.xcprivacy")
    frameworks = sorted(
        path.relative_to(app).as_posix()
        for path in app.rglob("*.framework")
        if path.is_dir()
    )

    source_commit = git_head()
    info_commit = str(info.get("WoahGitCommit", "")).strip()
    if info_commit != source_commit:
        raise SystemExit(
            "Built WoahGitCommit does not match the audited source commit: "
            f"plist={info_commit!r} source={source_commit!r}"
        )

    report = {
        "schema_version": 1,
        "phase": 7,
        "evidence_class": "apple-only-release-build-not-physical-device-acceptance",
        "git_commit": source_commit,
        "bundle": {
            "identifier": bundle_id,
            "version": bundle_name,
            "build": bundle_build,
            "minimum_os_version": info.get("MinimumOSVersion"),
            "woah_git_commit": info_commit,
            "smoke_hooks_enabled": smoke_hooks_enabled,
            "executable": executable_name,
            "executable_sha256": sha256(executable),
        },
        "dependencies": {
            "pubspec_lock_sha256": sha256(lock),
        },
        "model": {
            "contract_path": CONTRACT_PATH.relative_to(ROOT).as_posix(),
            "contract_sha256": sha256(CONTRACT_PATH),
            "file": model.relative_to(app).as_posix(),
            "expected_sha256": expected_model_hash,
            "packaged_sha256": packaged_model_hash,
            "size_bytes": model.stat().st_size,
            "expected_core_sha256": expected_core_hash,
            "packaged_core_sha256": packaged_core_hash,
            "core_size_bytes": len(packaged_core),
            "source_checkpoint": contract.get("source_checkpoint"),
            "exporter": reproducibility.get("exporter"),
            "export_environment": reproducibility.get("environment"),
        },
        "privacy_manifests": privacy_manifests,
        "frameworks": frameworks,
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"IOS_PHASE7_RELEASE_AUDIT=PASS output={output}")
    print(f"IOS_PHASE7_RELEASE_COMMIT={source_commit}")
    print(f"IOS_PHASE7_RELEASE_MODEL_SHA256={packaged_model_hash}")
    print(f"IOS_PHASE7_RELEASE_MODEL_CORE_SHA256={packaged_core_hash}")
    print(f"IOS_PHASE7_RELEASE_DEPENDENCY_SHA256={report['dependencies']['pubspec_lock_sha256']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
