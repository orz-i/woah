"""Media integrity and visual evidence; does not claim pixel-level privacy truth."""
import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[1] / "tools"))
from verify_video import inspect_mp4_atoms

def probe(path):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)
    ]))

def inspect(path):
    p = probe(path)
    v = next(s for s in p["streams"] if s["codec_type"] == "video")
    a = next((s for s in p["streams"] if s["codec_type"] == "audio"), None)
    frames = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
        "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", str(path)
    ]))["frames"]
    pts = [float(f["best_effort_timestamp_time"]) for f in frames if "best_effort_timestamp_time" in f]
    cap = cv2.VideoCapture(str(path))
    count = black = green = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        count += 1
        b, g, r = frame.mean(axis=(0, 1))
        black += bool(max(b, g, r) < 1)
        green += bool(g > 220 and r < 30 and b < 30)
    cap.release()
    result = {
        "file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "width": v["width"], "height": v["height"], "codec": v["codec_name"],
        "fps": v["r_frame_rate"], "video_duration": float(v["duration"]),
        "decoded_frames": count, "declared_frames": int(v.get("nb_frames", count)),
        "strictly_increasing_decoded_pts": all(y > x for x, y in zip(pts, pts[1:])),
        "pts_count": len(pts), "black_frames": black, "green_frames": green,
        "audio_codec": a["codec_name"] if a else None,
        "audio_duration": float(a["duration"]) if a else None,
        "av_duration_delta_ms": abs(float(a["duration"]) - float(v["duration"])) * 1000 if a else None,
        "atoms": inspect_mp4_atoms(path),
    }
    return result

def contact(path):
    cap = cv2.VideoCapture(str(path))
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    ratio = cap.get(cv2.CAP_PROP_FRAME_WIDTH) / cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
    w, h = (270, round(270 / ratio)) if ratio < 1 else (480, round(480 / ratio))
    fs = []
    for t in np.linspace(0, max(n-1, 0), 6).astype(int):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(t))
        ok, f = cap.read()
        if ok:
            f = cv2.resize(f, (w, h))
            cv2.putText(f, f"frame {t}", (10, 25), cv2.FONT_HERSHEY_SIMPLEX, .6, (0, 0, 255), 1)
            fs.append(f)
    cap.release()
    if len(fs) == 6:
        sheet = cv2.vconcat([cv2.hconcat(fs[:3]), cv2.hconcat(fs[3:])])
        cv2.imwrite(str(path.with_name(path.stem + "-contact.jpg")), sheet)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()
    results = []
    for name in args.files:
        path = ROOT / name
        result = inspect(path)
        contact(path)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False))
    target = ROOT / "media-inspection.json"
    previous = json.loads(target.read_text(encoding="utf-8")) if target.exists() else []
    merged = {row["file"]: row for row in previous}
    merged.update({row["file"]: row for row in results})
    target.write_text(json.dumps(list(merged.values()), ensure_ascii=False, indent=2), encoding="utf-8")
