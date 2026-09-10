#!/usr/bin/env python3
"""Host-independent contract gate for iOS Phase 7 pre-release readiness."""

from __future__ import annotations

import base64
import hashlib
import json
import plistlib
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "mobile/app"
IOS = APP / "ios"
SOURCES = ROOT / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
MODEL_CONTRACT = SOURCES / "Resources/yolo11n-seg-fp16.contract.json"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def text(path: Path, label: str) -> str:
    check(path.is_file(), f"Missing iOS Phase 7 asset: {label}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def is_git_tracked(path: Path) -> bool:
    try:
        relative = path.relative_to(ROOT).as_posix()
    except ValueError:
        return False
    completed = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "--", relative],
        cwd=ROOT,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return completed.returncode == 0


def load_plist(path: Path, label: str) -> dict:
    check(path.is_file(), f"Missing iOS Phase 7 plist: {label}")
    if not path.is_file():
        return {}
    with path.open("rb") as stream:
        return plistlib.load(stream)


def verify_boundary() -> None:
    docs = text(ROOT / "docs/ios_phase7_release_readiness.md", "Phase 7 boundary document")
    implementation = text(ROOT / "docs/ios_implementation.md", "iOS implementation history")
    phase5 = text(ROOT / "tools/release/verify_ios_phase5.py", "Phase 5 verifier")
    phase6 = text(ROOT / "tools/release/verify_ios_phase6.py", "Phase 6 verifier")
    for token in (
        "Phase 7 is a finite release-readiness phase",
        "Phase 5 remains permanently closed at Phase 5H",
        "Phase 6 remains permanently closed",
        "new tracking algorithms",
        "new FACE_ONLY algorithms",
        "HEVC export",
        "4K60 export",
        "CoreML/Metal delegate performance tuning",
        "implementation complete pending physical-device acceptance",
    ):
        check(token in docs, f"Phase 7 boundary document missing: {token}")
    check("Phase 8:" not in docs and "Phase 9:" not in docs, "Phase 7 must not pre-create later escape phases")
    check("### Phase 5 boundary (closed)" in implementation, "Phase 5 closure must remain documented")
    check("### Phase 6 boundary (closed)" in implementation, "Phase 6 closure must remain documented")
    check("verify_ios_phase7.py" not in phase5, "Phase 5 verifier must remain independent of Phase 7")
    check("verify_ios_phase7.py" not in phase6, "Phase 6 verifier must remain independent of Phase 7")


def verify_release_metadata() -> None:
    pubspec = text(APP / "pubspec.yaml", "application pubspec")
    match = re.search(r"(?m)^version:\s*([^+\s]+)\+(\d+)\s*$", pubspec)
    check(match is not None, "Application pubspec must pin a semantic release version and integer build number")
    if match is not None:
        check(
            re.fullmatch(r"\d+\.\d+\.\d+", match.group(1)) is not None,
            "iOS release version must be x.y.z",
        )

    lock = text(APP / "pubspec.lock", "tracked application dependency lock")
    for token in (
        "file_picker_darwin:",
        "video_player_avfoundation:",
        "dance_native:",
        "flutter_riverpod:",
        "go_router:",
    ):
        check(token in lock, f"Application dependency lock missing expected release dependency: {token}")
    gitignore = text(ROOT / ".gitignore", "root gitignore")
    check("!mobile/app/pubspec.lock" in gitignore, "Release app pubspec.lock must be explicitly tracked")
    check(
        "mobile/app/ios/Flutter/Phase7Release.xcconfig" in gitignore,
        "Generated Phase 7 commit xcconfig must stay untracked",
    )

    info = load_plist(IOS / "Runner/Info.plist", "Runner Info.plist")
    check(info.get("CFBundleIdentifier") == "$(PRODUCT_BUNDLE_IDENTIFIER)", "Runner bundle ID must remain build-setting driven")
    check(info.get("CFBundleShortVersionString") == "$(FLUTTER_BUILD_NAME)", "Runner version must remain Flutter driven")
    check(info.get("CFBundleVersion") == "$(FLUTTER_BUILD_NUMBER)", "Runner build number must remain Flutter driven")
    check(info.get("WoahGitCommit") == "$(WOAH_GIT_COMMIT)", "Runner must preserve the auditable commit Info.plist hook")
    check(
        info.get("WoahEnableSmokeHooks") == "$(WOAH_ENABLE_SMOKE_HOOKS)",
        "Runner must expose the release smoke-hook build setting for final bundle audit",
    )

    project = text(IOS / "Runner.xcodeproj/project.pbxproj", "Runner Xcode project")
    check(project.count("PRODUCT_BUNDLE_IDENTIFIER = art.gaoge.dance;") >= 3, "Runner Debug/Profile/Release bundle ID must remain art.gaoge.dance")
    check(project.count("IPHONEOS_DEPLOYMENT_TARGET = 17.0;") >= 3, "iOS deployment target must remain 17.0 across project configurations")
    release_config = text(IOS / "Flutter/Release.xcconfig", "Release xcconfig")
    check('#include "Generated.xcconfig"' in release_config, "Release xcconfig must inherit Flutter generated settings")
    check("WOAH_ENABLE_SMOKE_HOOKS = NO" in release_config, "Release xcconfig must disable smoke hooks by default")
    check('#include? "Phase7Release.xcconfig"' in release_config, "Release xcconfig must accept the CI-generated commit identity")


def verify_privacy_and_permissions() -> None:
    info = load_plist(IOS / "Runner/Info.plist", "Runner Info.plist")
    check(
        isinstance(info.get("NSPhotoLibraryAddUsageDescription"), str)
        and bool(str(info.get("NSPhotoLibraryAddUsageDescription", "")).strip()),
        "Runner must explain add-only Photos access",
    )
    check("NSPhotoLibraryUsageDescription" not in info, "Phase 7 must not add broad Photos read permission")

    for path, label in (
        (IOS / "Runner/PrivacyInfo.xcprivacy", "Runner privacy manifest"),
        (SOURCES / "PrivacyInfo.xcprivacy", "dance_native privacy manifest"),
    ):
        manifest = load_plist(path, label)
        check(manifest.get("NSPrivacyTracking") is False, f"{label} must explicitly disable tracking")
        check(manifest.get("NSPrivacyTrackingDomains") == [], f"{label} must declare no tracking domains")
        check(manifest.get("NSPrivacyCollectedDataTypes") == [], f"{label} must declare no collected data types")
        check(manifest.get("NSPrivacyAccessedAPITypes") == [], f"{label} must declare no required-reason API categories")

    bridge = text(SOURCES / "IOSMediaLibraryBridge.swift", "iOS media library bridge")
    for token in (
        "PHPhotoLibrary.authorizationStatus(for: .addOnly)",
        "PHPhotoLibrary.requestAuthorization(for: .addOnly)",
        'code: "PHOTO_LIBRARY_PERMISSION_DENIED"',
        "Add-only Photos access intentionally avoids requesting broad read access",
    ):
        check(token in bridge, f"Add-only Photos fail-safe contract missing: {token}")


def verify_model_contract() -> None:
    contract = json.loads(text(MODEL_CONTRACT, "YOLO model contract") or "{}")
    expected_hash = contract.get("expected_sha256")
    pinned = isinstance(expected_hash, str) and re.fullmatch(r"[0-9a-f]{64}", expected_hash) is not None
    check(pinned, "Phase 7 Release acceptance requires a pinned YOLO expected_sha256")
    check(
        contract.get("source") == "models/litert/yolo11n-seg-fp16.tflite",
        "Phase 7 canonical YOLO source path drifted",
    )
    check(contract.get("expected_size_bytes") == 11799725, "Phase 7 YOLO byte-size contract drifted")
    check(contract.get("flatbuffer_magic") == "TFL3", "Phase 7 YOLO FlatBuffer contract drifted")
    canonical_model = ROOT / str(contract.get("source", ""))
    check(canonical_model.is_file(), "Phase 7 Release checkout must contain the canonical YOLO model")
    check(is_git_tracked(canonical_model), "Phase 7 canonical YOLO model must be Git-tracked")
    if canonical_model.is_file():
        model_bytes = canonical_model.read_bytes()
        check(len(model_bytes) == 11799725, "Tracked canonical YOLO model size drifted")
        check(
            hashlib.sha256(model_bytes).hexdigest() == expected_hash,
            "Tracked canonical YOLO model whole-file SHA-256 drifted",
        )
        check(len(model_bytes) >= 8 and model_bytes[4:8] == b"TFL3", "Tracked canonical YOLO model is not TFL3")
        zip_offset = model_bytes.find(b"PK\x03\x04", max(8, len(model_bytes) - 65536))
        check(zip_offset == 11798720, "Tracked canonical YOLO model FlatBuffer/metadata boundary drifted")
        if zip_offset >= 0:
            check(
                hashlib.sha256(model_bytes[:zip_offset]).hexdigest()
                == "881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c",
                "Tracked canonical YOLO model FlatBuffer core SHA-256 drifted",
            )

    model_notice = ROOT / "models/litert/README.md"
    notice = text(model_notice, "canonical YOLO third-party model notice")
    check(is_git_tracked(model_notice), "Canonical YOLO third-party model notice must be Git-tracked")
    for token in (
        "AGPL-3.0",
        "not relicensed by",
        "ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340",
        "881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c",
    ):
        check(token in notice, f"Canonical YOLO third-party model notice missing: {token}")

    gitignore = text(ROOT / ".gitignore", "repository ignore policy")
    check(
        "!models/litert/yolo11n-seg-fp16.tflite" in gitignore,
        "Phase 7 canonical model must remain explicitly exempt from the global model ignore",
    )
    checkpoint = contract.get("source_checkpoint") or {}
    check(checkpoint.get("path") == "models/pytorch/yolo11n-seg.pt", "Phase 7 YOLO checkpoint path drifted")
    check(checkpoint.get("expected_size_bytes") == 6182636, "Phase 7 YOLO checkpoint size drifted")
    check(
        checkpoint.get("expected_sha256") == "55ed65c56c91713d23e8402371c6c49a6fd84f257f7dce452e8d70e41dcbe152",
        "Phase 7 YOLO checkpoint hash drifted",
    )
    reproducibility = contract.get("reproducibility") or {}
    check(reproducibility.get("expected_core_size_bytes") == 11798720, "Phase 7 YOLO FlatBuffer core size drifted")
    check(
        reproducibility.get("expected_core_sha256") == "881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c",
        "Phase 7 YOLO FlatBuffer core hash drifted",
    )
    check(reproducibility.get("canonical_metadata_tail_size_bytes") == 1005, "Phase 7 canonical metadata tail size drifted")
    check(
        reproducibility.get("canonical_metadata_tail_sha256") == "25c2b7ba6ecd5021c417426266682a7e580d2ea62fbf0da836fe4b7be8e5f2d1",
        "Phase 7 canonical metadata tail hash drifted",
    )
    exporter = reproducibility.get("exporter") or {}
    check(exporter.get("ultralytics_version") == "8.4.130", "Phase 7 canonical Ultralytics exporter version drifted")
    check(exporter.get("format") == "litert", "Phase 7 canonical export format must remain litert")
    check(exporter.get("quantize") is None, "Phase 7 canonical LiteRT export must remain unquantized")
    check(exporter.get("graph_precision") == "float32", "Phase 7 canonical graph precision contract drifted")
    environment = reproducibility.get("environment") or {}
    check(
        environment.get("cpu_dispatch_candidates") == ["default", "avx2"],
        "Phase 7 CPU dispatch candidate set drifted",
    )
    check(environment.get("torch_num_threads") == 1, "Phase 7 exporter torch_num_threads must remain 1")
    check(
        environment.get("torch_num_interop_threads") == 1,
        "Phase 7 exporter torch_num_interop_threads must remain 1",
    )
    check(
        environment.get("process_thread_env")
        == {
            "OMP_NUM_THREADS": "1",
            "MKL_NUM_THREADS": "1",
            "OPENBLAS_NUM_THREADS": "1",
            "NUMEXPR_NUM_THREADS": "1",
            "VECLIB_MAXIMUM_THREADS": "1",
            "PYTHONHASHSEED": "0",
        },
        "Phase 7 exporter process-thread environment drifted",
    )
    for key, expected in (
        ("isolation", "temporary_venv"),
        ("platform", "linux_x86_64"),
        ("torch_version", "2.13.0+cpu"),
        ("torch_wheel_sha256", "6746dbcbeb526eb61330b76b41ff1b4eb848951103a892eeb080dfa2b264667b"),
        ("torchvision_version", "0.28.0+cpu"),
        ("torchvision_wheel_sha256", "1dad604dfc0177ecebe0891bd9701fe2c62ec3f7819a247be541b3fb6effee99"),
        ("numpy_version", "2.4.6"),
        ("tflite_schema_version", "2.18.0"),
        ("flatbuffers_version", "25.2.10"),
        ("litert_torch_version", "0.9.4"),
        ("ai_edge_litert_version", "2.2.0"),
        ("ai_edge_quantizer_version", "0.9.0"),
        ("litert_converter_version", "0.4.0"),
        ("torchao_version", "0.18.0"),
        ("litert_lm_builder_version", "0.16.1"),
        ("exclude_newer_utc", "2026-08-27T05:36:51Z"),
    ):
        check(environment.get(key) == expected, f"Phase 7 canonical exporter environment drifted: {key}")
    tail_path = ROOT / str(reproducibility.get("canonical_metadata_tail_path", ""))
    check(tail_path.is_file(), "Phase 7 canonical metadata tail evidence is missing")

    semantic = reproducibility.get("semantic_fingerprint", {})
    for key, expected in (
        ("schema", 1),
        ("tensor_count", 643),
        ("operator_count", 394),
        ("constant_tensor_count", 248),
        ("constant_bytes", 11576060),
        ("structure_sha256", "ca2dc210ac9ada611b83d4cc5dd3400d8209b4f9464ee125ed1f4db3654dd66b"),
        ("constants_sha256", "8e2bc7b693a5d42c2a1a19ef2dfe2264f8b3be82e5f89a04684eaddc25a0f9a3"),
        ("semantic_sha256", "5b65e547b81bb394e0036948b7ece40c270bf88b89f92d78c19708f378be65bd"),
    ):
        check(semantic.get(key) == expected, f"Phase 7 canonical semantic fingerprint drifted: {key}")

    fingerprint_tool = ROOT / "tools/release/fingerprint_tflite_semantics.py"
    check(fingerprint_tool.is_file(), "Phase 7 TFLite semantic fingerprint tool is missing")
    fingerprint_source = text(fingerprint_tool, "Phase 7 TFLite semantic fingerprint tool")
    for token in (
        "multiset_sha256",
        "fp32_ulp8_multiset_sha256",
        "fp32_ulp12_multiset_sha256",
        "float32_stats",
        '"schema": 2',
    ):
        check(token in fingerprint_source, f"Phase 7 constant diagnostic fingerprint contract missing: {token}")
    constant_manifest = ROOT / str(reproducibility.get("constant_manifest_path", ""))
    check(constant_manifest.is_file(), "Phase 7 canonical constant manifest is missing")
    if constant_manifest.is_file():
        manifest_bytes = constant_manifest.read_bytes()
        check(
            hashlib.sha256(manifest_bytes).hexdigest() == reproducibility.get("constant_manifest_sha256"),
            "Phase 7 canonical constant manifest file hash drifted",
        )
        try:
            manifest = json.loads(manifest_bytes.decode("utf-8"))
        except Exception:
            manifest = {}
            check(False, "Phase 7 canonical constant manifest is invalid JSON")
        constants = manifest.get("constants") if isinstance(manifest, dict) else None
        check(manifest.get("schema") == 2 if isinstance(manifest, dict) else False,
              "Phase 7 canonical constant manifest schema drifted")
        check(isinstance(constants, list) and len(constants) == 248,
              "Phase 7 canonical constant manifest entry count drifted")
        if isinstance(constants, list):
            check(sum(int(item.get("bytes", 0)) for item in constants if isinstance(item, dict)) == 11576060,
                  "Phase 7 canonical constant manifest byte total drifted")
            required_constant_fields = {
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
                check(
                    isinstance(item, dict) and required_constant_fields.issubset(item),
                    "Phase 7 canonical constant diagnostic record is incomplete",
                )
                if not isinstance(item, dict):
                    continue
                if item.get("element_width") is not None:
                    check(
                        isinstance(item.get("multiset_sha256"), str)
                        and len(item.get("multiset_sha256", "")) == 64,
                        "Phase 7 constant multiset fingerprint is missing",
                    )
                if item.get("type") == "FLOAT32":
                    for field in ("fp32_ulp8_multiset_sha256", "fp32_ulp12_multiset_sha256"):
                        check(
                            isinstance(item.get(field), str) and len(item.get(field, "")) == 64,
                            f"Phase 7 FLOAT32 diagnostic fingerprint is missing: {field}",
                        )
                    check(isinstance(item.get("float32_stats"), dict),
                          "Phase 7 FLOAT32 diagnostic statistics are missing")
            constant_identity = [
                {
                    "tensor": item.get("tensor"),
                    "bytes": item.get("bytes"),
                    "sha256": item.get("sha256"),
                }
                for item in constants
                if isinstance(item, dict)
            ]
            canonical_digest = hashlib.sha256(
                json.dumps(constant_identity, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            ).hexdigest()
            check(canonical_digest == semantic.get("constants_sha256"),
                  "Phase 7 canonical constant manifest aggregate hash drifted")
    if tail_path.is_file():
        try:
            tail = base64.b64decode(tail_path.read_text(encoding="ascii").strip(), validate=True)
        except Exception:
            tail = b""
            check(False, "Phase 7 canonical metadata tail is not valid Base64")
        check(
            len(tail) == reproducibility.get("canonical_metadata_tail_size_bytes"),
            "Phase 7 canonical metadata tail decoded size drifted",
        )
        check(
            hashlib.sha256(tail).hexdigest() == reproducibility.get("canonical_metadata_tail_sha256"),
            "Phase 7 canonical metadata tail decoded hash drifted",
        )

    provision = text(ROOT / "tools/release/provision_ios_yolo_ci.py", "Phase 7 canonical model provisioner")
    for token in (
        '"--exclude-newer"',
        'format=exporter["format"]',
        'quantize=exporter["quantize"]',
        "fingerprint_tflite_semantics.py",
        "repeat_core_equal",
        "semantic_mismatches",
        "compare_constant_manifests",
        "constant_diff",
        "multiset_equal_count",
        "fp32_ulp8_equal_count",
        "fp32_ulp12_equal_count",
        "float_stats_equal_count",
        "ATEN_CPU_CAPABILITY",
        "cpu_dispatch_candidates",
        "torch_num_threads",
        "torch_num_interop_threads",
        "PHASE7_MODEL_CPU_RUNTIME",
        "PHASE7_MODEL_CPU_CANDIDATE",
        "Phase 7 LiteRT CPU dispatch reproducibility failure",
        "Phase 7 selected CPU dispatch repeat failure",
        "PHASE7_MODEL_CHECKPOINT_SHA256",
        "PHASE7_MODEL_EXPORTER_VERSIONS",
        "PHASE7_MODEL_EXPORTER_ISOLATION=temporary_venv",
        "PHASE7_MODEL_GENERATED",
        '"--worker-export"',
        '"--worker-versions"',
        '"-m", "venv"',
        "torch_wheel_url",
        "torchvision_wheel_url",
        "canonical_tail(contract)",
        "IOS_YOLO_CORE_SHA256",
    ):
        check(token in provision, f"Phase 7 model reproducibility provisioner missing: {token}")


def verify_release_regression_contract() -> None:
    matrix_path = ROOT / "tools/ios/phase7_regression_matrix.json"
    matrix = json.loads(text(matrix_path, "Phase 7 regression matrix") or "{}")
    check(matrix.get("phase") == 7, "Release regression matrix must belong to Phase 7")
    check(
        matrix.get("status_after_ci") == "implementation complete pending physical-device acceptance",
        "Regression matrix must preserve the post-CI physical-device boundary",
    )
    expected = {
        "full_body_real_mp4",
        "face_only_real_mp4",
        "mixed_full_body_face_only",
        "crossing_occlusion_reacquisition",
        "trim",
        "audio_preservation",
        "no_audio_source",
        "cancel_cleanup",
        "failure_cleanup",
        "portrait_landscape_transform",
        "common_cfr_inputs",
        "vfr_timestamp_handling",
        "h264_1080p30_output",
    }
    cases = matrix.get("cases", [])
    ids = {str(item.get("id")) for item in cases if isinstance(item, dict)}
    check(ids == expected, f"Phase 7 regression matrix drifted: expected={sorted(expected)} actual={sorted(ids)}")
    check(
        all(item.get("physical_device_required") is True for item in cases if isinstance(item, dict)),
        "Every Phase 7 release regression case must remain on the physical-device acceptance checklist",
    )
    phase7_simulator_cases = {
        "no_audio_source",
        "failure_cleanup",
        "portrait_landscape_transform",
        "vfr_timestamp_handling",
    }
    for item in cases:
        if isinstance(item, dict) and item.get("id") in phase7_simulator_cases:
            check(
                item.get("release_ci_evidence") == "phase7_release_simulator_smoke",
                f"{item.get('id')} must be backed by the Phase 7 Release Simulator smoke",
            )

    smoke = text(SOURCES / "IOSExportPhase4Smoke.swift", "real-media export smoke")
    for token in (
        "EXPORT_SMOKE_AUDIO_MISSING",
        "EXPORT_SMOKE_TRIM_MISMATCH",
        "EXPORT_SMOKE_CANCEL_STATE_MISMATCH",
        "EXPORT_SMOKE_CANCEL_PARTIAL_LEAK",
        "EXPORT_SMOKE_CODEC_MISMATCH",
        "EXPORT_SMOKE_DIMENSIONS_MISMATCH",
        "FACE_ONLY_EXPORT_SMOKE_MEDIA_MISMATCH",
    ):
        check(token in smoke, f"Inherited real-media release regression evidence missing: {token}")

    pipeline = text(SOURCES / "IOSExportPipeline.swift", "iOS export pipeline")
    for token in (
        "let audioTrack = try await asset.loadTracks(withMediaType: .audio).first",
        "if let audioTrack {",
        "audioInput = nil",
        "let sourcePTS = CMSampleBufferGetPresentationTimeStamp(sample)",
        "Int64(floor(relativeSeconds * targetFps + 0.0001))",
        "preferredTransform = try await videoTrack.load(.preferredTransform)",
        "if displaySize.height > displaySize.width",
        "writer.cancelWriting()",
        "try? fileManager.removeItem(at: output.temp)",
        "AVVideoCodecType.h264",
        "let targetFps = 30.0",
    ):
        check(token in pipeline, f"Phase 7 static export regression contract missing: {token}")

    phase7_smoke = text(SOURCES / "IOSReleasePhase7Smoke.swift", "Phase 7 Release media smoke")
    for token in (
        "enum IOSReleasePhase7Smoke",
        "PHASE7_NO_AUDIO_OUTPUT_HAS_AUDIO",
        "PHASE7_FAILURE_PARTIAL_LEAK",
        "PHASE7_PORTRAIT_TRANSFORM_FIXTURE",
        "PHASE7_VFR_FIXTURE_NOT_VFR",
        "distinctIntervals >= 2",
        "expectedWidth: 1920",
        "expectedHeight: 1080",
        "expectedWidth: 1080",
        "expectedHeight: 1920",
        "InjectedFailure.inference",
        "AVVideoCodecType.h264",
        "outputInfo.timeline.frameCount == expectedFrames",
        "measuredFps >= 28.5",
        "nominalFrameRate >= 29.0",
    ):
        check(token in phase7_smoke, f"Phase 7 Release media smoke missing: {token}")

    plugin = text(SOURCES / "DanceNativePlugin.swift", "dance_native iOS plugin")
    check('case "runIOSReleasePhase7Smoke"' in plugin, "Phase 7 Release smoke MethodChannel hook is missing")
    check("IOSReleasePhase7Smoke.run()" in plugin, "Phase 7 MethodChannel hook must execute the Phase 7-owned smoke")
    for token in (
        'call.method.hasPrefix("runIOS")',
        "iosSmokeHooksEnabled()",
        'object(forInfoDictionaryKey: "WoahEnableSmokeHooks")',
        "FlutterMethodNotImplemented",
    ):
        check(token in plugin, f"Release smoke-hook fail-closed guard missing: {token}")

    dart = text(APP / "lib/ios_phase7_smoke_main.dart", "Phase 7 Release smoke entrypoint")
    for token in (
        "runIOSMetalPhase3Smoke",
        "runIOSExportPhase4Smoke",
        "runIOSGoldenTracePhase5Smoke",
        "runIOSPrivacyClassPhase6Smoke",
        "runIOSReleasePhase7Smoke",
        "WOAH_RELEASE_PHASE7_SMOKE=PASS",
    ):
        check(token in dart, f"Phase 7 Release smoke entrypoint missing inherited/Phase 7 marker: {token}")


def verify_release_tooling() -> None:
    audit = text(ROOT / "tools/ios/audit_phase7_release_bundle.py", "Release bundle auditor")
    for token in (
        "pubspec_lock_sha256",
        "contract_sha256",
        "packaged_sha256",
        "privacy_manifests",
        "frameworks",
        "executable_sha256",
        "WoahGitCommit",
        "WoahEnableSmokeHooks",
        "smoke_hooks_enabled",
        "Production Release bundle must not enable iOS smoke MethodChannel hooks",
        "expected_sha256",
        "apple-only-release-build-not-physical-device-acceptance",
    ):
        check(token in audit, f"Release bundle audit missing evidence field/guard: {token}")

    production_smoke = text(
        ROOT / "tools/ios/run_phase7_production_simulator_smoke.py",
        "production Release Simulator smoke",
    )
    for token in (
        "verify_production_bundle_contract(app)",
        "WoahEnableSmokeHooks",
        "Production Release Simulator must not enable iOS smoke MethodChannel hooks",
        "IOS_PHASE7_PRODUCTION_SIMULATOR_SMOKE_HOOKS=DISABLED",
        '"--console"',
        "IOS_PHASE7_PRODUCTION_SIMULATOR_LIVENESS=PASS",
        "boot_iphone(app)",
        "safe_run",
    ):
        check(token in production_smoke, f"Production Simulator liveness gate missing: {token}")

    macos = text(ROOT / "tools/ios/run_phase7_macos_gate.py", "Phase 7 macOS gate")
    for token in (
        "for phase in range(0, 8)",
        'overrides = {"GITHUB_ACTIONS": "false"} if phase == 1 else None',
        "enable_smoke_hooks=True",
        "enable_smoke_hooks=False",
        "WOAH_ENABLE_SMOKE_HOOKS",
        "run_phase6_macos_gate.py",
        '"--enforce-lockfile"',
        '"--simulator"',
        '"--release"',
        "lib/ios_phase7_smoke_main.dart",
        "run_phase7_simulator_smoke.py",
        "lib/main.dart",
        "run_phase7_production_simulator_smoke.py",
        '"--no-codesign"',
        "audit_phase7_release_bundle.py",
        "Woah-ios-release-app.zip",
        "IOS_PHASE7_MACOS_GATE=PASS",
        "implementation complete pending physical-device acceptance",
    ):
        check(token in macos, f"Phase 7 macOS Release gate missing: {token}")

    simulator = text(ROOT / "tools/ios/run_phase7_simulator_smoke.py", "Phase 7 Release Simulator runner")
    for token in (
        "WOAH_METAL_PHASE3_SMOKE=PASS",
        "WOAH_EXPORT_PHASE4_SMOKE=PASS",
        "WOAH_GOLDEN_TRACE_PHASE5_SMOKE=PASS",
        "WOAH_PRIVACY_CLASS_PHASE6_SMOKE=PASS",
        "WOAH_RELEASE_PHASE7_SMOKE=PASS",
        "IOS_SIMULATOR_PHASE7_RELEASE_REGRESSION=PASS",
        "boot_iphone(app)",
    ):
        check(token in simulator, f"Phase 7 Release Simulator runner missing: {token}")

    workflow_template = ROOT / "tools/ios/ios-release.phase7.workflow.yml"
    workflow = text(workflow_template, "Phase 7 GitHub workflow template")
    workflow_path = ROOT / ".github/workflows/ios-release.yml"
    tracked_workflow = workflow_path.is_file() and is_git_tracked(workflow_path)
    check(
        tracked_workflow,
        "Phase 7 GitHub Release workflow must be committed, not merely present as an untracked workspace file",
    )
    if tracked_workflow:
        installed_workflow = text(workflow_path, "committed Phase 7 GitHub workflow")
        check(
            installed_workflow == workflow,
            "Committed Phase 7 GitHub Release workflow must exactly match the tracked workflow template",
        )
    for token in (
        "name: iOS Phase 7 Release Readiness",
        "runs-on: macos-26",
        "Reproduce pinned Release YOLO model",
        "provision_ios_yolo_ci.py",
        "woah-ios-phase7-model-${{ github.run_id }}",
        "run_phase7_macos_gate.py",
        "woah-ios-release-${{ github.sha }}",
        "phase7_release_audit.json",
    ):
        check(token in workflow, f"Independent Phase 7 GitHub Release workflow missing: {token}")
    legacy_cloud = text(ROOT / ".github/workflows/ios-cloud.yml", "legacy iOS Cloud CI")
    check("run_phase7_macos_gate.py" not in legacy_cloud, "Phase 7 must remain an independent Release workflow")


def verify_device_acceptance_package() -> None:
    runbook = text(ROOT / "docs/ios_phase7_device_acceptance.md", "physical-device acceptance runbook")
    for token in (
        "Git commit",
        "app executable SHA-256",
        "pubspec.lock",
        "model contract SHA-256",
        "testdata/videos/01_sample.mp4",
        "variable-frame-rate",
        "FULL_BODY",
        "FACE_ONLY",
        "mixed FULL_BODY + FACE_ONLY",
        "thermal state",
        "background",
        "interruption",
        "final visual privacy",
        "implementation complete pending physical-device acceptance",
    ):
        check(token.lower() in runbook.lower(), f"Physical-device acceptance runbook missing: {token}")

    report_template_path = ROOT / "tools/ios/phase7_device_acceptance_report.template.json"
    report_template = json.loads(text(report_template_path, "physical-device report template") or "{}")
    check(report_template.get("schema_version") == 1, "Physical-device report template schema must be version 1")
    check(report_template.get("phase") == 7, "Physical-device report template must belong to Phase 7")
    check(report_template.get("acceptance_status") == "pending", "Physical-device report template must start pending")
    matrix = json.loads(text(ROOT / "tools/ios/phase7_regression_matrix.json", "Phase 7 regression matrix") or "{}")
    expected_cases = {str(item.get("id")) for item in matrix.get("cases", []) if isinstance(item, dict)}
    template_cases = report_template.get("functional_cases", {})
    check(set(template_cases) == expected_cases, "Physical-device report functional cases must exactly mirror the Phase 7 regression matrix")
    check(len(report_template.get("media", {})) == 8, "Physical-device report template must contain the fixed eight-media set")
    for section in ("artifact", "device", "privacy_review", "performance", "background_interruption", "stability"):
        check(isinstance(report_template.get(section), dict), f"Physical-device report template missing section: {section}")

    preparer = text(ROOT / "tools/ios/prepare_phase7_device_acceptance_report.py", "physical-device report preparer")
    for token in (
        "phase7_release_audit.json",
        "release_audit_sha256",
        "pubspec_lock_sha256",
        "model_contract_sha256",
        "privacy_manifests",
        "testdata/videos/01_sample.mp4",
    ):
        check(token in preparer or token in runbook, f"Physical-device report preparer/runbook missing: {token}")

    verifier = text(ROOT / "tools/ios/verify_phase7_device_acceptance_report.py", "physical-device report verifier")
    for token in (
        '"--require-accepted"',
        "physical-device-acceptance",
        "unresolved_escape_count",
        "analyze_fps",
        "export_processed_fps",
        "peak_memory_mib",
        "thermal_states",
        "idle_background_round_trip",
        "export_background_round_trip",
        "crash_count",
        "hang_count",
        "IOS_PHASE7_PHYSICAL_DEVICE_ACCEPTANCE=PASS",
    ):
        check(token in verifier, f"Physical-device report verifier missing: {token}")


def main() -> int:
    verify_boundary()
    verify_release_metadata()
    verify_privacy_and_permissions()
    verify_model_contract()
    verify_release_regression_contract()
    verify_release_tooling()
    verify_device_acceptance_package()

    if FAILURES:
        print("iOS Phase 7 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 7 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

