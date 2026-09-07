#!/usr/bin/env python3
"""Provision the ignored YOLO LiteRT model into the iOS plugin resource bundle.

The repository intentionally does not track large model binaries. Android
already copies the same source artifact from models/litert during its build.
This tool gives iOS the same deterministic local-provisioning contract while
also writing the exact source SHA-256 next to the staged copy for diagnostics.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "yolo11n-seg-fp16.contract.json"
)
TARGET_DIR = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "InferenceAssets"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_flatbuffer(path: Path, expected_size: int, magic: str) -> None:
    if not path.is_file():
        raise SystemExit(
            f"Missing LiteRT model: {path}\n"
            "Provision models/litert first (the same source used by Android)."
        )
    size = path.stat().st_size
    if size != expected_size:
        raise SystemExit(
            f"Unexpected model size for {path}: expected {expected_size}, got {size}."
        )
    with path.open("rb") as stream:
        header = stream.read(8)
    if len(header) < 8 or header[4:8] != magic.encode("ascii"):
        raise SystemExit(
            f"Unexpected FlatBuffer identifier for {path}: expected {magic!r}."
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        default=None,
        help="Override the repository-local model source path.",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Verify source/staged model equality without copying.",
    )
    args = parser.parse_args()

    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    source = args.source or (ROOT / contract["source"])
    source = source.resolve()
    expected_size = int(contract["expected_size_bytes"])
    magic = str(contract["flatbuffer_magic"])
    verify_flatbuffer(source, expected_size, magic)
    source_hash = sha256(source)
    expected_hash = contract.get("expected_sha256")
    if expected_hash is not None and source_hash != expected_hash:
        raise SystemExit(
            "Repository-local YOLO model hash violates the tracked contract: "
            f"expected={expected_hash} actual={source_hash}"
        )
    if expected_hash is None:
        print(
            "NOTE: expected_sha256 is not pinned yet; capture the printed hash "
            "and commit it to yolo11n-seg-fp16.contract.json before Phase 1 acceptance."
        )

    target = TARGET_DIR / contract["model"]
    hash_file = TARGET_DIR / f"{contract['model']}.sha256"
    if args.verify_only:
        verify_flatbuffer(target, expected_size, magic)
        target_hash = sha256(target)
        if target_hash != source_hash:
            raise SystemExit(
                f"Staged iOS model hash mismatch: source={source_hash} target={target_hash}"
            )
        if not hash_file.is_file() or hash_file.read_text(encoding="ascii").strip() != source_hash:
            raise SystemExit("Staged model SHA-256 sidecar is missing or stale.")
        print(f"iOS YOLO model verified sha256={source_hash}")
        return

    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    if not target.is_file() or sha256(target) != source_hash:
        temp = target.with_suffix(target.suffix + ".tmp")
        shutil.copyfile(source, temp)
        verify_flatbuffer(temp, expected_size, magic)
        if sha256(temp) != source_hash:
            temp.unlink(missing_ok=True)
            raise SystemExit("Copied model hash does not match source.")
        temp.replace(target)
    hash_file.write_text(source_hash + "\n", encoding="ascii")
    print(f"iOS YOLO model staged: {target} sha256={source_hash}")


if __name__ == "__main__":
    main()
