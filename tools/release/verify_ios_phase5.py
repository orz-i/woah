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
            check(len(ids) == len(set(ids)), f"{name}: duplicate expected track IDs")


def verify_ios_replay_surface() -> None:
    tracker = text(SOURCES / "IOSTemporalIdentityTracker.swift", "IOSTemporalIdentityTracker.swift")
    smoke = text(SOURCES / "IOSGoldenTracePhase5Smoke.swift", "IOSGoldenTracePhase5Smoke.swift")
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
    ):
        check(token in smoke, f"Phase 5 Golden Trace replay missing: {token}")

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
        "val occlusionMaxDurationFrames: Int = 90",
        "val associationAmbiguityMargin: Float = 0.05f",
        "setIdentityProtectedTrackIds",
        "setPrivacySelectedTrackIds",
        "framesSinceLastObservation",
    ):
        check(token in android, f"Android tracking reference drifted; revisit Phase 5 Golden Trace: {token}")


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
