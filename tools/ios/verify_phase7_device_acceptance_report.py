#!/usr/bin/env python3
"""Validate completeness and acceptance semantics for a Phase 7 real-iPhone report."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "tools/ios/phase7_regression_matrix.json"
MODEL_CONTRACT = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources"
    / "yolo11n-seg-fp16.contract.json"
)
REQUIRED_MEDIA = {
    "repository_baseline",
    "portrait_audio",
    "landscape_audio",
    "no_audio",
    "vfr_phone_capture",
    "crossing_occlusion",
    "face_only_fast_motion",
    "mixed_full_body_face_only",
}
RESULTS = {"pending", "pass", "fail"}
INTERRUPTION_KEYS = {
    "idle_background_round_trip",
    "export_background_round_trip",
    "screen_lock",
    "app_switch",
}


def is_sha256(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def nonempty(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def positive_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def validate(report: dict, *, require_accepted: bool) -> list[str]:
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    check(report.get("schema_version") == 1, "schema_version must be 1")
    check(report.get("phase") == 7, "report must belong to Phase 7")
    check(report.get("evidence_class") == "physical-device-acceptance", "evidence_class must be physical-device-acceptance")
    status = report.get("acceptance_status")
    check(status in {"pending", "accepted", "rejected"}, "acceptance_status must be pending/accepted/rejected")
    if require_accepted:
        check(status == "accepted", "--require-accepted requires acceptance_status=accepted")

    artifact = report.get("artifact") if isinstance(report.get("artifact"), dict) else {}
    for key in (
        "release_audit_sha256",
        "executable_sha256",
        "pubspec_lock_sha256",
        "model_contract_sha256",
        "model_sha256",
    ):
        if artifact.get(key) is not None:
            check(is_sha256(artifact.get(key)), f"artifact.{key} must be a lowercase SHA-256")
    if artifact.get("git_commit") is not None:
        check(
            isinstance(artifact.get("git_commit"), str)
            and re.fullmatch(r"[0-9a-f]{40}", artifact["git_commit"]) is not None,
            "artifact.git_commit must be a full Git SHA",
        )
    if artifact.get("bundle_identifier") is not None:
        check(artifact.get("bundle_identifier") == "art.gaoge.dance", "artifact.bundle_identifier must be art.gaoge.dance")
    contract = json.loads(MODEL_CONTRACT.read_text(encoding="utf-8"))
    expected_model_hash = contract.get("expected_sha256")
    if artifact.get("model_sha256") is not None:
        check(artifact.get("model_sha256") == expected_model_hash, "artifact.model_sha256 must match the pinned model contract")

    device = report.get("device") if isinstance(report.get("device"), dict) else {}
    media = report.get("media") if isinstance(report.get("media"), dict) else {}
    check(set(media) == REQUIRED_MEDIA, f"media IDs must exactly match {sorted(REQUIRED_MEDIA)}")
    for media_id in REQUIRED_MEDIA:
        item = media.get(media_id, {}) if isinstance(media.get(media_id), dict) else {}
        if item.get("sha256") is not None:
            check(is_sha256(item.get("sha256")), f"media.{media_id}.sha256 must be a lowercase SHA-256")
        if item.get("codec") is not None:
            check(nonempty(item.get("codec")), f"media.{media_id}.codec must be non-empty when present")
        for key in ("width", "height"):
            if item.get(key) is not None:
                check(isinstance(item.get(key), int) and item[key] > 0, f"media.{media_id}.{key} must be a positive integer")
        for key in ("average_fps", "duration_seconds"):
            if item.get(key) is not None:
                check(positive_number(item.get(key)), f"media.{media_id}.{key} must be positive")
        if item.get("has_audio") is not None:
            check(isinstance(item.get("has_audio"), bool), f"media.{media_id}.has_audio must be boolean")

    matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
    expected_cases = {str(item["id"]) for item in matrix.get("cases", [])}
    cases = report.get("functional_cases") if isinstance(report.get("functional_cases"), dict) else {}
    check(set(cases) == expected_cases, f"functional case IDs must exactly match {sorted(expected_cases)}")
    for case_id in expected_cases:
        item = cases.get(case_id, {}) if isinstance(cases.get(case_id), dict) else {}
        check(item.get("result") in RESULTS, f"functional_cases.{case_id}.result must be pending/pass/fail")
        media_ids = item.get("media_ids")
        check(isinstance(media_ids, list), f"functional_cases.{case_id}.media_ids must be a list")
        if isinstance(media_ids, list):
            check(all(media_id in REQUIRED_MEDIA for media_id in media_ids), f"functional_cases.{case_id}.media_ids contains an unknown media ID")
        check(isinstance(item.get("evidence_files"), list), f"functional_cases.{case_id}.evidence_files must be a list")

    privacy = report.get("privacy_review") if isinstance(report.get("privacy_review"), dict) else {}
    check(privacy.get("result") in RESULTS, "privacy_review.result must be pending/pass/fail")
    check(isinstance(privacy.get("evidence_files"), list), "privacy_review.evidence_files must be a list")
    if privacy.get("unresolved_escape_count") is not None:
        check(
            isinstance(privacy.get("unresolved_escape_count"), int) and privacy["unresolved_escape_count"] >= 0,
            "privacy_review.unresolved_escape_count must be a non-negative integer",
        )

    performance = report.get("performance") if isinstance(report.get("performance"), dict) else {}
    for key in ("analyze_fps", "export_processed_fps", "export_wall_seconds", "peak_memory_mib", "sustained_run_minutes"):
        if performance.get(key) is not None:
            check(positive_number(performance.get(key)), f"performance.{key} must be positive")
    if performance.get("progress_event_count") is not None:
        check(
            isinstance(performance.get("progress_event_count"), int) and performance["progress_event_count"] >= 0,
            "performance.progress_event_count must be a non-negative integer",
        )
    check(isinstance(performance.get("terminal_errors"), list), "performance.terminal_errors must be a list")
    check(isinstance(performance.get("thermal_states"), list), "performance.thermal_states must be a list")

    interruptions = report.get("background_interruption") if isinstance(report.get("background_interruption"), dict) else {}
    for key in INTERRUPTION_KEYS:
        check(interruptions.get(key) in RESULTS, f"background_interruption.{key} must be pending/pass/fail")

    stability = report.get("stability") if isinstance(report.get("stability"), dict) else {}
    check(stability.get("result") in RESULTS, "stability.result must be pending/pass/fail")
    for key in ("crash_count", "hang_count"):
        if stability.get(key) is not None:
            check(isinstance(stability.get(key), int) and stability[key] >= 0, f"stability.{key} must be a non-negative integer")

    if status == "accepted" or require_accepted:
        for key in (
            "release_audit_sha256",
            "git_commit",
            "bundle_identifier",
            "version",
            "build",
            "executable_sha256",
            "pubspec_lock_sha256",
            "model_contract_sha256",
            "model_sha256",
        ):
            check(artifact.get(key) is not None, f"accepted report requires artifact.{key}")
        check(bool(artifact.get("privacy_manifests")), "accepted report requires bundled privacy manifests")
        for key in ("model", "ios_version", "available_storage_bytes", "battery_start_percent", "battery_end_percent", "initial_thermal_state"):
            check(device.get(key) is not None, f"accepted report requires device.{key}")
        for media_id in REQUIRED_MEDIA:
            item = media.get(media_id, {}) if isinstance(media.get(media_id), dict) else {}
            for key in ("path", "sha256", "codec", "width", "height", "average_fps", "duration_seconds", "has_audio"):
                check(item.get(key) is not None, f"accepted report requires media.{media_id}.{key}")
        for case_id in expected_cases:
            item = cases.get(case_id, {}) if isinstance(cases.get(case_id), dict) else {}
            check(item.get("result") == "pass", f"accepted report requires functional_cases.{case_id}.result=pass")
            check(bool(item.get("media_ids")), f"accepted report requires functional_cases.{case_id}.media_ids evidence")
            check(bool(item.get("evidence_files")), f"accepted report requires functional_cases.{case_id}.evidence_files")
        check(privacy.get("result") == "pass", "accepted report requires privacy_review.result=pass")
        check(privacy.get("unresolved_escape_count") == 0, "accepted report requires zero unresolved privacy escapes")
        check(bool(privacy.get("evidence_files")), "accepted report requires privacy_review.evidence_files")
        for key in ("analyze_fps", "export_processed_fps", "export_wall_seconds", "peak_memory_mib", "sustained_run_minutes"):
            check(positive_number(performance.get(key)), f"accepted report requires positive performance.{key}")
        check(nonempty(performance.get("effective_inference_backend")), "accepted report requires performance.effective_inference_backend")
        check(isinstance(performance.get("thermal_states"), list) and bool(performance.get("thermal_states")), "accepted report requires observed performance.thermal_states")
        for key in INTERRUPTION_KEYS:
            check(interruptions.get(key) == "pass", f"accepted report requires background_interruption.{key}=pass")
        check(stability.get("result") == "pass", "accepted report requires stability.result=pass")
        check(stability.get("crash_count") == 0, "accepted report requires stability.crash_count=0")
        check(stability.get("hang_count") == 0, "accepted report requires stability.hang_count=0")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--require-accepted", action="store_true")
    args = parser.parse_args()
    if not args.report.is_file():
        raise SystemExit(f"Acceptance report does not exist: {args.report}")
    report = json.loads(args.report.read_text(encoding="utf-8"))
    failures = validate(report, require_accepted=args.require_accepted)
    if failures:
        print("iOS Phase 7 physical-device report verification FAILED:")
        for failure in failures:
            print(f" - {failure}")
        return 1
    print(f"IOS_PHASE7_DEVICE_REPORT_VALID=PASS status={report.get('acceptance_status')}")
    if args.require_accepted:
        print("IOS_PHASE7_PHYSICAL_DEVICE_ACCEPTANCE=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
