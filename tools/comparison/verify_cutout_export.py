#!/usr/bin/env python3
"""Check the local comparison_cutout_smoke.dart MP4/preview artifacts.

Uses ffprobe and ffmpeg, no external Python packages. This deliberately uses
the fixture's 30 actual presentation samples, not nominal-FPS production
timing. The tolerance accounts for H.264 color conversion and quantization.
No frame or video is sent off the host.
"""
import argparse
import json
import math
import struct
import subprocess
from pathlib import Path

W, H = 320, 240
FRAME_BYTES = W * H * 3


def run(*args):
    return subprocess.check_output(args)


def rgb(path):
    return run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:v:0",
               "-pix_fmt", "rgb24", "-fps_mode", "passthrough", "-f", "rawvideo", "-")


def media(path):
    return json.loads(run("ffprobe", "-v", "error", "-show_streams",
                          "-show_format", "-show_frames", "-of", "json", str(path)))


def compare(expected, actual):
    assert len(expected) == len(actual) == FRAME_BYTES
    error = sum(abs(a - b) for a, b in zip(expected, actual)) / FRAME_BYTES
    # The quiet canvas corner must be the selected charcoal background, not
    # raw source pixels. Most of the frame is background, so also measure the
    # meaningful non-background foreground/edge region independently.
    background = (20, 33, 35)
    foreground_error = foreground_channels = 0
    for i in range(0, FRAME_BYTES, 3):
        if sum(abs(expected[i + c] - background[c]) for c in range(3)) > 45:
            foreground_error += sum(abs(expected[i+c] - actual[i+c]) for c in range(3))
            foreground_channels += 3
    assert foreground_channels > 300, "Preview had no meaningful subject region"
    foreground_mae = foreground_error / foreground_channels
    assert error < 12 and foreground_mae < 30, (error, foreground_mae)
    return {"rgbMae": round(error, 3), "foregroundMae": round(foreground_mae, 3)}


def verify(folder):
    report = json.loads((folder / "report.json").read_text())
    assert report["finished"] and report["passed"], report
    assert {r["id"] for r in report["results"]} >= {"mirrored", "middle", "long-ab"}
    mutation=json.loads((folder / "mutation-report.json").read_text())
    assert mutation["passed"] and mutation["state"] == "failed" and not mutation["retryAvailable"] and mutation["outputPath"] is None, mutation
    verified = []
    for row in report["results"]:
        name = row["id"]
        path = folder / f"{name}-cutout.mp4"
        metadata = media(path)
        video = next(s for s in metadata["streams"] if s["codec_type"] == "video")
        audio = next(s for s in metadata["streams"] if s["codec_type"] == "audio")
        assert video["codec_name"] == "h264" and audio["codec_name"] == "aac"
        assert (video["width"], video["height"]) == (W, H)
        assert int(audio["sample_rate"]) == 48000 and audio["channels"] == 2
        assert abs(float(metadata["format"]["duration"]) - 1) <= .001
        pts = [float(f["best_effort_timestamp_time"]) for f in metadata["frames"]
               if f.get("media_type") == "video"]
        assert len(pts) == 30 and abs(pts[0]) <= 1e-6
        assert all(abs(t - i / 30) < 2e-6 for i, t in enumerate(pts)), pts
        frames = rgb(path)
        assert len(frames) == 30 * FRAME_BYTES
        comparisons = []
        for us in (0, 400000, 800000):
            n = min(range(len(pts)), key=lambda i: abs(pts[i] - us / 1e6))
            expected = rgb(folder / f"{name}-export-preview-{us}.png")
            comparisons.append({"ptsUs": us, **compare(expected, frames[n*FRAME_BYTES:(n+1)*FRAME_BYTES])})
        resumed = rgb(folder / f"{name}-export-resumed.png")
        restored = rgb(folder / f"{name}-export-preview-400000.png")
        assert resumed == restored, f"{name}: resumed canvas pixels differ"
        pcm = run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0",
                  "-ac", "1", "-ar", "48000", "-f", "s16le", "-")
        samples = [v[0] for v in struct.iter_unpack("<h", pcm)]
        assert samples, "No decoded audio"
        rms = math.sqrt(sum(x*x for x in samples)/len(samples))
        if name == "long-ab":
            assert rms > 100, "Reference tone missing"
            # Count zero crossings in the interior to avoid AAC boundary effects.
            interior = samples[4800:-4800]
            hz = sum((a < 0) != (b < 0) for a, b in zip(interior, interior[1:])) * 24000 / len(interior)
            assert abs(hz - 440) < 8, hz
        else:
            assert rms < 2, "Unexpected audio in silent reference"
            hz = 0
        verified.append({"id": name, "frames": len(pts), "duration": float(metadata["format"]["duration"]),
                         "audioRms": round(rms, 2), "audioHz": round(hz, 2), "pixelComparisons": comparisons})
    return verified


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.folder), indent=2))
