#!/usr/bin/env python3
"""Host-independent guard for the shared mobile export media contract."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
FAILURES: list[str] = []


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        FAILURES.append(f"missing {path}")
        return ""
    return file.read_text(encoding="utf-8")


def require(text: str, token: str, label: str) -> None:
    if token not in text:
        FAILURES.append(f"{label} missing: {token}")


def forbid(text: str, token: str, label: str) -> None:
    if token in text:
        FAILURES.append(f"{label} reintroduced forbidden contract: {token}")


def main() -> int:
    plan = read("mobile/packages/dance_domain/lib/src/export_plan.dart")
    project = read("mobile/packages/dance_domain/lib/src/project.dart")
    controller = read("mobile/app/lib/features/export/presentation/export_controller.dart")
    protection_editor = read(
        "mobile/app/lib/features/protection_editor/presentation/protection_editor_screen.dart"
    )
    android = read(
        "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/pipeline/ExportPipeline.kt"
    )
    encoder = read(
        "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/media/VideoEncoder.kt"
    )
    android_caps = read(
        "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/device/DeviceCapabilities.kt"
    )
    ios = read(
        "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSExportPipeline.swift"
    )
    ios_caps = read(
        "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSDeviceCapabilities.swift"
    )
    smoke = read(
        "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSExportPhase4Smoke.swift"
    )

    for token in (
        "ExportTimingPolicy { preserveSourcePts }",
        "ExportFallbackReason { encoderDimensionLimit }",
        "OutputResolutionPreset.fhd => (width: 1920, height: 1080)",
        "OutputResolutionPreset.hd => (width: 1280, height: 720)",
        "maxEncodeWidth",
        "maxEncodeHeight",
        "0.13",
        "80_000_000",
    ):
        require(plan, token, "ExportPlan")
    require(project, "enum OutputResolutionPreset { source, fhd, hd }", "DanceProject")
    require(project, "outputResolutionPreset.name", "DanceProject")
    require(project, "Resolution limits belong to the", "DanceProject")
    forbid(project, "clamp(1, 60)", "DanceProject")

    for token in (
        "await _repository.getCapabilities()",
        "ExportPlan.forProject(",
        "targetWidth: plan.width",
        "targetHeight: plan.height",
        "targetFps: plan.nominalFps",
        "videoBitrate: plan.videoBitrate",
    ):
        require(controller, token, "ExportController")

    for token in (
        "output-resolution-preset",
        "OutputResolutionPreset.source",
        "OutputResolutionPreset.fhd",
        "OutputResolutionPreset.hd",
        "FHD / HD 只限制最大输出尺寸，不会放大低分辨率素材",
    ):
        require(protection_editor, token, "Protection editor resolution UI")

    for token in (
        "nominalOutputFps",
        "ptsUs - trimStartUs",
        "frameDurationNs",
        '"target_fps" to nominalOutputFps',
        "80_000_000L",
    ):
        require(android, token, "Android export")
    for token in ("maxDim > 1920", "request.targetFps in 1.0..60.0", "basePtsUs"):
        forbid(android, token, "Android export")
    require(encoder, "fps.roundToInt()", "Android encoder")
    require(android_caps, "var maxWidth = 0", "Android capabilities")
    require(android_caps, "var maxHeight = 0", "Android capabilities")

    for token in (
        "request.targetFps.isFinite",
        "CMTimeSubtract(sourcePTS, trimStart)",
        "withPresentationTime: presentation",
        "AVVideoExpectedSourceFrameRateKey: expectedFrameRate",
        "min(80_000_000",
    ):
        require(ios, token, "iOS export")
    for token in ("let targetFps = 30.0", "timescale: 30", "longest > 1920"):
        forbid(ios, token, "iOS export")
    require(ios_caps, "let h264Encoders = hardwareEncoders.filter", "iOS capabilities")
    require(ios_caps, "maximumHardwareEncodeSize(for: h264Encoders)", "iOS capabilities")

    require(smoke, "private static let sourceFps: Int32 = 60", "iOS media smoke")
    require(smoke, "expectedOutputFrames: Int64 = 36", "iOS media smoke")

    if FAILURES:
        for failure in FAILURES:
            print(f"FAIL: {failure}")
        return 1
    print("Shared export media contract verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
