#!/usr/bin/env python3
"""Optionally stage a verified crop-clarity LiteRT model into the iOS bundle.

No model is required for normal builds. A prototype is copied only when the
repository-local model and the SHA contract emitted by
``tools/litert/verify_crop_clarity_model.py`` both exist and agree.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = ROOT / "models/litert/crop-clarity-span-x2.tflite"
DEFAULT_CONTRACT = ROOT / "models/litert/crop-clarity-span-x2.contract.json"
TARGET_DIR = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "InferenceAssets"
)
TARGET = TARGET_DIR / "crop-clarity-span-x2.tflite"
TARGET_HASH = TARGET_DIR / "crop-clarity-span-x2.tflite.sha256"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_flatbuffer(path: Path) -> None:
    if not path.is_file() or path.stat().st_size < 8:
        raise ValueError(f"missing/empty LiteRT model: {path}")
    with path.open("rb") as stream:
        if stream.read(8)[4:8] != b"TFL3":
            raise ValueError(f"not a TFL3 FlatBuffer: {path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    if not args.source.exists() and not args.contract.exists():
        if not args.verify_only:
            TARGET.unlink(missing_ok=True)
            TARGET_HASH.unlink(missing_ok=True)
        print("IOS_CROP_CLARITY_MODEL=SKIP:not_provisioned")
        return 0

    try:
        validate_flatbuffer(args.source)
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        expected = contract.get("sha256")
        actual = sha256(args.source)
        if not isinstance(expected, str) or expected.lower() != actual:
            raise ValueError(f"SHA contract mismatch expected={expected} actual={actual}")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        if not args.verify_only:
            TARGET.unlink(missing_ok=True)
            TARGET_HASH.unlink(missing_ok=True)
        print(f"IOS_CROP_CLARITY_MODEL=SKIP:{exc}")
        return 0

    if args.verify_only:
        validate_flatbuffer(TARGET)
        if sha256(TARGET) != actual:
            raise SystemExit("Staged iOS crop-clarity model differs from verified source")
        if not TARGET_HASH.is_file() or TARGET_HASH.read_text(encoding="ascii").strip() != actual:
            raise SystemExit("Staged iOS crop-clarity SHA sidecar is missing or stale")
        print(f"IOS_CROP_CLARITY_MODEL=PASS sha256={actual}")
        return 0

    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    if not TARGET.is_file() or sha256(TARGET) != actual:
        temp = TARGET.with_suffix(TARGET.suffix + ".tmp")
        shutil.copyfile(args.source, temp)
        validate_flatbuffer(temp)
        if sha256(temp) != actual:
            temp.unlink(missing_ok=True)
            raise SystemExit("Copied crop-clarity model hash changed during staging")
        temp.replace(TARGET)
    TARGET_HASH.write_text(actual + "\n", encoding="ascii")
    print(f"IOS_CROP_CLARITY_MODEL=PASS path={TARGET} sha256={actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
