"""Source-only privacy overlap diagnostic contract; no real video or masks."""
from __future__ import annotations

import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.diagnostics import analyze_privacy_overlap_evidence as audit


SHA = "8e003ca8018318edd1e0e5717b56a5d5a2952fe2"


def fake_fields(job: str = "job_current", pts: int = 39_733_333) -> dict:
    grid = {key: [0] * 16 for key in audit.MUST_HAVE}
    grid["selected_raw"][0] = 4
    grid["final_merged"][0] = 3
    grid["final_merged_and_fresh_foreground"][0] = 2
    return {
        "job_id": job,
        "pts_us": pts,
        "valid": True,
        "evidence_scope": audit.SCOPE,
        "grid_shape": [4, 4],
        "grid_order": "ROW_MAJOR_TOP_TO_BOTTOM_SOURCE",
        "not_ground_truth_ownership": True,
        "target_detection_index": 2,
        "candidate_detection_index": 3,
        "corroborating_unselected_track_id": 2,
        "selected_bbox_proto": [50.0, 60.0, 70.0, 100.0],
        "grid_by_signal": grid,
        "total_by_signal": {key: sum(values) for key, values in grid.items()},
        "outside_selected_bbox_totals": {key: 0 for key in audit.OUTSIDE_KEYS},
    }


def fake_nms_shadow(job: str, pts: int = 39_800_000) -> dict:
    return {
        "job_id": job,
        "pts_us": pts,
        "evidence_scope": audit.NMS_SHADOW_SCOPE,
        "pixel_ownership_proven": False,
        "does_not_change_rendering": True,
        "raw_candidate_count": 6,
        "legacy_kept_count": 4,
        "strong_kept_count": 5,
        "strong_only_count": 1,
        "legacy_only_count": 0,
        "strong_only_geometry": [{
            "bbox_model": [250.0, 180.0, 310.0, 410.0],
            "confidence": 0.71,
        }],
        "strong_only_geometry_truncated": False,
    }


def make_bundle(path: Path, fields: dict, *, job: str = "job_current",
                sha: str = SHA, duplicate: bool = False,
                nms_shadow: dict | None = None) -> None:
    event = {"event": audit.EVENT_NAME, "fields": fields}
    stale = {"event": audit.EVENT_NAME, "fields": {**fields, "job_id": "old_job"}}
    warning = {"event": "PRIVACY_COVERAGE_DROP", "fields": {
        "job_id": job, "pts_us": 39_733_333, "target_detection_index": 2
    }}
    payload = [json.dumps(stale), json.dumps(event), json.dumps(warning)]
    if duplicate:
        payload.append(json.dumps(event))
    if nms_shadow is not None:
        payload.extend((
            json.dumps({"event": audit.NMS_SHADOW_EVENT, "fields": {
                **nms_shadow, "job_id": "old_job"
            }}),
            json.dumps({"event": audit.NMS_SHADOW_EVENT, "fields": nms_shadow}),
        ))
    with zipfile.ZipFile(path, "w") as out:
        out.writestr("manifest.json", json.dumps({
            "pipeline_lifecycle_job_id": job, "git_commit_sha": sha
        }))
        out.writestr("session_fixture_000.jsonl", "\n".join(payload) + "\n")


class PrivacyUnknownForegroundEvidenceAnalyzerTest(unittest.TestCase):
    def test_reads_only_current_job_and_deduplicates_matching_history(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bundle.zip"
            make_bundle(path, fake_fields(), duplicate=True)
            parsed = audit.read_bundle(path)
            self.assertEqual(parsed["commit"], SHA)
            self.assertEqual(len(parsed["events"]), 1)
            self.assertEqual(len(parsed["coverage_warnings"]), 1)
            self.assertEqual(next(iter(parsed["events"]))[0], 39_733_333)

    def test_source_bin_comparison_is_not_an_automatic_privacy_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            left = Path(directory) / "horizontal.zip"
            right = Path(directory) / "vertical.zip"
            make_bundle(left, fake_fields("job_l"), job="job_l")
            make_bundle(right, fake_fields("job_r"), job="job_r")
            result = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(result["shared_focus_conflicts"], 1)
            self.assertEqual(
                result["parity_status"],
                "SOURCE_MASK_BIN_PARITY_ONLY_NOT_VISUAL_ACCEPTANCE",
            )
            self.assertEqual(result["visual_privacy_acceptance"], "NOT_PROVEN")
            self.assertEqual(result["landscape"]["coverage_warning_count"], 1)
            self.assertEqual(result["portrait"]["audited_frames_total"], 1)
            self.assertEqual(result["landscape"]["focus_events"][0]["counts"]["selected_raw"], 4)
            self.assertEqual(
                result["landscape"]["focus_events"][0]["outside_selected_bbox_totals"]["selected_raw"],
                0
            )
            self.assertNotIn("raw_mask", json.dumps(result))
            self.assertNotIn("image", json.dumps(result))

    def test_detects_mismatched_instances_and_invalid_signal_totals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "l.zip"
            right = root / "r.zip"
            make_bundle(left, fake_fields("a"), job="a")
            altered = fake_fields("b")
            altered["grid_by_signal"]["final_merged"][0] = 0
            altered["total_by_signal"]["final_merged"] = 0
            make_bundle(right, altered, job="b")
            result = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(result["parity_status"], "FOCUS_BIN_COUNTS_MISMATCH")
            self.assertEqual(len(result["mismatched_focus_conflicts"]), 1)
            altered["total_by_signal"]["final_merged"] = 100
            make_bundle(right, altered, job="b")
            with self.assertRaisesRegex(ValueError, "sum mismatch"):
                audit.read_bundle(right)
            altered["total_by_signal"]["final_merged"] = 0
            altered["outside_selected_bbox_totals"]["selected_raw"] = -1
            make_bundle(right, altered, job="b")
            with self.assertRaisesRegex(ValueError, "outside-bbox"):
                audit.read_bundle(right)

    def test_new_strict_gate_diagnostics_preserve_old_bundle_compatibility(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "landscape.zip"
            right = root / "portrait.zip"
            fresh = fake_fields("l")
            fresh["strong_threshold_totals"] = {
                "selected_and_fresh_ge224": 21,
                "selected_and_observed_ge224": 18,
                "selected_and_both_ge192": 16,
                "selected_and_both_ge224": 12,
                "selected_and_both_ge224_vetoed_other": 2,
                "selected_and_both_ge224_without_other": 10,
            }
            fresh.update({
                "strong_veto_selected_mask_count": 3,
                "strong_foreground_gate_eligible": False,
                "strong_foreground_gate_failures": ["FOREGROUND_ROOT_AGE"],
                "strong_foreground_core_proto_pixels": 0,
                "strong_foreground_observed_age_frames": 5,
                "strong_foreground_observed_confidence": 0.78,
                "strong_foreground_veto_selected_masks_complete": True,
            })
            older = fake_fields("r")
            make_bundle(left, fresh, job="l")
            make_bundle(right, older, job="r")
            result = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(result["parity_status"], "FOCUS_BIN_COUNTS_MISMATCH")
            self.assertIsNone(result["portrait"]["focus_events"][0]["strong_gate_diagnostics"])
            updated = copy.deepcopy(fresh)
            updated["job_id"] = "r"
            make_bundle(right, updated, job="r")
            result = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(
                result["parity_status"],
                "SOURCE_MASK_BIN_PARITY_ONLY_NOT_VISUAL_ACCEPTANCE",
            )
            self.assertEqual(
                result["landscape"]["focus_events"][0]["strong_threshold_totals"][
                    "selected_and_both_ge224_without_other"
                ], 10,
            )
            self.assertEqual(
                result["landscape"]["focus_events"][0]["strong_gate_diagnostics"][
                    "strong_foreground_gate_failures"
                ], ["FOREGROUND_ROOT_AGE"],
            )
            self.assertEqual(
                result["landscape"]["focus_events"][0]["strong_veto_selected_mask_count"],
                3,
            )
            updated["strong_foreground_gate_eligible"] = True
            make_bundle(right, updated, job="r")
            with self.assertRaisesRegex(ValueError, "contradicts eligibility"):
                audit.read_bundle(right)
            updated["strong_foreground_gate_eligible"] = False
            updated["strong_threshold_totals"][
                "selected_and_both_ge224_without_other"
            ] = 14
            make_bundle(right, updated, job="r")
            with self.assertRaisesRegex(ValueError, "partition mismatch"):
                audit.read_bundle(right)

    def test_raw_model_top_band_probe_is_numeric_comparable_and_not_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "l.zip"
            right = root / "r.zip"
            raw = {
                "outside_sampled": 120,
                "outside_ge128": 57,
                "outside_ge192": 28,
                "outside_ge224": 12,
                "inside_sampled": 100,
                "inside_decoded_ge128": 30,
                "band_height_proto": 12,
            }
            first = fake_fields("a")
            first["target_yolo_raw_top_support"] = copy.deepcopy(raw)
            first["candidate_yolo_raw_top_support"] = copy.deepcopy(raw)
            second = copy.deepcopy(first)
            second["job_id"] = "b"
            make_bundle(left, first, job="a")
            make_bundle(right, second, job="b")
            result = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(result["parity_status"],
                             "SOURCE_MASK_BIN_PARITY_ONLY_NOT_VISUAL_ACCEPTANCE")
            self.assertEqual(result["visual_privacy_acceptance"], "NOT_PROVEN")
            self.assertEqual(
                result["landscape"]["focus_events"][0]["raw_yolo_top_support"]
                    ["candidate"]["outside_ge224"], 12
            )
            self.assertNotIn("mask_bytes", json.dumps(result))
            self.assertNotIn('"rgb":', json.dumps(result).lower())
            second["candidate_yolo_raw_top_support"]["outside_ge224"] = 11
            make_bundle(right, second, job="b")
            self.assertEqual(
                audit.compare(audit.read_bundle(left), audit.read_bundle(right))["parity_status"],
                "FOCUS_BIN_COUNTS_MISMATCH"
            )
            second["candidate_yolo_raw_top_support"]["outside_ge224"] = 60
            make_bundle(right, second, job="b")
            with self.assertRaisesRegex(ValueError, "inconsistent raw prototype"):
                audit.read_bundle(right)
            second["candidate_yolo_raw_top_support"]["outside_ge224"] = 11
            second["candidate_yolo_raw_top_support"]["inside_decoded_ge128"] = 101
            make_bundle(right, second, job="b")
            with self.assertRaisesRegex(ValueError, "raw prototype top support bounds"):
                audit.read_bundle(right)

    def test_debug_nms_shadow_reports_only_numeric_counterfactual_not_pixel_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "landscape.zip"
            right = root / "portrait.zip"
            make_bundle(left, fake_fields("a"), job="a", nms_shadow=fake_nms_shadow("a"))
            make_bundle(right, fake_fields("b"), job="b", nms_shadow=fake_nms_shadow("b"))
            report = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(report["nms_shadow"]["shared_focus_frames"], 1)
            self.assertEqual(report["nms_shadow"]["status"],
                             "NMS_SHADOW_PARITY_ONLY_NOT_PRIVACY_ACCEPTANCE")
            self.assertEqual(report["nms_shadow"]["pixels_restored"], 0)
            self.assertFalse(report["nms_shadow"]["protagonist_ownership_proven"])
            self.assertEqual(report["visual_privacy_acceptance"], "NOT_PROVEN")
            self.assertEqual(report["nms_shadow"]["landscape"][0]["strong_only_count"], 1)
            self.assertNotIn("mask_bytes", json.dumps(report))
            self.assertNotIn('"rgb":', json.dumps(report).lower())

    def test_nms_shadow_mode_mismatch_or_missing_frame_never_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "l.zip"
            right = root / "r.zip"
            make_bundle(left, fake_fields("a"), job="a", nms_shadow=fake_nms_shadow("a"))
            make_bundle(right, fake_fields("b"), job="b")
            report = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(report["nms_shadow"]["status"], "NMS_SHADOW_FRAME_SET_MISMATCH")
            self.assertEqual(report["parity_status"], "FOCUS_NMS_SHADOW_MISMATCH")
            changed = fake_nms_shadow("b")
            changed["raw_candidate_count"] = 7
            make_bundle(right, fake_fields("b"), job="b", nms_shadow=changed)
            report = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(report["nms_shadow"]["status"], "NMS_SHADOW_NUMERIC_MISMATCH")
            self.assertEqual(report["nms_shadow"]["mismatched_pts_us"], [39_800_000])

    def test_nms_shadow_rejects_unsafe_claims_and_invalid_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bundle.zip"
            for mutator, error in (
                (lambda f: f.update(pixel_ownership_proven=True), "unsafe acceptance"),
                (lambda f: f.update(raw_mask_bytes=[1, 2, 3]), "forbidden extra data"),
                (lambda f: f.update(strong_kept_count=6), "partition"),
                (lambda f: f.update(strong_only_geometry=[]), "bounded candidate geometry"),
                (lambda f: f["strong_only_geometry"][0].update(bbox_model=[float("nan"), 1, 2, 3]), "scalar geometry"),
            ):
                record = fake_nms_shadow("a")
                mutator(record)
                make_bundle(path, fake_fields("a"), job="a", nms_shadow=record)
                with self.assertRaisesRegex(ValueError, error):
                    audit.read_bundle(path)

    def test_missing_new_event_or_mismatched_build_does_not_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "l.zip"
            right = root / "r.zip"
            make_bundle(left, fake_fields("a"), job="a")
            make_bundle(right, fake_fields("b"), job="b", sha="f" * 40)
            report = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(report["parity_status"], "DIFFERENT_APK_COMMITS_NOT_COMPARABLE")
            self.assertEqual(report["visual_privacy_acceptance"], "NOT_PROVEN")
            without_focus = fake_fields("b", pts=35_000_000)
            make_bundle(right, without_focus, job="b")
            report = audit.compare(audit.read_bundle(left), audit.read_bundle(right))
            self.assertEqual(report["parity_status"], "NO_FOCUS_AUDIT_NOT_ACCEPTED")

    def test_prevents_commit_of_numeric_mask_bins_to_source_repository(self):
        repository = Path(audit.__file__).resolve().parents[2]
        argv = [
            "analyze_privacy_overlap_evidence.py",
            "--landscape", "/does-not-need-to-exist.zip",
            "--portrait", "/does-not-need-to-exist.zip",
            "--output", str(repository / ".git" / "private-mask-audit.json")
        ]
        with patch("sys.argv", argv), contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(audit.main(), 1)
        self.assertIn("outside the Git repository", stderr.getvalue())

    def test_conflicting_duplicate_for_same_job_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bundle.zip"
            first = fake_fields()
            different = copy.deepcopy(first)
            different["total_by_signal"]["final_merged"] = 0
            different["grid_by_signal"]["final_merged"] = [0] * 16
            with zipfile.ZipFile(path, "w") as out:
                out.writestr("manifest.json", json.dumps({
                    "pipeline_lifecycle_job_id": "job_current",
                    "git_commit_sha": SHA
                }))
                out.writestr("session_fixture_000.jsonl", "\n".join([
                    json.dumps({"event": audit.EVENT_NAME, "fields": first}),
                    json.dumps({"event": audit.EVENT_NAME, "fields": different})
                ]))
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                audit.read_bundle(path)


if __name__ == "__main__":
    unittest.main()
