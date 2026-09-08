#!/usr/bin/env python3
"""Static checks for the Woah iOS Phase 1 YOLO inference spike.

This verifier is intentionally host-independent. It validates repository
contracts, packaging intent, and the Android/iOS tensor agreement. It cannot
prove that CocoaPods resolves, Swift compiles, a delegate initializes, or an
iPhone produces parity results; those remain explicit macOS/device gates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
IOS_ROOT = ROOT / "mobile/packages/dance_native/ios/dance_native"
SOURCES = IOS_ROOT / "Sources/dance_native"
CONTRACT_PATH = SOURCES / "Resources/yolo11n-seg-fp16.contract.json"
GRAPH_REPORT_PATH = ROOT / "reports/yolo11n_seg_fp16_graph_report.json"
MODEL_DIR = SOURCES / "Resources/InferenceAssets"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_tensor_contract() -> dict:
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    report = json.loads(GRAPH_REPORT_PATH.read_text(encoding="utf-8"))

    check(contract["model"] == report["file_name"], "iOS model name must match the tracked graph report")
    check(
        contract["expected_size_bytes"] == report["file_size_bytes"],
        "iOS model byte-size contract must match the tracked graph report",
    )
    check(
        contract["flatbuffer_magic"] == report["format_magic"] == "TFL3",
        "iOS model FlatBuffer magic must remain TFL3",
    )
    check(len(report["input_tensors"]) == 1, "YOLO graph report must have exactly one input")
    if report["input_tensors"]:
        report_input = report["input_tensors"][0]
        check(contract["input"]["shape"] == report_input["shape"], "iOS input shape contract drifted")
        check(contract["input"]["dtype"] == report_input["dtype"], "iOS input dtype contract drifted")

    report_outputs = [
        {"dtype": item["dtype"], "shape": item["shape"]}
        for item in report["output_tensors"]
    ]
    check(contract["outputs"] == report_outputs, "iOS output tensor contract drifted from graph report")

    android_adapter = (
        ROOT
        / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/inference/YoloLiteRtTensorAdapter.kt"
    ).read_text(encoding="utf-8")
    for token in (
        "PROTO_SIZE = 160",
        "PROTO_CHANNELS = 32",
        "ATTR_COUNT = 116",
        "ANCHOR_COUNT = 8400",
    ):
        check(token in android_adapter, f"Android YOLO tensor contract changed: missing {token}")

    swift_post = (SOURCES / "IOSYoloPostprocessor.swift").read_text(encoding="utf-8")
    for token in (
        "static let protoSize = 160",
        "static let protoChannels = 32",
        "static let attributeCount = 116",
        "static let anchorCount = 8400",
        "bboxIoUThreshold: Float32 = 0.50",
        "maskIoUThreshold: Float32 = 0.50",
    ):
        check(token in swift_post, f"Swift YOLO contract missing Android parity token: {token}")

    swift_pre = (SOURCES / "IOSYoloPreprocessor.swift").read_text(encoding="utf-8")
    check("static let inputSize = 640" in swift_pre, "Swift YOLO preprocessor must remain 640x640")
    check("letterboxValue: UInt8 = 114" in swift_pre, "Swift letterbox padding must match Android RGB 114")
    check("let gOffset = pixels" in swift_pre and "let bOffset = pixels * 2" in swift_pre,
          "Swift preprocessor must emit NCHW RGB channels")
    return contract


def verify_runtime_packaging() -> None:
    podspec = (ROOT / "mobile/packages/dance_native/ios/dance_native.podspec").read_text(encoding="utf-8")
    check(
        "s.dependency 'TensorFlowLiteSwift/CoreML', '2.17.0'" in podspec,
        "Phase 1 must pin the Core ML TensorFlowLiteSwift pod",
    )
    check(
        "s.dependency 'TensorFlowLiteSwift/Metal', '2.17.0'" in podspec,
        "Phase 1 must pin the Metal TensorFlowLiteSwift pod",
    )
    check("'dance_native_models'" in podspec and "InferenceAssets/**/*" in podspec,
          "CocoaPods must package provisioned inference assets")
    check("s.source_files = 'dance_native/Sources/dance_native/**/*.swift'" in podspec,
          "CocoaPods source glob must not treat model/resources as Swift sources")

    # Flutter 3.44+ uses SwiftPM first. Google's general LiteRT Swift runtime is
    # only first-party distributed via CocoaPods/Bazel, so this plugin must be
    # CocoaPods-only during Phase 1 to trigger Flutter's supported per-plugin
    # fallback rather than silently compiling an inference-disabled SPM target.
    check(
        not (IOS_ROOT / "Package.swift").exists(),
        "dance_native Package.swift must stay absent during the CocoaPods-only Phase 1 runtime bridge",
    )

    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
    check("InferenceAssets/*.tflite" in gitignore, "Provisioned iOS model bytes must stay untracked")
    check("InferenceAssets/*.sha256" in gitignore, "Provisioned iOS model hashes must stay untracked")
    check((MODEL_DIR / "README.md").is_file(), "InferenceAssets bundle must retain a tracked placeholder README")


def verify_swift_runtime() -> None:
    runner = (SOURCES / "IOSYoloRunner.swift").read_text(encoding="utf-8")
    resource = (SOURCES / "IOSModelResources.swift").read_text(encoding="utf-8")
    probe = (SOURCES / "IOSYoloPhase1Probe.swift").read_text(encoding="utf-8")
    plugin = (SOURCES / "DanceNativePlugin.swift").read_text(encoding="utf-8")
    capabilities = (SOURCES / "IOSDeviceCapabilities.swift").read_text(encoding="utf-8")

    for token in (
        "#if canImport(TensorFlowLite)",
        "import TensorFlowLite",
        "private var activeDelegate: Delegate?",
        'case coreML = "tflite_coreml"',
        'case metal = "tflite_metal"',
        'case xnnpack = "tflite_xnnpack"',
        "[.coreML, .metal, .xnnpack]",
        "CoreMLDelegate()",
        "MetalDelegate(options: delegateOptions)",
        "options.isXNNPackEnabled = true",
        "try interpreter.allocateTensors()",
        "try interpreter.invoke()",
        "inputShape == [1, 3, 640, 640]",
    ):
        check(token in runner, f"iOS YOLO runtime is missing required token: {token}")
    check("activeDelegate = candidate.delegate" in runner,
          "Reusable interpreter must strongly retain its active native delegate")

    check("dance_native_models" in resource, "Model resolver must support the CocoaPods resource bundle")
    check("runIOSYoloPhase1Probe" in plugin, "Phase 1 MethodChannel diagnostic probe is missing")
    check("IOSYoloPhase1Probe.run" in plugin, "Phase 1 probe must invoke the isolated YOLO runner")
    check("requested_backend" in probe and "effective_backend" in probe and "fallback_reasons" in probe,
          "Phase 1 probe must expose effective backend and fallback telemetry")
    check("mask_coverage" in probe and "detection_count" in probe,
          "Phase 1 probe must expose comparison-friendly detection metrics")
    check("IOSYoloRuntimeSupport.candidateBackendNames()" in capabilities,
          "Capabilities must be derived from runtime/model availability")
    check("supportedProfiles: []" in capabilities,
          "Product processing profiles must remain gated during the Phase 1 spike")

    analyze_start = plugin.find("func analyzeVideo")
    analyze_end = plugin.find("func getPreviewFrame", analyze_start)
    analyze_block = plugin[analyze_start:analyze_end]
    check('code: "PLATFORM_NOT_SUPPORTED"' in analyze_block,
          "Phase 1 must not connect unvalidated YOLO output to product analyzeVideo")


def verify_model_if_present(
    contract: dict,
    require_model: bool,
    allow_unpinned_hash: bool,
) -> None:
    source = ROOT / contract["source"]
    staged = MODEL_DIR / contract["model"]
    sidecar = MODEL_DIR / f"{contract['model']}.sha256"

    if not source.is_file():
        check(not require_model, f"Required repository-local model is missing: {source}")
        if not require_model:
            print(f"NOTE: model source not materialized in this worktree: {source}")
        return

    expected_size = int(contract["expected_size_bytes"])
    expected_hash = contract.get("expected_sha256")
    if require_model and not allow_unpinned_hash:
        check(
            isinstance(expected_hash, str) and len(expected_hash) == 64,
            "Phase 1 acceptance requires expected_sha256 to be pinned in the tracked YOLO contract",
        )
    check(source.stat().st_size == expected_size, "Repository-local YOLO model size violates the tracked contract")
    with source.open("rb") as stream:
        header = stream.read(8)
    check(header[4:8] == contract["flatbuffer_magic"].encode("ascii"), "Repository-local YOLO model is not TFL3")

    source_hash = sha256(source)
    if require_model and allow_unpinned_hash and not expected_hash:
        print(f"BOOTSTRAP_SHA256={source_hash}")
    if isinstance(expected_hash, str) and expected_hash:
        check(source_hash == expected_hash, "Repository-local YOLO model SHA-256 violates the tracked contract")
    if require_model:
        check(staged.is_file(), "iOS YOLO model has not been staged; run sync_ios_yolo_model.py")
        check(sidecar.is_file(), "iOS YOLO SHA-256 sidecar is missing")
    if staged.is_file():
        check(staged.stat().st_size == expected_size, "Staged iOS YOLO model size violates the tracked contract")
        check(sha256(staged) == source_hash, "Staged iOS YOLO model differs from the repository-local source")
        if sidecar.is_file():
            check(sidecar.read_text(encoding="ascii").strip() == source_hash,
                  "Staged iOS YOLO SHA-256 sidecar is stale")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--require-model",
        action="store_true",
        help="Fail unless the repository-local and staged iOS YOLO model bytes are present and identical.",
    )
    parser.add_argument(
        "--allow-unpinned-hash",
        action="store_true",
        help="Bootstrap only: allow --require-model before expected_sha256 is pinned and print the observed hash.",
    )
    args = parser.parse_args()

    contract = verify_tensor_contract()
    verify_runtime_packaging()
    verify_swift_runtime()
    verify_model_if_present(
        contract,
        require_model=args.require_model,
        allow_unpinned_hash=args.allow_unpinned_hash,
    )

    if FAILURES:
        print("iOS Phase 1 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 1 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
