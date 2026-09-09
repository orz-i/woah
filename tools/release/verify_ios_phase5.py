#!/usr/bin/env python3
"""Static contract gate for Woah iOS Phase 5 Golden Trace tracking parity."""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
TRACE = SOURCES / "Resources/GoldenTraces/phase5_tracking_golden.json"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def text(path: Path, label: str) -> str:
    check(path.is_file(), f"Missing iOS Phase 5 asset: {label}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def verify_trace_contract() -> None:
    payload: dict[str, object] = {}
    if TRACE.is_file():
        try:
            payload = json.loads(TRACE.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            FAILURES.append(f"Phase 5 Golden Trace is not valid JSON: {exc}")
            return
    else:
        FAILURES.append("Missing Phase 5 Golden Trace JSON")
        return

    check(payload.get("schemaVersion") == 1, "Phase 5 Golden Trace schemaVersion must be 1")
    frame_interval = payload.get("frameIntervalUs")
    check(isinstance(frame_interval, int) and frame_interval > 0, "Golden Trace frameIntervalUs must be positive")
    cases = payload.get("cases")
    if not isinstance(cases, list):
        FAILURES.append("Golden Trace cases must be an array")
        return

    required_cases = {
        "short_gap_reacquire_full_body",
        "face_only_unselected_neighbor_isolation",
        "ambiguous_face_only_defers_identity_commit",
        "protected_lost_stale_anchor_isolation",
        "long_occlusion_grace_reacquires_original_id",
        "selected_identity_long_gap_fails_closed",
    }
    names = {case.get("name") for case in cases if isinstance(case, dict)}
    check(required_cases.issubset(names), f"Golden Trace missing privacy-critical cases: {sorted(required_cases - names)}")

    allowed_states = {"ACTIVE", "OCCLUDED", "REACQUIRING", "LOST"}
    for case in cases:
        if not isinstance(case, dict):
            FAILURES.append("Golden Trace case must be an object")
            continue
        name = str(case.get("name", "<unnamed>"))
        analysis = case.get("analysisPersons")
        steps = case.get("steps")
        references = case.get("androidReferences")
        selected = set(case.get("fullBodyIds", [])) | set(case.get("faceOnlyIds", []))
        analysis_ids = {
            person.get("id")
            for person in analysis
            if isinstance(analysis, list) and isinstance(person, dict)
        } if isinstance(analysis, list) else set()
        check(selected.issubset(analysis_ids), f"{name}: selected IDs must exist in analysis roots")
        check(isinstance(references, list) and references, f"{name}: Android reference provenance is required")
        if isinstance(references, list):
            for reference in references:
                if not isinstance(reference, dict):
                    FAILURES.append(f"{name}: invalid Android reference entry")
                    continue
                relative = reference.get("path")
                test_name = reference.get("testName")
                if not isinstance(relative, str) or not isinstance(test_name, str):
                    FAILURES.append(f"{name}: Android reference requires path/testName strings")
                    continue
                reference_path = ROOT / relative
                reference_text = text(reference_path, f"Android reference for {name}")
                check(test_name in reference_text, f"{name}: Android reference test not found: {test_name}")

        if not isinstance(steps, list) or not steps:
            FAILURES.append(f"{name}: steps must be a non-empty array")
            continue
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                FAILURES.append(f"{name}: step {index} must be an object")
                continue
            repeat = step.get("repeat")
            check(isinstance(repeat, int) and repeat > 0, f"{name}: step {index} repeat must be positive")
            expected_error = step.get("expectedErrorCode")
            if expected_error is not None:
                check(index == len(steps) - 1, f"{name}: expected error must terminate the trace")
                check(expected_error == "EXPORT_PRIVACY_UNRESOLVED", f"{name}: unexpected fail-closed code")
            expectations = step.get("expect")
            if expectations is None:
                continue
            if not isinstance(expectations, list):
                FAILURES.append(f"{name}: step {index} expect must be an array")
                continue
            ids: list[int] = []
            for expectation in expectations:
                if not isinstance(expectation, dict):
                    FAILURES.append(f"{name}: invalid track expectation")
                    continue
                track_id = expectation.get("id")
                if isinstance(track_id, int):
                    ids.append(track_id)
                check(expectation.get("state") in allowed_states, f"{name}: invalid expected track state")
                if expectation.get("fallback") is True:
                    check(expectation.get("outputPresent") is True, f"{name}: fallback must be emitted")
                    check(expectation.get("privacySelected") is True, f"{name}: fallback may only cover a selected identity")
                grace = expectation.get("occlusionGraceRemaining")
                if grace is not None:
                    check(isinstance(grace, int) and 0 <= grace <= 10, f"{name}: invalid occlusion grace expectation")
                travel_ratio = expectation.get("maxPredictionTravelRatio")
                if travel_ratio is not None:
                    check(
                        isinstance(travel_ratio, (int, float)) and 0 < travel_ratio <= 0.30,
                        f"{name}: protected prediction travel bound must be <= Android 0.30 ratio",
                    )
            check(len(ids) == len(set(ids)), f"{name}: duplicate expected track IDs")


def verify_ios_replay_surface() -> None:
    tracker = text(SOURCES / "IOSTemporalIdentityTracker.swift", "IOSTemporalIdentityTracker.swift")
    smoke = text(SOURCES / "IOSGoldenTracePhase5Smoke.swift", "IOSGoldenTracePhase5Smoke.swift")
    face_pipeline = text(SOURCES / "IOSFacePrivacyPipeline.swift", "IOSFacePrivacyPipeline.swift")
    face_smoke = text(SOURCES / "IOSFacePrivacyPhase5Smoke.swift", "IOSFacePrivacyPhase5Smoke.swift")
    privacy_class_tracker = text(
        SOURCES / "IOSPrivacyClassTemporalTracker.swift",
        "IOSPrivacyClassTemporalTracker.swift",
    )
    privacy_class_smoke = text(
        SOURCES / "IOSPrivacyClassPhase5Smoke.swift",
        "IOSPrivacyClassPhase5Smoke.swift",
    )
    renderer = text(SOURCES / "IOSMetalPreviewRenderer.swift", "IOSMetalPreviewRenderer.swift")
    export = text(SOURCES / "IOSExportPipeline.swift", "IOSExportPipeline.swift")
    coordinator = text(SOURCES / "IOSExportCoordinator.swift", "IOSExportCoordinator.swift")
    export_smoke = text(SOURCES / "IOSExportPhase4Smoke.swift", "IOSExportPhase4Smoke.swift")
    preview = text(SOURCES / "IOSPreviewPipeline.swift", "IOSPreviewPipeline.swift")
    resources = text(SOURCES / "IOSGoldenTraceResources.swift", "IOSGoldenTraceResources.swift")
    plugin = text(SOURCES / "DanceNativePlugin.swift", "DanceNativePlugin.swift")
    podspec = text(ROOT / "mobile/packages/dance_native/ios/dance_native.podspec", "dance_native.podspec")
    dart = text(ROOT / "mobile/app/lib/ios_metal_smoke_main.dart", "combined iOS smoke entrypoint")

    for token in (
        "struct IOSTemporalTrackSnapshot",
        "func paritySnapshots()",
        "identityProtected: identityProtectedIds.contains(track.id)",
        "privacySelected: privacyTargetIds.contains(track.id)",
        "track.state = .active",
        "REACQUIRING is reserved for the interval",
        "postOcclusionGraceFrames = 10",
        "protectedGroupActiveMinBBoxIoU: Float32 = 0.35",
        "protectedGroupReacquireMinBBoxIoU: Float32 = 0.45",
        "protectedRecoveryMinBBoxIoU: Float32 = 0.50",
        "protectedRecoveryMinMaskIoU: Float32 = 0.45",
        "protectedUnobservedMaxCenterTravelRatio: Float32 = 0.30",
        "protectedLostRecoveryGeometrySufficient",
        "boundProtectedPredictionAroundLastObservation",
        "track.occlusionGraceRemaining = postOcclusionGraceFrames",
        "struct IOSFreshFacePrivacyClassEvidence",
        "private let faceOnlyPrivacyIds: Set<Int>",
        "currentFacePrivacyEvidence.removeAll(keepingCapacity: true)",
        "var ambiguousProtectedDetections = Set<Int>()",
        "ambiguousProtectedDetections.insert(candidate.detectionIndex)",
        "ambiguousProtectedDetections.contains($0)",
        "inferFacePrivacyClassEvidence(",
        "associationAmbiguityMargin: Float32 = 0.05",
        "possibleOwners.insert(track.id)",
        "componentDetections.count == componentOwners.count",
        "A 1-detection / 2-owner merge is intentionally *not* enough",
        "componentOwners.allSatisfy({ faceOnlyPrivacyIds.contains($0) })",
        "reservedDetections.insert(detectionIndex)",
        "func facePrivacyClassEvidence() -> [IOSFreshFacePrivacyClassEvidence]",
    ):
        check(token in tracker, f"Phase 5 tracker parity surface missing: {token}")

    for token in (
        "IOSGoldenTraceResources.phase5TrackingURL()",
        "JSONDecoder().decode(IOSPhase5GoldenSuite.self",
        "IOSTemporalIdentityTracker(",
        "tracker.paritySnapshots()",
        "actualIds == expectedIds",
        "snapshot.state.rawValue == expectation.state",
        "snapshot.identityProtected == expectation.identityProtected",
        "snapshot.privacySelected == expectation.privacySelected",
        "conservativePrivacyFallback",
        "expectedErrorCode",
        'error.code == expectedErrorCode',
        "occlusionGraceRemaining == expectedGrace",
        "maxPredictionTravelRatio",
        "protected prediction escaped anchor bound",
        "IOSPrivacyClassPhase5Smoke.run()",
        '"privacyClass": privacyClassReport',
    ):
        check(token in smoke, f"Phase 5 Golden Trace replay missing: {token}")

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
        check(token in privacy_class_tracker, f"Phase 5I privacy-class tracker missing: {token}")

    for token in (
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
    ):
        check(token in privacy_class_smoke, f"Phase 5I deterministic smoke missing: {token}")

    for token in (
        "import Vision",
        "VNDetectFaceRectanglesRequest()",
        "protocol IOSFaceLocating",
        "final class IOSVisionFaceLocator",
        "enum IOSPersonBboxMotionEstimator",
        "enum IOSBodyMaskFaceHeadEstimator",
        "final class IOSFacePrivacyTemporalResolver",
        "IOSFacePrivacyGeometry.fallbackEllipse",
        "person.conservativePrivacyFallback",
        "persons: persons.filter { !$0.conservativePrivacyFallback }",
        "unselected neighbor's",
        "ambiguityMargin: Float32 = 0.08",
        "containment >= 0.80",
        "faceCenterY <= person.y1 + personHeight * 0.62",
        "detectedRadiusXFactor: Float32 = 0.66",
        "detectedRadiusYFactor: Float32 = 0.74",
        "detectedCenterYShift: Float32 = -0.04",
        "fallbackCenterYRatio: Float32 = 0.14",
        "fallbackRadiusXFromWidth: Float32 = 0.22",
        "fallbackRadiusYFromWidth: Float32 = 0.26",
        "fallbackReferenceExpansion: Float32 = 1.24",
        "fallbackMinTrustedExpansion: Float32 = 1.10",
        "detectedReferenceAlpha: Float32 = 0.25",
        "privacyTargetFloor: Float32 = 0.90",
        "positionMaxRadiusStep: Float32 = 0.80",
        "positionMaxUnobservedPersonRadiusStep: Float32 = 0.65",
        "maxPredictedFaceAgeUs: Int64 = 150_000",
        "trustedMaskSeedMaxAgeUs: Int64 = 800_000",
        "minPredictedFaceScale: Float32 = 0.88",
        "maxPredictedFaceScale: Float32 = 1.12",
        "maxPredictedAgeExpansion: Float32 = 0.10",
        "expiredFaceMaskFallbackExpansion: Float32 = 1.10",
        "IOSPersonBboxMotionEstimator.estimate(",
        "IOSBodyMaskFaceHeadEstimator.estimate(",
        "source: .predictedFace",
        "timestampUs - trusted.lastTrustedTimestampUs <= Self.maxPredictedFaceAgeUs",
        "timestampUs - trusted.lastTrustedTimestampUs <= Self.trustedMaskSeedMaxAgeUs",
        "Current YOLO segmentation must provide the rendered",
        "freshPrivacyClassEvidence: [IOSFreshFacePrivacyClassEvidence] = []",
        "evidence.residualTrackIds.allSatisfy({ faceOnlyIds.contains($0) })",
        "syntheticClassFallbackBase - evidence.detectionIndex",
        "classFallbackTrustedSizeExpansion: Float32 = 1.24",
        "Borrow only",
        "final class IOSFacePrivacyClassFallbackContinuity",
        "private var stateByOwnerId: [Int: State] = [:]",
        "func retain(ownerIds: Set<Int>)",
        "bodyMaskGuided: Bool",
        "classFallbackContinuity = IOSFacePrivacyClassFallbackContinuity()",
        "let activeUniqueClassOwners = Set(",
        "classFallbackContinuity.retain(ownerIds: activeUniqueClassOwners)",
        "region = classFallbackContinuity.stabilize(",
        "referenceRadius * positionMaxRadiusStep",
    ):
        check(token in face_pipeline, f"Phase 5C-H FACE_ONLY pipeline missing: {token}")
    trusted_start = face_pipeline.find("private struct TrustedFaceGeometry")
    mask_fallback_start = face_pipeline.find("func maskGuidedFallback(")
    state_start = face_pipeline.find("private struct State", trusted_start + 1)
    check(
        trusted_start >= 0
        and mask_fallback_start > trusted_start
        and state_start > mask_fallback_start,
        "Phase 5E maskGuidedFallback must remain scoped inside TrustedFaceGeometry",
    )
    vision_start = face_pipeline.find("final class IOSVisionFaceLocator")
    geometry_start = face_pipeline.find("enum IOSFacePrivacyGeometry", vision_start + 1)
    check(
        vision_start >= 0
        and geometry_start > vision_start
        and "maskGuidedFallback" not in face_pipeline[vision_start:geometry_start],
        "Vision locator must not own trusted-face fallback state",
    )

    for token in (
        "IOSPhase5SequenceFaceLocator",
        "IOSVisionFaceLocator().locateFaces(in: visionSource)",
        "Vision FACE_ONLY runtime probe failed",
        "detectedRegion.source == .detectedFace",
        "missedRegion.source == .predictedFace",
        "translatedRegion.source == .predictedFace",
        "expiredRegion.source == .yoloHeadFallback",
        "Stale FACE_ONLY trusted geometry remained renderable beyond the 150ms lease",
        "ambiguous[0]?.source == .yoloHeadFallback",
        "neighborCompetition[0]?.source == .yoloHeadFallback",
        "An unselected observed neighbor must participate in face ownership ambiguity",
        "conservativePrivacyFallback: true",
        "predicted[0]?.source == .yoloHeadFallback",
        "cachedPredictedRegion.source == .predictedFace",
        "IOSPersonBboxMotionEstimator.estimate(",
        'label: "top-edge jitter dy"',
        "makeProtoMask(",
        "maskGuidedRegion.centerX > 34",
        "maskSeedExpiredRegion.source == .yoloHeadFallback",
        'label: "800ms mask-seed expiry centerX"',
        "Missing current head-like mask support removed FACE_ONLY fallback privacy",
        'label: "unsupported-mask generic fallback centerX"',
        "selectedClassTracker.facePrivacyClassEvidence()",
        "freshAmbiguousDetections",
        "selectedClassEvidence.count == 2",
        "selectedClassEvidence.allSatisfy({ $0.residualTrackIds == Set([0, 1]) })",
        "selectedClassSnapshotIds == Set([0, 1])",
        "classRegions[-1_000_000]",
        "classRegions[-1_000_001]",
        'label: "second synthetic class fallback centerX"',
        "mixedClassTracker.facePrivacyClassEvidence().isEmpty",
        "uniqueClassRegions[-1_000_005]",
        'label: "unique class trusted radiusX"',
        "Metal did not render synthetic negative-ID FACE_ONLY privacy evidence",
        "let classContinuity = IOSFacePrivacyClassFallbackContinuity()",
        "continuityFirstStep < 35",
        "continuitySecondStep < 35",
        'label: "class continuity translated centerX"',
        'label: "class continuity translated centerY"',
        "Weak raw class fallback center pulled away from robust body translation",
        "faceRegions: [0: renderRegion]",
        "FACE_ONLY ellipse center was not covered",
        "FACE_ONLY privacy regressed to a rectangular mask",
        '"vision_runtime_face_count"',
    ):
        check(token in face_smoke, f"Phase 5C deterministic FACE_ONLY smoke missing: {token}")
    preprocess_decl = face_smoke.find("let preprocess = IOSYoloPreprocessResult(")
    first_face_resolve = face_smoke.find("resolver.resolve(")
    check(
        preprocess_decl >= 0
        and first_face_resolve >= 0
        and preprocess_decl < first_face_resolve,
        "Phase 5 FACE_ONLY smoke must declare preprocess before the first resolver call",
    )
    check(
        face_smoke.count("let preprocess = IOSYoloPreprocessResult(") == 1,
        "Phase 5 FACE_ONLY smoke must keep exactly one deterministic preprocess fixture",
    )

    for token in (
        "faceRegions: [Int: IOSFacePrivacyEllipse] = [:]",
        "IOSFacePrivacyGeometry.fallbackEllipse(person.detection)",
        "dx * dx + dy * dy <= 1",
        "faceRect(",
        "faceRegions.keys.filter({ $0 < 0 }).sorted()",
        "regionsToRender.append(region)",
        "freshFullBodyPrivacyEvidence: [IOSFreshPrivacyClassEvidence] = []",
        "preferFreshFullBodyClassPrimary: Bool = false",
        "let freshSyntheticBase = Int.min / 4",
        "evidence.selectionClass == .selected",
        "&& faceOnlyIds.isEmpty",
        "let fallbackDeficit = max(0, fullBodyIds.count - freshSelectedCount)",
        "privacyPersons = freshPersons + fallbackSelectedArray",
    ):
        check(token in renderer, f"Phase 5C-I Metal/privacy rendering missing: {token}")

    for token in (
        "IOSFacePrivacyTemporalResolver()",
        "typealias FaceLocatorProvider = () -> IOSFaceLocating",
        "private let faceLocatorProvider: FaceLocatorProvider?",
        "faceLocatorProvider: FaceLocatorProvider? = nil",
        "IOSFacePrivacyTemporalResolver(locator: faceLocatorProvider())",
        "facePrivacyResolver?.resolve(",
        "preprocess: inference.preprocess",
        "freshPrivacyClassEvidence: tracker.facePrivacyClassEvidence()",
        "faceRegions: faceRegions",
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
        check(token in export, f"Phase 5C-I export privacy wiring missing: {token}")
    check(
        "faceLocatorProvider: IOSExportPipeline.FaceLocatorProvider? = nil" in coordinator
        and "faceLocatorProvider: faceLocatorProvider" in coordinator,
        "Phase 5F coordinator must forward the optional face-locator test seam",
    )
    for token in (
        "runFaceOnlyExportSmoke(",
        '"phase5_face_only_e2e": faceOnlyReport',
        "faceOnlyExportRequest(",
        "selectedPersonIds: []",
        "faceOnlyPersonIds: [0]",
        "FaceOnlyInferenceState",
        "FaceOnlyLocatorState",
        "preferBundledImage: false",
        "faceLocatorProvider: { faceLocator }",
        "FACE_ONLY_EXPORT_SMOKE_FACE_UNCOVERED",
        "FACE_ONLY_EXPORT_SMOKE_BODY_OVERMASKED",
        "FACE_ONLY_EXPORT_SMOKE_BODY_GAP_MISSING",
        "FACE_ONLY_EXPORT_SMOKE_FACE_SEQUENCE_MISSING",
        "readOutputPixel(",
        "isPrivacyRed(upperFacePixel) || isPrivacyRed(mirroredFacePixel)",
        "!isPrivacyRed(bodyCenterPixel)",
        "outputInfo.presentationFrameCount == expectedOutputFrames",
        "outputInfo.hasAudio",
    ):
        check(token in export_smoke, f"Phase 5F FACE_ONLY real-MP4 gate missing: {token}")
    check(
        "faceLocatorProvider" not in text(SOURCES / "DanceNativePlugin.swift", "DanceNativePlugin.swift"),
        "Production plugin must not inject the Phase 5F deterministic face locator",
    )
    for token in (
        "facePrivacyResolvers: [String: IOSFacePrivacyTemporalResolver]",
        "facePrivacyResolver(cacheId: request.analysisCacheId).resolve(",
        "preprocess: frameAnalysis.preprocess",
        "facePrivacyResolvers.removeValue(forKey: cacheId)?.reset()",
        "faceRegions: faceRegions",
    ):
        check(token in preview, f"Phase 5C preview FACE_ONLY wiring missing: {token}")
    check(
        "IOSFacePrivacyPhase5Smoke.run()" in smoke and '"facePrivacy": facePrivacyReport' in smoke,
        "Phase 5 Golden Trace Simulator gate must include deterministic Phase 5C FACE_ONLY coverage",
    )

    check("dance_native_phase5" in resources, "Phase 5 resource locator must resolve the dedicated bundle")
    check("dance_native_phase5" in podspec and "Resources/GoldenTraces/**/*" in podspec, "Phase 5 Golden Trace must be bundled by CocoaPods")
    check('case "runIOSGoldenTracePhase5Smoke"' in plugin, "Phase 5 smoke MethodChannel hook is missing")
    check(
        "WOAH_GOLDEN_TRACE_PHASE5_SMOKE=PASS" in dart
        and "runIOSGoldenTracePhase5Smoke" in dart,
        "Combined Simulator app must execute and report the Phase 5 Golden Trace smoke",
    )


def verify_android_reference_boundary() -> None:
    android = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/tracking/TrackManager.kt",
        "Android TrackManager",
    )
    for token in (
        "val maxMissedFrames: Int = 15",
        "val postOcclusionGraceFrames: Int = 10",
        "val occlusionMaxDurationFrames: Int = 90",
        "val associationAmbiguityMargin: Float = 0.05f",
        "setIdentityProtectedTrackIds",
        "setPrivacySelectedTrackIds",
        "framesSinceLastObservation",
        "private const val PROTECTED_GROUP_ACTIVE_MIN_BBOX_IOU = 0.35f",
        "private const val PROTECTED_GROUP_REACQUIRE_MIN_BBOX_IOU = 0.45f",
        "private const val PROTECTED_RECOVERY_MIN_BBOX_IOU = 0.50f",
        "private const val PROTECTED_RECOVERY_MIN_MASK_IOU = 0.45f",
        "private const val PROTECTED_UNOBSERVED_MAX_CENTER_TRAVEL_RATIO = 0.30f",
        "val predictionProgress = if (predTravel > 1e-4f)",
        "val motionConsistent = !hasMeaningfulPrediction || bIoU > 0.05f || predictionProgress >= 0.25f",
        "currentFacePrivacyClassEvidence",
        "FreshPrivacyClassEvidence(",
        "selectionClass = PrivacySelectionClass.SELECTED",
    ):
        check(token in android, f"Android tracking reference drifted; revisit Phase 5 Golden Trace: {token}")

    face_geometry = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FacePrivacyRegionResolver.kt",
        "Android FacePrivacyRegionResolver",
    )
    for token in (
        "private const val DETECTED_RADIUS_X_FACTOR = 0.66f",
        "private const val DETECTED_RADIUS_Y_FACTOR = 0.74f",
        "private const val DETECTED_CENTER_Y_SHIFT = -0.04f",
        "private const val FALLBACK_CENTER_Y_RATIO = 0.14f",
        "private const val FALLBACK_RADIUS_X_FROM_WIDTH = 0.22f",
        "private const val FALLBACK_RADIUS_Y_FROM_WIDTH = 0.26f",
        "privacy falls back to a YOLO-derived",
    ):
        check(token in face_geometry, f"Android FACE_ONLY geometry reference drifted: {token}")

    face_temporal = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FacePrivacyTemporalStabilizer.kt",
        "Android FacePrivacyTemporalStabilizer",
    )
    for token in (
        "private const val FALLBACK_REFERENCE_EXPANSION = 1.24f",
        "private const val FALLBACK_MIN_TRUSTED_EXPANSION = 1.10f",
        "private const val DETECTED_REFERENCE_ALPHA = 0.25f",
        "private const val PRIVACY_TARGET_FLOOR = 0.90f",
        "private const val POSITION_MAX_RADIUS_STEP = 0.80f",
        "private const val POSITION_MAX_UNOBSERVED_PERSON_RADIUS_STEP = 0.65f",
    ):
        check(token in face_temporal, f"Android FACE_ONLY temporal reference drifted: {token}")

    face_processor = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FaceOnlyPrivacyFrameProcessor.kt",
        "Android FaceOnlyPrivacyFrameProcessor",
    )
    for token in (
        "private const val MAX_PREDICTED_FACE_AGE_US = 150_000L",
        "private const val MIN_PREDICTED_FACE_SCALE = 0.88f",
        "private const val MAX_PREDICTED_FACE_SCALE = 1.12f",
        "private const val MAX_PREDICTED_AGE_EXPANSION = 0.10f",
        "source = FacePrivacyRegionSource.PREDICTED_FACE",
        "cached.project(",
    ):
        check(token in face_processor, f"Android FACE_ONLY projection reference drifted: {token}")

    motion_estimator = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/PersonBboxMotionEstimator.kt",
        "Android PersonBboxMotionEstimator",
    )
    for token in (
        "private const val MIN_EDGE_AGREEMENT_PX = 8f",
        "private const val EDGE_AGREEMENT_DIMENSION_RATIO = 0.07f",
        "return if (abs(firstEdgeDelta) <= abs(secondEdgeDelta))",
    ):
        check(token in motion_estimator, f"Android person-bbox motion reference drifted: {token}")
    motion_test = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/com/danceanon/native/privacy/PersonBboxMotionEstimatorTest.kt",
        "Android PersonBboxMotionEstimatorTest",
    )
    for test_name in (
        "coherent whole-person translation follows both axes",
        "top-edge-only coverage jitter does not become vertical translation",
    ):
        check(test_name in motion_test, f"Android person-bbox motion regression test missing: {test_name}")

    body_mask_estimator = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/BodyMaskFaceHeadEstimator.kt",
        "Android BodyMaskFaceHeadEstimator",
    )
    for token in (
        "private const val MASK_THRESHOLD = 96",
        "private const val MIN_SUPPORT_ROWS = 2",
        "private const val PERSON_HEAD_MAX_Y_RATIO = 0.42f",
        "private const val MIN_RUN_WIDTH_RATIO = 0.30f",
        "private const val MAX_RUN_WIDTH_RATIO = 1.75f",
        "private const val MAX_RUN_CENTER_DISTANCE_RATIO = 1.05f",
        "head-like narrow run",
    ):
        check(token in body_mask_estimator, f"Android body-mask face-head reference drifted: {token}")

    body_mask_test = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/com/danceanon/native/privacy/BodyMaskFaceHeadEstimatorTest.kt",
        "Android BodyMaskFaceHeadEstimatorTest",
    )
    for test_name in (
        "current body mask pulls fallback toward shifted head silhouette",
        "distant raised arm cannot pull local face fallback away from head seed",
        "arm crossing local head window cannot turn body silhouette into face centroid",
    ):
        check(test_name in body_mask_test, f"Android body-mask face-head regression test missing: {test_name}")

    trusted_mask_fallback = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FaceTrustedMaskFallback.kt",
        "Android FaceTrustedMaskFallback",
    )
    for token in (
        "old trusted face only as a local seed",
        "stale face center is never rendered",
        "BodyMaskFaceHeadEstimator.estimate(",
    ):
        check(token in trusted_mask_fallback, f"Android trusted-mask fallback reference drifted: {token}")
    trusted_mask_test = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/com/danceanon/native/privacy/FaceTrustedMaskFallbackTest.kt",
        "Android FaceTrustedMaskFallbackTest",
    )
    for test_name in (
        "fresh mask moves trusted seed toward current head without following body box center",
        "stale trusted seed cannot render without current head like mask support",
    ):
        check(test_name in trusted_mask_test, f"Android trusted-mask fallback regression test missing: {test_name}")

    pixel_motion = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FacePixelMotionTracker.kt",
        "Android FacePixelMotionTracker",
    )
    check(
        "const val ROI_MAX_DETECTOR_SEED_AGE_US = 800_000L" in pixel_motion,
        "Android FACE_ONLY detector-seed age reference drifted from 800ms",
    )

    class_fallback = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FacePrivacyClassFallbackResolver.kt",
        "Android FacePrivacyClassFallbackResolver",
    )
    for token in (
        "SYNTHETIC_TRACK_ID_BASE = -1_000_000",
        "TRUSTED_SIZE_FALLBACK_EXPANSION = 1.24f",
        "it.selectionClass == PrivacySelectionClass.SELECTED",
        "item.residualTrackIds.all { faceOnlyTrackIds.contains(it) }",
        "This never assigns or updates identity",
        "syntheticTrackId = SYNTHETIC_TRACK_ID_BASE - item.detectionIndex",
    ):
        check(token in class_fallback, f"Android FACE_ONLY class-fallback reference drifted: {token}")
    class_fallback_test = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/com/danceanon/native/privacy/FacePrivacyClassFallbackResolverTest.kt",
        "Android FacePrivacyClassFallbackResolverTest",
    )
    for test_name in (
        "selected ambiguous evidence fills uncovered dormant face without identity assignment",
        "evidence with any non selected possible owner never gets class fallback",
        "unique owner fallback keeps fresh center but reuses conservative trusted face size",
    ):
        check(test_name in class_fallback_test, f"Android FACE_ONLY class-fallback regression test missing: {test_name}")

    class_continuity = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/privacy/FacePrivacyClassFallbackContinuity.kt",
        "Android FacePrivacyClassFallbackContinuity",
    )
    for token in (
        "Render-only temporal continuity for anonymous FACE_ONLY class fallbacks",
        "private val stateByOwnerId = mutableMapOf<Int, State>()",
        "it.residualTrackIds.singleOrNull()",
        "PersonBboxMotionEstimator.estimate(",
        "trustedCurrentPixelCenter = false",
        "nothing here is written",
        "back to TrackManager",
    ):
        check(token in class_continuity, f"Android FACE_ONLY class-continuity reference drifted: {token}")
    class_continuity_test = text(
        ROOT / "mobile/packages/dance_native/android/src/test/kotlin/com/danceanon/native/privacy/FacePrivacyClassFallbackContinuityTest.kt",
        "Android FacePrivacyClassFallbackContinuityTest",
    )
    for test_name in (
        "guided availability toggle is bounded for one anonymous selected owner",
        "unguided frame follows body translation instead of raw body head center",
    ):
        check(test_name in class_continuity_test, f"Android FACE_ONLY class-continuity regression test missing: {test_name}")

    privacy_class_tracker = text(
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
        check(token in privacy_class_tracker, f"Android privacy-class tracker reference drifted: {token}")
    privacy_class_test = text(
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
        check(test_name in privacy_class_test, f"Android privacy-class regression test missing: {test_name}")

    export_pipeline = text(
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/pipeline/ExportPipeline.kt",
        "Android ExportPipeline",
    )
    for token in (
        "shouldUseFreshFullBodyClassPrimary(",
        "fullBodyPersonIds.isNotEmpty() && faceOnlyPersonIds.isEmpty()",
        "Mixed / FACE_ONLY-only composition: never let",
        "non-identity temporal class evidence become the",
    ):
        check(token in export_pipeline, f"Android FULL_BODY-only class-primary boundary drifted: {token}")


def verify_cloud_gate() -> None:
    simulator = text(ROOT / "tools/ios/run_phase4_simulator_smoke.py", "hardened Simulator runner")
    macos = text(ROOT / "tools/ios/run_phase5_macos_gate.py", "Phase 5 macOS gate")
    phase1 = text(ROOT / "tools/release/verify_ios_phase1.py", "Phase 1 GitHub hook")
    for token in (
        "GOLDEN_TRACE_PASS_MARKER",
        '"--require-phase5"',
        "IOS_SIMULATOR_PHASE5_GOLDEN_TRACE_SMOKE=PASS",
    ):
        check(token in simulator, f"Phase 5 Simulator marker gate missing: {token}")
    for token in (
        "verify_ios_phase4.py",
        "verify_ios_phase5.py",
        "compile_phase3_metal.py",
        '"iphoneos"',
        '"iphonesimulator"',
        "ios_metal_smoke_main.dart",
        "run_phase4_simulator_smoke.py",
        '"--require-phase5"',
    ):
        check(token in macos, f"Phase 5 macOS gate missing inherited/required lane: {token}")
    check(
        "run_phase5_macos_gate.py" in phase1 and "GITHUB_ACTIONS" in phase1,
        "Existing GitHub iOS workflow hook must invoke the strongest Phase 5 Apple-only gate",
    )


def main() -> int:
    verify_trace_contract()
    verify_ios_replay_surface()
    verify_android_reference_boundary()
    verify_cloud_gate()
    if FAILURES:
        print("iOS Phase 5 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 5 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
