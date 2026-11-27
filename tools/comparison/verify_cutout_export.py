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


def verify_full(folder, platform, progressive=False):
    """Untrimmed 12-second pair; includes a 300-frame reverse prefix and
    seeks across the selected-alpha chunk boundary and far from the seed."""
    report = json.loads((folder / "full-report.json").read_text())
    assert report["finished"] and report["passed"], report
    row, = report["results"]
    assert row["id"] == "full-length" and row["visibility"] == [360] * 4
    assert row["window"] is None and row["anchor"] == [10000000, 10000000]
    if progressive:
        progress = row["progressive"]
        assert progress["range"] == [9500000, 10500000]
        assert 0 < progress["firstPlayableMs"] < row["analysisMs"]
        assert all(0 < n < 360 for n in progress["initialAnalyzed"])
        assert all(a <= b <= 360 for a, b in zip(progress["initialAnalyzed"], progress["laterAnalyzed"]))
        assert progress["range"][0] <= progress["playedPositionUs"] < progress["range"][1]
        assert progress["exportRejected"] and progress["anchorIdentical"]
        assert rgb(folder / "full-length-partial-anchor.png") == rgb(folder / "full-length-final-anchor.png")
        for key in ("anchorPixels", "livePixels"):
            counts = progress[key]
            assert counts["leftPixels"] > 80 and counts["rightPixels"] > 80 and counts["topPixels"] < 20

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


def verify_archive(folder, platform):
    """Verify a new-process restore and its strict 3-second A/B export."""
    report = json.loads((folder / "archive-report.json").read_text())
    assert report["phase"] == "finished" and report["passed"]
    assert report["pid"] != report["firstPid"]
    assert report["coverage"] == [90, 90]
    for key in ("anchorIdentical", "corruptionRejected", "mappingRejected",
                "sourceMutationRejected", "forgetPassed"):
        assert report[key], key
    assert rgb(folder / "archive-original-anchor.png") == rgb(folder / "archive-restored-anchor.png")
    export = report["export"]
    assert export["jobState"] == "completed" and export["resumed"]
    assert (export["startUs"], export["endUs"]) == (6000000, 9000000)
    path = folder / "archive-restored-cutout.mp4"
    metadata = media(path)
    video = next(s for s in metadata["streams"] if s["codec_type"] == "video")
    audio = next(s for s in metadata["streams"] if s["codec_type"] == "audio")
    assert video["codec_name"] == "h264" and audio["codec_name"] == "aac"
    assert (video["width"], video["height"]) == (320, 240)
    assert int(audio["sample_rate"]) == 48000 and audio["channels"] == (1 if platform == "android" else 2)
    assert abs(float(metadata["format"]["duration"]) - 3) <= .001
    pts = [float(f["best_effort_timestamp_time"]) for f in metadata["frames"]
           if f.get("media_type") == "video"]
    assert len(pts) == 90 and all(abs(t-i/30)<2e-6 for i,t in enumerate(pts))
    frames = rgb(path)
    assert len(frames) == 90 * FRAME_BYTES
    comparisons = [{"ptsUs":us, **compare(rgb(folder / f"archive-restored-export-preview-{us}.png"),
                    frames[i*FRAME_BYTES:(i+1)*FRAME_BYTES])} for us,i in ((0,0),(400000,12),(800000,24))]
    assert rgb(folder / "archive-restored-export-resumed.png") == rgb(folder / "archive-restored-export-preview-400000.png")
    pcm = run("ffmpeg","-v","error","-i",str(path),"-map","0:a:0","-ac","1",
              "-ar","48000","-f","s16le","-")
    samples = [v[0] for v in struct.iter_unpack("<h",pcm)][4800:-4800]
    hz = sum((a<0)!=(b<0) for a,b in zip(samples,samples[1:]))*24000/len(samples)
    assert abs(hz-440)<8
    published = folder / "archive-published.mp4"
    if platform == "android":
        assert export["savedToGallery"]
    if published.exists():
        assert published.read_bytes() == path.read_bytes()
    return {"frames": len(pts), "duration": float(metadata["format"]["duration"]),
            "analysisMs": report["analysisMs"], "saveMs": report["saveMs"],
            "restoreMs": report["restoreMs"], "audioHz":round(hz,2),
            "pixelComparisons":comparisons,"publicationBytesCompared":published.exists(),
            "newProcess":True,"negativeTests":"passed"}


def verify_repair(folder, platform):
    """Validate the local-patch QA outputs and independently decode its export."""
    report=json.loads((folder/"repair-report.json").read_text())
    assert report["finished"] and report["passed"],report
    for key in ("cancelPreserved","outsideIdentical","archiveRestored",
                "realDistinctSubjectReplaced","realOutsideIdentical","realCorrectedMatchesOriginalAnchor"):
        assert report[key],key
    def equal(a,b):return rgb(folder/f"{a}.png")==rgb(folder/f"{b}.png")
    assert equal("repair-before-inside","repair-cancelled")
    assert equal("repair-before-left","repair-after-left")
    assert equal("repair-before-right","repair-after-right")
    assert equal("repair-before-left","repair-restored-left")
    assert equal("repair-real-original-outside","repair-real-wrong-outside")
    assert equal("repair-real-original-outside","repair-real-corrected-outside")
    assert not equal("repair-real-wrong","repair-real-corrected")
    assert equal("repair-real-original-anchor","repair-real-corrected")
    path=folder/"repair-restored-cutout.mp4"
    metadata=media(path)
    video=next(s for s in metadata["streams"] if s["codec_type"]=="video")
    audio=next(s for s in metadata["streams"] if s["codec_type"]=="audio")
    assert (video["width"],video["height"])==(320,240)
    assert video["codec_name"]=="h264" and audio["codec_name"]=="aac"
    assert abs(float(metadata["format"]["duration"])-3)<=.001
    pts=[float(f["best_effort_timestamp_time"]) for f in metadata["frames"] if f.get("media_type")=="video"]
    assert len(pts)==90 and all(abs(t-i/30)<2e-6 for i,t in enumerate(pts))
    data=rgb(path)
    assert len(data)==90*FRAME_BYTES
    comparisons=[{"ptsUs":us,**compare(rgb(folder/f"repair-restored-export-preview-{us}.png"),
                 data[i*FRAME_BYTES:(i+1)*FRAME_BYTES])}
                 for us,i in ((0,0),(400000,12),(800000,24))]
    assert equal("repair-restored-export-preview-400000","repair-restored-export-resumed")
    pcm=run("ffmpeg","-v","error","-i",str(path),"-map","0:a:0","-ac","1","-ar","48000","-f","s16le","-")
    samples=[v[0] for v in struct.iter_unpack("<h",pcm)][4800:-4800]
    hz=sum((a<0)!=(b<0) for a,b in zip(samples,samples[1:]))*24000/len(samples)
    assert abs(hz-440)<8
    published=folder/"repair-published.mp4"
    if platform=="android":assert report["export"]["savedToGallery"]
    if published.exists():assert published.read_bytes()==path.read_bytes()
    return {"frames":len(pts),"duration":float(metadata["format"]["duration"]),
            "audioHz":round(hz,2),"pixelComparisons":comparisons,
            "patchElapsedMs":report["patchElapsedMs"],"publicationBytesCompared":published.exists(),
            "realIdentityPatch":True,"cancelAndOutsideUnchanged":True}


def verify_real_ab(folder, platform):
    """Verify the explicit, fully analyzed A/B window on independent footage."""
    report = json.loads((folder / "real-dance-report.json").read_text())
    assert report["finished"] and report["passed"] and report["allVisible"], report
    assert report["completeArchiveSaved"] and report["ordinaryRestored"]
    config = report["config"]
    start, end = config["exportWindowUs"]
    cadence = config["cadence"]
    count = (end - start) * cadence // 1000000
    assert report["visibility"] == [count] * 4
    assert report["anchor"] == [config["anchorUs"]] * 2
    assert all(row["pts"] == [row["positionUs"]] * 2 for row in report["samples"])
    export = report["export"]
    assert export["jobState"] == "completed" and export["resumed"]
    assert [export["startUs"], export["endUs"]] == [start, end]
    if platform == "android":
        assert export["savedToGallery"]
    path = folder / "real-ab-cutout.mp4"
    metadata = media(path)
    video = next(s for s in metadata["streams"] if s["codec_type"] == "video")
    audio = next(s for s in metadata["streams"] if s["codec_type"] == "audio")
    assert video["codec_name"] == "h264" and audio["codec_name"] == "aac"
    assert (video["width"], video["height"]) == (W, H)
    assert int(audio["sample_rate"]) == 48000
    duration = (end - start) / 1e6
    assert abs(float(metadata["format"]["duration"]) - duration) <= .002
    pts = [float(f["best_effort_timestamp_time"]) for f in metadata["frames"]
           if f.get("media_type") == "video"]
    assert len(pts) == count and all(abs(t-i/cadence) < 2e-6 for i,t in enumerate(pts))
    data = rgb(path)
    assert len(data) == count * FRAME_BYTES
    comparisons = []
    for us in (0, 400000, 800000):
        i = min(range(len(pts)), key=lambda n: abs(pts[n] - us / 1e6))
        comparisons.append({"ptsUs": us, **compare(
            rgb(folder / f"real-ab-export-preview-{us}.png"),
            data[i*FRAME_BYTES:(i+1)*FRAME_BYTES])})
    assert rgb(folder / "real-ab-export-resumed.png") == rgb(folder / "real-ab-export-preview-400000.png")
    published = folder / "real-ab-published.mp4"
    if published.exists():
        assert published.read_bytes() == path.read_bytes()
    return {"frames": count, "duration": duration, "audioChannels": audio["channels"],
            "pixelComparisons": comparisons, "resumeByteExactRGB": True,
            "analysisMs": report["analysisMs"], "exportMs": export["elapsedMs"],
            "publicationBytesCompared": published.exists()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--platform", choices=("ios", "android"), default="ios")
    parser.add_argument("--extended", action="store_true", help="verify the separate Android 5-second high-resolution fixture")
    parser.add_argument("--full", action="store_true", help="verify full 12-second segmented-cache fixture")
    parser.add_argument("--repair", action="store_true", help="verify local repair and restored export")
    parser.add_argument("--archive", action="store_true", help="verify new-process canvas archive restoration")
    parser.add_argument("--real-ab", action="store_true", help="verify explicit complete A/B export on independent footage")
    parser.add_argument("--progressive", action="store_true", help="also verify first playable window and final anchor equality")
    args = parser.parse_args()
    if args.progressive and not args.full:
        parser.error("--progressive requires --full")
    if sum((args.extended,args.full,args.archive,args.repair,args.real_ab))>1:
        parser.error("choose --extended, --full, --archive, --repair or --real-ab")
    if args.extended and args.platform != "android":
        parser.error("--extended requires --platform android")
    result = verify_real_ab(args.folder,args.platform) if args.real_ab else (
        verify_repair(args.folder,args.platform) if args.repair else (
        verify_archive(args.folder,args.platform) if args.archive else (
        verify_full(args.folder,args.platform,args.progressive) if args.full else (
        verify_extended(args.folder) if args.extended else verify(args.folder,args.platform)))))
    print(json.dumps(result,indent=2))
