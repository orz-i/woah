#!/usr/bin/env python3
"""Inspect M0 planned-frame metadata; never claim physical display acceptance."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def evaluate(report: dict, *, expected_offset_us: int | None = None,
             fps: int = 30, min_pairs: int = 60) -> dict:
    if report.get("schema") != "woah.comparison.m0.probe.v1":
        raise ValueError("Wrong M0 diagnostic schema")
    if report.get("evidenceType") != "pre_render_planned_release_metadata_only":
        raise ValueError("Wrong evidence provenance")
    if expected_offset_us is not None and report.get("offsetUs") != expected_offset_us:
        raise ValueError("Manual offset does not match fixture manifest")
    pairs = report.get("matchedPlannedReleaseFrames", 0)
    p95 = report.get("p95AbsMediaErrorUs")
    maximum = report.get("maxAbsMediaErrorUs")
    # Heuristic engineering thresholds for equal-frame-rate CFR: deliberately
    # report a candidate only, never display-PASS.
    tolerance_us = int(1_000_000 / fps + 0.5)
    measured = (pairs >= min_pairs and
                isinstance(p95, (int, float)) and
                isinstance(maximum, (int, float)))
    within_target = (measured and p95 <= tolerance_us and
                     maximum <= 2 * tolerance_us)
    return {
        "status": "PLANNED_METADATA_WITHIN_TARGET" if within_target
                  else "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA",
        "actualDisplayAcceptance": "NOT_PROVEN",
        "plannedFramePairs": pairs,
        "minRequiredPairs": min_pairs,
        "p95AbsMediaErrorUs": p95,
        "maxAbsMediaErrorUs": maximum,
        "thresholdUsOneFrame": tolerance_us,
        "offsetUs": report.get("offsetUs"),
        "bufferingEvents": report.get("bufferingEvents"),
        "speed": report.get("speed"),
        "device": report.get("device"),
        "limitation": ("Matched Media3 planned video frame release times only; "
                       "requires actual on-device screen observations."),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--fps", type=int, default=30, choices=(24, 30, 60))
    parser.add_argument("--min-pairs", type=int, default=60)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    manifest = (json.loads(args.manifest.read_text(encoding="utf-8"))
                if args.manifest else None)
    result = evaluate(
        report,
        expected_offset_us=manifest["expectedOffsetUs"] if manifest else None,
        fps=args.fps,
        min_pairs=args.min_pairs,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PLANNED_METADATA_WITHIN_TARGET" else 2


if __name__ == "__main__":
    raise SystemExit(main())
