#!/usr/bin/env python3
"""Host-independent contract gate for iOS Phase 7 pre-release readiness."""

from __future__ import annotations

import argparse
import json
import plistlib
import re
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

    project = text(IOS / "Runner.xcodeproj/project.pbxproj", "Runner Xcode project")
    check(project.count("PRODUCT_BUNDLE_IDENTIFIER = art.gaoge.dance;") >= 3, "Runner Debug/Profile/Release bundle ID must remain art.gaoge.dance")
    check(project.count("IPHONEOS_DEPLOYMENT_TARGET = 17.0;") >= 3, "iOS deployment target must remain 17.0 across project configurations")
    release_config = text(IOS / "Flutter/Release.xcconfig", "Release xcconfig")
    check('#include "Generated.xcconfig"' in release_config, "Release xcconfig must inherit Flutter generated settings")
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


def verify_model_contract(allow_unpinned_model: bool) -> None:
    contract = json.loads(text(MODEL_CONTRACT, "YOLO model contract") or "{}")
    expected_hash = contract.get("expected_sha256")
    pinned = isinstance(expected_hash, str) and re.fullmatch(r"[0-9a-f]{64}", expected_hash) is not None
    if not pinned and allow_unpinned_model:
        print("NOTE: Phase 7 model SHA-256 is not pinned yet; bootstrap mode only.")
    else:
        check(pinned, "Phase 7 Release acceptance requires a pinned YOLO expected_sha256")
    check(contract.get("expected_size_bytes") == 11799725, "Phase 7 YOLO byte-size contract drifted")
    check(contract.get("flatbuffer_magic") == "TFL3", "Phase 7 YOLO FlatBuffer contract drifted")


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
        "expected_sha256",
        "apple-only-release-build-not-physical-device-acceptance",
    ):
        check(token in audit, f"Release bundle audit missing evidence field/guard: {token}")

    production_smoke = text(
        ROOT / "tools/ios/run_phase7_production_simulator_smoke.py",
        "production Release Simulator smoke",
    )
    for token in (
        '"--console"',
        "IOS_PHASE7_PRODUCTION_SIMULATOR_LIVENESS=PASS",
        "boot_iphone(app)",
        "safe_run",
    ):
        check(token in production_smoke, f"Production Simulator liveness gate missing: {token}")

    macos = text(ROOT / "tools/ios/run_phase7_macos_gate.py", "Phase 7 macOS gate")
    for token in (
        "for phase in range(0, 8)",
        "run_phase6_macos_gate.py",
        '"--enforce-lockfile"',
        '"--simulator"',
        '"--release"',
        "lib/ios_phase6_smoke_main.dart",
        "lib/main.dart",
        "run_phase7_production_simulator_smoke.py",
        '"--no-codesign"',
        "audit_phase7_release_bundle.py",
        "Woah-ios-release-app.zip",
        "IOS_PHASE7_MACOS_GATE=PASS",
        "implementation complete pending physical-device acceptance",
    ):
        check(token in macos, f"Phase 7 macOS Release gate missing: {token}")

    workflow = text(ROOT / ".github/workflows/ios-release.yml", "Phase 7 GitHub workflow")
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--allow-unpinned-model",
        action="store_true",
        help="Bootstrap only: validate the rest of Phase 7 before the canonical model SHA-256 is pinned.",
    )
    args = parser.parse_args()

    verify_boundary()
    verify_release_metadata()
    verify_privacy_and_permissions()
    verify_model_contract(args.allow_unpinned_model)
    verify_release_regression_contract()
    verify_release_tooling()
    verify_device_acceptance_package()

    if FAILURES:
        print("iOS Phase 7 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    suffix = " (bootstrap: model hash not yet pinned)" if args.allow_unpinned_model else ""
    print(f"iOS Phase 7 static verification passed{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

