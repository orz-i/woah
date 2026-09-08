#!/usr/bin/env python3
"""Generate/provision the Phase 1 YOLO LiteRT model on a clean CI host.

The large model binaries are intentionally ignored by Git. This helper first
reuses an existing canonical model when available. On a clean cloud runner it
downloads the pinned Ultralytics YOLO11n segmentation checkpoint through the
locked Python environment, exports the exact FP16 TFLite recipe, and then calls
the normal iOS staging verifier. The tracked byte-size/TFL3 contract remains a
hard guard against silently accepting a different export.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "yolo11n-seg-fp16.contract.json"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_source_model(contract: dict) -> Path:
    target = ROOT / contract["source"]
    if target.is_file():
        return target

    from ultralytics import YOLO

    pytorch_dir = ROOT / "models/pytorch"
    pytorch_dir.mkdir(parents=True, exist_ok=True)
    checkpoint = pytorch_dir / "yolo11n-seg.pt"
    if not checkpoint.is_file():
        print("[iOS CI] Downloading pinned Ultralytics yolo11n-seg.pt checkpoint...")
        bootstrap = YOLO("yolo11n-seg.pt")
        candidates = [
            ROOT / "yolo11n-seg.pt",
            Path.cwd() / "yolo11n-seg.pt",
        ]
        ckpt_path = getattr(bootstrap, "ckpt_path", None)
        if ckpt_path:
            candidates.insert(0, Path(str(ckpt_path)))
        downloaded = next((path for path in candidates if path.is_file()), Path())
        if not downloaded.is_file():
            raise SystemExit("Ultralytics did not materialize yolo11n-seg.pt")
        shutil.move(str(downloaded), checkpoint)

    target.parent.mkdir(parents=True, exist_ok=True)
    print("[iOS CI] Exporting YOLO11n segmentation to FP16 TFLite...")
    model = YOLO(str(checkpoint))
    export_result = Path(
        model.export(
            format="tflite",
            imgsz=640,
            half=True,
            int8=False,
            nms=False,
        )
    )
    if export_result.is_dir():
        candidates = list(export_result.glob("*.tflite"))
        preferred = [
            path for path in candidates
            if "float16" in path.name.lower() or "fp16" in path.name.lower()
        ]
        if not candidates:
            raise SystemExit(f"No TFLite file produced under {export_result}")
        exported = preferred[0] if preferred else candidates[0]
    else:
        exported = export_result
    shutil.copyfile(exported, target)
    return target


def main() -> int:
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    source = ensure_source_model(contract)
    expected_size = int(contract["expected_size_bytes"])
    if source.stat().st_size != expected_size:
        raise SystemExit(
            f"Generated YOLO model size drifted: expected={expected_size} actual={source.stat().st_size}"
        )
    with source.open("rb") as stream:
        header = stream.read(8)
    if header[4:8] != contract["flatbuffer_magic"].encode("ascii"):
        raise SystemExit("Generated YOLO model does not carry the expected TFL3 FlatBuffer identifier")

    observed_hash = sha256(source)
    expected_hash = contract.get("expected_sha256")
    if expected_hash and observed_hash != expected_hash:
        raise SystemExit(
            f"Generated YOLO SHA-256 drifted: expected={expected_hash} actual={observed_hash}"
        )

    subprocess.run(
        [sys.executable, str(ROOT / "tools/release/sync_ios_yolo_model.py")],
        cwd=ROOT,
        check=True,
    )
    print(f"IOS_YOLO_SHA256={observed_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
