#!/usr/bin/env python3
"""Analyze only scalar logcat emitted by the opt-in Android Stage 3 smoke.

No source videos, screenshots, model weights, image pixels, confidence grids or
intermediate masks may appear in accepted records. Measurements are synthetic
CPU-only smoke diagnostics; they cannot certify privacy or release throughput.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import statistics
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TAG = "WoahMagicTouchStage3"
SCOPE = "STAGE3_PUBLIC_MODEL_SYNTHETIC_ONLY"
EXPECTED_MODEL_SHA = (
    "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03"
)
FIELDS = frozenset({
    "schema", "scope", "run_token", "git_commit", "model_sha256", "device_model", "android_api",
    "source_video_used", "person_identity_proven",
    "protected_person_occupancy_verified", "pixel_carve_authorization",
    "privacy_visual_acceptance", "delegate", "model_sha256_verified",
    "image_width", "image_height", "warmups", "measured_runs",
    "init_wall_ms", "set_image_median_ms", "set_image_p95_ms",
    "segment_median_ms", "segment_p95_ms",
    "last_positive_mask_pixels_ge_0_5",
    "last_confidence_min", "last_confidence_max",
    "wide_source_width", "wide_source_height",
    "wide_source_output_geometry_verified",
    "wide_set_image_ms", "wide_segment_ms",
    "pss_start_kb", "pss_end_kb", "thermal_start", "thermal_end",
    "peak_ram_measured", "gpu_latency_measured", "final_video_verified",
})
MEASUREMENTS = (
    "init_wall_ms", "set_image_median_ms", "set_image_p95_ms",
    "segment_median_ms", "segment_p95_ms",
    "wide_set_image_ms", "wide_segment_ms",
)


def validate(record: Any) -> dict:
    if not isinstance(record, dict) or set(record) != FIELDS:
        raise ValueError("unrecognized synthetic smoke fields or forbidden mask/image data")
    for key, expected in (
        ("schema", 1), ("scope", SCOPE), ("model_sha256", EXPECTED_MODEL_SHA),
        ("source_video_used", False), ("person_identity_proven", False),
        ("protected_person_occupancy_verified", False),
        ("pixel_carve_authorization", "NEVER"),
        ("privacy_visual_acceptance", "NOT_PROVEN"),
        ("delegate", "CPU"), ("model_sha256_verified", True),
        ("image_width", 384), ("image_height", 384), ("warmups", 1),
        ("measured_runs", 5), ("wide_source_width", 512),
        ("wide_source_height", 288),
        ("wide_source_output_geometry_verified", True),
        ("peak_ram_measured", False), ("gpu_latency_measured", False),
        ("final_video_verified", False),
    ):
        if type(record[key]) is not type(expected) or record[key] != expected:
            raise ValueError("invalid stage3 model/runtime/privacy contract: " + key)
    if (
        type(record["run_token"]) is not str
        or not re.fullmatch(r"[0-9a-f]{16}", record["run_token"])
        or type(record["git_commit"]) is not str
        or not re.fullmatch(r"[a-fA-F0-9]{40}", record["git_commit"])
        or type(record["device_model"]) is not str
        or not 1 <= len(record["device_model"]) <= 80
        or type(record["android_api"]) is not int
        or not 24 <= record["android_api"] <= 99
    ):
        raise ValueError("invalid Stage 3 device/commit identity")
    if any(
        type(record[key]) not in (float, int) or
        not math.isfinite(record[key]) or record[key] < 0 or record[key] > 1e6
        for key in MEASUREMENTS
    ):
        raise ValueError("invalid synthetic CPU latency")
    if (
        record["set_image_p95_ms"] < record["set_image_median_ms"]
        or record["segment_p95_ms"] < record["segment_median_ms"]
    ):
        raise ValueError("p95 cannot be smaller than median")
    for key in ("pss_start_kb", "pss_end_kb"):
        if type(record[key]) is not int or record[key] < 0:
            raise ValueError("invalid sampled PSS")
    for key in ("thermal_start", "thermal_end"):
        if type(record[key]) is not int or not -1 <= record[key] <= 6:
            raise ValueError("invalid sampled thermal status")
    count = record["last_positive_mask_pixels_ge_0_5"]
    if type(count) is not int or not 0 <= count <= 384 * 384:
        raise ValueError("invalid synthetic positive-pixel count")
    minimum, maximum = record["last_confidence_min"], record["last_confidence_max"]
    if (
        type(minimum) not in (int, float) or type(maximum) not in (int, float)
        or not math.isfinite(minimum) or not math.isfinite(maximum)
        or not 0 <= minimum <= maximum <= 1
    ):
        raise ValueError("invalid aggregate model confidence")
    return record


def read_logcat(content: str, *, run_token: str | None = None) -> list[dict]:
    records = []
    for line in content.splitlines():
        if TAG not in line or "{" not in line:
            continue
        try:
            record = json.loads(line[line.index("{"):])
        except (ValueError, TypeError):
            continue
        # Never merge old device logcat data from other repeated experiments.
        if run_token is not None and (
            not isinstance(record, dict) or record.get("run_token") != run_token
        ):
            continue
        records.append(validate(record))
    if not records:
        raise ValueError("no valid completed synthetic CPU smoke records found")
    return records


def summarize(
    records: list[dict], *, min_repeats: int = 3,
    distinct_run_tokens: bool = False,
) -> dict:
    if min_repeats < 2 or min_repeats > 20:
        raise ValueError("invalid repeat requirement")
    if not records:
        raise ValueError("no records")
    for record in records:
        validate(record)
    first = records[0]
    tokens = [record["run_token"] for record in records]
    if distinct_run_tokens and len(set(tokens)) != len(tokens):
        raise ValueError("repeated Stage 3 attempt token; runs not independent")
    comparison_fields = ("git_commit", "model_sha256", "device_model", "android_api")
    if not distinct_run_tokens:
        comparison_fields = ("run_token", *comparison_fields)
    for record in records:
        for key in comparison_fields:
            if record[key] != first[key]:
                raise ValueError("different device/build/model cannot be compared")
    report = {
        "schema": 1,
        "scope": "STAGE3_NUMERIC_SYNTHETIC_CPU_ONLY",
        "git_commit": first["git_commit"],
        "device_model": first["device_model"],
        "android_api": first["android_api"],
        "model_sha256": EXPECTED_MODEL_SHA,
        "runs": len(records),
        "repeat_status": (
            "REPEATED_CPU_SMOKE_NOT_PRODUCTION_BENCHMARK"
            if len(records) >= min_repeats else
            "REPEAT_REQUIRED"
        ),
        "cpu_init_median_ms": round(statistics.median(r["init_wall_ms"] for r in records), 3),
        "set_image_median_ms": round(statistics.median(
            r["set_image_median_ms"] for r in records
        ), 3),
        "segment_median_ms": round(statistics.median(
            r["segment_median_ms"] for r in records
        ), 3),
        "segment_p95_over_runs_ms": round(max(r["segment_p95_ms"] for r in records), 3),
        "wide_input_output_match_every_run": all(
            r["wide_source_output_geometry_verified"] for r in records
        ),
        "thermal_end_samples": [r["thermal_end"] for r in records],
        "end_pss_kb_samples": [r["pss_end_kb"] for r in records],
        "performance_limits": [
            "CPU_ONLY", "SYNTHETIC_GEOMETRY_ONLY",
            "SMALL_SAMPLE_NOT_THERMAL_STABILITY",
            "END_PSS_NOT_PEAK_MEMORY",
            "NO_REAL_VIDEO_OR_OCCLUSION",
        ],
        "android_api_smoke": "CPU_CODE_PATH_EXECUTED_ONLY",
        "pixel_carve_authorization": "NEVER",
        "privacy_visual_acceptance": "NOT_PROVEN",
        "release_acceptance": "NOT_PROVEN",
    }
    if distinct_run_tokens:
        report["run_token_policy"] = "UNIQUE_VERIFIED_TOKEN_PER_INSTRUMENTATION"
        report["run_tokens"] = tokens
    else:
        # Preserve read-only compatibility with pre-Stage3 telemetry reports.
        report["run_token"] = first["run_token"]
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--min-runs", type=int, default=3)
    parser.add_argument("--run-token", help="Restrict to one historical 16-hex test session")
    parser.add_argument("--run-tokens", nargs="+", metavar="TOKEN",
                        help="Verify exactly one event for each distinct Stage 3 attempt")
    args = parser.parse_args()
    try:
        dest = args.output.resolve()
        if dest == ROOT or ROOT in dest.parents:
            raise ValueError("synthetic smoke log report must remain outside Git tree")
        content = args.log.read_text(encoding="utf-8-sig")
        if args.run_token is not None and args.run_tokens is not None:
            raise ValueError("provide --run-token or --run-tokens, not both")
        if args.run_tokens is not None:
            tokens = args.run_tokens
            if (not 2 <= len(tokens) <= 20
                or len(set(tokens)) != len(tokens)
                or any(not re.fullmatch(r"[0-9a-f]{16}", t) for t in tokens)):
                raise ValueError("--run-tokens must contain distinct 16-hex values")
            records = []
            for token in tokens:
                matches = read_logcat(content, run_token=token)
                if len(matches) != 1:
                    raise ValueError("not exactly one synthetic log per attempt token")
                records.extend(matches)
            report = summarize(
                records, min_repeats=args.min_runs, distinct_run_tokens=True
            )
        else:
            report = summarize(
                read_logcat(content, run_token=args.run_token),
                min_repeats=args.min_runs,
            )
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, TypeError, KeyError) as error:
        print("WOAH_MAGIC_TOUCH_STAGE3_ANALYSIS=FAIL: " + str(error), file=sys.stderr)
        return 1
    print("WOAH_MAGIC_TOUCH_STAGE3_ANALYSIS=" + report["repeat_status"])
    print("PRIVACY_VISUAL_ACCEPTANCE=NOT_PROVEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
