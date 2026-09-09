#!/usr/bin/env python3
"""Reproduce and provision the canonical iOS YOLO LiteRT model on clean CI.

The inference FlatBuffer is deterministic, but Ultralytics appends a ZIP
``metadata.json`` entry containing export-time timestamps. Phase 7 therefore
proves reproducibility against the pinned FlatBuffer-core SHA-256, then restores
the 1 KiB historical metadata tail before enforcing the pinned whole-file SHA.

The repository root Python lock intentionally remains unchanged for Android and
other tooling. This helper uses the already-locked Torch/NumPy baseline and asks
``uv pip`` to install only the historically pinned LiteRT exporter stack with a
hard ``--exclude-newer`` cutoff matching the canonical export timestamp.
"""

from __future__ import annotations

import base64
import hashlib
import importlib.metadata
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "yolo11n-seg-fp16.contract.json"
)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def annotation_escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def fail(title: str, message: str) -> "NoReturn":
    print(f"::error title={annotation_escape(title)}::{annotation_escape(message)}", flush=True)
    raise SystemExit(message)


def distribution_version(name: str) -> str | None:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


def exporter_versions() -> dict[str, str | None]:
    return {
        "python": f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
        "ultralytics": distribution_version("ultralytics"),
        "torch": distribution_version("torch"),
        "torchvision": distribution_version("torchvision"),
        "numpy": distribution_version("numpy"),
        "litert-torch": distribution_version("litert-torch"),
        "ai-edge-litert": distribution_version("ai-edge-litert"),
        "ai-edge-quantizer": distribution_version("ai-edge-quantizer"),
        "litert-converter": distribution_version("litert-converter"),
        "torchao": distribution_version("torchao"),
    }


def expected_exporter_versions(contract: dict) -> dict[str, str]:
    environment = contract["reproducibility"]["environment"]
    return {
        "ultralytics": contract["reproducibility"]["exporter"]["ultralytics_version"],
        "torch": environment["torch_version"],
        "torchvision": environment["torchvision_version"],
        "numpy": environment["numpy_version"],
        "litert-torch": environment["litert_torch_version"],
        "ai-edge-litert": environment["ai_edge_litert_version"],
        "ai-edge-quantizer": environment["ai_edge_quantizer_version"],
        "litert-converter": environment["litert_converter_version"],
        "torchao": environment["torchao_version"],
    }


def ensure_exporter_environment(contract: dict) -> dict[str, str | None]:
    expected = expected_exporter_versions(contract)
    observed = exporter_versions()
    install_names = (
        "ultralytics",
        "litert-torch",
        "ai-edge-litert",
        "ai-edge-quantizer",
        "litert-converter",
        "torchao",
    )
    needs_install = any(observed.get(name) != expected[name] for name in install_names)
    if needs_install:
        uv = shutil.which("uv")
        if not uv:
            fail("Phase 7 model exporter environment", "uv is required to provision the pinned LiteRT exporter stack")
        cutoff = contract["reproducibility"]["environment"]["exclude_newer_utc"]
        packages = [f"{name}=={expected[name]}" for name in install_names]
        command = [
            uv,
            "pip",
            "install",
            "--python",
            sys.executable,
            "--exclude-newer",
            cutoff,
            *packages,
        ]
        print("[iOS CI] Installing pinned Phase 7 LiteRT exporter stack:", " ".join(packages), flush=True)
        subprocess.run(command, cwd=ROOT, check=True)
        observed = exporter_versions()

    mismatches = {
        name: {"expected": version, "actual": observed.get(name)}
        for name, version in expected.items()
        if observed.get(name) != version
    }
    python_expected = contract["reproducibility"]["environment"]["python_major_minor"]
    if f"{sys.version_info.major}.{sys.version_info.minor}" != python_expected:
        mismatches["python"] = {
            "expected": python_expected,
            "actual": f"{sys.version_info.major}.{sys.version_info.minor}",
        }
    if mismatches:
        fail("Phase 7 model exporter version drift", json.dumps(mismatches, sort_keys=True))

    print("PHASE7_MODEL_EXPORTER_VERSIONS=" + json.dumps(observed, sort_keys=True), flush=True)
    return observed


def verify_checkpoint(path: Path, contract: dict) -> None:
    checkpoint = contract["source_checkpoint"]
    if not path.is_file():
        fail("Phase 7 YOLO checkpoint missing", str(path))
    actual_size = path.stat().st_size
    actual_hash = sha256(path)
    if actual_size != int(checkpoint["expected_size_bytes"]) or actual_hash != checkpoint["expected_sha256"]:
        fail(
            "Phase 7 YOLO checkpoint drift",
            f"expected_size={checkpoint['expected_size_bytes']} actual_size={actual_size} "
            f"expected_sha256={checkpoint['expected_sha256']} actual_sha256={actual_hash}",
        )
    print(f"PHASE7_MODEL_CHECKPOINT_SHA256={actual_hash}", flush=True)


def ensure_checkpoint(contract: dict) -> Path:
    from ultralytics import YOLO

    checkpoint = ROOT / contract["source_checkpoint"]["path"]
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    if not checkpoint.is_file():
        print("[iOS CI] Downloading pinned Ultralytics yolo11n-seg.pt checkpoint...", flush=True)
        bootstrap = YOLO("yolo11n-seg.pt")
        candidates = [ROOT / "yolo11n-seg.pt", Path.cwd() / "yolo11n-seg.pt"]
        ckpt_path = getattr(bootstrap, "ckpt_path", None)
        if ckpt_path:
            candidates.insert(0, Path(str(ckpt_path)))
        downloaded = next((item for item in candidates if item.is_file()), None)
        if downloaded is None:
            fail("Phase 7 YOLO checkpoint download", "Ultralytics did not materialize yolo11n-seg.pt")
        if downloaded.resolve() != checkpoint.resolve():
            shutil.move(str(downloaded), checkpoint)
    verify_checkpoint(checkpoint, contract)
    return checkpoint


def load_export_metadata(path: Path) -> dict:
    try:
        with zipfile.ZipFile(path) as archive:
            return json.loads(archive.read("metadata.json").decode("utf-8"))
    except Exception as exc:
        fail("Phase 7 LiteRT metadata missing", f"{path}: {exc}")


def split_core(path: Path) -> tuple[bytes, bytes]:
    data = path.read_bytes()
    search_start = max(8, len(data) - 64 * 1024)
    offset = data.find(b"PK\x03\x04", search_start)
    if offset < 0:
        fail("Phase 7 LiteRT metadata container", f"No appended ZIP metadata found in {path}")
    return data[:offset], data[offset:]


def validate_export_metadata(metadata: dict, contract: dict) -> None:
    expected = contract["reproducibility"]["embedded_metadata"]
    checks = {
        "version": metadata.get("version"),
        "task": metadata.get("task"),
        "imgsz": metadata.get("imgsz"),
        "quantize": (metadata.get("args") or {}).get("quantize"),
        "end2end": metadata.get("end2end"),
    }
    mismatches = {key: {"expected": value, "actual": checks.get(key)} for key, value in expected.items() if checks.get(key) != value}
    if mismatches:
        fail("Phase 7 LiteRT embedded metadata drift", json.dumps(mismatches, sort_keys=True))


def canonical_tail(contract: dict) -> bytes:
    reproducibility = contract["reproducibility"]
    tail_path = ROOT / reproducibility["canonical_metadata_tail_path"]
    if not tail_path.is_file():
        fail("Phase 7 canonical metadata tail missing", str(tail_path))
    try:
        tail = base64.b64decode(tail_path.read_text(encoding="ascii").strip(), validate=True)
    except Exception as exc:
        fail("Phase 7 canonical metadata tail invalid", str(exc))
    if len(tail) != int(reproducibility["canonical_metadata_tail_size_bytes"]):
        fail("Phase 7 canonical metadata tail size drift", f"actual={len(tail)}")
    actual_hash = sha256_bytes(tail)
    if actual_hash != reproducibility["canonical_metadata_tail_sha256"]:
        fail(
            "Phase 7 canonical metadata tail hash drift",
            f"expected={reproducibility['canonical_metadata_tail_sha256']} actual={actual_hash}",
        )
    return tail


def reproduce_model(contract: dict, checkpoint: Path) -> Path:
    from ultralytics import YOLO

    target = ROOT / contract["source"]
    target.parent.mkdir(parents=True, exist_ok=True)
    print("[iOS CI] Reproducing YOLO11n segmentation with Ultralytics LiteRT export...", flush=True)
    exporter = contract["reproducibility"]["exporter"]
    model = YOLO(str(checkpoint))
    result = Path(
        model.export(
            format=exporter["format"],
            imgsz=int(exporter["imgsz"]),
            quantize=exporter["quantize"],
            nms=bool(exporter["nms"]),
        )
    )
    if result.is_dir():
        candidates = sorted(result.glob("*.tflite"))
        if len(candidates) != 1:
            fail("Phase 7 LiteRT export output", f"Expected one .tflite under {result}, found {candidates}")
        result = candidates[0]
    if not result.is_file():
        fail("Phase 7 LiteRT export output", f"Exporter did not produce a file: {result}")

    metadata = load_export_metadata(result)
    validate_export_metadata(metadata, contract)
    core, generated_tail = split_core(result)
    reproducibility = contract["reproducibility"]
    core_hash = sha256_bytes(core)
    print(
        "PHASE7_MODEL_GENERATED="
        + json.dumps(
            {
                "raw_size": result.stat().st_size,
                "raw_sha256": sha256(result),
                "core_size": len(core),
                "core_sha256": core_hash,
                "generated_tail_size": len(generated_tail),
                "metadata_version": metadata.get("version"),
                "metadata_args": metadata.get("args"),
            },
            sort_keys=True,
        ),
        flush=True,
    )
    if len(core) != int(reproducibility["expected_core_size_bytes"]) or core_hash != reproducibility["expected_core_sha256"]:
        fail(
            "Phase 7 LiteRT core reproducibility failure",
            f"expected_size={reproducibility['expected_core_size_bytes']} actual_size={len(core)} "
            f"expected_sha256={reproducibility['expected_core_sha256']} actual_sha256={core_hash}",
        )

    target.write_bytes(core + canonical_tail(contract))
    return target


def verify_canonical_model(path: Path, contract: dict) -> str:
    if not path.is_file():
        fail("Phase 7 canonical model missing", str(path))
    size = path.stat().st_size
    if size != int(contract["expected_size_bytes"]):
        fail("Phase 7 canonical model size drift", f"expected={contract['expected_size_bytes']} actual={size}")
    with path.open("rb") as stream:
        header = stream.read(8)
    if header[4:8] != contract["flatbuffer_magic"].encode("ascii"):
        fail("Phase 7 canonical model format drift", "Expected TFL3 FlatBuffer identifier")
    observed_hash = sha256(path)
    if observed_hash != contract["expected_sha256"]:
        fail(
            "Phase 7 canonical model whole-file hash drift",
            f"expected={contract['expected_sha256']} actual={observed_hash}",
        )
    core, tail = split_core(path)
    reproducibility = contract["reproducibility"]
    if len(core) != int(reproducibility["expected_core_size_bytes"]) or sha256_bytes(core) != reproducibility["expected_core_sha256"]:
        fail("Phase 7 canonical model core drift", f"size={len(core)} sha256={sha256_bytes(core)}")
    if sha256_bytes(tail) != reproducibility["canonical_metadata_tail_sha256"]:
        fail("Phase 7 canonical model metadata tail drift", sha256_bytes(tail))
    return observed_hash


def main() -> int:
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    target = ROOT / contract["source"]
    # Validate the tracked historical tail even on cache hits. A cached model
    # must not hide corruption of the evidence needed to reconstruct the
    # canonical whole-file identity after a future clean-cloud export.
    canonical_tail(contract)

    # An exact cached canonical artifact is already accepted; a cache miss must
    # prove graph reproducibility before it may recreate that artifact.
    if target.is_file() and sha256(target) == contract["expected_sha256"]:
        print("[iOS CI] Reusing exact cached canonical YOLO model.", flush=True)
    else:
        ensure_exporter_environment(contract)
        checkpoint = ensure_checkpoint(contract)
        target = reproduce_model(contract, checkpoint)

    observed_hash = verify_canonical_model(target, contract)
    subprocess.run(
        [sys.executable, str(ROOT / "tools/release/sync_ios_yolo_model.py")],
        cwd=ROOT,
        check=True,
    )
    print(f"IOS_YOLO_SHA256={observed_hash}")
    print(f"IOS_YOLO_CORE_SHA256={contract['reproducibility']['expected_core_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
