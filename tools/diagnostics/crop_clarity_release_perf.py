#!/usr/bin/env python3
"""Compare Android *release* export throughput with Woah crop clarity OFF vs ON.

Input: two text Logcat captures containing `WoahExportPerf` completed JSON
records, at least three repeated exports for each mode. Run the same phone,
source media, trim, FPS, output size, privacy selection and quality profile.
Source identity is a manual operator assertion (not proven by the logs).

No video is ingested. This script NEVER claims GPU kernel latency, peak RSS,
thermal safety, final picture quality or privacy correctness from these logs.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import statistics

TAG = "WoahExportPerf"
MATCH_FIELDS = (
    "git_commit", "device_model", "source_width", "source_height",
    "trim_start_ms", "trim_end_ms", "target_width", "target_height",
    "video_bitrate", "profile", "follow_enabled", "privacy_target_count",
    "yolo_accelerator",
)
FLOAT_FIELDS = ("source_fps", "target_fps")


def _read_text(path: Path) -> str:
    data = path.read_bytes()
    # Windows PowerShell 5 often writes redirected Logcat text as UTF-16 LE;
    # unlike binary ZIP files, log text can safely be decoded here.
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16")
    return data.decode("utf-8-sig")


def validate_release_record(record: dict, *, source: str = "release-record") -> dict:
    """Require complete, internally coherent, scalar-only Release telemetry.

    Native ExportPipeline emits this once after a successful atomic output.
    The native renderEffects profiler wraps every successfully rendered frame.
    A missing timer, bogus count, divergent throughput or invalid flags must
    not accidentally promote a partial record into matched evidence.
    """
    if (not isinstance(record, dict)
        or type(record.get("schema")) is not int
        or record["schema"] != 1):
        raise ValueError(f"{source}: unsupported Release performance record")
    if record.get("build_mode") != "release":
        raise ValueError(f"{source}: Debug data is not an acceptable release benchmark")
    if record.get("state") != "completed":
        raise ValueError(f"{source}: incomplete export is not a benchmark sample")
    if record.get("ab_capture_possible") is not False:
        raise ValueError(f"{source}: A/B capture-capable build rejected")
    if record.get("yolo_accelerator") != "GPU" or record.get("yolo_fallback") not in (None, ""):
        raise ValueError(f"{source}: GPU inference was unavailable or fallback occurred")
    commit = record.get("git_commit")
    if (not isinstance(commit, str) or
        not re.fullmatch(r"[0-9a-fA-F]{12,40}", commit)):
        raise ValueError(f"{source}: missing, unknown or invalid source Git commit")
    if (not isinstance(record.get("device_model"), str)
        or not 1 <= len(record["device_model"].strip()) <= 80
        or any(ord(c) < 32 for c in record["device_model"])):
        raise ValueError(f"{source}: missing or malformed device model")
    for key in (
        "source_width", "source_height", "target_width", "target_height",
        "video_bitrate", "privacy_target_count", "decoded_frames",
        "rendered_frames", "encoded_frames", "render_cpu_dispatch_count",
    ):
        value = record.get(key)
        if type(value) is not int or value <= 0:
            raise ValueError(f"{source}: invalid positive count/dimension {key}")
    for key in ("trim_start_ms", "trim_end_ms"):
        value = record.get(key)
        if type(value) is not int or value < 0:
            raise ValueError(f"{source}: invalid trim timing {key}")
    if record["trim_end_ms"] <= record["trim_start_ms"]:
        raise ValueError(f"{source}: nonpositive export trim duration")
    if type(record.get("follow_enabled")) is not bool:
        raise ValueError(f"{source}: missing follow-enabled contract")
    if record.get("profile") != "quality":
        raise ValueError(f"{source}: quality-profile export required")
    for key in ("source_fps", "target_fps", "elapsed_ms", "throughput_fps"):
        value = record.get(key)
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"{source}: invalid nonzero timing or FPS {key}")
    for key in ("render_cpu_dispatch_p50_ms", "render_cpu_dispatch_p95_ms"):
        value = record.get(key)
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise ValueError(f"{source}: invalid CPU dispatch timer {key}")
    if record["render_cpu_dispatch_p95_ms"] < record["render_cpu_dispatch_p50_ms"]:
        raise ValueError(f"{source}: CPU dispatch P95 smaller than P50")
    frame_keys = ("decoded_frames", "rendered_frames", "encoded_frames")
    if len({record[key] for key in frame_keys}) != 1:
        raise ValueError(f"{source}: decoded/rendered/encoded frame count mismatch")
    if record["render_cpu_dispatch_count"] != record["rendered_frames"]:
        raise ValueError(f"{source}: render CPU dispatch sample count differs from frames")
    expected_throughput = record["rendered_frames"] * 1000.0 / record["elapsed_ms"]
    if abs(record["throughput_fps"] - expected_throughput) > max(0.02, expected_throughput * 0.005):
        raise ValueError(f"{source}: throughput does not match elapsed time and frame count")
    scale = record.get("clarity_scale")
    if type(scale) not in (int, float) or not math.isfinite(scale) or not 1.0 <= scale <= 2.0:
        raise ValueError(f"{source}: invalid restoration scale")
    state = record.get("clarity_state")
    if state not in ("off", "on") or (scale <= 1.001) != (state == "off"):
        raise ValueError(f"{source}: declared clarity state contradicts scale")
    if state == "on" and record["follow_enabled"] is not True:
        raise ValueError(f"{source}: crop clarity cannot be ON without follow crop")
    thermal = record.get("thermal_status_end")
    if thermal is not None and (type(thermal) is not int or not 0 <= thermal <= 6):
        raise ValueError(f"{source}: invalid end thermal status sample")
    pss = record.get("pss_end_kb")
    if type(pss) is not int or pss <= 0:
        raise ValueError(f"{source}: invalid end PSS sample")
    return record


def read_records(path: Path) -> list[dict]:
    records = []
    for line in _read_text(path).splitlines():
        if TAG not in line and not line.lstrip().startswith("{"):
            continue
        begin = line.find("{")
        if begin < 0:
            continue
        try:
            record = json.loads(line[begin:])
        except (ValueError, TypeError):
            continue
        if not isinstance(record, dict) or record.get("schema") != 1:
            continue
        records.append(validate_release_record(record, source=path.name))
    if not records:
        raise ValueError(f"No completed release {TAG} records in {path}")
    return records


def _median(records: list[dict], key: str) -> float:
    return float(statistics.median(float(item[key]) for item in records))


def _percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round((len(ordered) - 1) * p))]


def compare(
    off: list[dict], on: list[dict], *, scenario: str,
    repeats_required: int = 3, max_slowdown_percent: float = 10.0,
    control_noop: bool = False,
) -> dict:
    if not scenario or not scenario.strip():
        raise ValueError("scenario/source label is required")
    if repeats_required < 2:
        raise ValueError("repeats_required must be >= 2")
    if not math.isfinite(max_slowdown_percent) or max_slowdown_percent < 0:
        raise ValueError("invalid slowdown budget")
    if not off or not on:
        raise ValueError("both OFF and ON Release runs are required")
    for side, records in (("off", off), ("on", on)):
        for index, record in enumerate(records):
            validate_release_record(record, source=f"{side}[{index}]")
    baseline = off[0]
    if not isinstance(baseline.get("git_commit"), str) or baseline["git_commit"] in ("", "unknown"):
        raise ValueError("release benchmark requires a known Git commit in both APKs")
    for side, records in (("off", off), ("on", on)):
        for record in records:
            for key in MATCH_FIELDS:
                if record.get(key) != baseline.get(key):
                    raise ValueError(f"{side}: non-comparable {key}: {record.get(key)!r} vs {baseline.get(key)!r}")
            for key in FLOAT_FIELDS:
                if not isinstance(record.get(key), (int, float)) or abs(float(record[key]) - float(baseline[key])) > 0.01:
                    raise ValueError(f"{side}: non-comparable {key}")
            if abs(record["encoded_frames"] - baseline["encoded_frames"]) > 1:
                raise ValueError(f"{side}: frame count differs by more than one")
            scale = record.get("clarity_scale")
            if not isinstance(scale, (int, float)) or not math.isfinite(scale):
                raise ValueError(f"{side}: invalid clarity scale")
            if (side == "off" or control_noop) and (scale > 1.001 or record.get("clarity_state") != "off"):
                raise ValueError(f"{side}: expected an OFF/no-op clarity export")
            if side == "on" and not control_noop and (scale <= 1.001 or record.get("clarity_state") != "on"):
                raise ValueError("ON run never activated crop clarity")
    elapsed_off = _median(off, "elapsed_ms")
    elapsed_on = _median(on, "elapsed_ms")
    slowdown = (elapsed_on / elapsed_off - 1.0) * 100.0
    throughput_off = _median(off, "throughput_fps")
    throughput_on = _median(on, "throughput_fps")
    enough_repeats = min(len(off), len(on)) >= repeats_required
    return {
        "schema": 1,
        "scenario": scenario,
        "source_identity": "OPERATOR_ASSERTED_NOT_LOG_VERIFIED",
        "device_model": baseline["device_model"],
        "git_commit": baseline["git_commit"],
        "target_size": [baseline["target_width"], baseline["target_height"]],
        "source_size": [baseline["source_width"], baseline["source_height"]],
        "clarity_control_noop": control_noop,
        "repeats_required": repeats_required,
        "off": {
            "runs": len(off),
            "wall_elapsed_median_ms": round(elapsed_off, 3),
            "wall_elapsed_p95_ms": round(_percentile([r["elapsed_ms"] for r in off], .95), 3),
            "throughput_median_fps": round(throughput_off, 3),
            "render_cpu_dispatch_p50_median_ms": round(_median(off, "render_cpu_dispatch_p50_ms"), 3),
            "render_cpu_dispatch_p95_median_ms": round(_median(off, "render_cpu_dispatch_p95_ms"), 3),
            "thermal_status_end_samples": [r.get("thermal_status_end") for r in off],
            "pss_end_kb_samples": [r.get("pss_end_kb") for r in off],
        },
        "on": {
            "runs": len(on),
            "wall_elapsed_median_ms": round(elapsed_on, 3),
            "wall_elapsed_p95_ms": round(_percentile([r["elapsed_ms"] for r in on], .95), 3),
            "throughput_median_fps": round(throughput_on, 3),
            "render_cpu_dispatch_p50_median_ms": round(_median(on, "render_cpu_dispatch_p50_ms"), 3),
            "render_cpu_dispatch_p95_median_ms": round(_median(on, "render_cpu_dispatch_p95_ms"), 3),
            "thermal_status_end_samples": [r.get("thermal_status_end") for r in on],
            "pss_end_kb_samples": [r.get("pss_end_kb") for r in on],
        },
        "wall_elapsed_slowdown_percent": round(slowdown, 3),
        "provisional_slowdown_budget_percent": max_slowdown_percent,
        "throughput_regression_review": (
            "REPEAT_REQUIRED" if not enough_repeats else
            "WITHIN_PROVISIONAL_BUDGET" if slowdown <= max_slowdown_percent else
            "EXCEEDS_PROVISIONAL_BUDGET"
        ),
        "measurement_limits": {
            "gpu_execution_time": "NOT_MEASURED_CPU_DISPATCH_ONLY",
            "peak_memory": "NOT_MEASURED_ONLY_END_PSS_SAMPLED",
            "thermal_safety": "NOT_PROVEN_BY_END_STATUS_ALONE",
            "visual_quality": "NOT_MEASURED",
            "privacy_correctness": "NOT_MEASURED",
        },
        "release_acceptance": "REQUIRES_DEVICE_REVIEW",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--off", type=Path, required=True, help="Logcat collected from benchmark-off Release APK")
    parser.add_argument("--on", type=Path, required=True, help="Logcat collected from normal Release APK")
    parser.add_argument("--scenario", required=True, help="Operator-asserted same input media label (never uploaded)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--min-runs", type=int, default=3)
    parser.add_argument("--max-slowdown-percent", type=float, default=10.0)
    parser.add_argument("--control-noop", action="store_true", help="4K control: both builds should run 1.0x")
    args = parser.parse_args()
    try:
        report = compare(
            read_records(args.off), read_records(args.on),
            scenario=args.scenario, repeats_required=args.min_runs,
            max_slowdown_percent=args.max_slowdown_percent,
            control_noop=args.control_noop,
        )
    except (OSError, ValueError, KeyError, UnicodeDecodeError) as exc:
        print(f"CROP_CLARITY_RELEASE_PERF=FAIL: {exc}")
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("CROP_CLARITY_RELEASE_PERF=" + json.dumps(report, ensure_ascii=False, sort_keys=True))
    print("CROP_CLARITY_RELEASE_PERF=REVIEW_REQUIRED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
