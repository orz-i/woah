#!/usr/bin/env python3
"""Reproduce and provision the canonical iOS YOLO LiteRT model on clean CI.

The inference FlatBuffer is deterministic, but Ultralytics appends a ZIP
``metadata.json`` entry containing export-time timestamps. Phase 7 therefore
proves reproducibility against the pinned FlatBuffer-core SHA-256, then restores
the 1 KiB historical metadata tail before enforcing the pinned whole-file SHA.

The repository root Python lock intentionally remains unchanged for Android and
other tooling. On a cache miss this helper creates a throw-away Python 3.11
virtual environment and resolves the historically pinned LiteRT exporter stack
with a hard ``--exclude-newer`` cutoff matching the canonical export timestamp.
The model-production interpreter is therefore independent of the app/root
``uv.lock`` and is destroyed after the raw FlatBuffer core has been verified.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.metadata
import json
import os
import shutil
import subprocess
import sys
import tempfile
import traceback
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


def run_checked(command: list[str], *, title: str) -> str:
    completed = subprocess.run(
        command,
        cwd=ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    output = completed.stdout or ""
    if output:
        print(output, end="" if output.endswith("\n") else "\n", flush=True)
    if completed.returncode != 0:
        fail(
            title,
            f"exit={completed.returncode}\ncommand={' '.join(command)}\n{output[-3000:]}",
        )
    return output


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
        "litert-lm-builder": distribution_version("litert-lm-builder"),
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
        "litert-lm-builder": environment["litert_lm_builder_version"],
    }


def isolated_python(venv: Path) -> Path:
    if os.name == "nt":
        return venv / "Scripts/python.exe"
    return venv / "bin/python"


def worker_version_report(python: Path) -> dict[str, str | None]:
    output = run_checked(
        [str(python), str(Path(__file__).resolve()), "--worker-versions"],
        title="Phase 7 isolated exporter version probe failed",
    )
    marker = "PHASE7_MODEL_EXPORTER_VERSIONS="
    lines = [line for line in output.splitlines() if line.startswith(marker)]
    if len(lines) != 1:
        fail("Phase 7 isolated exporter version probe", f"Missing version marker in:\n{output[-2000:]}")
    return json.loads(lines[0][len(marker) :])


def create_exporter_environment(contract: dict, root: Path) -> Path:
    environment = contract["reproducibility"]["environment"]
    if environment.get("isolation") != "temporary_venv":
        fail("Phase 7 model exporter isolation", f"Unsupported isolation={environment.get('isolation')!r}")
    if environment.get("platform") != "linux_x86_64":
        fail("Phase 7 model exporter platform", f"Unsupported platform={environment.get('platform')!r}")
    if sys.platform != "linux":
        fail("Phase 7 model exporter platform", f"Clean-cloud reproduction requires Linux, actual={sys.platform}")
    python_expected = environment["python_major_minor"]
    if f"{sys.version_info.major}.{sys.version_info.minor}" != python_expected:
        fail(
            "Phase 7 model exporter Python drift",
            f"expected={python_expected} actual={sys.version_info.major}.{sys.version_info.minor}",
        )

    venv = root / "venv"
    run_checked(
        [sys.executable, "-m", "venv", str(venv)],
        title="Phase 7 isolated exporter venv creation failed",
    )
    python = isolated_python(venv)
    uv = shutil.which("uv")
    if not uv:
        fail("Phase 7 model exporter environment", "uv is required to provision the isolated LiteRT exporter stack")

    torch_packages = [
        f"torch=={environment['torch_version']}",
        f"torchvision=={environment['torchvision_version']}",
    ]
    run_checked(
        [
            uv,
            "pip",
            "install",
            "--python",
            str(python),
            "--index-url",
            environment["torch_index"],
            "--extra-index-url",
            "https://pypi.org/simple",
            *torch_packages,
        ],
        title="Phase 7 isolated CPU Torch install failed",
    )

    expected = expected_exporter_versions(contract)
    install_names = (
        "ultralytics",
        "numpy",
        "litert-torch",
        "ai-edge-litert",
        "ai-edge-quantizer",
        "litert-converter",
        "torchao",
        "litert-lm-builder",
    )
    packages = [f"{name}=={expected[name]}" for name in install_names]
    cutoff = environment["exclude_newer_utc"]
    print("[iOS CI] Installing isolated Phase 7 LiteRT exporter stack:", " ".join(packages), flush=True)
    run_checked(
        [
            uv,
            "pip",
            "install",
            "--python",
            str(python),
            "--exclude-newer",
            cutoff,
            *packages,
        ],
        title="Phase 7 isolated exporter dependency install failed",
    )

    observed = worker_version_report(python)
    mismatches = {
        name: {"expected": version, "actual": observed.get(name)}
        for name, version in expected.items()
        if observed.get(name) != version
    }
    if not str(observed.get("python", "")).startswith(python_expected + "."):
        mismatches["python"] = {"expected": python_expected, "actual": observed.get("python")}
    if mismatches:
        fail("Phase 7 model exporter version drift", json.dumps(mismatches, sort_keys=True))
    print("PHASE7_MODEL_EXPORTER_ISOLATION=temporary_venv", flush=True)
    print("PHASE7_MODEL_EXPORTER_VERSIONS=" + json.dumps(observed, sort_keys=True), flush=True)
    return python


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


def checkpoint_path(contract: dict) -> Path:
    checkpoint = ROOT / contract["source_checkpoint"]["path"]
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    return checkpoint


def worker_export(contract: dict, checkpoint: Path, output: Path) -> int:
    from ultralytics import YOLO

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
            raise RuntimeError(f"Expected one .tflite under {result}, found {candidates}")
        result = candidates[0]
    if not result.is_file():
        raise RuntimeError(f"Exporter did not produce a file: {result}")
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(result, output)
    print(f"PHASE7_MODEL_WORKER_OUTPUT={output}", flush=True)
    return 0


def cli() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker-versions", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument(
        "--worker-export",
        nargs=2,
        metavar=("CHECKPOINT", "OUTPUT"),
        help=argparse.SUPPRESS,
    )
    args = parser.parse_args()
    if args.worker_versions:
        print("PHASE7_MODEL_EXPORTER_VERSIONS=" + json.dumps(exporter_versions(), sort_keys=True))
        return 0
    if args.worker_export:
        contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
        checkpoint = Path(args.worker_export[0]).resolve()
        output = Path(args.worker_export[1]).resolve()
        try:
            return worker_export(contract, checkpoint, output)
        except SystemExit:
            raise
        except Exception as exc:
            traceback.print_exc()
            # Keep the concise exception marker last so the parent annotation's
            # tail survives GitHub's 4096-byte annotation limit.
            print(f"PHASE7_WORKER_EXCEPTION={type(exc).__name__}: {exc}", flush=True)
            return 1
    return main()


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


def reproduce_model(contract: dict, checkpoint: Path, exporter_python: Path, temporary_root: Path) -> Path:
    target = ROOT / contract["source"]
    target.parent.mkdir(parents=True, exist_ok=True)
    print("[iOS CI] Reproducing YOLO11n segmentation with Ultralytics LiteRT export...", flush=True)
    result = temporary_root / "generated-yolo11n-seg.tflite"
    run_checked(
        [
            str(exporter_python),
            str(Path(__file__).resolve()),
            "--worker-export",
            str(checkpoint),
            str(result),
        ],
        title="Phase 7 isolated LiteRT export failed",
    )
    verify_checkpoint(checkpoint, contract)

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
        with tempfile.TemporaryDirectory(prefix="woah-phase7-model-export-") as temporary:
            temporary_root = Path(temporary)
            exporter_python = create_exporter_environment(contract, temporary_root)
            checkpoint = checkpoint_path(contract)
            target = reproduce_model(contract, checkpoint, exporter_python, temporary_root)

    observed_hash = verify_canonical_model(target, contract)
    run_checked(
        [sys.executable, str(ROOT / "tools/release/sync_ios_yolo_model.py")],
        title="Phase 7 canonical model staging failed",
    )
    print(f"IOS_YOLO_SHA256={observed_hash}")
    print(f"IOS_YOLO_CORE_SHA256={contract['reproducibility']['expected_core_sha256']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(cli())
    except SystemExit:
        raise
    except Exception:
        fail("Phase 7 model provisioner unhandled exception", traceback.format_exc()[-10000:])
