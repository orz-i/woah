#!/usr/bin/env python3
"""Inspect M0 planned-frame metadata; never claim physical display acceptance."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def evaluate(report: dict, *, expected_offset_us: int | None = None,
             fps: int = 30, min_pairs: int = 60) -> dict:
    is_gl = report.get("schema") == "woah.comparison.m0.gl.v1"
    if not is_gl and report.get("schema") != "woah.comparison.m0.probe.v1":
        raise ValueError("Wrong M0 diagnostic schema")
    evidence = ("joint_gl_submission_source_pts_only" if is_gl else
                "pre_render_planned_release_metadata_only")
    if report.get("evidenceType") != evidence:
        raise ValueError("Wrong evidence provenance")
    if expected_offset_us is not None and report.get("offsetUs") != expected_offset_us:
        raise ValueError("Manual offset does not match fixture manifest")
    pairs = report.get("jointSubmissions" if is_gl else "matchedPlannedReleaseFrames", 0)
    p95 = report.get("p95AbsMediaErrorUs")
    maximum = report.get("maxAbsMediaErrorUs")
    # Heuristic engineering thresholds for equal-frame-rate CFR: deliberately
    # report a candidate only, never display-PASS.
    tolerance_us = math.ceil(1_000_000 / fps)
    measured = (pairs >= min_pairs and
                isinstance(p95, (int, float)) and
                isinstance(maximum, (int, float)))
    usable = report.get("usableTeacherFrames")
    coverage = pairs / usable if isinstance(usable, int) and usable > 0 else None
    topology_valid = (not is_gl or (report.get("audioPlayerCount") == 1 and
                      report.get("videoWindowSurfaces") == 1 and
                      report.get("coverageMisses") == 0 and
                      not report.get("diagnosticsSaturated", False)))
    within_target = (topology_valid and report.get("sourceError") is None and
                     (coverage is None or coverage >= 0.8) and measured and
                     p95 <= tolerance_us and maximum <= 2 * tolerance_us)
    return {
        "status": ("JOINT_GL_METADATA_WITHIN_TARGET" if is_gl else
                   "PLANNED_METADATA_WITHIN_TARGET") if within_target
                  else "INSUFFICIENT_OR_OUTSIDE_PLANNED_METADATA",
        "actualDisplayAcceptance": "NOT_PROVEN",
        "backend": report.get("backend", "dual_exoplayer"),
        "metadataSamples": pairs,
        "plannedFramePairs": pairs if not is_gl else None,
        "p95ClockSampleAgeUs": report.get("p95ClockSampleAgeUs"),
        "maxClockSampleAgeUs": report.get("maxClockSampleAgeUs"),
        "plannedPairCoverage": coverage,
        "observationDurationUs": report.get("observationDurationUs"),
        "windows": report.get("windows", []),
        "minRequiredPairs": min_pairs,
        "p95AbsMediaErrorUs": p95,
        "maxAbsMediaErrorUs": maximum,
        "thresholdUsOneFrame": tolerance_us,
        "offsetUs": report.get("offsetUs"),
        "bufferingEvents": report.get("bufferingEvents"),
        "speed": report.get("speed"),
        "device": report.get("device"),
        "limitation": ("Joint GL source-PTS submission metadata only; " if is_gl else
                       "Matched Media3 planned video frame release times only; ") +
                       "requires actual on-device screen observations.",
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
    return 0 if result["status"] in ("PLANNED_METADATA_WITHIN_TARGET",
                                    "JOINT_GL_METADATA_WITHIN_TARGET") else 2


if __name__ == "__main__":
    raise SystemExit(main())
