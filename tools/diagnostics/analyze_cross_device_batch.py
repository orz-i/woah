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


def _timing_values(values: list[float]):
    if not values:
        return None
    ordered = sorted(values)
    def percentile(q: float) -> float:
        return ordered[min(len(ordered) - 1, round((len(ordered) - 1) * q))]
    return {
        "count": len(ordered),
        "avg_ms": sum(ordered) / len(ordered),
        "p50_ms": percentile(0.50),
        "p95_ms": percentile(0.95),
        "max_ms": ordered[-1],
    }


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
        face_production_tracks: dict[int, tuple] = {}
        face_production_identity: dict[int, tuple] = {}
        face_identity_roots: dict | None = None
        face_roi_detector: dict[tuple[int, int, str], tuple] = {}
        face_sticker_placements: dict[int, tuple] = {}
        face_class_fallbacks: dict[int, tuple] = {}
        face_temporal_class_evidence: dict[int, tuple] = {}
        face_temporal_class_timings_ms: list[float] = []
        new_track_events: list[dict] = []
        protected_lost_reservation_events: list[dict] = []
        shadow_inference_ordinal: dict[int, int] = {}
        shadow_should_infer: dict[int, bool] = {}
        shadow_disabled: list[dict] = []
        analysis_selection: dict | None = None

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
                if event_name == "ANALYZE_SELECTION_SIGNATURE":
                    analysis_selection = {
                        "analysis_cache_id": fields.get("analysis_cache_id"),
                        "requested_trim_start_us": fields.get("requested_trim_start_us"),
                        "analysis_pts_us": fields.get("analysis_pts_us"),
                        "input_path": fields.get("input_path"),
                        "canonical_rgba_sha256": fields.get("canonical_rgba_sha256"),
                        "bitmap_grid_sha256": fields.get("bitmap_grid_sha256"),
                        "canonical_fallback_reason": fields.get("canonical_fallback_reason"),
                        "canonical_codec_name": fields.get("canonical_codec_name"),
                        "canonical_color_standard": fields.get("canonical_color_standard"),
                        "canonical_color_range": fields.get("canonical_color_range"),
                        "yolo_requested_accelerator": fields.get("yolo_requested_accelerator"),
                        "yolo_effective_accelerator": fields.get("yolo_effective_accelerator"),
                        "cpu_num_threads": fields.get("cpu_num_threads"),
                        "detection_count": fields.get("detection_count"),
                        "candidate_ids_ge_0_60": tuple(fields.get("diagnostic_candidate_ids_ge_0_60", [])),
                        "detection_signature": _detection_signature(fields.get("detections", [])),
                    }
                elif event_name in DETECTION_EVENTS:
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
                elif event_name == "FACE_ONLY_IDENTITY_ROOTS_RESOLVED":
                    if fields.get("job_id") == job_id:
                        face_identity_roots = fields
                elif event_name == "FACE_ONLY_PRODUCTION_TRACK_SIGNATURE":
                    if fields.get("job_id") == job_id:
                        pts = int(fields["pts_us"])
                        face_tracks = fields.get("tracks", [])
                        face_production_tracks[pts] = _track_signature(face_tracks)
                        face_production_identity[pts] = _track_identity_signature(face_tracks)
                elif event_name == "FACE_ROI_DETECTOR_DIAGNOSTIC":
                    if fields.get("job_id") == job_id:
                        key = (
                            int(fields["pts_us"]),
                            int(fields["track_id"]),
                            str(fields.get("phase", "")),
                        )
                        face_roi_detector[key] = (
                            fields.get("render_mode"),
                            fields.get("rgba_grid_sha256"),
                            fields.get("observation_count"),
                            fields.get("selected_face"),
                            fields.get("detector_rejected"),
                            fields.get("pixel_reject_reason"),
                            tuple(fields.get("source_rect_q0_0625px", [])),
                            tuple(fields.get("person_bbox_q0_0625px", [])),
                            fields.get("roi_pixel_source"),
                        )
                elif event_name == "FACE_ONLY_STICKER_PLACEMENT_SIGNATURE":
                    if fields.get("job_id") == job_id:
                        pts = int(fields["pts_us"])
                        face_sticker_placements[pts] = (
                            fields.get("geometry_source"),
                            fields.get("mask_source"),
                            tuple(
                                (
                                    placement.get("track_id"),
                                    placement.get("source"),
                                    tuple(placement.get("source_rect_q0_0625px", [])),
                                )
                                for placement in fields.get("placements", [])
                            ),
                        )
                elif event_name == "FACE_ONLY_CLASS_FALLBACK":
                    if fields.get("job_id") == job_id:
                        pts = int(fields["pts_us"])
                        face_class_fallbacks[pts] = (
                            tuple(sorted(int(x) for x in fields.get("detection_indices", []))),
                            tuple(sorted(int(x) for x in fields.get("body_mask_guided_detection_indices", []))),
                            tuple(sorted(int(x) for x in fields.get("residual_track_ids", []))),
                            tuple(sorted(int(x) for x in fields.get("synthetic_track_ids", []))),
                        )
                elif event_name == "FACE_PRIVACY_TEMPORAL_CLASS_EVIDENCE":
                    if fields.get("job_id") == job_id:
                        pts = int(fields["pts_us"])
                        face_temporal_class_evidence[pts] = (
                            tuple(sorted(int(x) for x in fields.get("selected_detection_indices", []))),
                            tuple(sorted(int(x) for x in fields.get("unknown_detection_indices", []))),
                            tuple(sorted(int(x) for x in fields.get("mapped_detection_indices", []))),
                            tuple(sorted(int(x) for x in fields.get("mapped_unknown_detection_indices", []))),
                            tuple(sorted(int(x) for x in fields.get("mapped_residual_track_ids", []))),
                        )
                        if fields.get("elapsed_ms") is not None:
                            face_temporal_class_timings_ms.append(float(fields["elapsed_ms"]))
                elif event_name == "NEW_TRACK_CREATED":
                    new_track_events.append(
                        {
                            "pts_us": fields.get("pts_us"),
                            "track_id": fields.get("track_id"),
                            "det_index": fields.get("det_index"),
                            "bbox": fields.get("bbox"),
                        }
                    )
                elif event_name == "AMBIGUOUS_PROTECTED_LOST_DETECTION_RESERVED":
                    protected_lost_reservation_events.append(
                        {
                            "pts_us": fields.get("pts_us"),
                            "det_index": fields.get("det_index"),
                            "protected_lost_motion_owner_ids": fields.get(
                                "protected_lost_motion_owner_ids", []
                            ),
                            "strict_protected_lost_owner_ids": fields.get(
                                "strict_protected_lost_owner_ids", []
                            ),
                        }
                    )

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
                "production_yolo": _stage(summary, "yoloCpuInference"),
                "production_tracking": _stage(summary, "tracking"),
                "cpu_1t_total": _stage(summary, "yoloCpuDeterminismProbe"),
                "cpu_1t_run": _stage(summary, "yoloCpuProbe_yoloLiteRtRun"),
                "cpu_2t_total": _stage(summary, "yoloCpuMt2Probe"),
                "cpu_2t_run": _stage(summary, "yoloCpuMt2Probe_yoloLiteRtRun"),
                "cpu_4t_total": _stage(summary, "yoloCpuMt4Probe"),
                "cpu_4t_run": _stage(summary, "yoloCpuMt4Probe_yoloLiteRtRun"),
                "cpu_4t_decode": _stage(summary, "yoloCpuMt4Probe_yoloDecode"),
                "cpu_4t_mask_decode": _stage(summary, "yoloCpuMt4Probe_yoloMaskDecode"),
                "cpu_4t_diagnostics": _stage(summary, "yoloCpuMt4Probe_yoloDiagnostics"),
                "canonical": _stage(summary, "canonicalYuvToRgba"),
                "face_roi": _stage(summary, "faceRoiReadback"),
                "face_detector": _stage(summary, "faceDetectorCpu"),
                "face_pixel_motion": _stage(summary, "facePixelMotionCpu"),
                "face_privacy": _stage(summary, "faceOnlyPrivacy"),
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
            "face_production_tracks": face_production_tracks,
            "face_production_identity": face_production_identity,
            "face_identity_roots": face_identity_roots,
            "face_roi_detector": face_roi_detector,
            "face_sticker_placements": face_sticker_placements,
            "face_class_fallbacks": face_class_fallbacks,
            "face_temporal_class_evidence": face_temporal_class_evidence,
            "face_temporal_class_timings_ms": face_temporal_class_timings_ms,
            "new_track_events": new_track_events,
            "protected_lost_reservation_events": protected_lost_reservation_events,
            "pipeline_summary": summary,
            "shadow_inference_ordinal": shadow_inference_ordinal,
            "shadow_should_infer": shadow_should_infer,
            "shadow_disabled": shadow_disabled,
            "analysis_selection": analysis_selection,
        }


def compare_analysis_selection(a: dict | None, b: dict | None) -> dict:
    if a is None or b is None:
        return {
            "available_a": a is not None,
            "available_b": b is not None,
            "same_requested_trim_start": None,
            "same_analysis_pts": None,
            "same_input_path": None,
            "same_canonical_rgba_sha256": None,
            "same_candidate_ids_ge_0_60": None,
            "same_detection_signature": None,
        }
    return {
        "available_a": True,
        "available_b": True,
        "same_requested_trim_start": a["requested_trim_start_us"] == b["requested_trim_start_us"],
        "same_analysis_pts": a["analysis_pts_us"] == b["analysis_pts_us"],
        "same_input_path": a["input_path"] == b["input_path"],
        "same_canonical_rgba_sha256": a["canonical_rgba_sha256"] == b["canonical_rgba_sha256"],
        "same_candidate_ids_ge_0_60": a["candidate_ids_ge_0_60"] == b["candidate_ids_ge_0_60"],
        "same_detection_signature": a["detection_signature"] == b["detection_signature"],
    }


def compare_face_roi(a: dict[tuple[int, int, str], tuple], b: dict[tuple[int, int, str], tuple]) -> dict:
    common = sorted(set(a) & set(b))
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    source_rect_diffs = []
    person_bbox_diffs = []
    rgba_diffs_same_source = []
    detector_result_diffs_same_rgba = []
    pixel_source_diffs = []
    full_diffs = []
    for key in common:
        av = a[key]
        bv = b[key]
        if av != bv:
            full_diffs.append(key)
        if av[6] != bv[6]:
            source_rect_diffs.append(key)
        if av[7] != bv[7]:
            person_bbox_diffs.append(key)
        if av[6] == bv[6] and av[1] != bv[1]:
            rgba_diffs_same_source.append(key)
        if av[1] == bv[1] and (av[2], av[3], av[4]) != (bv[2], bv[3], bv[4]):
            detector_result_diffs_same_rgba.append(key)
        if len(av) > 8 and len(bv) > 8 and av[8] != bv[8]:
            pixel_source_diffs.append(key)
    return {
        "common_events": len(common),
        "only_a_events": len(only_a),
        "only_b_events": len(only_b),
        "different_events": len(full_diffs),
        "source_rect_different_events": len(source_rect_diffs),
        "person_bbox_different_events": len(person_bbox_diffs),
        "rgba_hash_different_with_same_source_rect": len(rgba_diffs_same_source),
        "detector_result_different_with_same_rgba": len(detector_result_diffs_same_rgba),
        "roi_pixel_source_different_events": len(pixel_source_diffs),
        "first_different_key": list(full_diffs[0]) if full_diffs else None,
        "first_rgba_difference_key": list(rgba_diffs_same_source[0]) if rgba_diffs_same_source else None,
        "first_detector_result_difference_key": (
            list(detector_result_diffs_same_rgba[0]) if detector_result_diffs_same_rgba else None
        ),
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


def summarize_cpu_reference_track_births(frames: dict[int, tuple]) -> dict:
    if not frames:
        return {"initial_track_ids": [], "new_track_events": []}
    ordered_pts = sorted(frames)
    first_pts = ordered_pts[0]
    initial_track_ids = sorted(int(track[0]) for track in frames[first_pts])
    seen = set(initial_track_ids)
    births: list[dict] = []
    for pts in ordered_pts[1:]:
        for track in frames[pts]:
            track_id = int(track[0])
            if track_id in seen:
                continue
            seen.add(track_id)
            births.append(
                {
                    "pts_us": pts,
                    "track_id": track_id,
                    "state": track[1],
                    "bbox_q0_0625px": list(track[2]),
                    "observed_this_frame": track[3],
                }
            )
    return {
        "initial_track_ids": initial_track_ids,
        "new_track_events": births,
    }


def summarize_face_selected_gaps(bundle: dict) -> dict:
    roots = bundle.get("face_identity_roots") or {}
    selected_ids = [int(x) for x in roots.get("face_only_person_ids", [])]
    placements = bundle.get("face_sticker_placements", {})
    fallbacks = bundle.get("face_class_fallbacks", {})
    ordered_pts = sorted(placements)
    if not ordered_pts:
        return {"selected_track_ids": selected_ids, "by_track_id": {}}

    def present_ids_at(pts: int) -> set[int]:
        value = placements[pts]
        return {
            int(item[0])
            for item in value[2]
            if isinstance(item[0], int) and int(item[0]) >= 0
        }

    def fallback_owner_ids_at(pts: int) -> set[int]:
        value = fallbacks.get(pts)
        if value is None:
            return set()
        # Current tuples are (detections, mask-guided detections, owners,
        # synthetic ids). Older bundles used (detections, owners, synthetic ids).
        owner_index = 2 if len(value) >= 4 else 1
        return {int(x) for x in value[owner_index]}

    def is_mask_guided_fallback_at(pts: int) -> bool:
        value = fallbacks.get(pts)
        return value is not None and len(value) >= 4 and bool(value[1])

    by_track: dict[str, dict] = {}
    for track_id in selected_ids:
        missing_indices = [
            index
            for index, pts in enumerate(ordered_pts)
            if track_id not in present_ids_at(pts)
        ]
        runs: list[tuple[int, int]] = []
        if missing_indices:
            run_start = missing_indices[0]
            run_end = missing_indices[0]
            for index in missing_indices[1:]:
                if index == run_end + 1:
                    run_end = index
                else:
                    runs.append((run_start, run_end))
                    run_start = run_end = index
            runs.append((run_start, run_end))
        longest = max(runs, key=lambda item: item[1] - item[0] + 1) if runs else None
        fallback_covered = [
            pts
            for pts in ordered_pts
            if track_id not in present_ids_at(pts) and track_id in fallback_owner_ids_at(pts)
        ]
        uncovered = [
            pts
            for pts in ordered_pts
            if track_id not in present_ids_at(pts) and track_id not in fallback_owner_ids_at(pts)
        ]
        by_track[str(track_id)] = {
            "missing_sticker_frames": len(missing_indices),
            "class_fallback_covered_missing_frames": len(fallback_covered),
            "missing_without_class_fallback_frames": len(uncovered),
            "longest_missing_run": None
            if longest is None
            else {
                "frames": longest[1] - longest[0] + 1,
                "start_pts_us": ordered_pts[longest[0]],
                "end_pts_us": ordered_pts[longest[1]],
                "span_us": ordered_pts[longest[1]] - ordered_pts[longest[0]],
            },
        }
    return {
        "selected_track_ids": selected_ids,
        "class_fallback_frames": len(fallbacks),
        "body_mask_guided_class_fallback_frames": sum(
            1 for pts in fallbacks if is_mask_guided_fallback_at(pts)
        ),
        "by_track_id": by_track,
    }


def selected_face_quality_summary(summary: dict) -> dict:
    exact_keys = {
        "face_dormant_suppressed_track_frames",
        "face_detector_rejected_call_count",
    }
    return {
        key: value
        for key, value in sorted(summary.items())
        if key in exact_keys or (key.startswith("face_") and key.endswith("_by_track_id"))
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
                "analysis_selection": b["analysis_selection"],
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
                "face_identity_roots": b["face_identity_roots"],
                "face_production_track_frames": len(b["face_production_tracks"]),
                "face_production_vs_cpu_full": {
                    "identity": compare_map(
                        b["face_production_identity"],
                        filter_identity_map(
                            b["shadow_cpu_identity"], b["shadow_protected_track_ids"]
                        ),
                    ),
                    "id_set": compare_map(
                        transform_map(
                            b["face_production_identity"], _track_id_set_signature_from_identity
                        ),
                        transform_map(
                            filter_identity_map(
                                b["shadow_cpu_identity"], b["shadow_protected_track_ids"]
                            ),
                            _track_id_set_signature_from_identity,
                        ),
                    ),
                    "state_topology": compare_map(
                        transform_map(
                            b["face_production_identity"], _track_state_signature_from_identity
                        ),
                        transform_map(
                            filter_identity_map(
                                b["shadow_cpu_identity"], b["shadow_protected_track_ids"]
                            ),
                            _track_state_signature_from_identity,
                        ),
                    ),
                    "bbox_delta": protected_bbox_delta_summary(
                        b["face_production_tracks"],
                        b["shadow_cpu_full"],
                        b["shadow_protected_track_ids"],
                    ),
                },
                "face_roi_detector_events": len(b["face_roi_detector"]),
                "face_sticker_placement_frames": len(b["face_sticker_placements"]),
                "face_selected_quality": summarize_face_selected_gaps(b),
                "face_pipeline_quality": selected_face_quality_summary(b["pipeline_summary"]),
                "face_temporal_class_evidence_frames": len(b["face_temporal_class_evidence"]),
                "face_temporal_class_mapped_frames": sum(
                    1 for value in b["face_temporal_class_evidence"].values() if value[2]
                ),
                "face_temporal_class_unknown_mapped_frames": sum(
                    1 for value in b["face_temporal_class_evidence"].values() if value[3]
                ),
                "face_temporal_class_timing": _timing_values(
                    b["face_temporal_class_timings_ms"]
                ),
                "face_deterministic_cpu_primary": {
                    "preferred": bool(
                        (b["pipeline_summary"] or {}).get(
                            "face_deterministic_cpu_primary_preferred", False
                        )
                    ),
                    "inference_frames": int(
                        (b["pipeline_summary"] or {}).get(
                            "face_deterministic_cpu_primary_inference_frames", 0
                        )
                    ),
                    "fallback_frames": int(
                        (b["pipeline_summary"] or {}).get(
                            "face_deterministic_cpu_primary_fallback_frames", 0
                        )
                    ),
                },
                # NEW_TRACK_CREATED comes from the production TrackManager. Keep
                # it explicitly labelled so it cannot be mistaken for the
                # deterministic CPU reference topology used by Face rendering.
                "production_new_track_ids": sorted(
                    {
                        int(event["track_id"])
                        for event in b["new_track_events"]
                        if isinstance(event.get("track_id"), int)
                    }
                ),
                "production_new_track_events": b["new_track_events"],
                "cpu_reference_track_births": summarize_cpu_reference_track_births(
                    b["shadow_cpu_full"]
                ),
                "protected_lost_reservation_event_count": len(
                    b["protected_lost_reservation_events"]
                ),
                "protected_lost_reservation_events": b["protected_lost_reservation_events"],
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
                    "analysis_selection": compare_analysis_selection(
                        a["analysis_selection"], b["analysis_selection"]
                    ),
                    "face_only_production_tracks": compare_map(
                        a["face_production_tracks"], b["face_production_tracks"]
                    ),
                    "face_only_production_identity": compare_map(
                        a["face_production_identity"], b["face_production_identity"]
                    ),
                    "face_only_production_id_set": compare_map(
                        transform_map(
                            a["face_production_identity"], _track_id_set_signature_from_identity
                        ),
                        transform_map(
                            b["face_production_identity"], _track_id_set_signature_from_identity
                        ),
                    ),
                    "face_only_production_state_topology": compare_map(
                        transform_map(
                            a["face_production_identity"], _track_state_signature_from_identity
                        ),
                        transform_map(
                            b["face_production_identity"], _track_state_signature_from_identity
                        ),
                    ),
                    "face_only_production_bbox_delta": protected_bbox_delta_summary(
                        a["face_production_tracks"],
                        b["face_production_tracks"],
                        a["shadow_protected_track_ids"] | b["shadow_protected_track_ids"],
                    ),
                    "face_roi_detector": compare_face_roi(
                        a["face_roi_detector"], b["face_roi_detector"]
                    ),
                    "face_sticker_placements": compare_map(
                        a["face_sticker_placements"], b["face_sticker_placements"]
                    ),
                    "face_class_fallbacks": compare_map(
                        a["face_class_fallbacks"], b["face_class_fallbacks"]
                    ),
                    "face_temporal_class_evidence": compare_map(
                        a["face_temporal_class_evidence"], b["face_temporal_class_evidence"]
                    ),
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
