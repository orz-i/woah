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
                    tracks_by_cadence = fields.get("tracks_by_cadence")
                    if isinstance(tracks_by_cadence, dict):
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
                }
            )

    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
