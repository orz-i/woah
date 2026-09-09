#!/usr/bin/env python3
"""Static contract gate for Woah iOS Phase 4 real-video export.

This verifier is host-independent. It proves repository intent and guards
cross-platform privacy/media semantics. Apple compilation and the real MP4
end-to-end export are validated by run_phase4_macos_gate.py on GitHub macOS.
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
    check(path.is_file(), f"Missing iOS Phase 4 asset: {label}")
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
        "let targetFps = 30.0",
        "AVVideoExpectedSourceFrameRateKey: 30",
        "AVVideoProfileLevelH264HighAutoLevel",
        "kAudioFormatMPEG4AAC",
        "kAudioFormatLinearPCM",
        "reader.timeRange = timeRange",
        "trimStartMs",
        "trimEndMs",
        "CMTimeSubtract(",
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
        check(token in pipeline, f"Phase 4 export pipeline missing: {token}")

    check(
        "typealias InferenceProvider" in pipeline
        and "if let inferenceProvider" in pipeline
        and "Production export identity/detection stays on XNNPACK" in pipeline,
        "Simulator inference seam must not replace the production XNNPACK path",
    )
    for unsupported in (
        "request.follow.enabled",
        "request.effects.skinWhiten > 0",
        "request.effects.legStretchEnabled",
    ):
        check(
            unsupported in pipeline,
            f"Phase 4 must explicitly reject unsupported semantics instead of silently downgrading: {unsupported}",
        )


def verify_temporal_privacy() -> None:
    tracker = source("IOSTemporalIdentityTracker.swift")
    renderer = source("IOSMetalPreviewRenderer.swift")
    matcher = source("IOSPreviewIdentityMatcher.swift")
    android = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/tracking/TrackManager.kt",
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
        "conservativePrivacyFallback: true",
    ):
        check(token in tracker, f"Phase 4 temporal privacy tracker missing: {token}")

    for token in (
        "val maxMissedFrames: Int = 15",
        "val occlusionMaxDurationFrames: Int = 90",
        "val associationAmbiguityMargin: Float = 0.05f",
        "setIdentityProtectedTrackIds",
        "setPrivacySelectedTrackIds",
    ):
        check(token in android, f"Android tracking contract drifted; revisit iOS Phase 4 parity: {token}")

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
        "iOS Phase 4 must document ambiguity behavior for protected identities",
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
        check(token in coordinator, f"Phase 4 job coordinator missing: {token}")

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
        "iOS Phase 4 startExport must not remain a platform stub",
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
        "iOS Phase 4 must forward native progress through the existing Pigeon FlutterApi",
    )


def verify_real_video_gate() -> None:
    smoke = source("IOSExportPhase4Smoke.swift")
    dart = text(ROOT / "mobile/app/lib/ios_phase4_smoke_main.dart", "combined iOS smoke entrypoint")
    simulator = text(ROOT / "tools/ios/run_phase4_simulator_smoke.py", "Phase 4 Simulator runner")
    macos_gate = text(ROOT / "tools/ios/run_phase4_macos_gate.py", "Phase 4 macOS gate")
    phase1 = text(ROOT / "tools/release/verify_ios_phase1.py", "Phase 1 verifier hook")

    for token in (
        "AVAssetWriter(outputURL: videoURL, fileType: .mp4)",
        "AVVideoCodecType.h264",
        "kAudioFormatMPEG4AAC",
        "createToneFile",
        "IOSExportCoordinator(",
        "inferenceProvider: provider",
        "targetWidth: 1920",
        "targetHeight: 1080",
        "targetFps: 30",
        "trimStartMs: trimStartMs",
        "trimEndMs: trimEndMs",
        "expectedOutputFrames: Int64 = 18",
        "finalStayedHiddenDuringProgress",
        "outputInfo.hasAudio",
        "outputInfo.audioDurationSeconds >= 0.50",
        "outputInfo.presentationFrameCount == expectedOutputFrames",
        "inspectVideoTimeline",
        "kCVPixelFormatType_32BGRA",
        "CMSampleBufferGetPresentationTimeStamp",
        "timestamps[index] <= timestamps[index - 1]",
        "outputInfo.nominalFrameRate >= 29.0",
        "outputInfo.minFrameDurationSeconds >= 0.030",
        "readOutputCenterPixel",
        "missingObservationCount",
        "coordinator.cancel(jobId: cancelJobId)",
        "cancel_partial_clean",
    ):
        check(token in smoke, f"Phase 4 real-video smoke missing: {token}")

    check(
        "WOAH_METAL_PHASE3_SMOKE=PASS" in dart
        and "WOAH_EXPORT_PHASE4_SMOKE=PASS" in dart,
        "Combined Simulator app must preserve Phase 3 and emit the Phase 4 export marker",
    )
    check(
        "WOAH_METAL_PHASE3_SMOKE=PASS" in simulator
        and "WOAH_EXPORT_PHASE4_SMOKE=PASS" in simulator
        and 'timeout=600' in simulator,
        "Phase 4 Simulator runner must require both GPU and real-video export markers",
    )
    for token in (
        "verify_ios_phase3.py",
        "verify_ios_phase4.py",
        "compile_phase3_metal.py",
        '"iphoneos"',
        '"iphonesimulator"',
        '"--simulator"',
        "ios_phase4_smoke_main.dart",
        "run_phase4_simulator_smoke.py",
    ):
        check(token in macos_gate, f"Phase 4 macOS gate missing preserved/required lane: {token}")
    check(
        (
            "run_phase4_macos_gate.py" in phase1
            or "run_phase5_macos_gate.py" in phase1
            or "run_phase6_macos_gate.py" in phase1
        )
        and 'GITHUB_ACTIONS' in phase1,
        "Existing GitHub iOS workflow hook must invoke Phase 4 or a stronger Apple-only gate",
    )


def main() -> int:
    verify_media_pipeline()
    verify_temporal_privacy()
    verify_job_contract()
    verify_real_video_gate()
    if FAILURES:
        print("iOS Phase 4 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 4 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
