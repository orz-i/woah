"""Fail-closed completion summary; pending or missing evidence never counts as passed."""
import hashlib
import json
from pathlib import Path
from review_audit import approved

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def read(path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


candidate = read(ROOT / "final-candidate.json", {})
folder = ROOT / candidate.get("run", "4k_final_20261105")
native = read(folder / "results.json", {})
cases = native.get("cases", [])
media = read(folder / "media-inspection.json", [])
holes = read(folder / "opaque-face-hole-audit.json", [])
ui = read(ROOT / "ui-results.json", [])
ui_visual = read(ROOT / "ui-visual-review.json", [])
visual = read(folder / "privacy-visual-review.json", [])
app = read(ROOT / "app-candidate.json", {})
plan = read(ROOT / "export-plan-validation.json", {})
fixture_plans = read(ROOT / "fixture-export-plans.json", [])
lifecycle = read(ROOT / "lifecycle-candidate.json", {})
terminal_dir = ROOT / lifecycle.get("run", "4k_terminal_20261105")
terminal_native = read(terminal_dir / "results.json", {})
terminal_media = read(terminal_dir / "media-inspection.json", [])
terminal_cases = terminal_native.get("cases", [])
terminal_log = (ROOT / (lifecycle.get("run", "4k_terminal_20261105") + "-matrix-instrumentation.txt"))
terminal_stdout = terminal_log.read_text(encoding="utf-8") if terminal_log.exists() else ""
legacy_render = ROOT / ("legacy_" + candidate.get("test_apk_sha256", "")[:8])
legacy = ROOT / ("legacy_" + lifecycle.get("test_apk_sha256", "pending")[:8])
old_cases = read(legacy / "final-device-results.json", [])
legacy_media = read(legacy_render / "media-inspection.json", [])
legacy_media_reuse = read(legacy_render / "media-reuse.json", {})
legacy_summary = read(legacy / "regression-summary.json", {})
sources_match = bool(candidate.get("source_sha256")) and all(
    (REPO / name).is_file() and hashlib.sha256((REPO / name).read_bytes()).hexdigest() == digest
    for name, digest in candidate.get("source_sha256", {}).items())
official_path = ROOT / (candidate.get("run", "") + "-matrix-instrumentation.txt")
official = official_path.read_text(encoding="utf-8") if official_path.exists() else ""
native_done = "OK (1 test)" in official and "INSTRUMENTATION_STATUS_CODE: 0" in official and "FAILURES!!!" not in official and native.get("result") == "passed" and len(cases) == 25 and all(c.get("result") == "passed" for c in cases)
complete = [c for c in cases if c.get("state") == "completed" and c.get("result") == "passed"]
media_names = {m["file"] for m in media if m.get("result") == "passed"}
checks = {
    "tested_native_sources_unchanged": sources_match,
    "frozen_apks_and_main_app_sources_match": bool(app.get("source_sha256")) and all(
        hashlib.sha256((REPO / name).read_bytes()).hexdigest() == digest
        for name, digest in app.get("source_sha256", {}).items()) and all(
        (ROOT / manifest.get("artifact_path", "missing")).is_file() and
        hashlib.sha256((ROOT / manifest["artifact_path"]).read_bytes()).hexdigest() == manifest[key]
        for manifest, key in ((candidate, "test_apk_sha256"), (lifecycle, "test_apk_sha256"), (app, "app_apk_sha256"))),
    "flutter_application_272_passes": app.get("flutter_app_tests") == {"tests":272,"passed":272},
    "production_export_plan_17_tests_and_both_source_sizes_pass": plan.get("tests") == plan.get("passed") == 17
        and all(hashlib.sha256((REPO / name).read_bytes()).hexdigest() == digest
                for name,digest in plan.get("source_sha256", {}).items())
        and len(fixture_plans) == 2 and all(p.get("result") == "passed" and p.get("fallback") is False
            and (p["input_width"],p["input_height"]) == (p["planned_width"],p["planned_height"])
            for p in fixture_plans),
    "all_full_length_modes_visually_reviewed": len(visual) == 23 and all(
        v.get("result") == "passed" and any(m["file"] == v["file"] and m["sha256"] == v["sha256"] for m in media)
        and (folder / v["comparison_artifact"]).is_file()
        and hashlib.sha256((folder / v["comparison_artifact"]).read_bytes()).hexdigest() == v["comparison_sha256"]
        for v in visual),
    "complete_jvm_suite_passes": read(ROOT / "jvm-summary.json", {}).get("counts") == {"tests":569,"failures":0,"errors":0,"skipped":0},
    "terminal_fix_sources_and_full_jvm_suite_pass": bool(lifecycle.get("source_sha256")) and all(
        (REPO / name).is_file() and hashlib.sha256((REPO / name).read_bytes()).hexdigest() == digest
        for name,digest in lifecycle.get("source_sha256", {}).items())
        and read(ROOT / "terminal-jvm-summary.json", {}).get("counts") == {"tests":571,"failures":0,"errors":0,"skipped":0},
    "terminal_fix_same_process_4k_lifecycle": terminal_native.get("result") == "passed"
        and "OK (1 test)" in terminal_stdout and "INSTRUMENTATION_STATUS_CODE: 0" in terminal_stdout
        and len(terminal_cases) == 4 and len({c["pid"] for c in terminal_cases}) == 1
        and {c["case"] for c in terminal_cases} == {"group_4k_body_mosaic_balanced", "solo_4k_cancel", "solo_4k_retry_sticker", "solo_4k_nonzero_trim"}
        and all(c.get("result") == "passed" for c in terminal_cases)
        and any(c["state"] == "cancelled" for c in terminal_cases)
        and len(terminal_media) == 3 and all(m.get("result") == "passed" for m in terminal_media)
        and sum(m["decoded_frames"] for m in terminal_media) == 1283,
    "previous_production_lifecycle_passes_after_terminal_fix": legacy_summary.get("result") == "passed"
        and legacy_summary.get("previous_production_lifecycle", {}).get("state") == "passed"
        and legacy_summary.get("fresh_observation_proof") is True,
    "same_process_25_case_matrix_complete": native_done and len({c["pid"] for c in cases}) == 1,
    "24_completed_4k_outputs_independently_verified": native_done and len(complete) == 24
        and all(c["case"] + ".mp4" in media_names for c in complete),
    "both_opaque_face_outputs_audited": {h["file"] for h in holes} == {"group_4k_face_solid.mp4", "solo_4k_face_solid.mp4"}
        and all(h["sha256"] == next((m["sha256"] for m in media if m["file"] == h["file"]), None)
                and approved(h, read(folder / "opaque-face-visual-review.json", []), folder) for h in holes),
    "visible_side_face_does_not_borrow_neighbor_cache": any(c.get("reviewed_side_face_protected_pixels",0) >= 519 and c.get("result") == "passed" for c in cases),
    "trouser_leg_is_not_a_false_face": any(c.get("reviewed_body_fragment_false_face_pixels",577) <= 57 and c.get("result") == "passed" for c in cases),
    "known_mirror_face_regression_passes_body_and_face": sum(
        c.get("reviewed_mirror_face_protected_pixels", 0) >= 519 and c.get("result") == "passed"
        for c in cases) == 2,
    "cancelled_4k_does_not_publish_partial_file_and_retry_passes": native_done
        and any(c["case"] == "solo_4k_cancel" and c["state"] == "cancelled" for c in cases)
        and any(c["case"] == "solo_4k_retry_sticker" and c.get("result") == "passed" for c in cases),
    "main_app_two_full_source_resolution_exports_saved": len(ui) >= 2
        and all(c.get("result") == "passed" and c.get("saved_to_gallery") is True
                and c.get("source_resolution_preserved") is True
                and c.get("gallery", {}).get("matches_export_bytes") is True
                and c.get("gallery", {}).get("sha256") == c["media"]["sha256"] for c in ui),
    "main_app_both_outputs_visually_reviewed": len(ui_visual) == 2 and all(
        v.get("result") == "passed" and any(u["tag"] == v["tag"] and u["media"]["sha256"] == v["sha256"] for u in ui)
        and (ROOT / v["comparison_artifact"]).is_file()
        and hashlib.sha256((ROOT / v["comparison_artifact"]).read_bytes()).hexdigest() == v["comparison_sha256"]
        for v in ui_visual),
    "main_app_two_sources_same_process": {u.get("source") for u in ui} == {"group","solo"}
        and len({u.get("pid") for u in ui}) == 1 and app.get("installed_on_device") is True
        and app.get("installed_apk_sha256") == app.get("app_apk_sha256"),
    "previous_40_device_cases_pass": len(old_cases) == 40 and all(c["result"] == "passed" for c in old_cases),
    "previous_22_media_exports_regressed": legacy_media_reuse.get("result") == "passed"
        and len(legacy_media) == 22 and all(m.get("result") == "passed" for m in legacy_media),
    "real_4k_index_relocation_preserves_packets": read(ROOT / "faststart-real-packet-proof.json", {})
        .get("all_encoded_samples_and_pts_identical") is True,
    "real_4k_relocated_file_decodes_and_streams": read(ROOT / "faststart-real-media-check.json", {})
        .get("result") == "passed",
}
failed = any(c.get("result") == "failed" for c in cases + terminal_cases) or any(m.get("result") == "failed" for m in media + terminal_media)
result = {"result": "passed" if all(checks.values()) else "failed" if failed else "in_progress",
          "checks": checks, "pending": [name for name, value in checks.items() if not value],
          "native_pid": native.get("pid"), "native_cases_passed": sum(c.get("result") == "passed" for c in cases),
          "native_cases_planned": 25, "independently_verified_outputs": len(media_names),
          "independently_decoded_frames": sum(m["decoded_frames"] for m in media),
          "ui_exports": len(ui), "legacy_device_cases_passed": sum(c["result"] == "passed" for c in old_cases),
          "terminal_replay_pid": terminal_native.get("pid"),
          "terminal_replay_passed": sum(c.get("result") == "passed" for c in terminal_cases),
          "terminal_replay_decoded_frames": sum(m["decoded_frames"] for m in terminal_media),
          "tested_candidate": candidate, "tested_terminal_candidate": lifecycle}
(ROOT / "test-summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in result.items() if k not in ("tested_candidate", "tested_terminal_candidate")}, ensure_ascii=False))
