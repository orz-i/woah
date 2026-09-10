#!/usr/bin/env python3
"""Verify/provision the canonical iOS YOLO LiteRT model on clean CI.

Phase 7 release checkouts track the exact audited canonical model bytes. The
normal Release path therefore verifies the pinned whole-file/core identities
and stages that binary byte-for-byte for iOS. This is the release supply-chain
authority.

The historical exporter implementation remains below as a diagnostic fallback
for development checkouts that do not contain the canonical binary. It is not
the Release artifact source: Runs #7-#12 demonstrated host-dependent low-order
FLOAT32 differences during CPU model fusion even with pinned inputs and graph
semantics. The root Python lock remains unchanged for Android and other tooling.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.metadata
import json
import math
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


def run_checked(command: list[str], *, title: str, env: dict[str, str] | None = None) -> str:
    process_env = None
    if env is not None:
        process_env = os.environ.copy()
        process_env.update(env)
    completed = subprocess.run(
        command,
        cwd=ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=process_env,
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


def deterministic_export_env(contract: dict, cpu_capability: str) -> dict[str, str]:
    environment = contract["reproducibility"]["environment"]
    candidates = [str(value) for value in environment["cpu_dispatch_candidates"]]
    if cpu_capability not in candidates:
        fail(
            "Phase 7 CPU dispatch candidate",
            f"Unexpected cpu_capability={cpu_capability!r}; allowed={candidates}",
        )
    result = {
        str(key): str(value)
        for key, value in environment["process_thread_env"].items()
    }
    result["ATEN_CPU_CAPABILITY"] = cpu_capability
    return result


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
        "tflite": distribution_version("tflite"),
        "flatbuffers": distribution_version("flatbuffers"),
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
        "tflite": environment["tflite_schema_version"],
        "flatbuffers": environment["flatbuffers_version"],
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
        f"{environment['torch_wheel_url']}#sha256={environment['torch_wheel_sha256']}",
        f"{environment['torchvision_wheel_url']}#sha256={environment['torchvision_wheel_sha256']}",
    ]
    run_checked(
        [
            uv,
            "pip",
            "install",
            "--python",
            str(python),
            "--exclude-newer",
            environment["exclude_newer_utc"],
            *torch_packages,
        ],
        title="Phase 7 isolated CPU Torch install failed",
    )

    expected = expected_exporter_versions(contract)
    install_names = (
        "ultralytics",
        "numpy",
        "tflite",
        "flatbuffers",
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
    import torch

    environment = contract["reproducibility"]["environment"]
    torch.set_num_threads(int(environment["torch_num_threads"]))
    torch.set_num_interop_threads(int(environment["torch_num_interop_threads"]))
    capability_reader = getattr(torch.backends.cpu, "get_cpu_capability", None)
    effective_capability = capability_reader() if callable(capability_reader) else None
    print(
        "PHASE7_MODEL_CPU_RUNTIME="
        + json.dumps(
            {
                "requested_capability": os.environ.get("ATEN_CPU_CAPABILITY"),
                "effective_capability": effective_capability,
                "torch_num_threads": torch.get_num_threads(),
                "torch_num_interop_threads": torch.get_num_interop_threads(),
                "omp_num_threads": os.environ.get("OMP_NUM_THREADS"),
                "mkl_num_threads": os.environ.get("MKL_NUM_THREADS"),
            },
            sort_keys=True,
        ),
        flush=True,
    )

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


def load_constant_manifest(path: Path) -> list[dict]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail("Phase 7 constant manifest invalid", f"{path}: {exc}")
    if payload.get("schema") != 2 or not isinstance(payload.get("constants"), list):
        fail("Phase 7 constant manifest invalid", f"Unexpected schema in {path}")
    constants = payload["constants"]
    required = {
        "tensor",
        "bytes",
        "sha256",
        "type",
        "shape",
        "element_width",
        "multiset_sha256",
        "fp32_ulp8_multiset_sha256",
        "fp32_ulp12_multiset_sha256",
        "float32_stats",
    }
    for item in constants:
        if not isinstance(item, dict) or not required.issubset(item):
            fail("Phase 7 constant manifest invalid", f"Malformed constant record in {path}: {item!r}")
    return constants


def float32_stats_delta(expected: dict | None, actual: dict | None) -> dict | None:
    if not isinstance(expected, dict) or not isinstance(actual, dict):
        return None
    if expected.get("count") != actual.get("count"):
        return {"count": [expected.get("count"), actual.get("count")]}

    def rel_hex(key: str, sqrt: bool = False) -> float | None:
        expected_hex = expected.get(key)
        actual_hex = actual.get(key)
        if not isinstance(expected_hex, str) or not isinstance(actual_hex, str):
            return None
        expected_value = float.fromhex(expected_hex)
        actual_value = float.fromhex(actual_hex)
        if sqrt:
            expected_value = math.sqrt(max(0.0, expected_value))
            actual_value = math.sqrt(max(0.0, actual_value))
        return abs(actual_value - expected_value) / max(abs(expected_value), 1e-30)

    return {
        "sum_abs_rel": rel_hex("sum_abs_hex"),
        "l2_rel": rel_hex("sum_sq_hex", sqrt=True),
        "min": [expected.get("min_hex"), actual.get("min_hex")],
        "max": [expected.get("max_hex"), actual.get("max_hex")],
    }


def compare_constant_manifests(expected: list[dict], actual: list[dict]) -> dict:
    expected_by_name = {str(item["tensor"]): item for item in expected}
    actual_by_name = {str(item["tensor"]): item for item in actual}
    missing = sorted(set(expected_by_name) - set(actual_by_name))
    extra = sorted(set(actual_by_name) - set(expected_by_name))
    changed = []
    for name in sorted(set(expected_by_name) & set(actual_by_name)):
        expected_item = expected_by_name[name]
        actual_item = actual_by_name[name]
        if (
            int(expected_item["bytes"]) != int(actual_item["bytes"])
            or str(expected_item["sha256"]) != str(actual_item["sha256"])
        ):
            changed.append(
                {
                    "tensor": name,
                    "bytes": int(expected_item["bytes"]),
                    "actual_bytes": int(actual_item["bytes"]),
                    "expected_sha256": str(expected_item["sha256"]),
                    "actual_sha256": str(actual_item["sha256"]),
                    "type_shape_equal": (
                        expected_item.get("type") == actual_item.get("type")
                        and expected_item.get("shape") == actual_item.get("shape")
                    ),
                    "multiset_equal": (
                        expected_item.get("multiset_sha256") is not None
                        and expected_item.get("multiset_sha256") == actual_item.get("multiset_sha256")
                    ),
                    "fp32_ulp8_equal": (
                        expected_item.get("fp32_ulp8_multiset_sha256") is not None
                        and expected_item.get("fp32_ulp8_multiset_sha256")
                        == actual_item.get("fp32_ulp8_multiset_sha256")
                    ),
                    "fp32_ulp12_equal": (
                        expected_item.get("fp32_ulp12_multiset_sha256") is not None
                        and expected_item.get("fp32_ulp12_multiset_sha256")
                        == actual_item.get("fp32_ulp12_multiset_sha256")
                    ),
                    "float_stats_equal": (
                        expected_item.get("float32_stats") is not None
                        and expected_item.get("float32_stats") == actual_item.get("float32_stats")
                    ),
                    "float_stats_delta": float32_stats_delta(
                        expected_item.get("float32_stats"), actual_item.get("float32_stats")
                    ),
                }
            )
    changed.sort(key=lambda item: max(item["bytes"], item["actual_bytes"]), reverse=True)
    compact = [
        {
            "tensor": item["tensor"][-220:],
            "bytes": item["bytes"],
            "actual_bytes": item["actual_bytes"],
            "expected": item["expected_sha256"][:16],
            "actual": item["actual_sha256"][:16],
            "type_shape_equal": item["type_shape_equal"],
            "multiset_equal": item["multiset_equal"],
            "ulp8_equal": item["fp32_ulp8_equal"],
            "ulp12_equal": item["fp32_ulp12_equal"],
            "stats_equal": item["float_stats_equal"],
            "stats_delta": item["float_stats_delta"],
        }
        for item in changed[:8]
    ]
    return {
        "changed_count": len(changed),
        "changed_bytes": sum(max(item["bytes"], item["actual_bytes"]) for item in changed),
        "type_shape_equal_count": sum(item["type_shape_equal"] for item in changed),
        "multiset_equal_count": sum(item["multiset_equal"] for item in changed),
        "fp32_ulp8_equal_count": sum(item["fp32_ulp8_equal"] for item in changed),
        "fp32_ulp12_equal_count": sum(item["fp32_ulp12_equal"] for item in changed),
        "float_stats_equal_count": sum(item["float_stats_equal"] for item in changed),
        "missing_count": len(missing),
        "extra_count": len(extra),
        "missing": [name[-220:] for name in missing[:8]],
        "extra": [name[-220:] for name in extra[:8]],
        "largest_changed": compact,
    }


def reproduce_model(contract: dict, checkpoint: Path, exporter_python: Path, temporary_root: Path) -> Path:
    target = ROOT / contract["source"]
    target.parent.mkdir(parents=True, exist_ok=True)
    print("[iOS CI] Reproducing YOLO11n segmentation with Ultralytics LiteRT export...", flush=True)

    reproducibility = contract["reproducibility"]
    environment = reproducibility["environment"]
    semantic_tool = ROOT / "tools/release/fingerprint_tflite_semantics.py"
    expected_semantic = reproducibility["semantic_fingerprint"]
    canonical_manifest_path = ROOT / reproducibility["constant_manifest_path"]
    canonical_constants = load_constant_manifest(canonical_manifest_path)
    fingerprint_marker = "PHASE7_TFLITE_SEMANTIC_FINGERPRINT="
    runtime_marker = "PHASE7_MODEL_CPU_RUNTIME="

    def export_candidate(cpu_capability: str, label: str) -> dict:
        result = temporary_root / f"generated-yolo11n-seg-{label}.tflite"
        worker_output = run_checked(
            [
                str(exporter_python),
                str(Path(__file__).resolve()),
                "--worker-export",
                str(checkpoint),
                str(result),
            ],
            title=f"Phase 7 isolated LiteRT export ({label}) failed",
            env=deterministic_export_env(contract, cpu_capability),
        )
        runtime_lines = [line for line in worker_output.splitlines() if line.startswith(runtime_marker)]
        runtime = json.loads(runtime_lines[-1][len(runtime_marker) :]) if runtime_lines else {
            "requested_capability": cpu_capability,
            "effective_capability": None,
        }
        metadata = load_export_metadata(result)
        validate_export_metadata(metadata, contract)
        core, generated_tail = split_core(result)
        constants_output = temporary_root / f"generated-{label}.constants.json"
        fingerprint_output = run_checked(
            [
                str(exporter_python),
                str(semantic_tool),
                str(result),
                "--constants-output",
                str(constants_output),
            ],
            title=f"Phase 7 generated LiteRT semantic fingerprint ({label}) failed",
        )
        marker_lines = [
            line for line in fingerprint_output.splitlines() if line.startswith(fingerprint_marker)
        ]
        if len(marker_lines) != 1:
            fail(
                "Phase 7 generated LiteRT semantic fingerprint",
                f"Missing semantic marker in output for {result}:\n{fingerprint_output[-2000:]}",
            )
        fingerprint = json.loads(marker_lines[0][len(fingerprint_marker) :])
        constants = load_constant_manifest(constants_output)
        constant_diff = compare_constant_manifests(canonical_constants, constants)
        semantic_mismatches = {
            key: {"expected": expected_semantic[key], "actual": fingerprint.get(key)}
            for key in (
                "tensor_count",
                "operator_count",
                "constant_tensor_count",
                "constant_bytes",
                "structure_sha256",
                "constants_sha256",
                "semantic_sha256",
            )
            if fingerprint.get(key) != expected_semantic[key]
        }
        candidate = {
            "cpu_capability": cpu_capability,
            "runtime": runtime,
            "path": result,
            "metadata": metadata,
            "core": core,
            "generated_tail": generated_tail,
            "fingerprint": fingerprint,
            "constants": constants,
            "constant_diff": constant_diff,
            "semantic_mismatches": semantic_mismatches,
            "core_sha256": sha256_bytes(core),
        }
        print(
            "PHASE7_MODEL_CPU_CANDIDATE="
            + json.dumps(
                {
                    "cpu_capability": cpu_capability,
                    "runtime": runtime,
                    "core_size": len(core),
                    "core_sha256": candidate["core_sha256"],
                    "structure_sha256": fingerprint["structure_sha256"],
                    "constants_sha256": fingerprint["constants_sha256"],
                    "semantic_sha256": fingerprint["semantic_sha256"],
                    "semantic_mismatches": semantic_mismatches,
                    "constant_diff": constant_diff,
                },
                sort_keys=True,
            ),
            flush=True,
        )
        return candidate

    candidates = [
        export_candidate(str(cpu_capability), f"candidate-{cpu_capability}")
        for cpu_capability in environment["cpu_dispatch_candidates"]
    ]
    # The first isolated worker owns checkpoint materialization on a clean
    # runner and validates it before export. Re-check it in the parent only
    # after the candidate workers have had that opportunity.
    verify_checkpoint(checkpoint, contract)
    expected_core_size = int(reproducibility["expected_core_size_bytes"])
    expected_core_hash = str(reproducibility["expected_core_sha256"])
    exact = [
        candidate
        for candidate in candidates
        if len(candidate["core"]) == expected_core_size
        and candidate["core_sha256"] == expected_core_hash
    ]
    if not exact:
        compact_candidates = []
        for candidate in candidates:
            diff = candidate["constant_diff"]
            compact_candidates.append(
                {
                    "cpu_capability": candidate["cpu_capability"],
                    "runtime": candidate["runtime"],
                    "core_size": len(candidate["core"]),
                    "core_sha256": candidate["core_sha256"],
                    "semantic_mismatches": candidate["semantic_mismatches"],
                    "constant_diff": {
                        key: value
                        for key, value in diff.items()
                        if key != "largest_changed"
                    }
                    | {"largest_changed": diff["largest_changed"][:2]},
                }
            )
        fail(
            "Phase 7 LiteRT CPU dispatch reproducibility failure",
            f"expected_size={expected_core_size} expected_sha256={expected_core_hash} "
            f"candidates={json.dumps(compact_candidates, sort_keys=True)}",
        )

    selected = exact[0]
    selected_capability = str(selected["cpu_capability"])
    repeated = export_candidate(selected_capability, f"repeat-{selected_capability}")
    repeat_core_equal = selected["core"] == repeated["core"]
    repeat_semantic_equal = (
        selected["fingerprint"]["semantic_sha256"]
        == repeated["fingerprint"]["semantic_sha256"]
    )
    if (
        not repeat_core_equal
        or len(repeated["core"]) != expected_core_size
        or repeated["core_sha256"] != expected_core_hash
    ):
        fail(
            "Phase 7 selected CPU dispatch repeat failure",
            f"cpu_capability={selected_capability} expected_sha256={expected_core_hash} "
            f"first_sha256={selected['core_sha256']} repeat_sha256={repeated['core_sha256']} "
            f"repeat_core_equal={repeat_core_equal} repeat_semantic_equal={repeat_semantic_equal}",
        )

    print(
        "PHASE7_MODEL_GENERATED="
        + json.dumps(
            {
                "selected_cpu_capability": selected_capability,
                "runtime": selected["runtime"],
                "raw_size": selected["path"].stat().st_size,
                "raw_sha256": sha256(selected["path"]),
                "core_size": len(selected["core"]),
                "core_sha256": selected["core_sha256"],
                "repeat_core_sha256": repeated["core_sha256"],
                "repeat_core_equal": repeat_core_equal,
                "semantic_sha256": selected["fingerprint"]["semantic_sha256"],
                "repeat_semantic_sha256": repeated["fingerprint"]["semantic_sha256"],
                "repeat_semantic_equal": repeat_semantic_equal,
                "structure_sha256": selected["fingerprint"]["structure_sha256"],
                "constants_sha256": selected["fingerprint"]["constants_sha256"],
                "generated_tail_size": len(selected["generated_tail"]),
                "metadata_version": selected["metadata"].get("version"),
                "metadata_args": selected["metadata"].get("args"),
            },
            sort_keys=True,
        ),
        flush=True,
    )
    target.write_bytes(selected["core"] + canonical_tail(contract))
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
