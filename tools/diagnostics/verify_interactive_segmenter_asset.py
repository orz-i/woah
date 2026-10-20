#!/usr/bin/env python3
"""Offline provenance inspection for an independent prompted segmentation .task.

Never downloads models, opens source videos or decodes any human image.
Checks only archive metadata and the first eight bytes of bundled TFLite
files. The report NEVER authorizes identity attribution or privacy clearing.
The model file itself must remain outside the Woah Git worktree.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
PINNED_URL = (
    "https://storage.googleapis.com/mediapipe-models/"
    "interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task"
)
SOURCE_LIBRARY = "com.google.mediapipe:tasks-vision:1.0.0"
MODEL_BYTES = 30_525_312
MODEL_NAME = "mediapipe_magic_touch_int8_1"
# GCS HEAD for the exact numbered object returned this 32-hex MD5 ETag.
# Independent SHA-256 remains the primary integrity check.
UPSTREAM_GCS_ETAG_MD5 = "eff9593362906a47112886311c6d34c6"
SCOPE = "PUBLIC_MODEL_ASSET_STRUCTURAL_PROVENANCE_ONLY_NO_IMAGE_IO"
MAX_ENTRY_UNCOMPRESSED = 128 * 1024 * 1024
MAX_ARCHIVE_UNCOMPRESSED = 256 * 1024 * 1024


def validate_lock(lock: Any) -> None:
    if not isinstance(lock, dict) or set(lock) != {
        "schema", "model_id", "model_uri", "sha256", "bytes",
        "upstream_gcs_etag_md5", "android_dependency", "expected_input_shape",
        "model_license_scope_review", "release_status",
    }:
        raise ValueError("invalid model lock fields")
    if (
        lock["schema"] != 1 or lock["model_id"] != MODEL_NAME
        or lock["model_uri"] != PINNED_URL or lock["bytes"] != MODEL_BYTES
        or lock["android_dependency"] != SOURCE_LIBRARY
        or lock["upstream_gcs_etag_md5"] != UPSTREAM_GCS_ETAG_MD5
        or lock["expected_input_shape"] != [768, 768, 3]
        or lock["model_license_scope_review"] != "REQUIRES_VERSION_SPECIFIC_REVIEW"
        or lock["release_status"] != "RESEARCH_ONLY_NO_MODEL_BUNDLING"
    ):
        raise ValueError("model lock must pin the exact candidate and deployment ban")
    sha = lock["sha256"]
    if not isinstance(sha, str) or not re.fullmatch("[0-9a-f]{64}", sha):
        raise ValueError("model lock is missing valid SHA-256")


def validate_android_dependency_source(gradle_source: str) -> None:
    if f'implementation("{SOURCE_LIBRARY}")' not in gradle_source:
        raise ValueError("native Gradle dependency does not match immutable asset lock")


def inspect_task_archive(path: Path) -> tuple[int, tuple[str, ...]]:
    """Validate ZIP structure and shallow embedded model signatures.

    Does not return or serialize the embedded model bytes. The ZIP can have
    a small preamble (e.g. a TFLite Tasks bundle); zipfile handles that.
    """
    if not zipfile.is_zipfile(path):
        raise ValueError("MagicTouch .task is not a readable ZIP container")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if not 1 <= len(entries) <= 64:
            raise ValueError("invalid number of model archive entries")
        names = []
        model_count = 0
        total_size = 0
        for item in entries:
            name = item.filename
            parsed = PurePosixPath(name)
            if (
                item.is_dir() or name.startswith("/")
                or "\\" in name or parsed.is_absolute()
                or ".." in parsed.parts or name in names
            ):
                raise ValueError("unsafe or duplicated archive member")
            if item.flag_bits & 1:
                raise ValueError("encrypted archive member unsupported")
            if item.file_size > MAX_ENTRY_UNCOMPRESSED:
                raise ValueError("model archive entry too large")
            total_size += item.file_size
            if total_size > MAX_ARCHIVE_UNCOMPRESSED:
                raise ValueError("model archive exceeds size budget")
            names.append(name)
            if name.endswith(".tflite"):
                with archive.open(item, "r") as source:
                    header = source.read(8)
                if len(header) < 8 or header[4:8] != b"TFL3":
                    raise ValueError("bundled TFLite file lacks TFL3 identifier")
                model_count += 1
        if model_count < 1:
            raise ValueError("no compatible TFLite model found in task archive")
        if archive.testzip() is not None:
            raise ValueError("model archive CRC verification failed")
        return model_count, tuple(names)


def verify(path: Path, lock: dict[str, Any], *, repo_root: Path = ROOT) -> dict:
    validate_lock(lock)
    resolved = path.resolve()
    repo = repo_root.resolve()
    if resolved == repo or repo in resolved.parents:
        raise ValueError("candidate model weights must not be placed in Git tree")
    if resolved.suffix != ".task":
        raise ValueError("expected a .task artifact, not an inferred model")
    if not resolved.is_file():
        raise ValueError("model artifact does not exist")
    if resolved.stat().st_size != lock["bytes"]:
        raise ValueError("model artifact byte size mismatch")
    digest = hashlib.sha256()
    md5 = hashlib.md5()
    with resolved.open("rb") as source:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
            md5.update(block)
    if digest.hexdigest() != lock["sha256"]:
        raise ValueError("model asset SHA-256 mismatch")
    if md5.hexdigest() != lock["upstream_gcs_etag_md5"]:
        raise ValueError("local artifact does not match numbered GCS object's recorded MD5 ETag")
    count, entries = inspect_task_archive(resolved)
    # Model output dimensions and model-vs-identity performance are not
    # established by ZIP and SHA alone. Never upgrade acceptance here.
    return {
        "schema": 1,
        "evidence_scope": SCOPE,
        "candidate_id": MODEL_NAME,
        "sha256_match": True,
        "upstream_gcs_etag_match": True,
        "bytes": lock["bytes"],
        "tflite_entries": count,
        "archive_members_count": len(entries),
        "archive_members": list(entries),
        "artifact_state": "PINNED_STRUCTURAL_INTEGRITY_ONLY",
        "android_api_abi": "NOT_VERIFIED_BY_ARCHIVE",
        "model_license_scope": "REQUIRES_VERSION_SPECIFIC_REVIEW",
        "device_runtime": "NOT_MEASURED",
        "selected_person_occupancy": "NOT_PROVEN",
        "privacy_visual_acceptance": "NOT_PROVEN",
        "pixel_carve_authorization": "NEVER",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--lock", type=Path, default=(
        ROOT / "tools/diagnostics/baselines/mediapipe_magictouch_int8_1.lock.json"
    ))
    args = parser.parse_args()
    try:
        if args.lock.resolve() != (
            ROOT / "tools/diagnostics/baselines/mediapipe_magictouch_int8_1.lock.json"
        ).resolve():
            raise ValueError("only the reviewed in-repository model lock is accepted")
        lock = json.loads(args.lock.read_text(encoding="utf-8"))
        gradle = (ROOT / "mobile/packages/dance_native/android/build.gradle.kts").read_text(encoding="utf-8")
        validate_android_dependency_source(gradle)
        result = verify(args.artifact, lock)
    except (ValueError, OSError, TypeError, KeyError, zipfile.BadZipFile,
            zipfile.LargeZipFile) as exc:
        print("INDEPENDENT_MODEL_ARTIFACT=FAIL: " + str(exc), file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
