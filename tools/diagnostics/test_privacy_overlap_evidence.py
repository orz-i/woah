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


def make_bundle(path: Path, fields: dict, *, job: str = "job_current",
                sha: str = SHA, duplicate: bool = False) -> None:
    event = {"event": audit.EVENT_NAME, "fields": fields}
    stale = {"event": audit.EVENT_NAME, "fields": {**fields, "job_id": "old_job"}}
    warning = {"event": "PRIVACY_COVERAGE_DROP", "fields": {
        "job_id": job, "pts_us": 39_733_333, "target_detection_index": 2
    }}
    payload = [json.dumps(stale), json.dumps(event), json.dumps(warning)]
    if duplicate:
        payload.append(json.dumps(event))
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
