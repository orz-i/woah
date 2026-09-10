#!/usr/bin/env python3
"""Stage the supported Android YOLO/LiteRT model without re-exporting it.

CI keeps using ``python tools/setup_models.py --android``. The canonical YOLO
model is tracked under models/litert. SAM2 is unavailable and ONNX is obsolete:
neither is required, inspected, copied, downloaded, or exported by this tool.
The separate Gradle face-detector model gate remains mandatory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MODEL_NAMES = (
    "yolo11n-seg-fp16.tflite",
)
ASSET_PATH = Path("mobile/packages/dance_native/android/src/main/assets/models/litert")
YOLO_CONTRACT = Path(
    "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources/"
    "yolo11n-seg-fp16.contract.json"
)


def compute_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def model_identity(path: Path) -> dict[str, str | int]:
    if not path.is_file() or path.stat().st_size < 8:
        raise ValueError(f"Missing or empty required LiteRT model: {path}")
    with path.open("rb") as stream:
        if stream.read(8)[4:8] != b"TFL3":
            raise ValueError(f"Not a LiteRT FlatBuffer (TFL3): {path}")
    return {"bytes": path.stat().st_size, "sha256": compute_sha256(path)}


def stage_android_models(root: Path, source_dir: Path | None = None) -> dict:
    """Validate the supported model before staging; replace it atomically.

    Only MODEL_NAMES are read. Unavailable/obsolete model caches are left alone.
    """
    source = source_dir.resolve() if source_dir is not None else root / "models/litert"
    target = root / ASSET_PATH
    contract = json.loads((root / YOLO_CONTRACT).read_text(encoding="utf-8"))
    expected_yolo = contract.get("expected_sha256")
    if (
        not isinstance(expected_yolo, str)
        or len(expected_yolo) != 64
        or any(c not in "0123456789abcdef" for c in expected_yolo.lower())
    ):
        raise ValueError("Canonical YOLO SHA-256 must be pinned before Android staging")
    expected_yolo = expected_yolo.lower()

    identities = {}
    errors = []
    for name in MODEL_NAMES:
        try:
            identities[name] = model_identity(source / name)
            if name == MODEL_NAMES[0] and identities[name]["sha256"] != expected_yolo:
                raise ValueError(f"Canonical YOLO SHA-256 mismatch: {source / name}")
        except (OSError, ValueError) as exc:
            errors.append(str(exc))
    if errors:
        raise ValueError(
            "Android LiteRT provisioning is incomplete:\n - " + "\n - ".join(errors)
            + "\nRestore the tracked canonical YOLO file in models/litert or supply --source-dir."
            + " SAM2 and ONNX are outside the supported build contract; do not provision them."
        )

    target.mkdir(parents=True, exist_ok=True)
    for name, identity in identities.items():
        destination = target / name
        if destination.is_file() and compute_sha256(destination) == identity["sha256"]:
            continue
        fd, temporary_name = tempfile.mkstemp(prefix=name + ".", suffix=".partial", dir=target)
        temporary = Path(temporary_name)
        os.close(fd)
        try:
            shutil.copyfile(source / name, temporary)
            if model_identity(temporary) != identity:
                raise ValueError(f"Model changed while staging: {name}")
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    # Verify the packaged copies, not only the cache files.
    for name, identity in identities.items():
        if model_identity(target / name) != identity:
            raise ValueError(f"Packaged LiteRT model verification failed: {name}")
    return {"source": str(source), "target": str(target), "models": identities}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", action="store_true", help="Stage the supported canonical YOLO/LiteRT model")
    parser.add_argument("--all", action="store_true", help="Compatibility alias for --android")
    parser.add_argument("--source-dir", type=Path, help="Directory containing the canonical YOLO/LiteRT model")
    args = parser.parse_args(argv)
    try:
        report = stage_android_models(ROOT, args.source_dir)
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print("ANDROID_LITERT_ASSETS=" + json.dumps(report, sort_keys=True))
    print("ANDROID_LITERT_ASSETS=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
