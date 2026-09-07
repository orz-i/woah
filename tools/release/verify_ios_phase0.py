#!/usr/bin/env python3
"""Static release checks for the Woah iOS Phase 0 bootstrap.

This verifier is intentionally runnable on non-macOS hosts. It does not replace
Xcode compilation or device tests; it protects the repository contracts that can
be validated deterministically in CI before the macOS lane runs.
"""

from __future__ import annotations

import json
import plistlib
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def load_plist(relative_path: str) -> dict:
    path = ROOT / relative_path
    with path.open("rb") as stream:
        return plistlib.load(stream)


def png_metadata(path: Path) -> tuple[int, int, int]:
    data = path.read_bytes()[:32]
    if len(data) < 26 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"Not a valid PNG: {path}")
    width, height = struct.unpack(">II", data[16:24])
    color_type = data[25]
    return width, height, color_type


def verify_runner_configuration() -> None:
    info = load_plist("mobile/app/ios/Runner/Info.plist")
    check(info.get("CFBundleDisplayName") == "Woah", "Runner display name must be Woah")
    check(info.get("CFBundleName") == "Woah", "Runner bundle name must be Woah")
    check(
        bool(info.get("NSPhotoLibraryAddUsageDescription")),
        "Runner must declare add-only Photos usage",
    )
    check(
        "NSPhotoLibraryUsageDescription" not in info,
        "Runner should not request broad Photos read access in Phase 0",
    )
    check(
        info.get("CFBundleShortVersionString") == "$(FLUTTER_BUILD_NAME)",
        "Runner version must remain driven by Flutter build metadata",
    )
    check(
        info.get("CFBundleVersion") == "$(FLUTTER_BUILD_NUMBER)",
        "Runner build number must remain driven by Flutter build metadata",
    )
    check(
        info.get("WoahGitCommit") == "$(WOAH_GIT_COMMIT)",
        "Runner must retain the optional WOAH_GIT_COMMIT build-setting hook",
    )

    privacy = load_plist("mobile/app/ios/Runner/PrivacyInfo.xcprivacy")
    check(privacy.get("NSPrivacyTracking") is False, "Runner privacy manifest must disable tracking")
    check(
        privacy.get("NSPrivacyCollectedDataTypes") == [],
        "Runner privacy manifest must not claim off-device data collection",
    )

    project = (ROOT / "mobile/app/ios/Runner.xcodeproj/project.pbxproj").read_text(
        encoding="utf-8"
    )
    check(
        project.count("IPHONEOS_DEPLOYMENT_TARGET = 17.0;") == 3,
        "Runner project must use iOS 17.0 for all build configurations",
    )
    check(
        project.count("PRODUCT_BUNDLE_IDENTIFIER = art.gaoge.dance;") == 3,
        "Runner bundle identifier must be art.gaoge.dance",
    )
    check(
        project.count("PRODUCT_BUNDLE_IDENTIFIER = art.gaoge.dance.RunnerTests;") == 3,
        "RunnerTests bundle identifier must follow art.gaoge.dance",
    )
    check(
        "PrivacyInfo.xcprivacy in Resources" in project,
        "Runner privacy manifest must be included in the Resources build phase",
    )


def verify_app_icons() -> None:
    root = ROOT / "mobile/app/ios/Runner/Assets.xcassets/AppIcon.appiconset"
    contents = json.loads((root / "Contents.json").read_text(encoding="utf-8"))
    for entry in contents.get("images", []):
        filename = entry.get("filename")
        if not filename:
            continue
        logical_size = float(entry["size"].split("x", 1)[0])
        scale = float(entry["scale"].rstrip("x"))
        expected = round(logical_size * scale)
        path = root / filename
        check(path.is_file(), f"Missing iOS app icon: {filename}")
        if not path.is_file():
            continue
        try:
            width, height, color_type = png_metadata(path)
        except ValueError as error:
            FAILURES.append(str(error))
            continue
        check((width, height) == (expected, expected), f"Unexpected icon size for {filename}")
        check(
            color_type in {0, 2},
            f"iOS app icon must not contain an alpha channel: {filename}",
        )


def verify_native_plugin_contract() -> None:
    podspec = (ROOT / "mobile/packages/dance_native/ios/dance_native.podspec").read_text(
        encoding="utf-8"
    )
    package = (
        ROOT / "mobile/packages/dance_native/ios/dance_native/Package.swift"
    ).read_text(encoding="utf-8")
    plugin_privacy = load_plist(
        "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/PrivacyInfo.xcprivacy"
    )
    check("s.platform = :ios, '17.0'" in podspec, "dance_native Pod must target iOS 17")
    check("s.swift_version = '5.0'" in podspec, "dance_native Pod must use Swift 5 language mode")
    check(
        "s.resource_bundles = {'dance_native_privacy'" in podspec,
        "dance_native Pod must package its privacy manifest",
    )
    check('.iOS("17.0")' in package, "dance_native SwiftPM package must target iOS 17")
    check(
        '.process("PrivacyInfo.xcprivacy")' in package,
        "dance_native SwiftPM package must process its privacy manifest",
    )
    check(
        plugin_privacy.get("NSPrivacyTracking") is False,
        "dance_native privacy manifest must disable tracking",
    )

    example_project = (
        ROOT / "mobile/packages/dance_native/example/ios/Runner.xcodeproj/project.pbxproj"
    ).read_text(encoding="utf-8")
    check(
        example_project.count("IPHONEOS_DEPLOYMENT_TARGET = 17.0;") == 3,
        "dance_native example must match the iOS 17 plugin baseline",
    )

    sources = ROOT / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
    plugin = (sources / "DanceNativePlugin.swift").read_text(encoding="utf-8")
    capabilities = (sources / "IOSDeviceCapabilities.swift").read_text(encoding="utf-8")
    probe = (sources / "IOSVideoProbe.swift").read_text(encoding="utf-8")
    media = (sources / "IOSMediaLibraryBridge.swift").read_text(encoding="utf-8")

    for method in (
        'case "getBuildInfo"',
        'case "saveVideoToGallery"',
        'case "shareVideo"',
        'case "openVideo"',
        'case "getVideoFrameThumbnails"',
    ):
        check(method in plugin, f"Missing iOS MethodChannel surface: {method}")
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' in plugin,
        "Unimplemented AI/export pipeline must remain explicit",
    )
    check(
        "VTIsHardwareEncodeSupported" in capabilities
        and "RequireHardwareAcceleratedVideoEncoder" in capabilities,
        "iOS capability detection must query hardware encoder support",
    )
    check(
        "supportedProfiles: []" in capabilities and "inferenceBackends: []" in capabilities,
        "iOS must not advertise inference profiles before LiteRT is connected",
    )
    check(
        "formatDescriptions" in probe and "video/hevc" in probe and "audio/mp4a-latm" in probe,
        "iOS video probe must derive real codec metadata",
    )
    check(
        "authorizationStatus(for: .addOnly)" in media
        and "creationRequestForAssetFromVideo" in media,
        "iOS gallery save must use add-only Photos access",
    )
    check(
        "AVAssetImageGenerator" in media and "generator.image(at:" in media,
        "iOS trim thumbnails must use AVAssetImageGenerator",
    )


def main() -> int:
    verify_runner_configuration()
    verify_app_icons()
    verify_native_plugin_contract()

    if FAILURES:
        print("iOS Phase 0 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1

    print("iOS Phase 0 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
