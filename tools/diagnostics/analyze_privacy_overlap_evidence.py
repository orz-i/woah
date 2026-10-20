#!/usr/bin/env python3
"""Analyze Debug-only aggregate mask ownership evidence in two Woah ZIPs.

This tool reads only numeric JSONL aggregate events, never video images or raw
segmentation probabilities. The 4x4 mask-bin counts are evidence about software
instance-mask overlap, NOT final-video pixel privacy acceptance.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys
import zipfile
from typing import Any

EVENT_NAME = "PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT"
SCOPE = "AGGREGATE_PROTO_MASK_COUNTS_ONLY_NO_RGB_NO_RAW_MASK"
NMS_SHADOW_EVENT = "YOLO_NMS_VISIBLE_THRESHOLD_SHADOW"
NMS_SHADOW_SCOPE = "MODEL_SPACE_NUMERIC_ONLY_NO_RGB_NO_MASK"
FOCUS_START_US = 39_600_000
FOCUS_END_US = 39_910_000
# Optional addition introduced after b80e60b. Older Debug bundles remain
# valid and surface None instead of fabricating a successful gate or pixels.
STRONG_KEYS = (
    "selected_and_fresh_ge224",
    "selected_and_observed_ge224",
    "selected_and_both_ge192",
    "selected_and_both_ge224",
    "selected_and_both_ge224_vetoed_other",
    "selected_and_both_ge224_without_other",
)
STRONG_SCALAR_FIELDS = (
    "strong_foreground_gate_eligible",
    "strong_foreground_gate_failures",
    "strong_foreground_core_proto_pixels",
    "strong_foreground_observed_age_frames",
    "strong_foreground_observed_confidence",
    "strong_foreground_veto_selected_masks_complete",
)
RAW_TOP_SUPPORT_FIELDS = (
    "target_yolo_raw_top_support", "candidate_yolo_raw_top_support",
)
RAW_TOP_SUPPORT_KEYS = (
    "outside_sampled", "outside_ge128", "outside_ge192", "outside_ge224",
    "inside_sampled", "inside_decoded_ge128", "band_height_proto",
)
OUTSIDE_KEYS = (
    "selected_raw",
    "selected_and_fresh_foreground",
    "selected_retained_effective",
    "selected_and_approved_carve",
    "selected_and_temporal_carve",
)
MUST_HAVE = (
    "selected_raw", "fresh_foreground", "selected_and_fresh_foreground",
    "selected_and_observed_foreground", "selected_with_weak_foreground",
    "selected_with_raw_foreground_advantage",
    "selected_and_probability_ownership", "selected_and_fresh_eroded_core",
    "selected_and_approved_carve", "selected_and_temporal_carve",
    "selected_retained_effective", "selected_retained_and_fresh_foreground",
    "selected_retained_with_weak_foreground", "competing_selected",
    "competing_selected_and_fresh_foreground", "final_merged",
    "final_merged_and_fresh_foreground",
)


def _validate_event(fields: dict[str, Any]) -> None:
    if fields.get("valid") is not True or fields.get("evidence_scope") != SCOPE:
        raise ValueError("invalid or unsupported aggregate pixel evidence")
    if fields.get("grid_shape") != [4, 4] or fields.get("not_ground_truth_ownership") is not True:
        raise ValueError("invalid aggregation or misleading ownership proof")
    summary = fields.get("total_by_signal")
    bins = fields.get("grid_by_signal")
    if not isinstance(summary, dict) or not isinstance(bins, dict):
        raise ValueError("missing mask signal totals or spatial bins")
    for key in MUST_HAVE:
        row = bins.get(key)
        total = summary.get(key)
        if not isinstance(row, list) or len(row) != 16:
            raise ValueError(f"invalid 4x4 aggregate grid for {key}")
        if any(type(v) is not int or v < 0 for v in row):
            raise ValueError(f"invalid numeric bins for {key}")
        if type(total) is not int or total < 0 or sum(row) != total:
            raise ValueError(f"aggregate sum mismatch for {key}")
    outside = fields.get("outside_selected_bbox_totals")
    if not isinstance(outside, dict):
        raise ValueError("missing outside-bbox mask-bleed totals")
    if any(type(outside.get(key)) is not int or outside[key] < 0
           for key in OUTSIDE_KEYS):
        raise ValueError("invalid outside-bbox mask-bleed totals")
    strong = fields.get("strong_threshold_totals")
    if strong is not None:
        if not isinstance(strong, dict) or any(
            type(strong.get(k)) is not int or strong[k] < 0
            for k in STRONG_KEYS
        ):
            raise ValueError("invalid strict foreground threshold totals")
        if (strong["selected_and_both_ge224"] >
            min(strong["selected_and_fresh_ge224"],
                strong["selected_and_observed_ge224"],
                strong["selected_and_both_ge192"])):
            raise ValueError("strict threshold intersection exceeds evidence")
        if (strong["selected_and_both_ge224_vetoed_other"] +
            strong["selected_and_both_ge224_without_other"] !=
            strong["selected_and_both_ge224"]):
            raise ValueError("strict foreground veto partition mismatch")
    if "strong_veto_selected_mask_count" in fields:
        veto_count = fields["strong_veto_selected_mask_count"]
        if type(veto_count) is not int or veto_count < 0:
            raise ValueError("invalid strict selected mask veto count")
    if "strong_foreground_gate_eligible" in fields:
        eligible = fields["strong_foreground_gate_eligible"]
        failures = fields.get("strong_foreground_gate_failures")
        if type(eligible) is not bool or not isinstance(failures, list) or any(
            not isinstance(reason, str) or not reason for reason in failures
        ):
            raise ValueError("invalid strict foreground gate diagnosis")
        if eligible != (len(failures) == 0):
            raise ValueError("strict gate diagnosis contradicts eligibility")
        for key in ("strong_foreground_core_proto_pixels",
                    "strong_foreground_observed_age_frames"):
            if type(fields.get(key)) is not int or fields[key] < 0:
                raise ValueError("invalid strict gate count")
        confidence = fields.get("strong_foreground_observed_confidence")
        if (type(confidence) not in (int, float) or
            not 0 <= confidence <= 1):
            raise ValueError("invalid observed root confidence")
        if type(fields.get("strong_foreground_veto_selected_masks_complete")) is not bool:
            raise ValueError("invalid strict veto mask completeness")
        if strong is None:
            raise ValueError("strict gate diagnosis lacks threshold evidence")
    for name in RAW_TOP_SUPPORT_FIELDS:
        # Older builds lack this optional counterfactual; None never implies
        # the model had no signal or that foreground ownership was verified.
        value = fields.get(name)
        if value is None:
            continue
        if not isinstance(value, dict) or any(
            type(value.get(key)) is not int or value[key] < 0
            for key in RAW_TOP_SUPPORT_KEYS
        ):
            raise ValueError(f"invalid raw prototype top support: {name}")
        if not (value["outside_ge224"] <= value["outside_ge192"] <=
                value["outside_ge128"] <= value["outside_sampled"]):
            raise ValueError(f"inconsistent raw prototype top support: {name}")
        if value["inside_decoded_ge128"] > value["inside_sampled"] or (
            value["band_height_proto"] != 12
        ):
            raise ValueError(f"invalid raw prototype top support bounds: {name}")
    if type(fields.get("pts_us")) is not int or fields["pts_us"] < 0:
        raise ValueError("missing or invalid PTS")
    for key in ("target_detection_index", "candidate_detection_index",
                "corroborating_unselected_track_id"):
        if type(fields.get(key)) is not int:
            raise ValueError(f"invalid target/candidate identity metadata: {key}")


def _validate_nms_shadow(fields: dict[str, Any]) -> None:
    """Reject malformed or image-like shadow data; no privacy PASS is inferred."""
    if (
        fields.get("evidence_scope") != NMS_SHADOW_SCOPE or
        fields.get("pixel_ownership_proven") is not False or
        fields.get("does_not_change_rendering") is not True
    ):
        raise ValueError("invalid NMS shadow scope / unsafe acceptance assertion")
    counts = (
        "raw_candidate_count", "legacy_kept_count", "strong_kept_count",
        "strong_only_count", "legacy_only_count"
    )
    allowed = set(counts) | {
        "job_id", "pts_us", "evidence_scope", "pixel_ownership_proven",
        "does_not_change_rendering", "strong_only_geometry",
        "strong_only_geometry_truncated",
    }
    if set(fields) != allowed:
        raise ValueError("unexpected NMS shadow field / forbidden extra data")
    if any(type(fields.get(key)) is not int or fields[key] < 0 for key in counts):
        raise ValueError("invalid NMS shadow candidate counts")
    raw = fields["raw_candidate_count"]
    legacy = fields["legacy_kept_count"]
    strong = fields["strong_kept_count"]
    add = fields["strong_only_count"]
    lost = fields["legacy_only_count"]
    if raw > 8400 or max(legacy, strong) > raw or add > strong or lost > legacy or (
        legacy - lost + add != strong
    ):
        raise ValueError("inconsistent NMS shadow candidate partition")
    geometry = fields.get("strong_only_geometry")
    if not isinstance(geometry, list) or len(geometry) != min(add, 6) or (
        fields.get("strong_only_geometry_truncated") is not (add > 6)
    ):
        raise ValueError("invalid NMS shadow bounded candidate geometry")
    for item in geometry:
        if not isinstance(item, dict) or set(item) != {"bbox_model", "confidence"}:
            raise ValueError("invalid NMS shadow candidate shape")
        bbox = item["bbox_model"]
        confidence = item["confidence"]
        if not isinstance(bbox, list) or len(bbox) != 4 or any(
            type(v) not in (float, int) or not math.isfinite(v) for v in bbox
        ) or type(confidence) not in (float, int) or (
            not math.isfinite(confidence) or not 0 <= confidence <= 1
        ):
            raise ValueError("invalid NMS shadow scalar geometry")
    if type(fields.get("pts_us")) is not int or fields["pts_us"] < 0:
        raise ValueError("invalid NMS shadow PTS")


def read_bundle(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as zipped:
        manifest = json.loads(zipped.read("manifest.json"))
        job = manifest.get("pipeline_lifecycle_job_id")
        commit = manifest.get("git_commit_sha")
        if not isinstance(job, str) or not job:
            raise ValueError(f"{path.name}: job ID is missing")
        if not isinstance(commit, str) or len(commit) != 40:
            raise ValueError(f"{path.name}: build commit cannot be verified")
        per_key: dict[tuple[int, int, int], dict[str, Any]] = {}
        nms_events: dict[int, dict[str, Any]] = {}
        coverage_warnings: set[tuple[int, int | None]] = set()
        for name in zipped.namelist():
            if not (name.startswith("session_") and name.endswith(".jsonl")):
                continue
            for line in zipped.read(name).decode("utf-8", "replace").splitlines():
                try:
                    event = json.loads(line)
                except (ValueError, TypeError):
                    continue
                if not isinstance(event, dict):
                    continue
                fields = event.get("fields") or {}
                if not isinstance(fields, dict) or fields.get("job_id") != job:
                    continue
                kind = event.get("event")
                if kind == "PRIVACY_COVERAGE_DROP":
                    pts = fields.get("pts_us")
                    if type(pts) is int:
                        coverage_warnings.add((pts, fields.get("target_detection_index")))
                if kind == NMS_SHADOW_EVENT:
                    _validate_nms_shadow(fields)
                    pts = fields["pts_us"]
                    if pts in nms_events and nms_events[pts] != fields:
                        raise ValueError(f"{path.name}: conflicting duplicate NMS shadow at {pts}")
                    nms_events[pts] = fields
                    continue
                if kind != EVENT_NAME:
                    continue
                _validate_event(fields)
                key = (fields["pts_us"], fields["target_detection_index"],
                       fields["candidate_detection_index"])
                if key in per_key and per_key[key] != fields:
                    raise ValueError(f"{path.name}: conflicting duplicate ownership audit at {key}")
                per_key[key] = fields

    return {
        "bundle_name": path.name,
        "job_id": job,
        "commit": commit,
        "coverage_warnings": sorted(
            coverage_warnings, key=lambda item: (item[0], str(item[1]))
        ),
        "events": per_key,
        "nms_shadow_events": nms_events,
    }


def compare(landscape: dict[str, Any], portrait: dict[str, Any]) -> dict[str, Any]:
    def focus(sample: dict[str, Any]) -> dict[tuple[int, int, int], dict[str, Any]]:
        return {
            key: value for key, value in sample["events"].items()
            if FOCUS_START_US <= key[0] <= FOCUS_END_US
        }
    la = focus(landscape)
    po = focus(portrait)
    common = sorted(set(la) & set(po))
    disagreements = []
    for key in common:
        # Different output modes have different job IDs, never different
        # source-space mask counts if they share the same tracking source.
        retained = (
            "grid_by_signal", "total_by_signal", "selected_bbox_proto",
            "outside_selected_bbox_totals", "strong_threshold_totals",
            "strong_veto_selected_mask_count", *STRONG_SCALAR_FIELDS,
            *RAW_TOP_SUPPORT_FIELDS
        )
        if any(la[key].get(field) != po[key].get(field) for field in retained):
            disagreements.append(key)
    def summarize(sample: dict[str, Any], by_key: dict) -> dict[str, Any]:
        records = []
        for key, event in sorted(by_key.items()):
            totals = event["total_by_signal"]
            records.append({
                "pts_us": key[0],
                "target_detection_index": key[1],
                "candidate_detection_index": key[2],
                "corroborating_unselected_track_id":
                    event["corroborating_unselected_track_id"],
                "counts": {signal: totals[signal] for signal in MUST_HAVE},
                "grid_by_signal": {
                    signal: event["grid_by_signal"][signal] for signal in MUST_HAVE
                },
                "outside_selected_bbox_totals": {
                    signal: event["outside_selected_bbox_totals"][signal]
                    for signal in OUTSIDE_KEYS
                },
                "strong_threshold_totals": event.get("strong_threshold_totals"),
                "strong_veto_selected_mask_count": event.get(
                    "strong_veto_selected_mask_count"
                ),
                "raw_yolo_top_support": {
                    "target": event.get("target_yolo_raw_top_support"),
                    "candidate": event.get("candidate_yolo_raw_top_support"),
                },
                "strong_gate_diagnostics": (
                    {name: event[name] for name in STRONG_SCALAR_FIELDS}
                    if all(name in event for name in STRONG_SCALAR_FIELDS)
                    else None
                ),
            })
        return {
            "build_commit": sample["commit"],
            "job_id": sample["job_id"],
            "audited_frames_total": len({
                key[0] for key in sample["events"]
            }),
            "audited_conflicts_total": len(sample["events"]),
            "coverage_warning_count": len(sample["coverage_warnings"]),
            "focus_events": records,
        }
    comparable = landscape["commit"] == portrait["commit"]
    shadow_left = {
        pts: value for pts, value in landscape.get("nms_shadow_events", {}).items()
        if FOCUS_START_US <= pts <= FOCUS_END_US
    }
    shadow_right = {
        pts: value for pts, value in portrait.get("nms_shadow_events", {}).items()
        if FOCUS_START_US <= pts <= FOCUS_END_US
    }
    shadow_fields = (
        "raw_candidate_count", "legacy_kept_count", "strong_kept_count",
        "strong_only_count", "legacy_only_count", "strong_only_geometry",
        "strong_only_geometry_truncated"
    )
    shadow_common = sorted(set(shadow_left) & set(shadow_right))
    shadow_mismatches = [
        pts for pts in shadow_common
        if any(shadow_left[pts][key] != shadow_right[pts][key]
               for key in shadow_fields)
    ]
    shadow_status = (
        "DIFFERENT_APK_COMMITS_NOT_COMPARABLE" if not comparable else
        "NMS_SHADOW_NOT_PRESENT" if not shadow_left and not shadow_right else
        "NMS_SHADOW_FRAME_SET_MISMATCH" if set(shadow_left) != set(shadow_right) else
        "NMS_SHADOW_NUMERIC_MISMATCH" if shadow_mismatches else
        "NMS_SHADOW_PARITY_ONLY_NOT_PRIVACY_ACCEPTANCE"
    )
    def shadow_rows(records: dict[int, dict]) -> list[dict]:
        return [
            {"pts_us": pts, **{key: fields[key] for key in shadow_fields}}
            for pts, fields in sorted(records.items())
        ]
    status = (
        "DIFFERENT_APK_COMMITS_NOT_COMPARABLE" if not comparable else
        "NO_FOCUS_AUDIT_NOT_ACCEPTED" if not la or not po else
        "FOCUS_EVENTS_MISSING_BETWEEN_MODES" if set(la) != set(po) else
        "FOCUS_BIN_COUNTS_MISMATCH" if disagreements else
        "FOCUS_NMS_SHADOW_MISMATCH" if shadow_status in (
            "NMS_SHADOW_FRAME_SET_MISMATCH", "NMS_SHADOW_NUMERIC_MISMATCH"
        ) else "SOURCE_MASK_BIN_PARITY_ONLY_NOT_VISUAL_ACCEPTANCE"
    )
    return {
        "schema": 1,
        "evidence_scope": SCOPE,
        "visual_privacy_acceptance": "NOT_PROVEN",
        "focus_pts_us": [FOCUS_START_US, FOCUS_END_US],
        "parity_status": status,
        "shared_focus_conflicts": len(common),
        "mismatched_focus_conflicts": [list(k) for k in disagreements[:20]],
        "nms_shadow": {
            "status": shadow_status,
            "shared_focus_frames": len(shadow_common),
            "mismatched_pts_us": shadow_mismatches[:20],
            "landscape": shadow_rows(shadow_left),
            "portrait": shadow_rows(shadow_right),
            "pixels_restored": 0,
            "protagonist_ownership_proven": False,
        },
        "landscape": summarize(landscape, la),
        "portrait": summarize(portrait, po),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--landscape", type=Path, required=True)
    parser.add_argument("--portrait", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True,
                        help="Numeric-only JSON report; no image/mask bytes")
    args = parser.parse_args()
    try:
        repo_root = Path(__file__).resolve().parents[2]
        report_path = args.output.resolve()
        if repo_root == report_path or repo_root in report_path.parents:
            raise ValueError(
                "Numeric mask evidence must be saved outside the Git repository"
            )
        result = compare(read_bundle(args.landscape), read_bundle(args.portrait))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8"
        )
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as exc:
        print(f"PRIVACY_OVERLAP_EVIDENCE=FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"PRIVACY_OVERLAP_EVIDENCE={result['parity_status']}")
    print(f"YOLO_NMS_SHADOW={result['nms_shadow']['status']}")
    print(f"PRIVACY_VISUAL_ACCEPTANCE={result['visual_privacy_acceptance']}")
    print(f"Report: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
