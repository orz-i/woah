#!/usr/bin/env python3
"""Prepare an offline, source-of-truth *exported-video* privacy review.

This is a local-only reviewer for the Woah 2026-10-19 FULL_BODY regression,
not a model/privacy evaluator. It extracts protected FINAL output frames at
specific risk windows, never original media or raw segmentation intermediates.

Requires ffmpeg and ffprobe on PATH. The input files remain untouched.
Outputs contain potentially identifying pixels: keep them private.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Any

EXPECTED_FRAMES = 1391
EXPECTED_FPS = 30.0
EXPECTED_DIMENSIONS = {"landscape": (1280, 720), "portrait": (810, 1440)}
REQUIRED_TIMES = (39.733333, 45.966667, 46.233333, 46.333333)


@dataclass(frozen=True)
class ReviewWindow:
    slug: str
    label: str
    start_s: float
    end_s: float
    question: str


WINDOWS = (
    ReviewWindow(
        "overlap_39s", "39.733 秒 · 前景交叠", 39.633, 39.900,
        "所有可见非主角人物是否持续被保护？交叠边界是否有裸露？主角是否被误遮挡？",
    ),
    ReviewWindow(
        "end_46s", "45.967–46.333 秒 · 漏检与历史 Mask", 45.833, 46.367,
        "人物离场后是否残留幽灵遮挡？无检测的 11 帧中，可见非主角是否仍被保护？",
    ),
)


def sha256_file(path: Path) -> str:
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def run_command(command: list[str]) -> str:
    completed = subprocess.run(
        command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace", check=False,
    )
    if completed.returncode != 0:
        # Error details may contain local file paths, but never image pixels.
        raise ValueError(
            f"{Path(command[0]).name} failed (exit {completed.returncode}): "
            + completed.stderr[-1200:].strip()
        )
    return completed.stdout


def probe_video(path: Path, ffprobe: str) -> dict[str, Any]:
    data = json.loads(run_command([
        ffprobe, "-v", "error",
        "-select_streams", "v:0",
        "-show_streams", "-show_frames",
        "-show_entries",
        "stream=width,height,avg_frame_rate:frame=best_effort_timestamp_time",
        "-of", "json", str(path),
    ]))
    streams = data.get("streams", [])
    if len(streams) != 1:
        raise ValueError(f"Expected one primary video stream in {path.name}")
    stream = streams[0]
    try:
        rate = float(Fraction(stream["avg_frame_rate"]))
        pts = [float(frame["best_effort_timestamp_time"]) for frame in data["frames"]]
        width, height = int(stream["width"]), int(stream["height"])
    except (KeyError, TypeError, ValueError, ZeroDivisionError) as exc:
        raise ValueError(f"Missing frame timestamps / format in {path.name}") from exc
    if not pts or not all(math.isfinite(t) for t in pts):
        raise ValueError(f"No valid frame timestamps in {path.name}")
    if any(b <= a for a, b in zip(pts, pts[1:])):
        raise ValueError(f"Non-increasing frame PTS in {path.name}")
    if not math.isfinite(rate) or rate <= 0:
        raise ValueError(f"Invalid frame rate in {path.name}")
    offset = pts[0]
    return {
        "width": width,
        "height": height,
        "fps": rate,
        "frame_count": len(pts),
        "relative_pts_s": [t - offset for t in pts],
        "original_first_pts_s": offset,
    }


def validate_export(meta: dict[str, Any], mode: str) -> None:
    expected = EXPECTED_DIMENSIONS[mode]
    if (meta["width"], meta["height"]) != expected:
        raise ValueError(
            f"{mode} is {meta['width']}x{meta['height']}, expected {expected[0]}x{expected[1]}; "
            "use the actual final export matching the diagnostic job"
        )
    if meta["frame_count"] != EXPECTED_FRAMES:
        raise ValueError(
            f"{mode} contains {meta['frame_count']} frames, expected {EXPECTED_FRAMES}; "
            "do not review a shortened/re-encoded excerpt as the original job"
        )
    if abs(meta["fps"] - EXPECTED_FPS) > 0.5:
        raise ValueError(
            f"{mode} frame rate {meta['fps']:.6f} differs from expected 30 fps"
        )
    end = meta["relative_pts_s"][-1]
    if abs(end - 46.333333) > 2.0 / EXPECTED_FPS:
        raise ValueError(
            f"{mode} final frame PTS {end:.3f}s is inconsistent with 46.333s"
        )
    for target in REQUIRED_TIMES:
        delta = min(abs(t - target) for t in meta["relative_pts_s"])
        if delta > 0.55 / EXPECTED_FPS:
            raise ValueError(
                f"{mode} contains no frame within half a frame of {target:.3f}s "
                f"(closest difference {delta:.3f}s)"
            )


def window_frames(relative_pts_s: list[float], window: ReviewWindow) -> list[int]:
    indices = [
        i for i, t in enumerate(relative_pts_s)
        if window.start_s - 1e-6 <= t <= window.end_s + 1e-6
    ]
    if len(indices) < 2 or len(indices) > 60:
        raise ValueError(
            f"{window.slug}: unexpected {len(indices)} review frames; "
            "verify timeline before extracting"
        )
    return indices


def make_select_filter(indices: list[int]) -> str:
    if not indices:
        raise ValueError("No selected frame indices")
    if indices != sorted(set(indices)) or indices[0] < 0:
        raise ValueError("Frame indices must be sorted, unique and nonnegative")
    # Commas are escaped for FFmpeg's filter parser, never shell-escaped.
    return "select=" + "+".join(f"eq(n\\,{index})" for index in indices)


def extract_window(
    video: Path, *, ffmpeg: str, destination: Path, indices: list[int],
) -> list[Path]:
    destination.mkdir(parents=True, exist_ok=False)
    # Use index-exact frame selection, then synthesize dense 1-second output
    # timestamps solely for the extracted PNGs. The original video PTS is
    # independently obtained from ffprobe and preserved in the manifest.
    # Some Windows FFmpeg builds reject -vsync; matching the proven working
    # woah_auto_privacy_capture.py path avoids version-specific -fps_mode.
    # setpts=N/TB spaces only the *selected* images 1s apart so -r 1 cannot
    # silently duplicate/drop stills across large gaps in original frame PTS.
    run_command([
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(video),
        "-map", "0:v:0", "-an", "-sn", "-dn",
        "-vf", make_select_filter(indices) + ",setpts=N/TB",
        "-r", "1", "-start_number", "0",
        "-f", "image2", str(destination / "frame_%04d.png"),
    ])
    written = sorted(destination.glob("frame_*.png"))
    if len(written) != len(indices):
        raise ValueError(
            f"Decoded {len(written)} extracted frames; expected {len(indices)}. "
            "Do not label mismatched frames as the expected risk timestamps."
        )
    for frame in written:
        if frame.stat().st_size <= 8:
            raise ValueError(f"Empty PNG output: {frame.name}")
    return written


def validate_inputs(inputs: dict[str, Path], output_dir: Path) -> None:
    if not inputs:
        raise ValueError("Provide at least --landscape or --portrait")
    repo_root = Path(__file__).resolve().parents[2]
    output_resolved = output_dir.resolve()
    if output_resolved == repo_root or repo_root in output_resolved.parents:
        raise ValueError(
            "Privacy review PNGs may identify people; output directory must be outside "
            "the Git repository to prevent accidental commits"
        )
    seen = set()
    for mode, video in inputs.items():
        if not video.is_file():
            raise ValueError(f"{mode} final exported video not found: {video}")
        if video.suffix.lower() not in {".mp4", ".mov", ".m4v"}:
            raise ValueError(f"{mode} must be an actual exported video, not a diagnostic ZIP")
        resolved = video.resolve()
        if resolved in seen:
            raise ValueError("Landscape and portrait must be different exported files")
        seen.add(resolved)
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("Review output directory must be new or empty; refusing stale evidence")


def prepare_review(
    inputs: dict[str, Path], output_dir: Path,
    *, ffprobe: str, ffmpeg: str, expected_commit: str | None = None,
) -> dict[str, Any]:
    if expected_commit is not None and not re.fullmatch(r"[0-9a-fA-F]{40}", expected_commit):
        raise ValueError("--expected-commit must be the complete 40-character Git SHA")
    validate_inputs(inputs, output_dir)
    if not shutil.which(ffprobe) or not shutil.which(ffmpeg):
        raise ValueError("ffmpeg and ffprobe are required on PATH")
    # Validate *both* complete videos before writing any review artifacts.
    metadata = {mode: probe_video(video, ffprobe) for mode, video in inputs.items()}
    for mode, meta in metadata.items():
        validate_export(meta, mode)
    selected = {
        mode: {window.slug: window_frames(meta["relative_pts_s"], window)
               for window in WINDOWS}
        for mode, meta in metadata.items()
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    results: dict[str, Any] = {}
    for mode, video in inputs.items():
        meta = metadata[mode]
        windows = []
        for window in WINDOWS:
            indices = selected[mode][window.slug]
            dest = output_dir / mode / window.slug
            files = extract_window(video, ffmpeg=ffmpeg, destination=dest, indices=indices)
            frames = [
                {
                    "index": index,
                    "pts_s": round(meta["relative_pts_s"][index], 6),
                    "image": (Path(mode) / window.slug / path.name).as_posix(),
                }
                for index, path in zip(indices, files)
            ]
            windows.append({
                "slug": window.slug,
                "label": window.label,
                "start_s": window.start_s,
                "end_s": window.end_s,
                "frames": frames,
                "question": window.question,
                "manual_result": "NOT_REVIEWED",
            })
        results[mode] = {
            "video_basename": video.name,
            "video_sha256": sha256_file(video),
            "video_bytes": video.stat().st_size,
            "width": meta["width"],
            "height": meta["height"],
            "frame_count": meta["frame_count"],
            "fps": round(meta["fps"], 6),
            "first_video_pts_s": round(meta["original_first_pts_s"], 6),
            "windows": windows,
        }
    report: dict[str, Any] = {
        "schema": 1,
        "purpose": "woah_final_export_privacy_visual_review",
        "evidence_scope": "FINAL_EXPORTED_VIDEO_FRAMES_NOT_AUTOMATIC_PRIVACY_PROOF",
        "expected_commit": expected_commit.lower() if expected_commit else None,
        "expected_commit_evidence": (
            "OPERATOR_ASSERTED_NOT_VERIFIED_FROM_VIDEO" if expected_commit
            else "NOT_PROVIDED"
        ),
        "clip_identity": "USER_ASSERTED_FROM_FINAL_EXPORT_NOT_CRYPTOGRAPHICALLY_LINKED_TO_JOB",
        "mode_coverage": "BOTH" if len(results) == 2 else "PARTIAL",
        "visual_acceptance": "NOT_REVIEWED",
        "videos": results,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    write_review_html(report, output_dir)
    write_checklist(report, output_dir)
    return report


def write_review_html(report: dict[str, Any], output_dir: Path) -> None:
    sections = []
    for mode, video in report["videos"].items():
        for window in video["windows"]:
            cards = []
            for frame in window["frames"]:
                image_path = html.escape(frame["image"], quote=True)
                cards.append(
                    '<figure><a href="' + image_path + '"><img loading="lazy" '
                    'src="' + image_path + '" alt="review frame"></a>'
                    '<figcaption>源帧 #' + str(frame["index"]) +
                    ' · ' + f'{frame["pts_s"]:.6f}' + 's</figcaption></figure>'
                )
            sections.append(
                '<section><h2>' + html.escape(mode) + ' · ' +
                html.escape(window["label"]) + '</h2><p>' +
                html.escape(window["question"]) +
                '</p><div class="frames">' + "".join(cards) + '</div></section>'
            )
    page = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Woah 本地最终视频隐私复核</title>
<style>
body{font:15px/1.6 system-ui,sans-serif;background:#151515;color:#f2f2f2;
margin:24px auto;padding:0 20px;max-width:1550px}
h1{font-size:23px}h2{font-size:19px}p{color:#bbb}
section{border-top:1px solid #444;padding-top:14px;margin-top:28px}
.frames{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:16px}
figure{margin:0;background:#252525;border-radius:8px;padding:10px}
img{width:100%;height:auto;display:block;border-radius:5px}
figcaption{padding:8px 2px 0;color:#ddd;font-variant-numeric:tabular-nums}
.notice{background:#242424;padding:14px;border-radius:8px}
</style></head><body>
<h1>Woah：最终导出帧隐私验收</h1>
<p class="notice">本页面仅打开本地最终导出 PNG。导出帧可能仍包含可识别人物，
不要公开分享整份文件夹。图像出现并不代表隐私通过。
逐帧检查裸露、错误遮挡与连续帧闪烁，并在 review-checklist.md 记录结论。</p>
""" + "".join(sections) + "</body></html>\n"
    (output_dir / "index.html").write_text(page, encoding="utf-8")


def write_checklist(report: dict[str, Any], output_dir: Path) -> None:
    lines = [
        "# Woah 最终视频隐私人工验收（待核验）",
        "",
        "这是实际导出文件的本地 PNG 复核，不是自动通过证明。",
        "视频 SHA256 仅用于标识本地文件，不能单凭哈希证明与诊断 job_id 对应。",
        "",
    ]
    for mode, video in report["videos"].items():
        lines.extend([
            f"## {mode}：{video['video_basename']}",
            f"- SHA256: `{video['video_sha256']}`",
            f"- {video['width']}×{video['height']} / {video['frame_count']} frames",
            "",
        ])
        for window in video["windows"]:
            lines.extend([
                f"### {window['label']}",
                f"- 检查：{window['question']}",
                "- [ ] 所有可见非主角人物持续被匿名化，无明显边界漏保护",
                "- [ ] 主角未被错误遮挡；没有明显的历史 Mask 幽灵或闪烁",
                "- [ ] 逐帧查看过所有 PNG（不仅是起始帧）",
                "- 结论：待核验 / 通过 / 不通过 / 不适用（注明原因）",
                "- 问题帧（源帧编号、PTS、表现）：",
                "",
            ])
    lines.extend([
        "## 最终结论",
        "- 横屏：待核验",
        "- 竖屏：待核验",
        "- 任一风险帧出现裸露或无法确认，保持 REVIEW_REQUIRED，不宣称通过。",
        "- 仅凭此人工抽帧仍不能构成对全部未抽样帧的数学证明。",
        "- 如果两个输出模式都逐帧确认无漏保护，再结合已有 1391 帧诊断回归记录阶段通过。",
    ])
    (output_dir / "review-checklist.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--landscape", type=Path, help="Full 1280x720 exported MP4")
    parser.add_argument("--portrait", type=Path, help="Full 810x1440 exported MP4")
    parser.add_argument(
        "--expected-commit",
        help="Optional full build Git SHA; operator assertion only, not verified from MP4"
    )
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="New private local directory, never committed")
    args = parser.parse_args(argv)
    inputs = {
        mode: path for mode, path in (
            ("landscape", args.landscape), ("portrait", args.portrait)
        ) if path is not None
    }
    try:
        result = prepare_review(
            inputs, args.output_dir, ffprobe="ffprobe", ffmpeg="ffmpeg",
            expected_commit=args.expected_commit,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"PRIVACY_FINAL_FRAME_REVIEW=FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        f"PRIVACY_FINAL_FRAME_REVIEW=PREPARED_NOT_ACCEPTED "
        f"modes={','.join(result['videos'])} output={args.output_dir}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
