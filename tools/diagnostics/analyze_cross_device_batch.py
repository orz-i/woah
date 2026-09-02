#!/usr/bin/env python3
"""Summarize one batch cross-device diagnostic run from 2+ Woah bundles."""

from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path


DETECTION_EVENTS = {"YOLO_TENSOR_CAPTURED", "YOLO_DETECTION_SIGNATURE_CAPTURED"}


def _device_name(z: zipfile.ZipFile) -> str:
    try:
        device = json.loads(z.read("device.json"))
        return str(device.get("build", {}).get("MODEL") or "unknown")
    except Exception:
        return "unknown"


def _stage(summary: dict, name: str):
    return summary.get("stage_timings", {}).get(name)


def _detection_signature(detections: list[dict]) -> tuple:
    return tuple(
        (
            d.get("index"),
            d.get("confidence_q1e4"),
            tuple(d.get("bbox_q0_0625px", [])),
            d.get("mask_width"),
            d.get("mask_height"),
            d.get("mask_assoc_binary_sha256"),
            d.get("mask_assoc_foreground_pixels"),
            d.get("mask_assoc_near_threshold_pixels"),
        )
        for d in detections
    )


def _track_identity_signature(tracks: list[dict]) -> tuple:
    return tuple(
        (
            t.get("id"),
            t.get("state"),
            t.get("observed_this_frame"),
            t.get("frames_since_last_observation"),
            t.get("missed_frames"),
            tuple(t.get("occluded_by_track_ids", [])),
        )
        for t in tracks
    )


def _track_id_set_signature_from_identity(identity_signature: tuple) -> tuple:
    return tuple(item[0] for item in identity_signature)


def _track_state_signature_from_identity(identity_signature: tuple) -> tuple:
    return tuple((item[0], item[1], item[5]) for item in identity_signature)


def _track_signature(tracks: list[dict]) -> tuple:
    return tuple(
        (
            t.get("id"),
            t.get("state"),
            tuple(t.get("bbox_q0_0625px", [])),
            t.get("observed_this_frame"),
            t.get("frames_since_last_observation"),
            t.get("missed_frames"),
            tuple(t.get("occluded_by_track_ids", [])),
        )
        for t in tracks
    )


def read_bundle(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        manifest = json.loads(z.read("manifest.json"))
        session_id = manifest.get("session_id")
        job_id = manifest.get("pipeline_lifecycle_job_id")
        summary = json.loads(z.read(f"pipeline_summary_{job_id}.json")).get("summary", {})
        detections = {"cpu_probe": {}, "cpu_mt2_probe": {}, "cpu_mt4_probe": {}}
        shadow_raw: dict[int, tuple] = {}
        shadow_stabilized: dict[int, tuple] = {}
        shadow_cadence_full: dict[int, dict[int, tuple]] = {}
        shadow_cadence_identity: dict[int, dict[int, tuple]] = {}
        shadow_cpu_full: dict[int, tuple] = {}
        shadow_cpu_identity: dict[int, tuple] = {}
        shadow_hybrid_full: dict[str, dict[int, tuple]] = {}
        shadow_hybrid_identity: dict[str, dict[int, tuple]] = {}
        shadow_adaptive_full: dict[str, dict[int, tuple]] = {}
        shadow_adaptive_identity: dict[str, dict[int, tuple]] = {}
        shadow_adaptive_sources: dict[str, dict[int, str]] = {}
        shadow_adaptive_reasons: dict[str, dict[int, str]] = {}
        shadow_adaptive_metrics: dict[str, dict[int, dict]] = {}
        shadow_protected_track_ids: set[int] = set()
        shadow_inference_ordinal: dict[int, int] = {}
        shadow_should_infer: dict[int, bool] = {}
        shadow_disabled: list[dict] = []

        for name in z.namelist():
            if not (name.startswith("session_") and name.endswith(".jsonl")):
                continue
            for line in z.read(name).decode("utf-8", "replace").splitlines():
                try:
                    event = json.loads(line)
                except Exception:
                    continue
                if event.get("session_id") != session_id:
                    continue
                event_name = event.get("event")
                fields = event.get("fields", {})
                if event_name in DETECTION_EVENTS:
                    diagnostic_job = str(fields.get("job_id", ""))
                    backend = None
                    for candidate in detections:
                        if diagnostic_job == f"{job_id}_{candidate}":
                            backend = candidate
                            break
                    if backend is not None:
                        detections[backend][int(fields["pts_us"])] = _detection_signature(
                            fields.get("detections", [])
                        )
                elif event_name == "YOLO_CPU_MT4_TRACK_SHADOW":
                    if fields.get("job_id") != job_id:
                        continue
                    pts = int(fields["pts_us"])
                    shadow_inference_ordinal[pts] = int(fields.get("cpu_inference_ordinal", 0))
                    shadow_should_infer[pts] = bool(fields.get("should_infer", False))
                    cpu_full_tracks = fields.get("cpu_full_tracks")
                    protected_track_ids = fields.get("identity_protected_track_ids")
                    adaptive_tracks = fields.get("adaptive_tracks")
                    adaptive_sources = fields.get("adaptive_sources")
                    adaptive_reasons = fields.get("adaptive_reasons")
                    adaptive_metrics = fields.get("adaptive_metrics")
                    hybrid_tracks = fields.get("hybrid_tracks")
                    tracks_by_cadence = fields.get("tracks_by_cadence")
                    if isinstance(protected_track_ids, list):
                        for track_id in protected_track_ids:
                            if isinstance(track_id, int):
                                shadow_protected_track_ids.add(track_id)
                    if isinstance(cpu_full_tracks, list) and isinstance(adaptive_tracks, dict):
                        shadow_cpu_full[pts] = _track_signature(cpu_full_tracks)
                        shadow_cpu_identity[pts] = _track_identity_signature(cpu_full_tracks)
                        for adaptive_key, tracks in adaptive_tracks.items():
                            if not isinstance(adaptive_key, str) or not isinstance(tracks, list):
                                continue
                            shadow_adaptive_full.setdefault(adaptive_key, {})[pts] = _track_signature(tracks)
                            shadow_adaptive_identity.setdefault(adaptive_key, {})[pts] = _track_identity_signature(tracks)
                            if isinstance(adaptive_sources, dict):
                                source = adaptive_sources.get(adaptive_key)
                                if isinstance(source, str):
                                    shadow_adaptive_sources.setdefault(adaptive_key, {})[pts] = source
                            if isinstance(adaptive_reasons, dict):
                                reason = adaptive_reasons.get(adaptive_key)
                                if isinstance(reason, str):
                                    shadow_adaptive_reasons.setdefault(adaptive_key, {})[pts] = reason
                            if isinstance(adaptive_metrics, dict):
                                metrics = adaptive_metrics.get(adaptive_key)
                                if isinstance(metrics, dict):
                                    shadow_adaptive_metrics.setdefault(adaptive_key, {})[pts] = metrics
                    elif isinstance(cpu_full_tracks, list) and isinstance(hybrid_tracks, dict):
                        shadow_cpu_full[pts] = _track_signature(cpu_full_tracks)
                        shadow_cpu_identity[pts] = _track_identity_signature(cpu_full_tracks)
                        for hybrid_key, tracks in hybrid_tracks.items():
                            if not isinstance(hybrid_key, str) or not isinstance(tracks, list):
                                continue
                            shadow_hybrid_full.setdefault(hybrid_key, {})[pts] = _track_signature(tracks)
                            shadow_hybrid_identity.setdefault(hybrid_key, {})[pts] = _track_identity_signature(tracks)
                    elif isinstance(tracks_by_cadence, dict):
                        for cadence_text, tracks in tracks_by_cadence.items():
                            try:
                                cadence = int(cadence_text)
                            except (TypeError, ValueError):
                                continue
                            if not isinstance(tracks, list):
                                continue
                            shadow_cadence_full.setdefault(cadence, {})[pts] = _track_signature(tracks)
                            shadow_cadence_identity.setdefault(cadence, {})[pts] = _track_identity_signature(tracks)
                    else:
                        # Backward-compatible parsing for the earlier raw/stabilized shadow format.
                        shadow_raw[pts] = _track_signature(fields.get("raw_tracks", []))
                        shadow_stabilized[pts] = _track_signature(fields.get("stabilized_tracks", []))
                elif event_name == "YOLO_CPU_MT4_TRACK_SHADOW_DISABLED":
                    if fields.get("job_id") == job_id:
                        shadow_disabled.append(fields)

        historical = []
        for name in z.namelist():
            if name.startswith("session_") and name.endswith(".jsonl") and session_id not in name:
                historical.append(name)
            elif name.startswith("pipeline_summary_") and name != f"pipeline_summary_{job_id}.json":
                historical.append(name)
            elif name.startswith(("yolo_tensor_", "inference_rgba_", "decoder_yuv_")):
                prefix = name.split("_", 2)[:2]
                del prefix  # only keep the explicit job-id checks below readable
                if job_id not in name:
                    historical.append(name)

        return {
            "path": str(path),
            "device": _device_name(z),
            "commit": manifest.get("git_commit_sha"),
            "job_id": job_id,
            "zip_size_bytes": path.stat().st_size,
            "historical_entries": historical,
            "manifest_excluded_historical": manifest.get("snapshot_files_excluded_as_historical"),
            "timings": {
                "cpu_1t_total": _stage(summary, "yoloCpuDeterminismProbe"),
                "cpu_1t_run": _stage(summary, "yoloCpuProbe_yoloLiteRtRun"),
                "cpu_2t_total": _stage(summary, "yoloCpuMt2Probe"),
                "cpu_2t_run": _stage(summary, "yoloCpuMt2Probe_yoloLiteRtRun"),
                "cpu_4t_total": _stage(summary, "yoloCpuMt4Probe"),
                "cpu_4t_run": _stage(summary, "yoloCpuMt4Probe_yoloLiteRtRun"),
                "canonical": _stage(summary, "canonicalYuvToRgba"),
            },
            "detections": detections,
            "shadow_raw": shadow_raw,
            "shadow_stabilized": shadow_stabilized,
            "shadow_cadence_full": shadow_cadence_full,
            "shadow_cadence_identity": shadow_cadence_identity,
            "shadow_cpu_full": shadow_cpu_full,
            "shadow_cpu_identity": shadow_cpu_identity,
            "shadow_hybrid_full": shadow_hybrid_full,
            "shadow_hybrid_identity": shadow_hybrid_identity,
            "shadow_adaptive_full": shadow_adaptive_full,
            "shadow_adaptive_identity": shadow_adaptive_identity,
            "shadow_adaptive_sources": shadow_adaptive_sources,
            "shadow_adaptive_reasons": shadow_adaptive_reasons,
            "shadow_adaptive_metrics": shadow_adaptive_metrics,
            "shadow_protected_track_ids": shadow_protected_track_ids,
            "shadow_inference_ordinal": shadow_inference_ordinal,
            "shadow_should_infer": shadow_should_infer,
            "shadow_disabled": shadow_disabled,
        }


def compare_map(a: dict[int, tuple], b: dict[int, tuple]) -> dict:
    common = sorted(set(a) & set(b))
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    diffs = [pts for pts in common if a[pts] != b[pts]]
    return {
        "common_frames": len(common),
        "only_a_frames": len(only_a),
        "only_b_frames": len(only_b),
        "different_frames": len(diffs),
        "first_different_pts_us": diffs[0] if diffs else None,
    }


def protected_bbox_delta_summary(
    reference: dict[int, tuple],
    candidate: dict[int, tuple],
    protected_ids: set[int],
) -> dict:
    deltas_px: list[float] = []
    frames_with_missing_protected = 0
    common = sorted(set(reference) & set(candidate))
    for pts in common:
        reference_by_id = {item[0]: item for item in reference[pts] if item and item[0] in protected_ids}
        candidate_by_id = {item[0]: item for item in candidate[pts] if item and item[0] in protected_ids}
        if set(reference_by_id) != set(candidate_by_id):
            frames_with_missing_protected += 1
        for track_id in set(reference_by_id) & set(candidate_by_id):
            reference_bbox = reference_by_id[track_id][2]
            candidate_bbox = candidate_by_id[track_id][2]
            if len(reference_bbox) != 4 or len(candidate_bbox) != 4:
                continue
            deltas_px.append(
                max(abs(a - b) for a, b in zip(reference_bbox, candidate_bbox)) / 16.0
            )

    ordered = sorted(deltas_px)
    if ordered:
        p95_index = min(len(ordered) - 1, max(0, int((len(ordered) * 0.95) + 0.999999) - 1))
        p95 = ordered[p95_index]
        maximum = ordered[-1]
    else:
        p95 = None
        maximum = None
    return {
        "common_frames": len(common),
        "protected_track_samples": len(deltas_px),
        "frames_with_missing_protected": frames_with_missing_protected,
        "p95_px": p95,
        "max_px": maximum,
        "samples_gt_1px": sum(1 for value in deltas_px if value > 1.0),
        "samples_gt_5px": sum(1 for value in deltas_px if value > 5.0),
        "samples_gt_20px": sum(1 for value in deltas_px if value > 20.0),
    }


def transform_map(values: dict[int, tuple], transform) -> dict[int, tuple]:
    return {pts: transform(signature) for pts, signature in values.items()}


def filter_identity_map(values: dict[int, tuple], protected_ids: set[int]) -> dict[int, tuple]:
    return {
        pts: tuple(item for item in signature if item and item[0] in protected_ids)
        for pts, signature in values.items()
    }


def parse_hybrid_cadence(key: str) -> int | None:
    marker = "_c"
    if marker not in key:
        return None
    try:
        return int(key.rsplit(marker, 1)[1])
    except ValueError:
        return None


def filter_anchor_frames(values: dict[int, tuple], ordinals: dict[int, int], cadence: int) -> dict[int, tuple]:
    return {
        pts: signature
        for pts, signature in values.items()
        if ordinals.get(pts, 0) == 0 or ordinals.get(pts, 0) % cadence == 0
    }


def compare_hybrid_to_cpu_full(bundle: dict, key: str) -> dict:
    cadence = parse_hybrid_cadence(key)
    cpu_identity = bundle["shadow_cpu_identity"]
    hybrid_identity = bundle["shadow_hybrid_identity"].get(key, {})
    cpu_ids = transform_map(cpu_identity, _track_id_set_signature_from_identity)
    hybrid_ids = transform_map(hybrid_identity, _track_id_set_signature_from_identity)
    cpu_state = transform_map(cpu_identity, _track_state_signature_from_identity)
    hybrid_state = transform_map(hybrid_identity, _track_state_signature_from_identity)
    result = {
        "identity_all": compare_map(cpu_identity, hybrid_identity),
        "id_set_all": compare_map(cpu_ids, hybrid_ids),
        "state_topology_all": compare_map(cpu_state, hybrid_state),
    }
    if cadence is not None:
        result["id_set_anchor"] = compare_map(
            filter_anchor_frames(cpu_ids, bundle["shadow_inference_ordinal"], cadence),
            filter_anchor_frames(hybrid_ids, bundle["shadow_inference_ordinal"], cadence),
        )
        result["state_topology_anchor"] = compare_map(
            filter_anchor_frames(cpu_state, bundle["shadow_inference_ordinal"], cadence),
            filter_anchor_frames(hybrid_state, bundle["shadow_inference_ordinal"], cadence),
        )
    return result


def compare_adaptive_to_cpu_full(bundle: dict, key: str) -> dict:
    cpu_identity = bundle["shadow_cpu_identity"]
    adaptive_identity = bundle["shadow_adaptive_identity"].get(key, {})
    cpu_full = bundle["shadow_cpu_full"]
    adaptive_full = bundle["shadow_adaptive_full"].get(key, {})
    protected_ids = bundle["shadow_protected_track_ids"]
    cpu_protected_identity = filter_identity_map(cpu_identity, protected_ids)
    adaptive_protected_identity = filter_identity_map(adaptive_identity, protected_ids)
    cpu_ids = transform_map(cpu_identity, _track_id_set_signature_from_identity)
    adaptive_ids = transform_map(adaptive_identity, _track_id_set_signature_from_identity)
    cpu_state = transform_map(cpu_identity, _track_state_signature_from_identity)
    adaptive_state = transform_map(adaptive_identity, _track_state_signature_from_identity)
    cpu_protected_ids = transform_map(cpu_protected_identity, _track_id_set_signature_from_identity)
    adaptive_protected_ids = transform_map(
        adaptive_protected_identity, _track_id_set_signature_from_identity
    )
    cpu_protected_state = transform_map(cpu_protected_identity, _track_state_signature_from_identity)
    adaptive_protected_state = transform_map(
        adaptive_protected_identity, _track_state_signature_from_identity
    )
    inference_pts = {
        pts for pts, should_infer in bundle["shadow_should_infer"].items() if should_infer
    }
    sources = bundle["shadow_adaptive_sources"].get(key, {})
    cpu_anchor_count = sum(1 for pts in inference_pts if sources.get(pts) == "CPU")
    predict_count = sum(1 for pts in inference_pts if sources.get(pts) == "PREDICT")
    reason_counts: dict[str, int] = {}
    for pts in inference_pts:
        reason = bundle["shadow_adaptive_reasons"].get(key, {}).get(pts)
        if reason is not None:
            reason_counts[reason] = reason_counts.get(reason, 0) + 1
    metric_summary: dict[str, dict] = {}
    metrics_by_pts = bundle["shadow_adaptive_metrics"].get(key, {})
    metric_names = sorted({name for metrics in metrics_by_pts.values() for name in metrics})
    for metric_name in metric_names:
        values = [
            metrics.get(metric_name)
            for pts, metrics in metrics_by_pts.items()
            if pts in inference_pts and metrics.get(metric_name) is not None
        ]
        if not values:
            continue
        if all(isinstance(value, bool) for value in values):
            metric_summary[metric_name] = {
                "count": len(values),
                "true_count": sum(1 for value in values if value),
            }
        elif all(isinstance(value, (int, float)) and not isinstance(value, bool) for value in values):
            metric_summary[metric_name] = {
                "count": len(values),
                "min": min(values),
                "max": max(values),
            }
    return {
        "identity_all": compare_map(cpu_identity, adaptive_identity),
        "id_set_all": compare_map(cpu_ids, adaptive_ids),
        "state_topology_all": compare_map(cpu_state, adaptive_state),
        "protected_identity_all": compare_map(cpu_protected_identity, adaptive_protected_identity),
        "protected_id_set_all": compare_map(cpu_protected_ids, adaptive_protected_ids),
        "protected_state_topology_all": compare_map(
            cpu_protected_state, adaptive_protected_state
        ),
        "protected_bbox_delta_vs_cpu_full": protected_bbox_delta_summary(
            cpu_full, adaptive_full, protected_ids
        ),
        "inference_frames": len(inference_pts),
        "cpu_anchor_frames": cpu_anchor_count,
        "predict_frames": predict_count,
        "cpu_anchor_ratio": (cpu_anchor_count / len(inference_pts)) if inference_pts else None,
        "reason_counts": dict(sorted(reason_counts.items())),
        "metric_summary": metric_summary,
    }


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: analyze_cross_device_batch.py <bundle1.zip> <bundle2.zip> [bundle3.zip ...]", file=sys.stderr)
        return 2

    bundles = [read_bundle(Path(p)) for p in sys.argv[1:]]
    result = {
        "devices": [
            {
                "device": b["device"],
                "commit": b["commit"],
                "job_id": b["job_id"],
                "zip_size_bytes": b["zip_size_bytes"],
                "historical_entry_count": len(b["historical_entries"]),
                "manifest_excluded_historical": b["manifest_excluded_historical"],
                "timings": b["timings"],
                "frame_counts": {k: len(v) for k, v in b["detections"].items()},
                "shadow_frame_count": max(
                    len(b["shadow_raw"]),
                    len(b["shadow_cadence_identity"].get(1, {})),
                    len(b["shadow_cpu_identity"]),
                ),
                "shadow_cadence_frame_counts": {
                    str(cadence): len(frames)
                    for cadence, frames in sorted(b["shadow_cadence_identity"].items())
                },
                "shadow_disabled": b["shadow_disabled"],
                "raw_vs_stabilized_shadow": compare_map(b["shadow_raw"], b["shadow_stabilized"]),
                "cadence_vs_1_identity": {
                    str(cadence): compare_map(
                        b["shadow_cadence_identity"].get(1, {}),
                        frames,
                    )
                    for cadence, frames in sorted(b["shadow_cadence_identity"].items())
                    if cadence != 1
                },
                "hybrid_frame_counts": {
                    key: len(frames)
                    for key, frames in sorted(b["shadow_hybrid_identity"].items())
                },
                "hybrid_vs_cpu_full": {
                    key: compare_hybrid_to_cpu_full(b, key)
                    for key in sorted(b["shadow_hybrid_identity"])
                },
                "adaptive_frame_counts": {
                    key: len(frames)
                    for key, frames in sorted(b["shadow_adaptive_identity"].items())
                },
                "adaptive_vs_cpu_full": {
                    key: compare_adaptive_to_cpu_full(b, key)
                    for key in sorted(b["shadow_adaptive_identity"])
                },
                "identity_protected_track_ids": sorted(b["shadow_protected_track_ids"]),
            }
            for b in bundles
        ],
        "pairs": [],
    }

    for i in range(len(bundles)):
        for j in range(i + 1, len(bundles)):
            a, b = bundles[i], bundles[j]
            common_cadences = sorted(
                set(a["shadow_cadence_identity"]) & set(b["shadow_cadence_identity"])
            )
            common_hybrids = sorted(
                set(a["shadow_hybrid_identity"]) & set(b["shadow_hybrid_identity"])
            )
            common_adaptive = sorted(
                set(a["shadow_adaptive_identity"]) & set(b["shadow_adaptive_identity"])
            )
            result["pairs"].append(
                {
                    "a": a["device"],
                    "b": b["device"],
                    "cpu_1t_detection": compare_map(a["detections"]["cpu_probe"], b["detections"]["cpu_probe"]),
                    "cpu_2t_detection": compare_map(a["detections"]["cpu_mt2_probe"], b["detections"]["cpu_mt2_probe"]),
                    "cpu_4t_detection": compare_map(a["detections"]["cpu_mt4_probe"], b["detections"]["cpu_mt4_probe"]),
                    "cpu_4t_shadow_raw": compare_map(a["shadow_raw"], b["shadow_raw"]),
                    "cpu_4t_shadow_stabilized": compare_map(
                        a["shadow_stabilized"], b["shadow_stabilized"]
                    ),
                    "cpu_4t_shadow_cadences": {
                        str(cadence): {
                            "identity": compare_map(
                                a["shadow_cadence_identity"][cadence],
                                b["shadow_cadence_identity"][cadence],
                            ),
                            "full": compare_map(
                                a["shadow_cadence_full"][cadence],
                                b["shadow_cadence_full"][cadence],
                            ),
                        }
                        for cadence in common_cadences
                    },
                    "cpu_4t_shadow_hybrids": {
                        key: {
                            "identity": compare_map(
                                a["shadow_hybrid_identity"][key],
                                b["shadow_hybrid_identity"][key],
                            ),
                            "full": compare_map(
                                a["shadow_hybrid_full"][key],
                                b["shadow_hybrid_full"][key],
                            ),
                        }
                        for key in common_hybrids
                    },
                    "cpu_4t_shadow_adaptive": {
                        key: {
                            "identity": compare_map(
                                a["shadow_adaptive_identity"][key],
                                b["shadow_adaptive_identity"][key],
                            ),
                            "full": compare_map(
                                a["shadow_adaptive_full"][key],
                                b["shadow_adaptive_full"][key],
                            ),
                            "schedule": compare_map(
                                a["shadow_adaptive_sources"].get(key, {}),
                                b["shadow_adaptive_sources"].get(key, {}),
                            ),
                            "reason": compare_map(
                                a["shadow_adaptive_reasons"].get(key, {}),
                                b["shadow_adaptive_reasons"].get(key, {}),
                            ),
                            "protected_identity": compare_map(
                                filter_identity_map(
                                    a["shadow_adaptive_identity"][key],
                                    a["shadow_protected_track_ids"],
                                ),
                                filter_identity_map(
                                    b["shadow_adaptive_identity"][key],
                                    b["shadow_protected_track_ids"],
                                ),
                            ),
                            "protected_bbox_delta": protected_bbox_delta_summary(
                                a["shadow_adaptive_full"][key],
                                b["shadow_adaptive_full"][key],
                                a["shadow_protected_track_ids"] | b["shadow_protected_track_ids"],
                            ),
                        }
                        for key in common_adaptive
                    },
                }
            )

    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
