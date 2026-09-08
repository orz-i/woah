#!/usr/bin/env python3
"""Pure-Python validation for Woah Phase 1 cloud-device probe reports."""

from __future__ import annotations

from typing import Any


DEFAULT_REQUIRED_BACKENDS = ("auto", "tflite_xnnpack")


def evaluate_phase1_report(
    payload: dict[str, Any],
    *,
    required_backends: tuple[str, ...] = DEFAULT_REQUIRED_BACKENDS,
    expected_detections: int = 1,
    bbox_tolerance: float = 0.02,
    confidence_tolerance: float = 0.03,
    mask_coverage_tolerance: float = 0.03,
) -> dict[str, Any]:
    errors: list[str] = []
    comparisons: list[dict[str, Any]] = []
    reports = payload.get("reports")
    if not isinstance(reports, list):
        return {"ok": False, "errors": ["payload.reports is missing"], "comparisons": []}

    by_backend: dict[str, dict[str, Any]] = {}
    for entry in reports:
        if isinstance(entry, dict) and isinstance(entry.get("backend"), str):
            by_backend[entry["backend"]] = entry

    for backend in required_backends:
        entry = by_backend.get(backend)
        if entry is None:
            errors.append(f"missing required backend report: {backend}")
            continue
        if entry.get("ok") is not True:
            errors.append(f"required backend failed: {backend}: {entry.get('error')}")
            continue
        report = entry.get("report")
        if not isinstance(report, dict):
            errors.append(f"required backend has no report payload: {backend}")
            continue
        if report.get("detection_count") != expected_detections:
            errors.append(
                f"{backend} detection_count={report.get('detection_count')} expected={expected_detections}"
            )

    baseline_entry = by_backend.get("tflite_xnnpack")
    if not isinstance(baseline_entry, dict) or baseline_entry.get("ok") is not True:
        baseline_entry = by_backend.get("auto")
    baseline = baseline_entry.get("report") if isinstance(baseline_entry, dict) else None
    if not isinstance(baseline, dict):
        errors.append("no successful XNNPACK/auto baseline report is available")
        return {"ok": False, "errors": errors, "comparisons": comparisons}

    baseline_detections = baseline.get("detections")
    if not isinstance(baseline_detections, list):
        errors.append("baseline detections are missing")
        return {"ok": False, "errors": errors, "comparisons": comparisons}

    for backend, entry in sorted(by_backend.items()):
        if entry.get("ok") is not True:
            continue
        report = entry.get("report")
        if not isinstance(report, dict) or report is baseline:
            continue
        detections = report.get("detections")
        comparison: dict[str, Any] = {"backend": backend, "ok": True, "issues": []}
        if not isinstance(detections, list) or len(detections) != len(baseline_detections):
            comparison["ok"] = False
            comparison["issues"].append("detection count differs from baseline")
        else:
            for index, (actual, expected) in enumerate(zip(detections, baseline_detections)):
                if not isinstance(actual, dict) or not isinstance(expected, dict):
                    comparison["ok"] = False
                    comparison["issues"].append(f"detection {index} payload is invalid")
                    continue
                actual_bbox = actual.get("bbox")
                expected_bbox = expected.get("bbox")
                if (
                    not isinstance(actual_bbox, list)
                    or not isinstance(expected_bbox, list)
                    or len(actual_bbox) != 4
                    or len(expected_bbox) != 4
                ):
                    comparison["ok"] = False
                    comparison["issues"].append(f"detection {index} bbox is invalid")
                else:
                    bbox_delta = max(abs(float(a) - float(b)) for a, b in zip(actual_bbox, expected_bbox))
                    if bbox_delta > bbox_tolerance:
                        comparison["ok"] = False
                        comparison["issues"].append(
                            f"detection {index} bbox delta {bbox_delta:.6f} > {bbox_tolerance}"
                        )
                confidence_delta = abs(
                    float(actual.get("confidence", 0.0)) - float(expected.get("confidence", 0.0))
                )
                if confidence_delta > confidence_tolerance:
                    comparison["ok"] = False
                    comparison["issues"].append(
                        f"detection {index} confidence delta {confidence_delta:.6f} > {confidence_tolerance}"
                    )
                mask_delta = abs(
                    float(actual.get("mask_coverage", 0.0))
                    - float(expected.get("mask_coverage", 0.0))
                )
                if mask_delta > mask_coverage_tolerance:
                    comparison["ok"] = False
                    comparison["issues"].append(
                        f"detection {index} mask coverage delta {mask_delta:.6f} > {mask_coverage_tolerance}"
                    )
        comparisons.append(comparison)
        if not comparison["ok"] and backend in required_backends:
            errors.extend(f"{backend}: {issue}" for issue in comparison["issues"])

    return {
        "ok": not errors,
        "errors": errors,
        "comparisons": comparisons,
        "required_backends": list(required_backends),
        "expected_detections": expected_detections,
    }
