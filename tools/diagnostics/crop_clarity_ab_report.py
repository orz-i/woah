#!/usr/bin/env python3
"""Review *real protected GPU captures* from Woah's opt-in Android debug A/B export.

The ZIP has baseline/current variants from the SAME decoded frame, privacy
composition and crop matrix. This tool does not infer improved visual quality:
without a high-resolution ground truth, edge energy is only a descriptive
signal. Inspect the generated contact sheet for ringing, exaggerated noise,
text edges, hair/clothing artifacts and visible privacy-boundary changes.

No media is uploaded. ZIPs and generated PNGs may contain user video imagery;
keep them private and delete after review. Normal Woah diagnostics exclude them.
"""
from __future__ import annotations

import argparse
from io import BytesIO
import json
from pathlib import Path
import re
from typing import Any
import zipfile

MAX_PAIRS = 12
SCENE_KINDS = {"content_anchor", "privacy_overlap", "high_contrast", "fast_motion", "temporal_burst"}
MAX_PNG_BYTES = 30 * 1024 * 1024
SAFE_PNG = re.compile(r"^frame_[0-9]{6,9}_(?:off|on)\.png$")


def validate_manifest(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    schema = manifest.get("schema")
    valid_modes = {1: "same_frame_same_crop_two_pass",
                   2: "same_frame_same_crop_two_pass_scene_driven"}
    if schema not in valid_modes or manifest.get("capture_mode") != valid_modes[schema]:
        raise ValueError("unknown crop-clarity A/B capture contract")
    if manifest.get("privacy_composited") is not True or manifest.get("source_material_included") is not False:
        raise ValueError("only protected post-composition capture bundles are accepted")
    samples = manifest.get("samples")
    if not isinstance(samples, list) or not 1 <= len(samples) <= (4 if schema == 1 else MAX_PAIRS):
        raise ValueError("A/B bundle exceeds the schema-specific pair count limit")
    if schema == 2 and manifest.get("selection_policy") != "online_scene_heuristics_v2":
        raise ValueError("invalid phase-2 selection policy")
    expected = manifest.get("expected_frames")
    if expected is not None and (
        not isinstance(expected, list) or len(expected) > MAX_PAIRS or
        not all(isinstance(number, int) and number >= 1 for number in expected) or
        expected != sorted(set(expected))
    ):
        raise ValueError("invalid expected sample frame schedule")
    width = manifest.get("output_width")
    height = manifest.get("output_height")
    if not isinstance(width, int) or not isinstance(height, int) or width < 2 or height < 2:
        raise ValueError("invalid target dimensions")
    if width * height > 8_300_000:
        raise ValueError("capture is larger than the supported review envelope")
    seen = set()
    last_frame = 0
    for sample in samples:
        if not isinstance(sample, dict):
            raise ValueError("sample is not a mapping")
        frame = sample.get("frame")
        if not isinstance(frame, int) or frame <= last_frame:
            raise ValueError("A/B sample frames must be strictly increasing")
        last_frame = frame
        if not all(sample.get(flag) is True for flag in (
            "same_decoded_frame", "same_privacy_state", "same_crop_matrix"
        )):
            raise ValueError("A/B pair is not proven to share frame/privacy/crop")
        if schema == 2:
            kind = sample.get("scene_kind")
            if kind not in SCENE_KINDS:
                raise ValueError("invalid scene kind")
            for feature in ("luma_mean", "contrast", "protected_overlap",
                            "protagonist_motion", "protected_count"):
                if not isinstance(sample.get(feature), (int, float)):
                    raise ValueError(f"missing scene evidence: {feature}")
            if kind == "temporal_burst":
                if type(sample.get("burst_index")) is not int or not 0 <= sample["burst_index"] < 5:
                    raise ValueError("invalid temporal burst index")
            elif sample.get("burst_index") is not None:
                raise ValueError("non-temporal scene has burst index")
        crop = sample.get("crop")
        if (not isinstance(crop, list) or len(crop) != 4 or
            not all(isinstance(x, (float, int)) and 0 <= x <= 1 for x in crop) or
            crop[0] >= crop[2] or crop[1] >= crop[3]):
            raise ValueError("invalid crop coordinates")
        for key, suffix in (("baseline", "off"), ("enhanced", "on")):
            name = sample.get(key)
            if not isinstance(name, str) or not SAFE_PNG.fullmatch(name) or not name.endswith(f"_{suffix}.png"):
                raise ValueError(f"unsafe or invalid {key} PNG name")
            if name in seen:
                raise ValueError("duplicate A/B PNG name")
            seen.add(name)
    if expected is not None and not set(sample["frame"] for sample in samples).issubset(set(expected)):
        raise ValueError("captured frames do not belong to the expected schedule")
    return samples


def _require_images():
    try:
        import numpy as np  # type: ignore
        from PIL import Image, ImageDraw, ImageOps  # type: ignore
    except ImportError as exc:
        raise SystemExit("The report needs numpy and pillow (already in Woah's Python dependencies).") from exc
    return np, Image, ImageDraw, ImageOps


def _read_rgb(archive: zipfile.ZipFile, name: str, width: int, height: int, Image: Any) -> Any:
    info = archive.getinfo(name)
    if info.file_size <= 0 or info.file_size > MAX_PNG_BYTES:
        raise ValueError(f"invalid image size for {name}")
    with archive.open(info) as stream:
        payload = stream.read(MAX_PNG_BYTES + 1)
    if len(payload) > MAX_PNG_BYTES:
        raise ValueError(f"PNG exceeds review limit: {name}")
    with Image.open(BytesIO(payload)) as decoded:
        if decoded.format != "PNG" or decoded.size != (width, height):
            raise ValueError(f"wrong PNG contract for {name}")
        return decoded.convert("RGB")


def edge_energy(array: Any, np: Any) -> float:
    pixels = array.astype(np.float32)
    horizontal = np.abs(np.diff(pixels, axis=1)).mean() if pixels.shape[1] > 1 else 0.0
    vertical = np.abs(np.diff(pixels, axis=0)).mean() if pixels.shape[0] > 1 else 0.0
    return float((horizontal + vertical) / 2.0)


def review_bundle(bundle: Path, output_dir: Path) -> dict[str, Any]:
    np, Image, ImageDraw, ImageOps = _require_images()
    with zipfile.ZipFile(bundle, "r") as archive:
        if archive.namelist().count("manifest.json") != 1:
            raise ValueError("bundle must contain exactly one manifest.json")
        manifest_info = archive.getinfo("manifest.json")
        if manifest_info.file_size > 100_000:
            raise ValueError("manifest is too large")
        manifest = json.loads(archive.read("manifest.json"))
        samples = validate_manifest(manifest)
        expected_entries = {"manifest.json"}
        for sample in samples:
            expected_entries.update((sample["baseline"], sample["enhanced"]))
        names = archive.namelist()
        if len(names) != len(expected_entries) or set(names) != expected_entries:
            raise ValueError("bundle must contain only the declared protected A/B PNGs")
        width, height = int(manifest["output_width"]), int(manifest["output_height"])
        rows = []
        reports = []
        residual_previous = None
        off_previous = None
        previous_frame = None
        adjacent_proxy = []
        temporal_runs = []
        current_run = []
        scene_counts = {}
        thumbnail_width = 360
        thumbnail_height = max(1, round(height * thumbnail_width / width))
        for sample in samples:
            off = _read_rgb(archive, sample["baseline"], width, height, Image)
            on = _read_rgb(archive, sample["enhanced"], width, height, Image)
            off_np = np.asarray(off, dtype=np.int16)
            on_np = np.asarray(on, dtype=np.int16)
            residual = (on_np - off_np).astype(np.int16)
            abs_delta = np.abs(residual)
            pixel_delta = abs_delta.max(axis=2)
            clip_new = np.logical_and(
                np.logical_or(on_np <= 0, on_np >= 255),
                np.logical_and(off_np > 2, off_np < 253),
            )
            off_edges = edge_energy(off_np, np)
            on_edges = edge_energy(on_np, np)
            kind = sample.get("scene_kind", "legacy_fixed_frame")
            scene_counts[kind] = scene_counts.get(kind, 0) + 1
            if kind == "temporal_burst" and (
                not current_run or (sample["frame"] == current_run[-1] + 1 and
                                    sample.get("burst_index") == len(current_run))
            ):
                current_run.append(sample["frame"])
            else:
                if current_run:
                    temporal_runs.append(current_run)
                current_run = [sample["frame"]] if kind == "temporal_burst" and sample.get("burst_index") == 0 else []
            report = {
                "frame": sample["frame"],
                "scene_kind": kind,
                "pts_us": sample.get("pts_us"),
                "crop": sample["crop"],
                "mean_abs_rgb": round(float(abs_delta.mean()), 4),
                "max_abs_rgb": int(abs_delta.max()),
                "pixels_changed_gt2_percent": round(float((pixel_delta > 2).mean() * 100), 4),
                "edge_energy_off": round(off_edges, 4),
                "edge_energy_on": round(on_edges, 4),
                "edge_energy_ratio": round(on_edges / off_edges, 4) if off_edges > 1e-7 else None,
                "new_clipped_channel_percent": round(float(clip_new.mean() * 100), 4),
                "mostly_black_percent": round(float((off_np.max(axis=2) < 16).mean() * 100), 4),
            }
            if manifest.get("schema") == 2:
                report["scene_evidence"] = {
                    feature: sample.get(feature) for feature in
                    ("burst_index", "luma_mean", "contrast", "protected_overlap",
                     "protagonist_motion", "protected_count")
                }
            if previous_frame is not None and sample["frame"] == previous_frame + 1:
                # This is NOT motion-compensated: a high value may just be motion.
                residual_change = np.abs(residual.astype(np.int32) - residual_previous.astype(np.int32))
                # Compare on near-static pixels only. This is a screening proxy,
                # NOT motion-compensated temporal PSNR nor a proof of no flicker.
                stable_mask = (np.abs(off_np - off_previous).max(axis=2) <= 4)
                stable_coverage = float(stable_mask.mean())
                adjacent_proxy.append({
                    "frames": [previous_frame, sample["frame"]],
                    "uncompensated_residual_change_mean_abs": round(float(residual_change.mean()), 4),
                    "near_static_pixel_percent": round(100 * stable_coverage, 2),
                    "near_static_residual_change_mean_abs": (
                        round(float(residual_change[stable_mask].mean()), 4)
                        if stable_coverage >= 0.05 else None
                    ),
                    "note": "near_static_proxy_not_motion_compensated",
                })
            previous_frame = sample["frame"]
            residual_previous = residual
            off_previous = off_np
            reports.append(report)

            heat = np.clip(pixel_delta.astype(np.float32) * 4.0, 0, 255).astype(np.uint8)
            heat_image = Image.fromarray(heat, mode="L").convert("RGB")
            canvases = [off, on, heat_image]
            row = Image.new("RGB", (thumbnail_width * 3 + 32, thumbnail_height + 64), "#141414")
            draw = ImageDraw.Draw(row)
            for index, image in enumerate(canvases):
                # Use ASCII labels for compatibility with Pillow's default font.
                safe_label = ("OFF - Bilinear", "ON - Shader", "Difference x4")[index]
                x = 8 + index * (thumbnail_width + 8)
                thumb = image.resize((thumbnail_width, thumbnail_height), Image.Resampling.LANCZOS)
                row.paste(thumb, (x, 38))
                draw.text((x, 8), safe_label, fill="white")
            draw.text((8, thumbnail_height + 46), f"Frame {sample['frame']} / PTS {sample.get('pts_us', '')} us", fill="#cccccc")
            rows.append(row)

        if current_run:
            temporal_runs.append(current_run)
    output_dir.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (rows[0].width, sum(row.height for row in rows)), "#141414")
    top = 0
    for row in rows:
        sheet.paste(row, (0, top))
        top += row.height
    sheet_path = output_dir / "crop_clarity_ab_contact_sheet.png"
    sheet.save(sheet_path)
    summary = {
        "schema": manifest["schema"],
        "input": bundle.name,
        "job_id": manifest.get("job_id"),
        "output_size": [width, height],
        "strength_off": manifest.get("strength_off"),
        "strength_on": manifest.get("strength_on"),
        "same_decoded_frame_privacy_and_crop": True,
        "image_pairs": reports,
        "scene_counts": scene_counts,
        "scene_coverage": {
            "privacy_overlap": (scene_counts.get("privacy_overlap", 0) > 0),
            "high_contrast": (scene_counts.get("high_contrast", 0) > 0),
            "fast_motion_or_burst": (
                scene_counts.get("fast_motion", 0) > 0 or
                scene_counts.get("temporal_burst", 0) > 0
            ),
            "complete_five_frame_burst": any(len(run) >= 5 for run in temporal_runs),
        } if manifest["schema"] == 2 else None,
        "temporal_runs": temporal_runs,
        "expected_frames": manifest.get("expected_frames"),
        "missing_expected_frames": sorted(set(manifest.get("expected_frames") or []) - {sample["frame"] for sample in samples}),
        "adjacent_frame_proxy": adjacent_proxy,
        "quality_acceptance": "MANUAL_REVIEW_REQUIRED",
        "temporal_flicker_acceptance": "NOT_PROVEN_BY_UNCOMPENSATED_PROXY",
        "privacy_acceptance": "NOT_PROVEN_BY_PNG_DIFF_ALONE",
        "contact_sheet": sheet_path.name,
    }
    report_path = output_dir / "crop_clarity_ab_report.json"
    report_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = review_bundle(args.bundle, args.output_dir)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print(f"CROP_CLARITY_AB_REPORT=FAIL: {exc}")
        return 1
    print("CROP_CLARITY_AB_REPORT=" + json.dumps(result, sort_keys=True, ensure_ascii=False))
    print("CROP_CLARITY_AB_REPORT=PASS: generated for human review, not a quality verdict")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
