#!/usr/bin/env python3
"""Compare manually reviewed *coarse* source-video cells with Woah proto evidence.

Never reconstructs video or binary masks. A labeled 4x4 cell is NOT a pixel
ownership oracle: it may contain visible protagonist and an occluded protected
person simultaneously. This report may prioritize investigation, never enable
automatic privacy-mask removal or certify a protected export.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any

PURPOSE = "woah_local_coarse_visual_overlap_annotation"
EVIDENCE_SCOPE = "HUMAN_COARSE_VISIBLE_REGIONS_NO_VIDEO_NO_PIXEL_MASKS"
CATEGORIES = {
    "UNREVIEWED", "PROTAGONIST_VISIBLE", "PROTECTED_VISIBLE",
    "MIXED_UNCERTAIN", "BACKGROUND_VISIBLE",
}
ALLOWED_TOP = {
    "schema", "purpose", "evidence_scope", "report_build_commit",
    "source_width", "source_height", "source_duration_s",
    "manual_source_video_match",
    "visual_privacy_acceptance", "pixel_carve_authorization",
    "label_definitions", "frames",
}
ALLOWED_FRAME = {
    "pts_us", "selected_bbox_proto", "cells", "browser_media_time_s",
}
REQUIRED_SIGNALS = (
    "selected_raw",
    "selected_and_fresh_foreground",
    "selected_retained_and_fresh_foreground",
    "selected_retained_with_weak_foreground",
    "competing_selected",
)
OPTIONAL_SIGNALS = ("selected_retained_effective", "final_merged")
RELEVANT = REQUIRED_SIGNALS + OPTIONAL_SIGNALS


def read_numeric(source: dict[str, Any]) -> tuple[dict[int, dict[str, Any]], str | None]:
    frames: list[dict[str, Any]] = []
    commit = None
    if isinstance(source.get("focus_frames"), list):
        commit = source.get("build_commit")
        for frame in source["focus_frames"]:
            frames.append({
                "pts_us": frame["pts_us"],
                "box": frame["protected_mask_proto_bbox"],
                "grids": frame["coarse_4x4_cells"],
            })
    elif isinstance(source.get("landscape"), dict):
        if source.get("parity_status") != "SOURCE_MASK_BIN_PARITY_ONLY_NOT_VISUAL_ACCEPTANCE":
            raise ValueError("numeric report does not establish source-grid parity")
        commit = source["landscape"].get("build_commit")
        for frame in source["landscape"].get("focus_events", []):
            frames.append({
                "pts_us": frame["pts_us"],
                "box": frame["selected_bbox_proto"],
                "grids": frame["grid_by_signal"],
            })
    else:
        raise ValueError("unsupported numeric report schema")
    if not frames:
        raise ValueError("numeric report has no risk-frame signal data")
    by_pts: dict[int, dict[str, Any]] = {}
    for frame in frames:
        pts = frame["pts_us"]
        if type(pts) is not int or not 39_600_000 <= pts <= 39_910_000:
            raise ValueError("numeric PTS is missing or outside the audited interval")
        box = frame["box"]
        if (not isinstance(box, list) or len(box) != 4 or
            not all(type(v) in (int, float) for v in box) or
            not (0 <= box[0] < box[2] <= 160 and 0 <= box[1] < box[3] <= 160)):
            raise ValueError("invalid model-proto bbox")
        for key in RELEVANT:
            arr = frame["grids"].get(key)
            if key in OPTIONAL_SIGNALS and arr is None:
                continue
            if (not isinstance(arr, list) or len(arr) != 16 or
                any(type(v) is not int or v < 0 for v in arr)):
                raise ValueError("invalid numeric proto-grid signal: " + key)
        if pts in by_pts:
            raise ValueError("duplicate risk-frame PTS")
        by_pts[pts] = frame
    return by_pts, commit


def proto_bbox_to_source(box: list[float], width: int = 1280,
                         height: int = 720) -> list[float]:
    """Mirror ModelCoordinateMapper(model=640, proto=160) without cropping."""
    scale = min(640.0 / width, 640.0 / height)
    left_pad = (640.0 - width * scale) / 2.0
    top_pad = (640.0 - height * scale) / 2.0
    return [
        round((box[0] * 4.0 - left_pad) / scale, 3),
        round((box[1] * 4.0 - top_pad) / scale, 3),
        round((box[2] * 4.0 - left_pad) / scale, 3),
        round((box[3] * 4.0 - top_pad) / scale, 3),
    ]


def validate_manual(labels: dict[str, Any], numeric: dict[int, dict[str, Any]],
                    expected_commit: str | None) -> dict[int, dict[str, Any]]:
    if not isinstance(labels, dict) or set(labels) - ALLOWED_TOP:
        raise ValueError("unexpected manual annotation fields")
    if (labels.get("schema") != 1 or labels.get("purpose") != PURPOSE or
        labels.get("evidence_scope") != EVIDENCE_SCOPE or
        labels.get("pixel_carve_authorization") != "NEVER" or
        labels.get("visual_privacy_acceptance") != "NOT_PROVEN"):
        raise ValueError("annotations must not claim privacy acceptance or carving authority")
    if (labels.get("source_width"), labels.get("source_height")) != (1280, 720):
        raise ValueError("source geometry not consistent with this Woah export")
    duration = labels.get("source_duration_s")
    if (type(duration) not in (int, float) or not 40 <= duration < 24 * 3600):
        raise ValueError("source video duration cannot contain the audited risk frames")
    if labels.get("manual_source_video_match") != "OPERATOR_ASSERTED_NOT_CRYPTOGRAPHICALLY_VERIFIED":
        raise ValueError("manual source provenance may only be operator asserted")
    claim = labels.get("report_build_commit")
    if claim is not None and (not isinstance(claim, str) or len(claim) != 40 or
                              any(c not in "0123456789abcdefABCDEF" for c in claim)):
        raise ValueError("invalid claimed Git build commit")
    if claim is not None and expected_commit and claim.lower() != expected_commit.lower():
        raise ValueError("annotation does not match numeric report build commit")
    raw = labels.get("frames")
    if not isinstance(raw, list) or not raw:
        raise ValueError("no manual frame annotations")
    found: dict[int, dict[str, Any]] = {}
    for item in raw:
        if not isinstance(item, dict) or set(item) - ALLOWED_FRAME:
            raise ValueError("unexpected manual per-frame fields")
        pts = item.get("pts_us")
        if type(pts) is not int or pts not in numeric or pts in found:
            raise ValueError("missing/duplicate/unknown manual frame PTS")
        cells = item.get("cells")
        if not isinstance(cells, list) or len(cells) != 16 or any(
            not isinstance(v, str) or v not in CATEGORIES for v in cells
        ):
            raise ValueError("manual labels must contain sixteen allowed categories")
        box = item.get("selected_bbox_proto")
        target = numeric[pts]["box"]
        if (not isinstance(box, list) or len(box) != 4 or
            any(type(a) not in (float, int) or abs(a - b) > 0.005
                for a, b in zip(box, target))):
            raise ValueError("manual label bbox does not match numeric target")
        media = item.get("browser_media_time_s")
        if media is not None and (type(media) not in (float, int) or
                                  not -1e6 < media < 1e6):
            raise ValueError("invalid browser decoded media time")
        found[pts] = item
    if set(found) != set(numeric):
        raise ValueError("manual label frames incomplete or from a different report")
    if not any(v != "UNREVIEWED" for item in found.values() for v in item["cells"]):
        raise ValueError("no grid cell has been manually reviewed")
    return found


def evaluate(labels: dict[str, Any], numeric_report: dict[str, Any]) -> dict[str, Any]:
    numeric, commit = read_numeric(numeric_report)
    frames = validate_manual(labels, numeric, commit)
    entries: list[dict[str, Any]] = []
    for pts, model in sorted(numeric.items()):
        item = frames[pts]
        cells = item["cells"]
        signals = model["grids"]
        by_class = {}
        for label in sorted(CATEGORIES):
            indices = [i for i, category in enumerate(cells) if category == label]
            by_class[label] = {
                "cell_count": len(indices),
                "proto_counts_within_labeled_cells": {
                    signal: (
                        sum(signals[signal][i] for i in indices)
                        if signal in signals else None
                    )
                    for signal in RELEVANT
                },
            }
        marked_protagonist = by_class["PROTAGONIST_VISIBLE"]["proto_counts_within_labeled_cells"]
        observed_media_time = item.get("browser_media_time_s")
        alignment = (
            "MEDIA_TIME_UNVERIFIED" if observed_media_time is None
            else "WITHIN_HALF_FRAME" if abs(observed_media_time - pts / 1e6) <= 0.018
            else "MEDIA_TIME_DIFFERENT_FROM_TARGET"
        )
        entries.append({
            "pts_us": pts,
            "manual_cell_labels": by_class,
            "source_video_time_alignment": alignment,
            "selected_bbox_proto": model["box"],
            "target_bbox_source_px_approx": proto_bbox_to_source(model["box"]),
            "triage_candidates_not_proven_mask_errors": {
                "protected_pixels_in_cells_with_visible_protagonist":
                    marked_protagonist["selected_retained_effective"],
                "protected_pixels_also_in_fresh_foreground_in_those_cells":
                    marked_protagonist["selected_retained_and_fresh_foreground"],
                "other_selected_mask_pixels_in_those_cells":
                    marked_protagonist["competing_selected"],
                "source_masks_missing_fresh_foreground_in_those_cells": (
                    marked_protagonist["selected_retained_effective"] -
                    marked_protagonist["selected_retained_and_fresh_foreground"]
                    if marked_protagonist["selected_retained_effective"] is not None
                    else None
                ),
            },
        })
    complete = all(
        entry["manual_cell_labels"]["UNREVIEWED"]["cell_count"] == 0
        for entry in entries
    )
    aligned = all(
        entry["source_video_time_alignment"] == "WITHIN_HALF_FRAME"
        for entry in entries
    )
    return {
        "schema": 1,
        "purpose": "woah_coarse_label_vs_proto_evidence_triage",
        "build_commit": commit,
        "source_provenance": "OPERATOR_ASSERTED_NO_IMAGE_VERIFICATION",
        "privacy_acceptance": "NOT_PROVEN",
        "pixel_carve_authorization": "NEVER",
        "manual_review_complete": complete,
        "decoded_video_time_verified_for_all_frames": aligned,
        "interpretation": (
            "Coarse cells can contain multiple people and both background and foreground. "
            "These counts identify areas worth independent pixel-accurate inspection; "
            "they are not a supervised segmentation mask or permission to uncover someone."
        ),
        "risk_frames": entries,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--numeric-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        repository = Path(__file__).resolve().parents[2]
        target = args.output.resolve()
        if repository == target or repository in target.parents:
            raise ValueError("manual label reports must stay outside the Git repository")
        manual = json.loads(args.annotations.read_text(encoding="utf-8"))
        model = json.loads(args.numeric_report.read_text(encoding="utf-8"))
        result = evaluate(manual, model)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8"
        )
    except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError) as exc:
        print("PRIVACY_MANUAL_COARSE_REVIEW=FAIL: " + str(exc), file=sys.stderr)
        return 1
    print("PRIVACY_MANUAL_COARSE_REVIEW=TRIAGE_ONLY_NOT_ACCEPTED")
    print("Report:", args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
