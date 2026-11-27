#!/usr/bin/env python3
"""Audit-only conservative source envelopes for the tracked affine fixture.

The coarse manually reviewed rectangles describe the visible target head and
neighbor head in the source JPEG. Registration uses source images only; no
detector or rendered region can choose or move the audit labels. This is a
geometric pixel envelope check, not semantic face ground truth or native export.
"""
import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "testdata/face_dynamic_smoke/dynamic_manifest.json"
SOURCE = ROOT / "testdata/face_export_smoke/person3_frame.jpg"
# Approximate, deliberately inclusive hand-reviewed envelopes, in the base
# image source coordinates. The very blurry source does not support precise
# face-skin or hairstyle segmentation.
TARGET = [[330, 306], [465, 306], [465, 465], [330, 465]]
NEIGHBOR = [[0, 398], [90, 398], [90, 510], [0, 510]]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def register():
    fixture = json.loads(FIXTURE.read_text())
    if sha(SOURCE) != fixture["source_sha256"]:
        raise ValueError("Source image hash mismatch")
    source = cv2.imread(str(SOURCE), cv2.IMREAD_GRAYSCALE)
    if source is None or source.shape != (fixture["height"], fixture["width"]):
        raise ValueError("Invalid source image")
    last = np.eye(2, 3, dtype=np.float32)
    result = []
    for frame in fixture["frames"]:
        path = FIXTURE.parent / frame["asset"]
        if sha(path) != frame["sha256"]:
            raise ValueError(f"Fixture hash mismatch: {path}")
        current = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        score, affine = cv2.findTransformECC(
            source, current, last.copy(), cv2.MOTION_AFFINE,
            (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 80, 1e-6),
            None, 5,
        )
        last = affine
        actual = affine @ np.array([*fixture["base_head"], 1])
        error = float(np.linalg.norm(actual - [frame["head_x"], frame["head_y"]]))
        if score < .99 or error > 1.0:
            raise ValueError(f"Independent affine registration failed: {frame['index']}")
        masks = []
        for polygon in (TARGET, NEIGHBOR):
            points = np.asarray(polygon) @ affine[:, :2].T + affine[:, 2]
            mask = np.zeros(source.shape, np.uint8)
            cv2.fillConvexPoly(mask, np.round(points).astype(np.int32), 1)
            yy, xx = np.nonzero(mask)
            masks.append((xx.astype(float) + .5, yy.astype(float) + .5))
        result.append((frame["index"], float(score), error, masks))
    return result


def audit(report, registered):
    audited = {}
    for key in ("baseline", "size_only", "candidate"):
        if key not in report:
            continue
        rows = []
        for row, (index, score, registration_error, masks) in zip(
            report[key]["frames"], registered, strict=True
        ):
            if row["index"] != index:
                raise ValueError("Frame index mismatch")
            data = dict(index=index, registration_correlation=score,
                        registration_head_error_px=registration_error)
            for name, (xx, yy) in zip(("target", "neighbor"), masks, strict=True):
                if not len(xx):
                    data[name + "_square"] = None
                    data[name + "_ellipse"] = None
                    continue
                if row["render"] is None:
                    square = ellipse = 0.0
                else:
                    x, y, rx, ry = row["render"]
                    half = max(8, 1.02 * max(rx, ry))
                    square = float(np.mean((np.abs(xx-x) <= half) & (np.abs(yy-y) <= half)))
                    ellipse = float(np.mean(((xx-x)/rx)**2 + ((yy-y)/ry)**2 <= 1))
                data[name + "_square"] = square
                data[name + "_ellipse"] = ellipse
            rows.append(data)
        def values(field):
            return [row[field] for row in rows if row[field] is not None]
        audited[key] = dict(
            min_target_square=min(values("target_square")),
            min_target_ellipse=min(values("target_ellipse")),
            target_square_full_frames=sum(r["target_square"] == 1 for r in rows),
            max_neighbor_square=max(values("neighbor_square"), default=None),
            neighbor_square_overlap_frames=[r["index"] for r in rows if (r["neighbor_square"] or 0)>0],
            frames=rows,
        )
    return audited


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--replay", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    r = json.loads(args.replay.read_text())
    output = dict(scope=__doc__, base_sha256=sha(SOURCE), manifest_sha256=sha(FIXTURE),
                  replay_sha256=sha(args.replay), target_source_polygon=TARGET,
                  neighbor_source_polygon=NEIGHBOR, results=audit(r, register()))
    args.output.write_text(json.dumps(output, indent=2)+"\n")
    for name, result in output["results"].items():
        print(name, json.dumps({k: v for k, v in result.items() if k != "frames"}))


if __name__ == "__main__":
    main()
