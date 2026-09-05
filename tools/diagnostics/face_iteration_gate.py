#!/usr/bin/env python3
"""Single-device promotion gate for deterministic FACE_ONLY performance work.

The expensive three-device loop is a milestone gate, not an iteration loop. This
tool lets one target device reject low-value or behavior-changing candidates
before they consume a full cross-device run.

Examples:

  python tools/diagnostics/face_iteration_gate.py check logs/candidate.zip \
      --contract tools/diagnostics/baselines/face_only_78a1beca_contract.json \
      --target-stage face_privacy --min-improvement-pct 5

  python tools/diagnostics/face_iteration_gate.py snapshot logs/accepted.zip \
      --output tools/diagnostics/baselines/face_only_next.json
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import sys
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ANALYZER_PATH = SCRIPT_DIR / "analyze_cross_device_batch.py"


def _load_analyzer():
    spec = importlib.util.spec_from_file_location("face_cross_device_analyzer", ANALYZER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load analyzer: {ANALYZER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ANALYZER = _load_analyzer()


TIMING_KEYS = {
    "canonical": "canonical",
    "cpu4t_total": "cpu_4t_total",
    "cpu4t_run": "cpu_4t_run",
    "cpu4t_decode": "cpu_4t_decode",
    "cpu4t_mask_decode": "cpu_4t_mask_decode",
    "cpu4t_diagnostics": "cpu_4t_diagnostics",
    "face_roi": "face_roi",
    "face_detector": "face_detector",
    "face_detector_wall": "face_detector_wall",
    "face_privacy": "face_privacy",
}


def _normalize(value: Any) -> Any:
    if isinstance(value, dict):
        items = [(_normalize(key), _normalize(item)) for key, item in value.items()]
        items.sort(key=lambda pair: json.dumps(pair[0], sort_keys=True, separators=(",", ":")))
        return items
    if isinstance(value, (tuple, list)):
        return [_normalize(item) for item in value]
    if isinstance(value, set):
        normalized = [_normalize(item) for item in value]
        return sorted(normalized, key=lambda item: json.dumps(item, sort_keys=True, separators=(",", ":")))
    return value


def _fingerprint(value: Any) -> str:
    payload = json.dumps(
        _normalize(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _timing_summary(bundle: dict) -> dict[str, dict | None]:
    result: dict[str, dict | None] = {}
    for public_name, analyzer_name in TIMING_KEYS.items():
        timing = bundle["timings"].get(analyzer_name)
        if timing is None:
            result[public_name] = None
        else:
            result[public_name] = {
                "count": int(timing["count"]),
                "avg_ms": float(timing["avg_ms"]),
                "p50_ms": float(timing["p50_ms"]),
                "p95_ms": float(timing["p95_ms"]),
                "max_ms": float(timing["max_ms"]),
            }
    return result


def extract_bundle(bundle_path: Path, include_fingerprints: bool = True) -> dict:
    bundle = ANALYZER.read_bundle(bundle_path)
    pipeline_summary = bundle["pipeline_summary"] or {}
    selected_quality = ANALYZER.summarize_face_selected_gaps(bundle)
    quality = {
        "analysis_canonical_rgba_sha256": (bundle["analysis_selection"] or {}).get(
            "canonical_rgba_sha256"
        ),
        "analysis_candidate_ids_ge_0_60": (bundle["analysis_selection"] or {}).get(
            "candidate_ids_ge_0_60"
        ),
        "deterministic_primary": {
            "preferred": bool(
                pipeline_summary.get("face_deterministic_cpu_primary_preferred", False)
            ),
            "inference_frames": int(
                pipeline_summary.get("face_deterministic_cpu_primary_inference_frames", 0)
            ),
            "fallback_frames": int(
                pipeline_summary.get("face_deterministic_cpu_primary_fallback_frames", 0)
            ),
        },
        "cpu_identity_frames": len(bundle["shadow_cpu_identity"]),
        "sticker_frames": len(bundle["face_sticker_placements"]),
        "class_fallback_frames": selected_quality["class_fallback_frames"],
        "body_mask_guided_class_fallback_frames": selected_quality[
            "body_mask_guided_class_fallback_frames"
        ],
        "temporal_frames": len(bundle["face_temporal_class_evidence"]),
        "selected_track_ids": selected_quality["selected_track_ids"],
        "by_track_id": selected_quality["by_track_id"],
    }
    result = {
        "schema_version": 1,
        "bundle": str(bundle_path),
        "device": bundle["device"],
        "commit": bundle["commit"],
        "quality": quality,
        "performance": _timing_summary(bundle),
        "observability": {
            "face_roi_detector_events": len(bundle["face_roi_detector"]),
            "detector_calls_by_track_id": pipeline_summary.get("face_detector_calls_by_track_id"),
            "detector_calls_total": sum(
                int(value)
                for value in (pipeline_summary.get("face_detector_calls_by_track_id") or {}).values()
            ),
            "pixel_motion_frames_by_track_id": pipeline_summary.get(
                "face_pixel_motion_frames_by_track_id"
            ),
            "pixel_motion_rejected_frames_by_track_id": pipeline_summary.get(
                "face_pixel_motion_rejected_frames_by_track_id"
            ),
        },
    }
    if include_fingerprints:
        result["fingerprints"] = {
            "sticker_placements": _fingerprint(bundle["face_sticker_placements"]),
            "class_fallbacks": _fingerprint(bundle["face_class_fallbacks"]),
            "temporal_class_evidence": _fingerprint(bundle["face_temporal_class_evidence"]),
            "cpu_identity": _fingerprint(bundle["shadow_cpu_identity"]),
        }
    return result


def _compare_expected(expected: Any, actual: Any, path: str, mismatches: list[dict]) -> None:
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            mismatches.append({"path": path, "expected": expected, "actual": actual})
            return
        for key, expected_value in expected.items():
            child = f"{path}.{key}" if path else str(key)
            if key not in actual:
                mismatches.append({"path": child, "expected": expected_value, "actual": "<missing>"})
            else:
                _compare_expected(expected_value, actual[key], child, mismatches)
        return
    if isinstance(expected, (list, tuple)):
        if not isinstance(actual, (list, tuple)) or len(expected) != len(actual):
            mismatches.append({"path": path, "expected": expected, "actual": actual})
            return
        for index, expected_value in enumerate(expected):
            _compare_expected(expected_value, actual[index], f"{path}[{index}]", mismatches)
        return
    if expected != actual:
        mismatches.append({"path": path, "expected": expected, "actual": actual})


def _performance_baseline(contract: dict, device: str, stage: str, stat: str) -> float | None:
    by_device = contract.get("performance_by_device", {})
    device_values = by_device.get(device, {})
    value = device_values.get(stage)
    if isinstance(value, dict):
        value = value.get(stat)
    if value is None:
        snapshot_perf = contract.get("performance", {})
        timing = snapshot_perf.get(stage)
        if isinstance(timing, dict):
            value = timing.get(stat)
    return float(value) if value is not None else None


def _work_baseline(contract: dict, device: str, key: str) -> float | None:
    value = contract.get("observability_by_device", {}).get(device, {}).get(key)
    if value is None:
        value = contract.get("observability", {}).get(key)
    return float(value) if value is not None else None


def check_candidate(args: argparse.Namespace) -> int:
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    candidates = [extract_bundle(path, include_fingerprints=True) for path in args.bundle]
    devices = {candidate["device"] for candidate in candidates}
    if len(devices) != 1:
        raise SystemExit("All canary bundles must come from the same device")
    device = candidates[0]["device"]

    mismatches: list[dict] = []
    for index, candidate in enumerate(candidates):
        prefix = f"canary[{index}]"
        _compare_expected(
            contract.get("quality", {}), candidate["quality"], f"{prefix}.quality", mismatches
        )
        if contract.get("fingerprints"):
            _compare_expected(
                contract["fingerprints"],
                candidate.get("fingerprints", {}),
                f"{prefix}.fingerprints",
                mismatches,
            )

    stage_result = None
    performance_pass = True
    if args.target_stage:
        timings = [candidate["performance"].get(args.target_stage) for candidate in candidates]
        baseline = _performance_baseline(contract, device, args.target_stage, args.target_stat)
        if any(timing is None for timing in timings) or baseline is None:
            performance_pass = False
            stage_result = {
                "stage": args.target_stage,
                "stat": args.target_stat,
                "error": "missing candidate timing or device baseline",
                "candidate": timings,
                "baseline": baseline,
            }
        else:
            values = [float(timing[args.target_stat]) for timing in timings if timing is not None]
            candidate_value = float(statistics.median(values))
            improvement_pct = (
                ((baseline - candidate_value) / baseline) * 100.0 if baseline > 0 else 0.0
            )
            performance_pass = improvement_pct >= args.min_improvement_pct
            stage_result = {
                "stage": args.target_stage,
                "stat": args.target_stat,
                "baseline": baseline,
                "candidate_values": values,
                "candidate_median": candidate_value,
                "improvement_pct": improvement_pct,
                "required_improvement_pct": args.min_improvement_pct,
                "pass": performance_pass,
            }

    work_result = None
    if args.target_work:
        baseline_work = _work_baseline(contract, device, args.target_work)
        candidate_work = [candidate["observability"].get(args.target_work) for candidate in candidates]
        if baseline_work is None or any(value is None for value in candidate_work):
            performance_pass = False
            work_result = {
                "key": args.target_work,
                "error": "missing candidate work count or device baseline",
                "baseline": baseline_work,
                "candidate": candidate_work,
            }
        else:
            values = [float(value) for value in candidate_work]
            median_value = float(statistics.median(values))
            reduction_pct = (
                ((baseline_work - median_value) / baseline_work) * 100.0
                if baseline_work > 0
                else 0.0
            )
            if args.target_work_mode == "equal":
                work_pass = all(value == baseline_work for value in values)
            else:
                work_pass = reduction_pct >= args.min_work_reduction_pct
            performance_pass = performance_pass and work_pass
            work_result = {
                "key": args.target_work,
                "mode": args.target_work_mode,
                "baseline": baseline_work,
                "candidate_values": values,
                "candidate_median": median_value,
                "reduction_pct": reduction_pct,
                "required_reduction_pct": (
                    args.min_work_reduction_pct if args.target_work_mode == "reduce" else None
                ),
                "pass": work_pass,
            }

    quality_pass = not mismatches
    enough_canary_runs = len(candidates) >= args.min_canary_runs_for_milestone
    ready = quality_pass and performance_pass and enough_canary_runs
    if not quality_pass:
        recommendation = "NO_GO_TRI_DEVICE_QUALITY_DRIFT"
    elif not performance_pass:
        recommendation = "CONTINUE_SINGLE_DEVICE_OPTIMIZATION"
    elif not enough_canary_runs:
        recommendation = "READY_FOR_CONFIRMING_CANARY"
    else:
        recommendation = "READY_FOR_MILESTONE_TRI_DEVICE"

    report = {
        "candidates": [
            {
                "bundle": str(path),
                "device": candidate["device"],
                "commit": candidate["commit"],
            }
            for path, candidate in zip(args.bundle, candidates)
        ],
        "contract": {
            "path": str(args.contract),
            "name": contract.get("name"),
            "source_commit": contract.get("source_commit"),
        },
        "quality_pass": quality_pass,
        "quality_mismatches": mismatches,
        "performance_pass": performance_pass,
        "target_performance": stage_result,
        "target_work": work_result,
        "canary_runs": len(candidates),
        "required_canary_runs_for_milestone": args.min_canary_runs_for_milestone,
        "recommendation": recommendation,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if ready:
        return 0
    if not quality_pass:
        return 2
    return 3


def snapshot(args: argparse.Namespace) -> int:
    data = extract_bundle(args.bundle, include_fingerprints=True)
    contract = {
        "schema_version": 1,
        "name": args.name or f"face_only_{data['commit'][:8]}_{data['device']}",
        "source_commit": data["commit"],
        "source_device": data["device"],
        "quality": data["quality"],
        "fingerprints": data["fingerprints"],
        "performance": data["performance"],
        "observability": data["observability"],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(contract, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"written": str(args.output), "name": contract["name"]}, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("check", help="Gate one candidate bundle before a three-device run")
    check.add_argument("bundle", type=Path, nargs="+")
    check.add_argument("--contract", type=Path, required=True)
    check.add_argument("--target-stage", choices=sorted(TIMING_KEYS))
    check.add_argument(
        "--target-stat",
        choices=("avg_ms", "p50_ms", "p95_ms"),
        default="p50_ms",
        help="Use p50 by default to reduce thermal/scheduler outlier sensitivity",
    )
    check.add_argument("--min-improvement-pct", type=float, default=0.0)
    check.add_argument("--target-work")
    check.add_argument(
        "--target-work-mode",
        choices=("reduce", "equal"),
        default="reduce",
        help="Require structural work to decrease, or remain exactly equal to baseline",
    )
    check.add_argument("--min-work-reduction-pct", type=float, default=0.0)
    check.add_argument("--min-canary-runs-for-milestone", type=int, default=2)
    check.set_defaults(func=check_candidate)

    snap = sub.add_parser("snapshot", help="Create an exact golden contract from an accepted bundle")
    snap.add_argument("bundle", type=Path)
    snap.add_argument("--output", type=Path, required=True)
    snap.add_argument("--name")
    snap.set_defaults(func=snapshot)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
