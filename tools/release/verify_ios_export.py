#!/usr/bin/env python3
"""Static contract gate for Woah iOS export real-video export.

This verifier is host-independent. It proves repository intent and guards
cross-platform privacy/media semantics. Apple compilation and the real MP4
end-to-end export are validated by run_native_regression.py on GitHub macOS.
Real iPhone visual/performance/thermal acceptance remains a separate gate.
"""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def text(path: Path, label: str) -> str:
    check(path.is_file(), f"Missing iOS export asset: {label}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def source(name: str) -> str:
    return text(SOURCES / name, name)


def verify_media_pipeline() -> None:
    pipeline = source("IOSExportPipeline.swift")
    for token in (
        "AVAssetReader",
        "AVAssetReaderTrackOutput",
        "AVAssetWriter",
        "AVAssetWriterInputPixelBufferAdaptor",
        "AVVideoCodecType.h264",
        "request.targetFps.isFinite",
        "AVVideoExpectedSourceFrameRateKey: expectedFrameRate",
        "AVVideoProfileLevelH264HighAutoLevel",
        "kAudioFormatMPEG4AAC",
        "kAudioFormatLinearPCM",
        "reader.timeRange = timeRange",
        "trimStartMs",
        "trimEndMs",
        "CMTimeSubtract(sourcePTS, trimStart)",
        "withPresentationTime: presentation",
        "CMSampleBufferCreateCopyWithNewTiming",
        "preferredTransform",
        "IOSYoloRunner()",
        "preferredBackend: .xnnpack",
        "IOSTemporalIdentityTracker(",
        "IOSMetalPreviewRenderer()",
        "outputWidth: target.width",
        "outputHeight: target.height",
        "cancellation.isCancelled",
        "commitIfNotCancelled",
        "guard committed else { throw IOSExportPipelineError.cancelled }",
        'state: "exporting"',
        'partial.mp4',
        "fileManager.replaceItemAt",
        "fileManager.moveItem(at: temp, to: final)",
        "try? fileManager.removeItem(at: output.temp)",
    ):
        check(token in pipeline, f"export export pipeline missing: {token}")

    check(
        "let targetFps = 30.0" not in pipeline
        and "timescale: 30" not in pipeline
        and "longest > 1920" not in pipeline,
        "iOS export must not reintroduce fixed 30fps timing or a hidden 1920-pixel cap",
    )
    check(
        "typealias InferenceProvider" in pipeline
        and "if let inferenceProvider" in pipeline
        and "Production export identity/detection stays on XNNPACK" in pipeline,
        "Simulator inference seam must not replace the production XNNPACK path",
    )
    for supported_boundary in (
        "request.follow.enabled",
        "request.effects.skinWhiten > 0",
    ):
        check(
            supported_boundary in pipeline,
            f"export must preserve explicit validation and unsupported-effect rejection: {supported_boundary}",
        )
    # The renderer added protagonist leg stretch after export. Reject only an
    # impossible leg-stretch request (no follow root); do not require the now
    # supported effect to be rejected on every export.
    for token in (
        "request.effects.legStretchEnabled &&",
        "!request.follow.enabled || request.follow.targetPersonId == nil",
        '"Leg stretch requires an enabled protagonist follow target."',
        "legStretchTargetId: followTargetId",
    ):
        check(token in pipeline, f"iOS leg stretch export contract missing: {token}")
    renderer = source("IOSMetalPreviewRenderer.swift")
    for token in (
        "effects.legStretchEnabled ? legStretchTargetId : nil",
        "Self.buildLegStretchInputs(",
        "let legStretchValue = max(1.0, min(1.5, 1.0 + effects.legStretch))",
        # Simulator Metal has 14 constant-buffer slots. The W lane packs
        # independent leg-stretch (bit 0) and horizontal mirror (bit 1).
        "legStretchFlag: UInt32 = legInputs.enabled ? 1 : 0",
        "let geometryFlags = legStretchFlag | (mirrorHorizontally ? UInt32(2) : UInt32(0))",
        "var packedFlags = SIMD4<UInt32>(fillMode, stickerFlag, customStickerFlag, geometryFlags)",
        "encoder.setBytes(&packedFlags, length: MemoryLayout<SIMD4<UInt32>>.size, index: 0)",
        "const uint legStretchEnabled = packedFlags.w & 1u;",
        "if (legStretchEnabled == 1 && legStretch > 1.0",
    ):
        check(token in renderer, f"iOS renderer leg stretch implementation missing: {token}")

    # Check both sides of the packed flag ABI and the actual final pixel write,
    # not just the presence of a mirror setting. Preserve the existing slots.
    for token in (
        "const bool mirrorHorizontally = (packedFlags.w & 2u) != 0u;",
        "uint2 outputPixel = mirrorHorizontally ? uint2(width - 1u - gid.x, gid.y) : gid;",
        "output.write(float4(color.rgb, 1.0), outputPixel);",
    ):
        check(token in renderer, f"iOS renderer horizontal mirror implementation missing: {token}")
    for label, callsite in (
        ("preview", source("IOSPreviewPipeline.swift")),
        ("export", pipeline),
    ):
        check(
            "mirrorHorizontally: request.mirrorHorizontally == true" in callsite,
            f"iOS {label} must forward horizontal mirror to the renderer",
        )


def verify_temporal_privacy() -> None:
    tracker = source("IOSTemporalIdentityTracker.swift")
    renderer = source("IOSMetalPreviewRenderer.swift")
    matcher = source("IOSPreviewIdentityMatcher.swift")
    android = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/tracking/TrackManager.kt",
        "Android TrackManager",
    )

    for token in (
        "identityProtectedIds",
        "privacyTargetIds",
        ".filter { $0.confidence >= 0.60 }",
        "private let maxMissedFrames = 15",
        "private let maxOcclusionFrames = 90",
        "associationAmbiguityMargin: Float32 = 0.05",
        "bboxIoU",
        "maskIoU",
        "motionScore",
        "case active",
        "case occluded",
        "case lost",
        "case reacquiring",
        'code: "EXPORT_PRIVACY_UNRESOLVED"',
        "conservativeFallbackDetection",
        "conservativePrivacyFallback: !region.observed",
    ):
        check(token in tracker, f"export temporal privacy tracker missing: {token}")

    for token in (
        "val maxMissedFrames: Int = 15",
        "val occlusionMaxDurationFrames: Int = 90",
        "val associationAmbiguityMargin: Float = 0.05f",
        "setIdentityProtectedTrackIds",
        "setPrivacySelectedTrackIds",
    ):
        check(token in android, f"Android tracking contract drifted; revisit iOS export parity: {token}")

    check(
        "conservativePrivacyFallback" in matcher,
        "Tracked render persons must carry an explicit conservative fallback marker",
    )
    check(
        "if target.conservativePrivacyFallback" in renderer
        and "return effective" in renderer,
        "Predicted privacy fallback masks must not be carved by single-frame occlusion logic",
    )
    check(
        "ambiguous protected identities are not reassigned" in tracker,
        "iOS export must document ambiguity behavior for protected identities",
    )


def verify_job_contract() -> None:
    coordinator = source("IOSExportCoordinator.swift")
    plugin = source("DanceNativePlugin.swift")
    for token in (
        'state: "preparing"',
        'state: "completed"',
        'state: "cancelled"',
        'state: "failed"',
        'code: "EXPORT_BUSY"',
        "IOSExportCancellationFlag()",
        "pipeline.execute(",
        "cancellation?.cancel()",
        "guard cancellation?.cancel() == true else { return }",
        "hasActiveRuntime",
        "outputURL.absoluteString",
    ):
        check(token in coordinator, f"export job coordinator missing: {token}")

    start = plugin.find("func startExport")
    cancel = plugin.find("func cancelJob", start)
    status = plugin.find("func getJobStatus", cancel)
    release = plugin.find("func releaseProject", status)
    start_block = plugin[start:cancel]
    cancel_block = plugin[cancel:status]
    status_block = plugin[status:release]
    check(
        "exportCoordinator.start(request: request)" in start_block,
        "DanceNativePlugin.startExport must route through the iOS export coordinator",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' not in start_block,
        "iOS export startExport must not remain a platform stub",
    )
    check(
        "exportCoordinator.cancel(jobId: jobId)" in cancel_block,
        "DanceNativePlugin.cancelJob must route through the iOS export coordinator",
    )
    check(
        "exportCoordinator.status(jobId: jobId)" in status_block,
        "DanceNativePlugin.getJobStatus must expose the iOS job registry",
    )
    check(
        "DanceProcessingEvents(binaryMessenger:" in plugin
        and "processingEvents.onProgressUpdate(status: status)" in plugin,
        "iOS export must forward native progress through the existing Pigeon FlutterApi",
    )


def verify_real_video_gate() -> None:
    smoke = source("IOSExportSmoke.swift")
    dart = text(ROOT / "mobile/app/lib/ios_native_regression_main.dart", "combined iOS smoke entrypoint")
    simulator = text(ROOT / "tools/ios/run_simulator_regression.py", "export Simulator runner")
    macos_gate = text(ROOT / "tools/ios/run_native_regression.py", "export macOS gate")
    workflow = text(ROOT / ".github/workflows/ios-cloud.yml", "iOS Cloud CI")

    for token in (
        "AVAssetWriter(outputURL: videoURL, fileType: .mp4)",
        "AVVideoCodecType.h264",
        "kAudioFormatMPEG4AAC",
        "createToneFile",
        "IOSExportCoordinator(",
        "inferenceProvider: provider",
        "targetWidth: 1920",
        "targetHeight: 1080",
        "targetFps: Double(sourceFps)",
        "trimStartMs: trimStartMs",
        "trimEndMs: trimEndMs",
        "expectedOutputFrames: Int64 = 36",
        "finalStayedHiddenDuringProgress",
        "outputInfo.hasAudio",
        "outputInfo.audioDurationSeconds >= 0.50",
        "outputInfo.presentationFrameCount == expectedOutputFrames",
        "inspectVideoTimeline",
        "kCVPixelFormatType_32BGRA",
        "CMSampleBufferGetPresentationTimeStamp",
        "timestamps[index] <= timestamps[index - 1]",
        "outputInfo.nominalFrameRate >= 58.0",
        "outputInfo.minFrameDurationSeconds >= 0.015",
        "readOutputCenterPixel",
        "missingObservationCount",
        "cancelCoordinator.cancel(jobId: cancelJobId)",
        "CACHE_LEASE_RELEASED_EARLY",
        "CACHE_LEASE_NOT_RELEASED",
        "cancel_partial_clean",
    ):
        check(token in smoke, f"export real-video smoke missing: {token}")

    check(
        "WOAH_METAL_SMOKE=PASS" in dart
        and "WOAH_EXPORT_SMOKE=PASS" in dart,
        "Combined Simulator app must preserve preview and emit the export export marker",
    )
    check(
        "WOAH_METAL_SMOKE=PASS" in simulator
        and "WOAH_EXPORT_SMOKE=PASS" in simulator
        and 'timeout=600' in simulator,
        "export Simulator runner must require both GPU and real-video export markers",
    )
    for token in (
        "verify_export_media_contract.py",
        "verify_ios_preview.py",
        "verify_ios_export.py",
        "compile_preview_metal.py",
        '"iphoneos"',
        '"iphonesimulator"',
        '"--simulator"',
        "ios_native_regression_main.dart",
        "run_simulator_regression.py",
    ):
        check(token in macos_gate, f"export macOS gate missing preserved/required lane: {token}")
    check(
        "run: python tools/ios/run_native_regression.py" in workflow,
        "iOS Cloud CI must explicitly invoke the privacy-class gate preserving export",
    )


def main() -> int:
    verify_media_pipeline()
    verify_temporal_privacy()
    verify_job_contract()
    verify_real_video_gate()
    if FAILURES:
        print("iOS export verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS export static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
