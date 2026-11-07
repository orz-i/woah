#!/usr/bin/env python3
"""Static contract gate for Woah iOS Phase 6 privacy-class prototype tracking."""

from __future__ import annotations

import sys
import hashlib
import json
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
        "enum IOSPrivacySelectionClass: String", "struct IOSFreshPrivacyClassEvidence",
        "final class IOSPrivacyClassTemporalTracker", "IOSSharedClassSession",
        "frame_kernel_class_create", "frame_kernel_class_update", "frame_kernel_class_reset", "frame_kernel_class_destroy",
        "lastSimilarityEvaluationCount", "session.update(obs,roots:roots)",
    ):
        check(token in tracker, f"Shared iOS privacy-class transport missing: {token}")
    check("private final class Prototype" not in tracker, "iOS must not keep an independent class algorithm")

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
        "FULL_BODY fresh-class compositor omitted an unresolved selected root's fail-closed mask",
        "full_body_unresolved_selected_fallback_pixel",
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
        # Count-first selection of *observed* root masks drops an unresolved
        # selected root during selected/unselected merges. Always keep the
        # privacy-grade conservative fallback before filling a class deficit.
        "let unresolvedSelected = trackedFullBodyPersons",
        ".filter { $0.conservativePrivacyFallback }",
        "let remainingDeficit = max(0, fullBodyIds.count - freshSelectedIds.count",
        "- unresolvedSelected.count)",
        "let fallbackSelectedArray = unresolvedSelected + observedSupplement",
        "privacyPersons = freshPersons + fallbackSelectedArray",
    ):
        check(token in renderer, f"Phase 6 FULL_BODY fresh-primary renderer missing: {token}")

    for token in (
        "let privacyClassTracker = !freshSceneCoverage && !fullBodyIds.isEmpty && faceOnlyIds.isEmpty",
        "let renderFullBodyIds = freshSceneCoverage && !fullBodyIds.isEmpty ? sceneIds : fullBodyIds",
        "IOSPrivacyClassTemporalTracker()",
        "var privacyClassHardRootsSent = false",
        "let rootPersons = try IOSPreviewIdentityMatcher.assign(",
        "hardRoots[index] = fullBodyIds.contains(rootPersons[index].id)",
        "privacyClassHardRootsSent = true",
        "freshFullBodyPrivacyEvidence = try privacyClassTracker.update(",
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
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/privacy/PrivacyClassTemporalTracker.kt",
        "Android PrivacyClassTemporalTracker",
    )
    for token in ("FrameKernel.ClassSession", "owned().update", "override fun close", "session?.close()"):
        check(token in tracker, f"Shared Android privacy-class transport missing: {token}")
    check("private data class Prototype" not in tracker, "Android must not keep an independent class algorithm")
    core=text(ROOT/"crates/frame-kernel/src/privacy_class.rs", "Shared Rust privacy-class algorithm")
    for token in ("!self.root_seeded && !hard.is_empty()", "class.unwrap_or(SelectionClass::Selected)",
                  "conservative_unknown: class.is_none()", "p.reliability *= 0.72", "0.40 * overlap_iou",
                  "tracking_mask::prepare_warped_samples", "maximum_misses: 4", "minimum: 0.42", "single_minimum: 0.65", "margin: 0.12"):
        check(token in core, f"Shared class invariant missing: {token}")
    manifest=ROOT/"crates/frame-kernel/testdata/privacy-class-native-v1.json"
    frozen=ROOT/"crates/frame-kernel/testdata/privacy-class-native-v1.bin"
    binding=json.loads(manifest.read_text(encoding="utf-8"))
    check(binding["frames"]==1024 and binding["scenarios"]==16, "Class oracle must cover every frozen native step")
    check(frozen.stat().st_size==binding["bytes"] and hashlib.sha256(frozen.read_bytes()).hexdigest()==binding["sha256"], "Frozen native class oracle changed")
    check(binding["sha256"]=="d17fad6313559b4c6e3a6363ccc228901188399d1fac82bf7874ecc44eba9d92", "Independent class reference binding changed")

    tests = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/art/gaoge/dance/engine/privacy/PrivacyClassTemporalTrackerTest.kt",
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
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/ExportPipeline.kt",
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
    workflow = text(ROOT / ".github/workflows/ios-cloud.yml", "iOS Cloud CI")
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
        "sync_ios_yolo_model.py",
        '"--verify-only"',
        "verify_ios_model(APP /",
        "ios_phase6_smoke_main.dart",
        "run_phase4_simulator_smoke.py",
        '"--require-phase6"',
        "IOS_PHASE6_MACOS_GATE=PASS",
    ):
        check(token in macos, f"Phase 6 macOS gate missing inherited/required lane: {token}")
    check(
        "run: python tools/ios/run_phase6_macos_gate.py" in workflow,
        "iOS Cloud CI must explicitly invoke the strongest Phase 6 Apple-only gate",
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
