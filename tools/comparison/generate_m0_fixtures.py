#!/usr/bin/env python3
"""Generate reproducible, timecoded M0 videos with known shared-music offset.

Offline only; requires FFmpeg with H.264/AAC encoders; no fonts or Fontconfig. No dance
videos, original user media, or source audio are committed or uploaded.
"""
from __future__ import annotations

import argparse
from array import array
from hashlib import sha256
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile

from m0_pixels import FRAME_CODE, render_frame
import wave

SAMPLE_RATE = 16000
MUSIC_SECONDS = 10.0


def generate_music(seconds: float = MUSIC_SECONDS) -> bytes:
    # Each beat has a deliberately distinct pitch. Two identical recorded
    # waveforms with independent silent leads allow known-offset verification.
    notes = (392.0, 587.33, 440.0, 659.25, 523.25, 783.99, 349.23, 698.46,
             494.0, 622.25, 370.0, 554.37, 415.3, 740.0, 466.16, 830.61,
             311.13, 622.25, 523.25, 880.0)
    samples = array("h")
    for i in range(round(SAMPLE_RATE * seconds)):
        t = i / SAMPLE_RATE
        beat_idx = int(t * 2)
        within_beat = t * 2 - beat_idx
        envelope = math.exp(-within_beat * 6.0)
        bass = 0.22 * math.sin(2 * math.pi * 110.0 * t) * envelope
        melody = 0.30 * math.sin(2 * math.pi * notes[beat_idx % len(notes)] * t) * envelope
        percussion = 0.13 * math.sin(2 * math.pi * 75.0 * t) * math.exp(-within_beat * 20)
        value = max(-0.9, min(0.9, bass + melody + percussion))
        samples.append(int(32767 * value))
    if __import__("sys").byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def write_audio(path: Path, lead_ms: int, music: bytes) -> None:
    assert lead_ms >= 0
    silence = bytes(SAMPLE_RATE * 2 * lead_ms // 1000)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(silence + music)


def render_video(
    ffmpeg: str, label: str, color: str, wav: Path, mp4: Path,
    fps: int, size: str, seconds: float, lead_us: int = 0,
) -> None:
    width, height = map(int, size.split("x"))
    frame_count = math.ceil(seconds * fps)
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", size,
        "-framerate", str(fps), "-i", "pipe:0", "-i", str(wav),
        "-map", "0:v:0", "-map", "1:a:0",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k",
        "-movflags", "+faststart", str(mp4),
    ]
    print("RUN", " ".join(command), flush=True)
    # Drain stderr into a bounded-on-disk temporary file, not a pipe which can
    # deadlock while we stream frames. Only one RGB frame is resident at a time.
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=errors)
        try:
            assert process.stdin is not None
            for index in range(frame_count):
                process.stdin.write(render_frame(label, color, width, height,
                                                index, fps, lead_us))
            process.stdin.close()
            code = process.wait()
        except (BrokenPipeError, OSError):
            if process.stdin is not None:
                try:
                    process.stdin.close()
                except OSError:
                    pass
            code = process.wait()
            if code == 0:
                raise
        except BaseException:
            process.kill()
            process.wait()
            raise
        errors.seek(0)
        stderr = errors.read().decode("utf-8", errors="replace")
        if code != 0:
            raise RuntimeError(f"FFmpeg failed ({code}; 0x{code & 0xffffffff:08X}): "
                               f"{stderr.strip()}")
        if stderr:
            print(stderr, file=sys.stderr)


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def describe(ffprobe: str, path: Path) -> dict:
    result = subprocess.run([
        ffprobe, "-v", "error", "-show_entries",
        "stream=codec_type,codec_name,width,height,avg_frame_rate,time_base,start_time,duration,nb_frames,sample_rate,channels:format=duration",
        "-of", "json", str(path),
    ], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("testdata/comparison/generated"))
    parser.add_argument("--fps", type=int, default=30, choices=(24, 30, 60))
    parser.add_argument("--size", default="640x360")
    parser.add_argument("--teacher-lead-ms", type=int, default=2000)
    parser.add_argument("--offset-ms", type=int, default=3000)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--music-seconds", type=int, default=10,
                        help="Shared music length (10..600s); use >=120 for drift runs")
    args = parser.parse_args(argv)
    if not 10 <= args.music_seconds <= 600:
        parser.error("Music length must be between 10 and 600 seconds")
    learner_lead = args.teacher_lead_ms + args.offset_ms
    if args.teacher_lead_ms < 0 or learner_lead < 0:
        parser.error("Both leads must be nonnegative in whole milliseconds.")
    if args.size not in ("640x360", "1280x720", "1920x1080"):
        parser.error("Size must be 640x360, 1280x720, or 1920x1080")
    args.out.mkdir(parents=True, exist_ok=True)
    music = generate_music(args.music_seconds)
    manifest = {
        "schema": "woah.comparison.m0.fixtures.v1",
        "expectedOffsetUs": args.offset_ms * 1000,
        "teacherMusicStartUs": args.teacher_lead_ms * 1000,
        "learnerMusicStartUs": learner_lead * 1000,
        "musicDurationUs": args.music_seconds * 1_000_000,
        "fps": args.fps, "size": args.size, "sources": {},
        "renderer": "stdlib_rgb_bitmap_v1", "frameCode": FRAME_CODE,
        "notes": "Shared PCM source encoded separately as AAC; the printed frame time is source PTS.",
    }
    # Encode and probe both sources privately. A failed run retains the previous
    # complete fixture set and removes all intermediates automatically.
    with tempfile.TemporaryDirectory(prefix=".m0-", dir=args.out.parent) as temp:
        staging = Path(temp)
        for role, lead, color in (
            ("teacher", args.teacher_lead_ms, "183c69"),
            ("learner", learner_lead, "533a64"),
        ):
            wav = staging / f"{role}.wav"
            video = staging / f"{role}.mp4"
            seconds = lead / 1000 + args.music_seconds
            write_audio(wav, lead, music)
            render_video(args.ffmpeg, role.upper(), color, wav, video,
                         args.fps, args.size, seconds, lead * 1000)
            manifest["sources"][role] = {
                "name": video.name,
                "durationUs": round(seconds * 1_000_000),
                "frameCount": math.ceil(seconds * args.fps),
                "sha256": file_sha256(video),
                "ffprobe": describe(args.ffprobe, video),
            }
        result = args.out / "manifest.json"
        # The manifest is the commit marker. Never leave an old manifest pointing
        # at a partially replaced pair if publication itself is interrupted.
        result.unlink(missing_ok=True)
        for role in ("teacher", "learner"):
            (staging / f"{role}.mp4").replace(args.out / f"{role}.mp4")
        staged_manifest = staging / "manifest.json"
        staged_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                   encoding="utf-8")
        staged_manifest.replace(result)
    print(json.dumps({
        "teacher": str(args.out / "teacher.mp4"),
        "learner": str(args.out / "learner.mp4"),
        "manifest": str(result),
        "offsetUs": args.offset_ms * 1000,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
