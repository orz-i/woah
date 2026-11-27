"""Frozen source-sample and sparse point/contour helpers for cutout QA."""
from __future__ import annotations

import hashlib
import json
import subprocess

import numpy as np

from review_holdout import contour_metrics
from review_real_dance import point_check

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

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
