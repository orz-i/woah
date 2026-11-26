#!/usr/bin/env python3
"""Offline selected-mask refinement A/B; not a production or release gate.

Use frozen native selected alpha and the same pinned instance model on an
expanded crop of the exact source frame. Selection uses only the original
mask and current crop observations, never annotation points or contours.
Missing masks remain missing. No tracker state or native archive is changed.
Requires the local cutout_m0 host environment and built FrameKernel.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time

import cv2
import numpy as np

import cutout_m0 as m
from review_holdout import contour_metrics
from review_real_dance import frames, point_check

PADDING = .35
MIN_IOU = .65
MIN_AGREEMENT = .80
COMPETITOR_IOU = .25
MAX_ADDITION_COMPETITOR_FRACTION = .10


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def crop_bounds(alpha, source_width, source_height):
    """Align crop edges to both source and alpha pixel boundaries."""
    h, w = alpha.shape
    ys, xs = np.where(alpha >= 128)
    if not len(xs):
        return None
    ax = w // math.gcd(w, source_width)
    ay = h // math.gcd(h, source_height)
    x, y = int(xs.min()), int(ys.min())
    bw, bh = int(xs.max()) + 1 - x, int(ys.max()) + 1 - y
    px, py = math.ceil(bw * PADDING), math.ceil(bh * PADDING)
    x0 = max(0, (x-px)//ax*ax)
    y0 = max(0, (y-py)//ay*ay)
    x1 = min(w, math.ceil((x+bw+px)/ax)*ax)
    y1 = min(h, math.ceil((y+bh+py)/ay)*ay)
    return x0, y0, x1, y1


def touches_artificial_edge(mask, bounds, shape):
    x0, y0, x1, y1 = bounds
    h, w = shape
    return bool((x0 > 0 and mask[:, 0].any()) or
                (y0 > 0 and mask[0, :].any()) or
                (x1 < w and mask[:, -1].any()) or
                (y1 < h and mask[-1, :].any()))


def agreement(old, new):
    common = int((old & new).sum())
    return (common / max(1, int((old | new).sum())),
            common / max(1, int(old.sum())),
            common / max(1, int(new.sum())))


def choose(old_alpha, candidates, bounds):
    """Return a geometry-only provisional choice and a conservative output."""
    old = old_alpha >= 128
    x0, y0, x1, y1 = bounds
    if not old.any() or not candidates:
        return None, "no_candidate", []
    binary = [a >= 128 for a in candidates]
    scores = [agreement(old, b) for b in binary]
    order = sorted(range(len(scores)), key=lambda i: scores[i][0], reverse=True)
    j = order[0]
    iou, recall, precision = scores[j]
    reason = "accepted"
    if iou < MIN_IOU or min(recall, precision) < MIN_AGREEMENT:
        reason = "insufficient_agreement"
    elif any(scores[i][0] >= COMPETITOR_IOU for i in order[1:]):
        reason = "ambiguous_candidate"
    elif touches_artificial_edge(binary[j][y0:y1, x0:x1], bounds, old.shape):
        reason = "artificial_crop_edge"
    else:
        added = binary[j] & ~old
        if added.any() and any((added & binary[i]).sum() / added.sum() >
                               MAX_ADDITION_COMPETITOR_FRACTION for i in order[1:]):
            reason = "competing_addition"
    return j, reason, scores


def refine(lib, runner, rgba, alpha):
    if alpha is None:
        return None, None, {"reason": "missing_baseline"}
    h, w = alpha.shape
    sh, sw = rgba.shape[:2]
    bounds = crop_bounds(alpha, sw, sh)
    if bounds is None:
        return None, alpha, {"reason": "empty_baseline"}
    x0, y0, x1, y1 = bounds
    sx0, sx1 = x0*sw//w, x1*sw//w
    sy0, sy1 = y0*sh//h, y1*sh//h
    gain = max(sw, sh) / max(sx1-sx0, sy1-sy0)
    info = {"crop": [sx0, sy0, sx1, sy1], "linearInputGain": gain}
    if gain < 1.25:
        return None, alpha, {**info, "reason": "insufficient_magnification"}
    roi = np.ascontiguousarray(rgba[sy0:sy1, sx0:sx1])
    start = time.monotonic()
    ds, masks, transform = m.infer(lib, runner, roi, .25)
    candidates = []
    for raw in masks:
        projected = np.empty((y1-y0, x1-x0), np.uint8)
        rc = lib.frame_kernel_cutout_alpha(m.ptr(raw), raw.size, transform,
            x1-x0, y1-y0, m.ptr(projected), projected.size)
        if rc != 0:
            raise ValueError(f"Crop alpha projection failed: {rc}")
        out = np.zeros_like(alpha)
        out[y0:y1, x0:x1] = projected
        candidates.append(out)
    j, reason, scores = choose(alpha, candidates, bounds)
    info.update(reason=reason, inferenceAndSelectionMs=(time.monotonic()-start)*1000,
                candidateCount=len(candidates), agreement=scores,
                candidateConfidence=[float(d.confidence) for d in ds], selectedIndex=j)
    provisional = candidates[j] if j is not None else None
    return provisional, provisional if reason == "accepted" else alpha, info


def source_samples(path, manifest, requested):
    """Verify source PTS and decode exact requested frame indices in one pass."""
    w, h = manifest["dimensions"]
    count, cadence = manifest["framesPerRole"], manifest["cadence"]
    expected = [i*1000000//cadence for i in range(count)]
    probe = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height:stream_side_data=rotation:frame=best_effort_timestamp_time",
        "-show_frames", "-show_streams", "-of", "json", str(path)]))
    stream, = probe["streams"]
    actual = [round(float(f["best_effort_timestamp_time"])*1000000) for f in probe["frames"]]
    if ([stream["width"], stream["height"]] != [w, h] or actual != expected or
            any(int(s.get("rotation", 0)) for s in stream.get("side_data_list", [])) or
            not set(requested) <= set(expected)):
        raise ValueError("Source does not match frozen display dimensions/PTS")
    indices = [i for i, pts in enumerate(expected) if pts in requested]
    expression = "+".join(f"eq(n\\,{i})" for i in indices)
    data = subprocess.check_output(["ffmpeg", "-v", "error", "-noautorotate", "-i", str(path),
        "-map", "0:v:0", "-vf", f"select={expression}", "-fps_mode", "passthrough",
        "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1"])
    if len(data) != len(indices)*w*h*4:
        raise ValueError("Unexpected decoded sample count")
    images = np.frombuffer(data, np.uint8).reshape(len(indices), h, w, 4)
    return {expected[i]: image for i, image in zip(indices, images)}


def score(alpha, annotation, size):
    if annotation is None:
        return None
    result = {"selectedHit": point_check(alpha, annotation["selected"]),
              "distractorHit": point_check(alpha, annotation["distractor"])
                  if "distractor" in annotation else None,
              "additionalDistractorHits": [point_check(alpha, item["point"])
                  for item in annotation.get("additionalDistractors", [])]}
    if "visibleContour" in annotation:
        result["contour"] = contour_metrics(alpha, annotation["visibleContour"], *size)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    model = m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    if sha(model) != manifest["modelSha256"] or sha(model) != m.MODEL_SHA:
        raise ValueError("Unexpected model")
    report = json.loads((args.evidence/"report.json").read_text())
    if (not report["completed"] or report["limitUs"] != manifest["durationUs"] or
            report["anchorUs"] != manifest["anchorUs"]):
        raise ValueError("Incompatible native evidence")
    runner = m.Interpreter(model_path=str(model), num_threads=4)
    runner.allocate_tensors()
    lib = m.api(args.library)
    result = {"manifestSha256": sha(args.manifest), "modelSha256": sha(model),
              "librarySha256": sha(args.library), "nativeReportSha256": sha(args.evidence/"report.json"),
              "policy": {"padding": PADDING, "minIoU": MIN_IOU, "minAgreement": MIN_AGREEMENT,
                         "competitorIoU": COMPETITOR_IOU, "maxAdditionCompetitorFraction":
                         MAX_ADDITION_COMPETITOR_FRACTION, "minLinearInputGain": 1.25},
              "roles": {}}
    for role, source in manifest["sources"].items():
        video = args.media/source["file"]
        if sha(video) != source["testedMp4Sha256"]:
            raise ValueError("Source hash mismatch")
        annotations = {a["ptsUs"]: a for a in manifest["annotations"][role]}
        images = source_samples(video, manifest, annotations)
        rows = result["roles"][role] = {}
        for backend in ("cpu", "gpu"):
            if not any(run["requested"].lower() == backend and run["effective"].lower() == backend
                       and run["status"] == "complete" for run in report["runs"]):
                raise ValueError(f"Missing effective native backend {backend}")
            path = args.evidence/f"{backend}-{role}.alpha"
            records = dict(frames(path))
            if list(records) != [i*1000000//manifest["cadence"] for i in range(manifest["framesPerRole"])]:
                raise ValueError("Alpha PTS mismatch")
            samples = []
            for pts, annotation in annotations.items():
                alpha = records[pts]
                raw, candidate, info = refine(lib, runner, images[pts], alpha)
                samples.append({"ptsUs": pts, **info,
                    "baseline": score(alpha, annotation, manifest["contourCoordinateSize"]),
                    "provisional": score(raw, annotation, manifest["contourCoordinateSize"]),
                    "refined": score(candidate, annotation, manifest["contourCoordinateSize"])})
            rows[backend] = {"alphaSha256": sha(path), "samples": samples}
            print(role, backend, {reason: sum(s["reason"] == reason for s in samples)
                for reason in sorted(set(s["reason"] for s in samples))}, flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+"\n")


if __name__ == "__main__":
    main()
