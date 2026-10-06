#!/usr/bin/env python3
"""Extract a deterministic training-frame sample from licensed/source videos.

The decoded source already contains the camera ISP and H.264/H.265/social-media
compression characteristics that synthetic image degradation cannot reproduce.
Frames are saved losslessly as PNG so the extractor itself adds no new JPEG
artifacts. Feed the output directory to ``train_crop_clarity_span_x2.py
--data-dir``; that trainer will apply an additional Real-ESRGAN-inspired
synthetic degradation to form LR/HR pairs.

This tool never downloads media. Only use videos you are allowed to use for
model training.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v", ".mkv", ".webm"}


def sample_frame_indices(
    *,
    frame_count: int,
    source_fps: float,
    sample_fps: float,
    max_frames: int | None,
) -> list[int]:
    """Return deterministic source-frame indices at approximately sample_fps."""
    if frame_count <= 0:
        return []
    safe_source_fps = source_fps if source_fps > 0 and source_fps < 1000 else 30.0
    safe_sample_fps = max(0.01, min(sample_fps, safe_source_fps))
    stride = max(1, round(safe_source_fps / safe_sample_fps))
    indices = list(range(0, frame_count, stride))
    if max_frames is not None and max_frames > 0 and len(indices) > max_frames:
        # Preserve temporal coverage instead of taking only the beginning.
        if max_frames == 1:
            return [indices[len(indices) // 2]]
        last = len(indices) - 1
        positions = [round(i * last / (max_frames - 1)) for i in range(max_frames)]
        indices = [indices[position] for position in positions]
    return indices


def discover_videos(directory: Path) -> list[Path]:
    if not directory.is_dir():
        raise ValueError(f"Video directory does not exist: {directory}")
    return sorted(
        path
        for path in directory.rglob("*")
        if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS
    )


def _require_cv2():
    try:
        import cv2  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit(
            "opencv-python is required for video-frame extraction. Install the Woah "
            "Python tool dependencies or add opencv-python to the isolated SR tooling environment."
        ) from exc
    return cv2


def _fourcc_text(cv2: Any, value: float) -> str:
    code = int(value)
    chars = [chr((code >> (8 * index)) & 0xFF) for index in range(4)]
    rendered = "".join(chars).strip("\x00 ")
    return rendered if rendered.isprintable() else "unknown"


def extract_video(
    video_path: Path,
    *,
    root: Path,
    output_dir: Path,
    sample_fps: float,
    max_frames: int | None,
) -> dict[str, Any]:
    cv2 = _require_cv2()
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise ValueError(f"Could not open video: {video_path}")
    try:
        frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = float(capture.get(cv2.CAP_PROP_FPS))
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fourcc = _fourcc_text(cv2, capture.get(cv2.CAP_PROP_FOURCC))
        indices = sample_frame_indices(
            frame_count=frame_count,
            source_fps=fps,
            sample_fps=sample_fps,
            max_frames=max_frames,
        )

        relative = video_path.relative_to(root)
        video_output = output_dir / relative.parent / relative.stem
        video_output.mkdir(parents=True, exist_ok=True)
        # Re-running with a different sampling rate must not leave stale frames
        # that would later be picked up by the trainer's recursive image scan.
        for stale in video_output.glob("frame_*_src_*.png"):
            stale.unlink()
        frames: list[dict[str, Any]] = []
        safe_fps = fps if fps > 0 and fps < 1000 else 30.0
        for ordinal, frame_index in enumerate(indices):
            capture.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
            ok, frame = capture.read()
            if not ok or frame is None:
                continue
            file_name = f"frame_{ordinal:06d}_src_{frame_index:09d}.png"
            destination = video_output / file_name
            if not cv2.imwrite(str(destination), frame, [cv2.IMWRITE_PNG_COMPRESSION, 3]):
                raise OSError(f"Failed to write extracted frame: {destination}")
            frames.append(
                {
                    "frame_index": frame_index,
                    "timestamp_ms": round(frame_index * 1000.0 / safe_fps, 3),
                    "path": destination.relative_to(output_dir).as_posix(),
                    "width": int(frame.shape[1]),
                    "height": int(frame.shape[0]),
                }
            )
        return {
            "source": relative.as_posix(),
            "source_fps": fps,
            "frame_count": frame_count,
            "width": width,
            "height": height,
            "fourcc": fourcc,
            "sampled_frames": frames,
        }
    finally:
        capture.release()


def extract_dataset(
    videos_dir: Path,
    output_dir: Path,
    *,
    sample_fps: float,
    max_frames_per_video: int | None,
) -> dict[str, Any]:
    videos_dir = videos_dir.resolve()
    output_dir = output_dir.resolve()
    videos = discover_videos(videos_dir)
    if not videos:
        raise ValueError(f"No supported videos found under {videos_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)

    reports = [
        extract_video(
            video,
            root=videos_dir,
            output_dir=output_dir,
            sample_fps=sample_fps,
            max_frames=max_frames_per_video,
        )
        for video in videos
    ]
    manifest = {
        "schema": 1,
        "purpose": "woah_crop_clarity_training_frames",
        "sampling": {
            "requested_fps": sample_fps,
            "max_frames_per_video": max_frames_per_video,
            "image_format": "png",
        },
        "videos": reports,
        "total_frames": sum(len(report["sampled_frames"]) for report in reports),
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--videos-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--sample-fps", type=float, default=1.0)
    parser.add_argument("--max-frames-per-video", type=int)
    args = parser.parse_args()
    if args.sample_fps <= 0:
        parser.error("--sample-fps must be > 0")
    try:
        report = extract_dataset(
            args.videos_dir,
            args.output_dir,
            sample_fps=args.sample_fps,
            max_frames_per_video=args.max_frames_per_video,
        )
    except (OSError, ValueError) as exc:
        print(f"CROP_CLARITY_FRAME_EXTRACT=FAIL: {exc}")
        return 1
    print("CROP_CLARITY_FRAME_EXTRACT=" + json.dumps(report, sort_keys=True))
    print("CROP_CLARITY_FRAME_EXTRACT=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
