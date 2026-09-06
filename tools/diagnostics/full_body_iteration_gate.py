#!/usr/bin/env python3
"""Gate Full Body optimization against device- and LiteRT-runtime-specific goldens.

Full Body production inference is allowed to use the device's effective LiteRT
accelerator, so cross-device GPU bytes are not assumed to be identical. Each
device keeps its own exact quality/fingerprint contract while the CPU4T probe
remains a deterministic reference lane.

Normal accumulation requires the same runtime lane as the performance baseline.
Accelerator/backend experiments must opt into ``--runtime-compatibility report-only``;
when the runtime actually changes, timing is reported but the candidate is not
accepted into normal accumulation from that comparison alone.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
ANALYZER_PATH = SCRIPT_DIR / "analyze_cross_device_batch.py"


def _load_analyzer():
    spec = importlib.util.spec_from_file_location("full_body_cross_device_analyzer", ANALYZER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load analyzer: {ANALYZER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ANALYZER = _load_analyzer()

TIMING_KEYS = {
    "canonical": "canonical",
    "production_yolo": "production_yolo",
    "production_tracking": "production_tracking",
    "privacy_class_tracking": "privacy_class_tracking",
    "render_effects": "render_effects",
    "cpu4t_total": "cpu_4t_total",
    "cpu4t_run": "cpu_4t_run",
    "cpu4t_decode": "cpu_4t_decode",
    "cpu4t_mask_decode": "cpu_4t_mask_decode",
    "cpu4t_diagnostics": "cpu_4t_diagnostics",
}

RUNTIME_KEYS = (
    "yolo_requested_accelerator",
    "yolo_effective_accelerator",
    "yolo_gpu_fallback_reason",
    "yolo_effective_cpu_num_threads",
    "yolo_inference_input_path",
    "cpu_mt4_probe_threads",
    "cpu_mt4_signature_scope",
    "cpu_mt4_probe_fallback_reason",
)


def _normalize(value: Any) -> Any:
    if isinstance(value, dict):
        items = [(_normalize(key), _normalize(item)) for key, item in value.items()]
        items.sort(key=lambda pair: json.dumps(pair[0], sort_keys=True, separators=(",", ":")))
        return items
    if isinstance(value, (tuple, list)):
        return [_normalize(item) for item in value]
    if isinstance(value, set):
        normalized = [_normalize(item) for item in value]
        return sorted(
            normalized,
            key=lambda item: json.dumps(item, sort_keys=True, separators=(",", ":")),
        )
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


def _runtime_summary(summary: dict) -> dict[str, Any]:
    return {key: summary.get(key) for key in RUNTIME_KEYS}


def extract_bundle(bundle_path: Path, include_fingerprints: bool = True) -> dict:
    bundle = ANALYZER.read_bundle(bundle_path)
    summary = bundle["pipeline_summary"] or {}
    production_tracks = bundle.get("full_body_production_tracks", {})
    production_identity = bundle.get("full_body_production_identity", {})
    privacy_inputs = bundle.get("full_body_privacy_inputs", {})
    production_detections = bundle.get("detections", {}).get("production", {})
    cpu4t_detections = bundle.get("detections", {}).get("cpu_mt4_probe", {})

    quality = {
        "analysis_canonical_rgba_sha256": (bundle.get("analysis_selection") or {}).get(
            "canonical_rgba_sha256"
        ),
        "analysis_candidate_ids_ge_0_60": (bundle.get("analysis_selection") or {}).get(
            "candidate_ids_ge_0_60"
        ),
        "selected_ids": sorted(summary.get("selected_ids") or []),
        "face_only_ids": sorted(summary.get("face_only_ids") or []),
        "fresh_full_body_class_primary_enabled": bool(
            summary.get("fresh_full_body_class_primary_enabled", False)
        ),
        "decoded_frames": int(summary.get("decoded_frames", 0)),
        "latched_frames": int(summary.get("latched_frames", 0)),
        "rendered_frames": int(summary.get("rendered_frames", 0)),
        "encoded_frames": int(summary.get("encoded_frames", 0)),
        "production_track_frames": len(production_tracks),
        "privacy_input_frames": len(privacy_inputs),
    }

    result = {
        "schema_version": 1,
        "bundle": str(bundle_path),
        "device": bundle["device"],
        "commit": bundle["commit"],
        "quality": quality,
        "runtime": _runtime_summary(summary),
        "performance": _timing_summary(bundle),
        "observability": {
            "production_track_frames_total": len(production_tracks),
            "privacy_input_frames_total": len(privacy_inputs),
            "production_detection_signature_frames_total": len(production_detections),
            "cpu4t_detection_signature_frames_total": len(cpu4t_detections),
            "cpu4t_reference_track_frames_total": len(bundle.get("shadow_cpu_full", {})),
            "adaptive_shadow_matrix_enabled": summary.get(
                "cross_device_adaptive_shadow_matrix_enabled"
            ),
            "adaptive_shadow_tracker_steps": summary.get(
                "cross_device_adaptive_shadow_tracker_steps"
            ),
        },
    }
    if include_fingerprints:
        result["fingerprints"] = {
            "production_tracks": _fingerprint(production_tracks),
            "production_identity": _fingerprint(production_identity),
            "privacy_inputs": _fingerprint(privacy_inputs),
            "production_detection_probe": _fingerprint(production_detections),
            "cpu4t_detections": _fingerprint(cpu4t_detections),
            "cpu4t_reference_tracks": _fingerprint(bundle.get("shadow_cpu_full", {})),
            "cpu4t_reference_identity": _fingerprint(bundle.get("shadow_cpu_identity", {})),
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


def _device_section(contract: dict, section: str, device: str) -> Any:
    value = contract.get(f"{section}_by_device", {}).get(device)
    if value is None and contract.get("source_device") == device:
        value = contract.get(section)
    return value


def _performance_baseline(contract: dict, device: str, stage: str, stat: str) -> float | None:
    timing = (_device_section(contract, "performance", device) or {}).get(stage)
    if isinstance(timing, dict) and timing.get(stat) is not None:
        return float(timing[stat])
    return None


def _work_baseline(contract: dict, device: str, key: str) -> float | None:
    value = (_device_section(contract, "observability", device) or {}).get(key)
    return float(value) if value is not None else None


def _runtime_baseline(contract: dict, device: str) -> dict[str, Any] | None:
    value = _device_section(contract, "runtime", device)
    return value if isinstance(value, dict) else None


def check_candidate(args: argparse.Namespace) -> int:
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    candidates = [extract_bundle(path, include_fingerprints=True) for path in args.bundle]
    devices = {candidate["device"] for candidate in candidates}
    if len(devices) != 1:
        raise SystemExit("All Full Body canary bundles must come from the same device")
    device = candidates[0]["device"]

    expected_quality = _device_section(contract, "quality", device)
    expected_fingerprints = _device_section(contract, "fingerprints", device)
    if expected_quality is None or expected_fingerprints is None:
        raise SystemExit(f"Contract has no Full Body exact golden for device {device}")

    baseline_bundle = None
    baseline_contract = None
    if args.performance_baseline_bundle is not None:
        baseline_bundle = extract_bundle(args.performance_baseline_bundle, include_fingerprints=False)
        if baseline_bundle["device"] != device:
            raise SystemExit("Performance baseline bundle must use the same device as the canary")
    elif args.performance_baseline_contract is not None:
        baseline_contract = json.loads(args.performance_baseline_contract.read_text(encoding="utf-8"))
        if _device_section(baseline_contract, "performance", device) is None:
            raise SystemExit("Performance baseline contract has no entry for the canary device")

    quality_mismatches: list[dict] = []
    for index, candidate in enumerate(candidates):
        _compare_expected(
            expected_quality,
            candidate["quality"],
            f"canary[{index}].quality",
            quality_mismatches,
        )
        _compare_expected(
            expected_fingerprints,
            candidate.get("fingerprints", {}),
            f"canary[{index}].fingerprints",
            quality_mismatches,
        )

    if baseline_bundle is not None:
        baseline_runtime = baseline_bundle.get("runtime")
        runtime_source = "performance_baseline_bundle"
    elif baseline_contract is not None:
        baseline_runtime = _runtime_baseline(baseline_contract, device)
        runtime_source = "performance_baseline_contract"
    else:
        baseline_runtime = _runtime_baseline(contract, device)
        runtime_source = "contract"

    runtime_mismatches: list[dict] = []
    if baseline_runtime is not None:
        for index, candidate in enumerate(candidates):
            _compare_expected(
                baseline_runtime,
                candidate.get("runtime", {}),
                f"canary[{index}].runtime",
                runtime_mismatches,
            )
    runtime_changed = bool(runtime_mismatches)

    performance_pass = True
    stage_result = None
    if args.target_stage:
        timings = [candidate["performance"].get(args.target_stage) for candidate in candidates]
        if baseline_bundle is not None:
            baseline_timing = baseline_bundle["performance"].get(args.target_stage)
            baseline = (
                float(baseline_timing[args.target_stat])
                if isinstance(baseline_timing, dict)
                and baseline_timing.get(args.target_stat) is not None
                else None
            )
        elif baseline_contract is not None:
            baseline = _performance_baseline(
                baseline_contract, device, args.target_stage, args.target_stat
            )
        else:
            baseline = _performance_baseline(contract, device, args.target_stage, args.target_stat)

        if baseline is None or any(timing is None for timing in timings):
            performance_pass = False
            stage_result = {
                "stage": args.target_stage,
                "stat": args.target_stat,
                "error": "missing candidate timing or device baseline",
                "baseline": baseline,
                "candidate": timings,
            }
        else:
            values = [float(timing[args.target_stat]) for timing in timings]
            candidate_value = float(statistics.median(values))
            improvement_pct = ((baseline - candidate_value) / baseline * 100.0) if baseline > 0 else 0.0
            stage_pass = improvement_pct >= args.min_improvement_pct
            performance_pass = performance_pass and stage_pass
            stage_result = {
                "stage": args.target_stage,
                "stat": args.target_stat,
                "baseline": baseline,
                "candidate_values": values,
                "candidate_median": candidate_value,
                "improvement_pct": improvement_pct,
                "required_improvement_pct": args.min_improvement_pct,
                "pass": stage_pass,
            }

    work_result = None
    if args.target_work:
        if baseline_bundle is not None:
            baseline_value = baseline_bundle["observability"].get(args.target_work)
            baseline_work = float(baseline_value) if baseline_value is not None else None
        elif baseline_contract is not None:
            baseline_work = _work_baseline(baseline_contract, device, args.target_work)
        else:
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

    if runtime_changed and args.runtime_compatibility == "same":
        performance_pass = False

    quality_pass = not quality_mismatches
    ready = False
    if not quality_pass:
        recommendation = "REJECT_FULL_BODY_QUALITY_DRIFT"
    elif runtime_changed and args.runtime_compatibility == "report-only":
        recommendation = "REPORT_ONLY_RUNTIME_CHANGE"
    elif runtime_changed:
        recommendation = "RUNTIME_MISMATCH_NO_PERF_COMPARISON"
    elif not performance_pass:
        recommendation = "CONTINUE_SAME_LANE_OPTIMIZATION"
    else:
        recommendation = "ACCEPT_FOR_ACCUMULATION"
        ready = True

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
        "quality_mismatches": quality_mismatches,
        "runtime_compatibility": {
            "mode": args.runtime_compatibility,
            "baseline_source": runtime_source if baseline_runtime is not None else None,
            "baseline": baseline_runtime,
            "candidates": [candidate.get("runtime", {}) for candidate in candidates],
            "mismatches": runtime_mismatches,
            "comparable": not runtime_changed,
            "performance_report_only": runtime_changed and args.runtime_compatibility == "report-only",
        },
        "performance_pass": performance_pass,
        "target_performance": stage_result,
        "target_work": work_result,
        "recommendation": recommendation,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if ready:
        return 0
    if not quality_pass:
        return 2
    return 3


def snapshot(args: argparse.Namespace) -> int:
    previous_contract: dict[str, Any] = {}
    if args.output.exists():
        try:
            loaded = json.loads(args.output.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                previous_contract = loaded
        except (OSError, json.JSONDecodeError):
            previous_contract = {}

    data = [extract_bundle(path, include_fingerprints=True) for path in args.bundle]
    commits = {item["commit"] for item in data}
    devices = [item["device"] for item in data]
    if len(set(devices)) != len(devices):
        raise SystemExit(f"Full Body golden bundles must contain unique devices, got {devices}")
    for item in data:
        if not item["quality"]["fresh_full_body_class_primary_enabled"]:
            raise SystemExit(f"Bundle from {item['device']} is not a Full Body-only production fixture")
        if item["quality"]["face_only_ids"]:
            raise SystemExit(f"Bundle from {item['device']} contains Face-only ids")
        if item["quality"]["production_track_frames"] <= 0:
            raise SystemExit(
                f"Bundle from {item['device']} lacks FULL_BODY_PRODUCTION_TRACK_SIGNATURE; "
                "build the instrumented current code before freezing the baseline"
            )

    reference_cpu4t_identity = data[0]["fingerprints"]["cpu4t_reference_identity"]
    cross_device_identity_mismatches = [
        {
            "device": item["device"],
            "expected": reference_cpu4t_identity,
            "actual": item["fingerprints"]["cpu4t_reference_identity"],
        }
        for item in data[1:]
        if item["fingerprints"]["cpu4t_reference_identity"] != reference_cpu4t_identity
    ]
    if cross_device_identity_mismatches:
        raise SystemExit(
            "Full Body CPU4T identity topology must be cross-device exact before freezing a golden:\n"
            + json.dumps(cross_device_identity_mismatches, ensure_ascii=False, indent=2)
        )

    single_commit = next(iter(commits)) if len(commits) == 1 else None
    contract = {
        "schema_version": 2,
        "name": args.name or (
            f"full_body_{single_commit[:8]}_device_lanes"
            if single_commit is not None
            else "full_body_mixed_device_lanes"
        ),
        "source_commit": single_commit,
        "source_commit_by_device": {item["device"]: item["commit"] for item in data},
        "source_devices": devices,
        "source_bundles": [str(path) for path in args.bundle],
        "source_bundle_by_device": {
            item["device"]: str(path) for path, item in zip(args.bundle, data)
        },
        "quality_by_device": {item["device"]: item["quality"] for item in data},
        "fingerprints_by_device": {item["device"]: item["fingerprints"] for item in data},
        "runtime_by_device": {item["device"]: item["runtime"] for item in data},
        "performance_by_device": {item["device"]: item["performance"] for item in data},
        "observability_by_device": {item["device"]: item["observability"] for item in data},
        "cross_device_invariants": {
            "cpu4t_reference_identity": reference_cpu4t_identity,
        },
    }
    previous_promotion_evidence = previous_contract.get("promotion_evidence_by_device")
    if isinstance(previous_promotion_evidence, dict):
        contract["promotion_evidence_by_device"] = previous_promotion_evidence
    if len(data) == 1:
        reference = data[0]
        contract["source_device"] = reference["device"]
        contract["quality"] = reference["quality"]
        contract["fingerprints"] = reference["fingerprints"]
        contract["runtime"] = reference["runtime"]
        contract["performance"] = reference["performance"]
        contract["observability"] = reference["observability"]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(contract, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"written": str(args.output), "name": contract["name"]}, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("check", help="Gate same-device Full Body candidates")
    check.add_argument("bundle", type=Path, nargs="+")
    check.add_argument("--contract", type=Path, required=True)
    performance_baseline = check.add_mutually_exclusive_group()
    performance_baseline.add_argument("--performance-baseline-bundle", type=Path)
    performance_baseline.add_argument("--performance-baseline-contract", type=Path)
    check.add_argument("--target-stage", choices=sorted(TIMING_KEYS))
    check.add_argument(
        "--target-stat",
        choices=("avg_ms", "p50_ms", "p95_ms"),
        default="p50_ms",
    )
    check.add_argument("--min-improvement-pct", type=float, default=0.0)
    check.add_argument(
        "--runtime-compatibility",
        choices=("same", "report-only"),
        default="same",
        help=(
            "same requires requested/effective LiteRT accelerator, fallback state, inference input "
            "path and CPU4T probe config to match the performance baseline. report-only is for "
            "explicit hardware-acceleration experiments and never auto-accepts a runtime change."
        ),
    )
    check.add_argument("--target-work")
    check.add_argument(
        "--target-work-mode",
        choices=("reduce", "equal"),
        default="reduce",
    )
    check.add_argument("--min-work-reduction-pct", type=float, default=0.0)
    check.set_defaults(func=check_candidate)

    snap = sub.add_parser(
        "snapshot",
        help="Freeze one or more device-specific Full Body exact/runtime baselines",
    )
    snap.add_argument("bundle", type=Path, nargs="+")
    snap.add_argument("--output", type=Path, required=True)
    snap.add_argument("--name")
    snap.set_defaults(func=snapshot)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
