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


def verify(folder, platform="ios"):
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
        # Android preserves mono/stereo source PCM; iOS normalizes to stereo.
        expected_channels = 1 if platform == "android" and name == "long-ab" else 2
        assert int(audio["sample_rate"]) == 48000 and audio["channels"] == expected_channels
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
                         "audioChannels": audio["channels"], "audioRms": round(rms, 2),
                         "audioHz": round(hz, 2), "pixelComparisons": comparisons})
    return verified


def verify_extended(folder):
    """The Android 5-second / 1440x1080 fixture is exported from the same
    4:3 canvas, then reduced to the 320x240 preview for comparison."""
    report = json.loads((folder / "extended-report.json").read_text())
    assert report["finished"] and report["passed"], report
    row, = report["results"]
    assert row["id"] == "extended-ab" and row["visibility"] == [150] * 4
    assert row["window"] == [6000000, 11000000]
    assert row["export"]["savedToGallery"] and row["export"]["resumed"]
    path = folder / "extended-ab-cutout.mp4"
    metadata = media(path)
    video = next(s for s in metadata["streams"] if s["codec_type"] == "video")
    audio = next(s for s in metadata["streams"] if s["codec_type"] == "audio")
    assert video["codec_name"] == "h264" and audio["codec_name"] == "aac"
    assert (video["width"], video["height"]) == (1440, 1080)
    assert int(audio["sample_rate"]) == 48000 and audio["channels"] == 1
    assert abs(float(metadata["format"]["duration"]) - 5) <= .001
    pts = [float(f["best_effort_timestamp_time"]) for f in metadata["frames"]
           if f.get("media_type") == "video"]
    assert len(pts) == 150 and all(abs(t-i/30) < 2e-6 for i, t in enumerate(pts))
    frames = run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:v:0",
                 "-vf", "select='eq(n,0)+eq(n,12)+eq(n,24)',scale=320:240",
                 "-fps_mode", "passthrough", "-pix_fmt", "rgb24", "-f", "rawvideo", "-")
    assert len(frames) == 3 * FRAME_BYTES
    comparisons = [{"ptsUs": us, **compare(rgb(folder / f"extended-ab-export-preview-{us}.png"),
                   frames[n*FRAME_BYTES:(n+1)*FRAME_BYTES])}
                   for n, us in enumerate((0, 400000, 800000))]
    assert rgb(folder / "extended-ab-export-resumed.png") == rgb(folder / "extended-ab-export-preview-400000.png")
    pcm = run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0",
              "-ac", "1", "-ar", "48000", "-f", "s16le", "-")
    samples = [v[0] for v in struct.iter_unpack("<h", pcm)]
    interior = samples[4800:-4800]
    hz = sum((a < 0) != (b < 0) for a, b in zip(interior, interior[1:])) * 24000 / len(interior)
    assert abs(hz - 440) < 8
    published = folder / "extended-ab-published.mp4"
    if published.exists():
        assert published.read_bytes() == path.read_bytes(), "MediaStore publication changed bytes"
    return {"frames": len(pts), "duration": float(metadata["format"]["duration"]),
            "audioChannels": audio["channels"], "audioHz": round(hz, 2),
            "pixelComparisons": comparisons, "savedToGallery": True,
            "publicationBytesCompared": published.exists()}


def verify_full(folder, platform):
    """Untrimmed 12-second pair; includes a 300-frame reverse prefix and
    seeks across the selected-alpha chunk boundary and far from the seed."""
    report = json.loads((folder / "full-report.json").read_text())
    assert report["finished"] and report["passed"], report
    row, = report["results"]
    assert row["id"] == "full-length" and row["visibility"] == [360] * 4
    assert row["window"] is None and row["anchor"] == [10000000, 10000000]
    assert row["export"]["resumed"] and row["export"]["startUs"] == 0
    assert row["export"]["endUs"] == 12000000
    if platform == "android":
        assert row["export"]["savedToGallery"]
    path = folder / "full-length-cutout.mp4"
    metadata = media(path)
    video = next(s for s in metadata["streams"] if s["codec_type"] == "video")
    audio = next(s for s in metadata["streams"] if s["codec_type"] == "audio")
    assert video["codec_name"] == "h264" and audio["codec_name"] == "aac"
    assert (video["width"], video["height"]) == (1440, 1080)
    assert int(audio["sample_rate"]) == 48000 and audio["channels"] == (1 if platform == "android" else 2)
    assert abs(float(metadata["format"]["duration"]) - 12) <= .001
    pts = [float(f["best_effort_timestamp_time"]) for f in metadata["frames"]
           if f.get("media_type") == "video"]
    assert len(pts) == 360 and all(abs(t-i/30)<2e-6 for i,t in enumerate(pts))
    times=(0,400000,800000,1066667,5000000,11900000)
    indices=[min(range(len(pts)),key=lambda i:abs(pts[i]-us/1e6)) for us in times]
    selection="+".join(f"eq(n,{n})" for n in indices)
    frames=run("ffmpeg","-v","error","-i",str(path),"-map","0:v:0",
               "-vf",f"select='{selection}',scale=320:240","-fps_mode","passthrough",
               "-pix_fmt","rgb24","-f","rawvideo","-")
    assert len(frames)==len(times)*FRAME_BYTES
    comparisons=[{"ptsUs":us,**compare(rgb(folder/f"full-length-export-preview-{us}.png"),
                 frames[n*FRAME_BYTES:(n+1)*FRAME_BYTES])} for n,us in enumerate(times)]
    assert rgb(folder/"full-length-export-resumed.png")==rgb(folder/"full-length-export-preview-400000.png")
    pcm=run("ffmpeg","-v","error","-i",str(path),"-map","0:a:0","-ac","1","-ar","48000","-f","s16le","-")
    samples=[v[0] for v in struct.iter_unpack("<h",pcm)]
    interior=samples[4800:-4800]
    hz=sum((a<0)!=(b<0) for a,b in zip(interior,interior[1:]))*24000/len(interior)
    assert abs(hz-440)<8
    published=folder/"full-length-published.mp4"
    if published.exists():
        assert published.read_bytes()==path.read_bytes()
    return {"frames":len(pts),"duration":float(metadata["format"]["duration"]),
            "audioHz":round(hz,2),"pixelComparisons":comparisons,
            "publicationBytesCompared":published.exists(),"analysisMs":row["analysisMs"],
            "exportMs":row["export"]["elapsedMs"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--platform", choices=("ios", "android"), default="ios")
    parser.add_argument("--extended", action="store_true", help="verify the separate Android 5-second high-resolution fixture")
    parser.add_argument("--full", action="store_true", help="verify full 12-second segmented-cache fixture")
    args = parser.parse_args()
    if args.extended and args.full:
        parser.error("choose --extended or --full")
    if args.extended and args.platform != "android":
        parser.error("--extended requires --platform android")
    result = verify_full(args.folder,args.platform) if args.full else (
        verify_extended(args.folder) if args.extended else verify(args.folder,args.platform))
    print(json.dumps(result,indent=2))
