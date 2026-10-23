#!/usr/bin/env python3
"""Generate reproducible, timecoded M0 videos with known shared-music offset.

Offline only; requires FFmpeg with drawtext and H.264/AAC encoder. No dance
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
import wave

SAMPLE_RATE = 16000
MUSIC_SECONDS = 10.0


def generate_music() -> bytes:
    # Each beat has a deliberately distinct pitch. Two identical recorded
    # waveforms with independent silent leads allow known-offset verification.
    notes = (392.0, 587.33, 440.0, 659.25, 523.25, 783.99, 349.23, 698.46,
             494.0, 622.25, 370.0, 554.37, 415.3, 740.0, 466.16, 830.61,
             311.13, 622.25, 523.25, 880.0)
    samples = array("h")
    for i in range(int(SAMPLE_RATE * MUSIC_SECONDS)):
        t = i / SAMPLE_RATE
        beat_idx = min(int(t * 2), len(notes) - 1)
        within_beat = t * 2 - beat_idx
        envelope = math.exp(-within_beat * 6.0)
        bass = 0.22 * math.sin(2 * math.pi * 110.0 * t) * envelope
        melody = 0.30 * math.sin(2 * math.pi * notes[beat_idx] * t) * envelope
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


def tool(command: list[str]) -> None:
    print("RUN", " ".join(command[:5]), "...", flush=True)
    subprocess.run(command, check=True)


def render_video(
    ffmpeg: str, label: str, color: str, wav: Path, mp4: Path,
    fps: int, size: str, seconds: float,
) -> None:
    # 'n' is the *source* frame index, not a made-up comparison frame.
    # Font path is deliberately not hardcoded (Linux/macOS/Windows compatible).
    overlay = (
        f"drawtext=text={label}:fontcolor=white:fontsize=40:x=24:y=28,"
        "drawtext=text='source frame %{n}':fontcolor=white:fontsize=28:x=24:y=96,"
        "drawtext=text='source pts %{pts\\:hms}':fontcolor=white:fontsize=26:x=24:y=144"
    )
    tool([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"color=c={color}:s={size}:r={fps}:d={seconds:.6f}",
        "-i", str(wav), "-vf", overlay,
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k",
        "-movflags", "+faststart", "-shortest", str(mp4),
    ])


def describe(ffprobe: str, path: Path) -> dict:
    result = subprocess.run([
        ffprobe, "-v", "error", "-show_entries",
        "stream=codec_type,codec_name,avg_frame_rate:format=duration",
        "-of", "json", str(path),
    ], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("testdata/comparison/generated"))
    parser.add_argument("--fps", type=int, default=30, choices=(24, 30, 60))
    parser.add_argument("--size", default="640x360")
    parser.add_argument("--teacher-lead-ms", type=int, default=2000)
    parser.add_argument("--offset-ms", type=int, default=3000)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args()
    learner_lead = args.teacher_lead_ms + args.offset_ms
    if args.teacher_lead_ms < 0 or learner_lead < 0:
        parser.error("Both leads must be nonnegative in whole milliseconds.")
    if args.size not in ("640x360", "1280x720", "1920x1080"):
        parser.error("Size must be 640x360, 1280x720, or 1920x1080")
    args.out.mkdir(parents=True, exist_ok=True)
    music = generate_music()
    manifest = {
        "schema": "woah.comparison.m0.fixtures.v1",
        "expectedOffsetUs": args.offset_ms * 1000,
        "teacherMusicStartUs": args.teacher_lead_ms * 1000,
        "learnerMusicStartUs": learner_lead * 1000,
        "musicDurationUs": int(MUSIC_SECONDS * 1_000_000),
        "fps": args.fps, "size": args.size, "sources": {},
        "notes": "Shared PCM source encoded separately as AAC; the printed frame time is source PTS.",
    }
    for role, lead, color in (
        ("teacher", args.teacher_lead_ms, "0x183c69"),
        ("learner", learner_lead, "0x533a64"),
    ):
        wav = args.out / f"{role}.wav"
        video = args.out / f"{role}.mp4"
        write_audio(wav, lead, music)
        render_video(args.ffmpeg, role.upper(), color, wav, video,
                     args.fps, args.size, lead / 1000.0 + MUSIC_SECONDS)
        manifest["sources"][role] = {
            "name": video.name,
            "durationUs": int((lead / 1000 + MUSIC_SECONDS) * 1_000_000),
            "sha256": sha256(video.read_bytes()).hexdigest(),
            "ffprobe": describe(args.ffprobe, video),
        }
        wav.unlink()  # keep only the paired MP4s, not intermediary PCM
    result = args.out / "manifest.json"
    result.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    print(json.dumps({
        "teacher": str(args.out / "teacher.mp4"),
        "learner": str(args.out / "learner.mp4"),
        "manifest": str(result),
        "offsetUs": args.offset_ms * 1000,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
