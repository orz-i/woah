#!/usr/bin/env python3
"""Static contract gate for Woah iOS Phase 6 privacy-class prototype tracking."""

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
    check(path.is_file(), f"Missing iOS Phase 6 asset: {label}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def verify_phase_boundary() -> None:
    phase5_smoke = text(SOURCES / "IOSGoldenTracePhase5Smoke.swift", "Phase 5 Golden Trace smoke")
    phase5_verifier = text(ROOT / "tools/release/verify_ios_phase5.py", "Phase 5 verifier")
    docs = text(ROOT / "docs/ios_implementation.md", "iOS implementation documentation")
    check(
        "IOSPrivacyClassPhase6Smoke" not in phase5_smoke and '"privacyClass"' not in phase5_smoke,
        "Phase 5 Golden Trace smoke must remain frozen and must not execute Phase 6 privacy-class tracking",
    )
    check(
        "IOSPrivacyClassTemporalTracker" not in phase5_verifier
        and "IOSPrivacyClassPhase6Smoke" not in phase5_verifier,
        "Phase 5 verifier must not expand with Phase 6 implementation requirements",
    )
    check("Phase 5 boundary" in docs and "### Phase 6:" in docs, "Docs must state the Phase 5/6 boundary explicitly")


def verify_ios_surface() -> None:
    tracker = text(SOURCES / "IOSPrivacyClassTemporalTracker.swift", "IOSPrivacyClassTemporalTracker.swift")
    smoke = text(SOURCES / "IOSPrivacyClassPhase6Smoke.swift", "IOSPrivacyClassPhase6Smoke.swift")
    renderer = text(SOURCES / "IOSMetalPreviewRenderer.swift", "IOSMetalPreviewRenderer.swift")
    export = text(SOURCES / "IOSExportPipeline.swift", "IOSExportPipeline.swift")
    plugin = text(SOURCES / "DanceNativePlugin.swift", "DanceNativePlugin.swift")
    dart = text(ROOT / "mobile/app/lib/ios_phase6_smoke_main.dart", "combined iOS smoke entrypoint")

    for token in (
        "enum IOSPrivacySelectionClass: String",
        "struct IOSFreshPrivacyClassEvidence",
        "final class IOSPrivacyClassTemporalTracker",
        "minClassScore: Float32 = 0.42",
        "minSingleClassScore: Float32 = 0.65",
        "minClassMargin: Float32 = 0.12",
        "maxPrototypeMisses: Int = 4",
        "if !rootSeeded && !hardClassByDetectionIndex.isEmpty",
        "rootClassByDetectionIndex = hardClassByDetectionIndex",
        "classified[index] ?? .selected",
        "conservativeUnknown: classified[index] == nil",
        "prototype.reliability *= 0.72",
        "prototype.misses > maxPrototypeMisses",
        "bbox * 0.40 + mask * 0.40 + distanceScore * 0.20",
        "buildWarpedMaskSupport(",
        "reuseFrameSimilarityCache",
        "lastSimilarityEvaluationCount",
    ):
        check(token in tracker, f"Phase 6 privacy-class tracker missing: {token}")

    for token in (
        "enum IOSPrivacyClassPhase6Smoke",
        "IOSPrivacyClassTemporalTracker()",
        "hardClassByDetectionIndex: [0: .selected, 1: .unselected]",
        "hardClassByDetectionIndex: [0: .unselected, 1: .selected]",
        "let crossingFrames = [",
        "IOSPrivacyClassTemporalTracker(minClassMargin: 0.20)",
        "requireClass(merged, index: 0, selectionClass: .selected, unknown: true)",
        "requireClass(entrant, index: 0, selectionClass: .selected, unknown: true)",
        "one_frame_occlusion_return",
        "reuseFrameSimilarityCache: true",
        "reuseFrameSimilarityCache: false",
        "cachedEvaluations == 12",
        "uncachedEvaluations == 18",
        "Frame similarity cache changed privacy-class decisions",
        "freshFullBodyPrivacyEvidence: evidence",
        "preferFreshFullBodyClassPrimary: true",
        "FULL_BODY-only fresh privacy-class primary rendered the wrong class",
        "Mixed/FACE_ONLY composition consumed non-identity FULL_BODY class evidence",
        "IOS_PHASE6_PRIVACY_CLASS_SMOKE_FAILED",
    ):
        check(token in smoke, f"Phase 6 deterministic smoke missing: {token}")

    for token in (
        "freshFullBodyPrivacyEvidence: [IOSFreshPrivacyClassEvidence] = []",
        "preferFreshFullBodyClassPrimary: Bool = false",
        "let freshSyntheticBase = Int.min / 4",
        "evidence.selectionClass == .selected",
        "&& faceOnlyIds.isEmpty",
        "let fallbackDeficit = max(0, fullBodyIds.count - freshSelectedCount)",
        "privacyPersons = freshPersons + fallbackSelectedArray",
    ):
        check(token in renderer, f"Phase 6 FULL_BODY fresh-primary renderer missing: {token}")

    for token in (
        "let privacyClassTracker = !fullBodyIds.isEmpty && faceOnlyIds.isEmpty",
        "IOSPrivacyClassTemporalTracker()",
        "var privacyClassHardRootsSent = false",
        "let rootPersons = IOSPreviewIdentityMatcher.assign(",
        "hardRoots[index] = fullBodyIds.contains(rootPersons[index].id)",
        "privacyClassHardRootsSent = true",
        "freshFullBodyPrivacyEvidence = privacyClassTracker.update(",
        "freshFullBodyPrivacyEvidence: freshFullBodyPrivacyEvidence",
        "preferFreshFullBodyClassPrimary: privacyClassTracker != nil",
    ):
        check(token in export, f"Phase 6 FULL_BODY-only export wiring missing: {token}")

    check('case "runIOSPrivacyClassPhase6Smoke"' in plugin, "Phase 6 smoke MethodChannel hook is missing")
    check(
        "WOAH_PRIVACY_CLASS_PHASE6_SMOKE=PASS" in dart
        and "runIOSPrivacyClassPhase6Smoke" in dart,
        "Combined Simulator app must execute and report the Phase 6 privacy-class smoke",
    )


def verify_android_reference_boundary() -> None:
    tracker = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/PrivacyClassTemporalTracker.kt",
        "Android PrivacyClassTemporalTracker",
    )
    for token in (
        "private val minClassScore: Float = 0.42f",
        "private val minSingleClassScore: Float = 0.65f",
        "private val minClassMargin: Float = 0.12f",
        "private val maxPrototypeMisses: Int = 4",
        "if (!rootSeeded && hardClassByDetectionIndex.isNotEmpty())",
        "classified[index] ?: PrivacySelectionClass.SELECTED",
        "conservativeUnknown = !classified.containsKey(index)",
        "prototype.reliability *= 0.72f",
        "0.40f * bboxIoU + 0.40f * maskIoU + 0.20f * distanceScore",
        "TrackManager.computeWarpedMaskIoU(",
        "runtime TrackManager IDs are not privacy-class truth",
    ):
        check(token in tracker, f"Android privacy-class tracker reference drifted: {token}")

    tests = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/com/danceanon/native/privacy/PrivacyClassTemporalTrackerTest.kt",
        "Android PrivacyClassTemporalTrackerTest",
    )
    for test_name in (
        "hardSeedsAllowFreshClassInferenceOnFollowingFrame",
        "selectedAndUnselectedCrossWithoutExactIdentityCommits",
        "mergedDetectionBetweenClassesRemainsUnknown",
        "farNewEntrantWithoutHardEvidenceStaysUnknown",
        "runtimeHardLabelsCannotOverwriteInitialPrivacyRoots",
        "oneFrameOcclusionRetainsUnselectedClassOnReturn",
        "frameSimilarityCachePreservesDecisionsAndEliminatesDuplicateEvaluations",
    ):
        check(test_name in tests, f"Android privacy-class regression test missing: {test_name}")

    export = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/pipeline/ExportPipeline.kt",
        "Android ExportPipeline",
    )
    for token in (
        "shouldUseFreshFullBodyClassPrimary(",
        "fullBodyPersonIds.isNotEmpty() && faceOnlyPersonIds.isEmpty()",
        "Mixed / FACE_ONLY-only composition: never let",
        "non-identity temporal class evidence become the",
    ):
        check(token in export, f"Android FULL_BODY-only class-primary boundary drifted: {token}")


def verify_cloud_gate() -> None:
    simulator = text(ROOT / "tools/ios/run_phase4_simulator_smoke.py", "Simulator smoke runner")
    macos = text(ROOT / "tools/ios/run_phase6_macos_gate.py", "Phase 6 macOS gate")
    phase1 = text(ROOT / "tools/release/verify_ios_phase1.py", "Phase 1 GitHub hook")
    for token in (
        "PRIVACY_CLASS_PHASE6_PASS_MARKER",
        '"--require-phase6"',
        "IOS_SIMULATOR_PHASE6_PRIVACY_CLASS_SMOKE=PASS",
    ):
        check(token in simulator, f"Phase 6 Simulator marker gate missing: {token}")
    for token in (
        "verify_ios_phase5.py",
        "verify_ios_phase6.py",
        "compile_phase3_metal.py",
        '"iphoneos"',
        '"iphonesimulator"',
        "ios_phase6_smoke_main.dart",
        "run_phase4_simulator_smoke.py",
        '"--require-phase6"',
        "IOS_PHASE6_MACOS_GATE=PASS",
    ):
        check(token in macos, f"Phase 6 macOS gate missing inherited/required lane: {token}")
    check(
        "run_phase6_macos_gate.py" in phase1 and "GITHUB_ACTIONS" in phase1,
        "GitHub macOS hook must invoke the strongest Phase 6 Apple-only gate",
    )


def main() -> int:
    verify_phase_boundary()
    verify_ios_surface()
    verify_android_reference_boundary()
    verify_cloud_gate()
    if FAILURES:
        print("iOS Phase 6 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 6 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
