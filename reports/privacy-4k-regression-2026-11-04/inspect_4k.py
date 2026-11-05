"""Independent FFmpeg/decoded-pixel checks for actual encoded 4K outputs."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent
HOLE_AUDIT_VERSION = 3
sys.path.insert(0, str(ROOT.parents[1] / "tools"))
from verify_video import inspect_mp4_atoms


def command(*args):
    return subprocess.run(list(map(str, args)), check=True, capture_output=True).stdout


def probe(path):
    return json.loads(command("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path))


def pts(path):
    result = json.loads(command("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
                                "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", path))
    return [round(float(f["best_effort_timestamp_time"]) * 1_000_000)
            for f in result["frames"] if "best_effort_timestamp_time" in f]


def contact(path, count=9):
    cap = cv2.VideoCapture(str(path))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    ratio = cap.get(cv2.CAP_PROP_FRAME_WIDTH) / cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
    width = 270 if ratio < 1 else 480
    height = round(width / ratio)
    images = []
    for frame in np.linspace(0, max(total - 1, 0), count).astype(int):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(frame))
        ok, pixels = cap.read()
        if ok:
            pixels = cv2.resize(pixels, (width, height))
            cv2.putText(pixels, f"frame {frame}", (8, 22), cv2.FONT_HERSHEY_SIMPLEX, .55, (0, 255, 255), 1)
            images.append(pixels)
    cap.release()
    if len(images) == count:
        sheet = cv2.vconcat([cv2.hconcat(images[i:i + 3]) for i in range(0, count, 3)])
        cv2.imwrite(str(path.with_name(path.stem + "-contact.jpg")), sheet)


def inspect(path, case, expected_pts):
    streams = probe(path)["streams"]
    video = next(s for s in streams if s["codec_type"] == "video")
    audio = next((s for s in streams if s["codec_type"] == "audio"), None)
    actual_pts = pts(path)
    # Decode every full-size frame, then scale only the black/green detector's input.
    # Geometry and timestamps are checked above on the original encoded stream.
    raw = command("ffmpeg", "-v", "error", "-threads", "2", "-i", path,
                  "-map", "0:v:0", "-an", "-vf", "scale=96:54", "-fps_mode", "passthrough",
                  "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1")
    assert len(raw) % (96 * 54 * 3) == 0, "Partial decoded frame"
    pixels = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 54, 96, 3)
    means = pixels.mean(axis=(1, 2))
    black = int(np.count_nonzero(means.max(axis=1) < 1))
    green = int(np.count_nonzero((means[:, 1] > 220) & (means[:, 0] < 30) & (means[:, 2] < 30)))
    expected = [t - case["trim_start_ms"] * 1000 for t in expected_pts
                if case["trim_start_ms"] * 1000 <= t < case["trim_end_ms"] * 1000]
    expected_audio = case["input"].startswith("solo-")
    av_delta = abs(float(audio["duration"]) - float(video["duration"])) * 1000 if audio else None
    if audio:
        command("ffmpeg", "-v", "error", "-i", path, "-map", "0:a:0", "-f", "null", "-")
    atoms = inspect_mp4_atoms(path)
    checks = {
        "source_resolution_preserved": (video["width"], video["height"]) == (case["width"], case["height"]),
        "every_frame_decodes": len(pixels) == len(expected) == len(actual_pts) == int(video["nb_frames"]),
        "every_source_presentation_timestamp_preserved": len(expected) == len(actual_pts)
            and all(abs(a - b) <= 1 for a, b in zip(expected, actual_pts)),
        "strictly_increasing_pts": all(a < b for a, b in zip(actual_pts, actual_pts[1:])),
        "no_black_frames": black == 0, "no_green_frames": green == 0,
        "audio_presence_preserved": bool(audio) == expected_audio,
        "av_duration_delta_within_100ms": av_delta is None or av_delta <= 100,
        "faststart": atoms.get("valid") is True and atoms.get("fast_start_ready") is True,
    }
    result = {"file": path.name, "case": case["case"], "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "width": video["width"], "height": video["height"], "codec": video["codec_name"],
              "fps": video["r_frame_rate"], "bitrate": video.get("bit_rate"),
              "duration_seconds": float(video["duration"]), "decoded_frames": len(pixels),
              "expected_frames": len(expected), "first_pts_us": actual_pts[0], "last_pts_us": actual_pts[-1],
              "black_frames": black, "green_frames": green, "audio_codec": audio["codec_name"] if audio else None,
              "av_duration_delta_ms": av_delta, "atoms": atoms, "checks": checks,
              "native_case_result": case.get("result"),
              "result": "passed" if all(checks.values()) else "failed"}
    contact(path)
    return result


def audit_face_solid(path):
    cap = cv2.VideoCapture(str(path))
    # Preserve the previous 960px output's 3px interior margin: half one
    # canonical 160px mask texel. Scaling output pixels does not create new
    # mask information; H.264/linear-sampling transitions are not deep holes.
    interior_margin = max(3.0, max(cap.get(cv2.CAP_PROP_FRAME_WIDTH), cap.get(cv2.CAP_PROP_FRAME_HEIGHT)) / 320.0)
    frames = 0
    findings = []
    while True:
        ok, pixels = cap.read()
        if not ok:
            break
        b, g, r = cv2.split(pixels)
        mask = np.uint8((r > 170) & (g < 110) & (b < 110)) * 255
        contours, hierarchy = cv2.findContours(mask, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        if hierarchy is not None:
            for index, item in enumerate(hierarchy[0]):
                if item[3] < 0 or cv2.contourArea(contours[index]) <= 5:
                    continue
                x, y, w, h = cv2.boundingRect(contours[item[3]])
                # Work in a component crop to retain full 4K pixel precision with bounded memory.
                # A zero border is essential: a tight component crop otherwise
                # treats its outside edges as deep interior in distanceTransform.
                outer = np.zeros((h + 2, w + 2), np.uint8)
                cv2.drawContours(outer, [contours[item[3]] - (x - 1, y - 1)], -1, 255, cv2.FILLED)
                hole = np.zeros_like(outer)
                cv2.drawContours(hole, [contours[index] - (x - 1, y - 1)], -1, 255, cv2.FILLED)
                distance = cv2.distanceTransform(outer, cv2.DIST_L2, 5)
                component = np.pad(mask[y:y + h, x:x + w], 1)
                exposed = (hole > 0) & (component == 0) & (distance > interior_margin)
                count = int(np.count_nonzero(exposed))
                if count > 5:
                    findings.append({"frame": frames, "interior_pixels": count,
                                     "bbox": cv2.boundingRect(contours[index]), "parent_bbox": (x, y, w, h)})
        frames += 1
    cap.release()
    return {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "algorithm_version": HOLE_AUDIT_VERSION,
            "interior_margin_source_pixels": interior_margin,
            "frames": frames, "frames_with_candidates": len({f["frame"] for f in findings}),
            "candidates": findings}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="4k_20261104")
    parser.add_argument("--holes", action="store_true")
    args = parser.parse_args()
    folder = ROOT / args.run
    summary = json.loads((folder / "results.json").read_text(encoding="utf-8"))
    source_pts_path = ROOT / "source-timestamps.json"
    source_pts = json.loads(source_pts_path.read_text(encoding="utf-8")) if source_pts_path.exists() else {}
    rows = []
    destination = folder / ("opaque-face-hole-audit.json" if args.holes else "media-inspection.json")
    previous = {r["file"]: r for r in json.loads(destination.read_text(encoding="utf-8"))} if destination.exists() else {}
    for case in summary["cases"]:
        if case.get("state") != "completed":
            continue
        path = folder / (case["case"] + ".mp4")
        if not path.is_file():
            continue
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name in previous and previous[path.name]["sha256"] == fingerprint and (
            not args.holes or previous[path.name].get("algorithm_version") == HOLE_AUDIT_VERSION
        ):
            rows.append(previous[path.name])
            continue
        if args.holes:
            if not case["face_only"] or case["fill"] != "solid":
                continue
            row = audit_face_solid(path)
        else:
            if case["input"] not in source_pts:
                source_pts[case["input"]] = pts(ROOT.parent / "privacy-e2e-2026-11-04" / case["input"])
                source_pts_path.write_text(json.dumps(source_pts, indent=2) + "\n", encoding="utf-8")
            row = inspect(path, case, source_pts[case["input"]])
        rows.append(row)
        destination.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(row), flush=True)
    assert rows, "No completed artifacts inspected"
    if not args.holes:
        assert all(row["result"] == "passed" for row in rows), "Encoded media integrity check failed"
