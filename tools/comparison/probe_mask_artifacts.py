#!/usr/bin/env python3
"""Diagnostic only: quantify small disconnected components and projection error.

No production masks are modified. Approximate source-only silhouettes do not
constitute dense ground truth. Compare the same native raw selected mask at a
dense output grid with the actual alpha only when full-only and actual native
outputs have the same current mask; ROI-filled frames are excluded.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

import cutout_m0 as m
from replay_native_observations import read_observations
from review_holdout import contour_metrics
from review_real_dance import frames


def project(lib, raw, transform, width, height):
    out = np.empty((height, width), np.uint8)
    if lib.frame_kernel_cutout_alpha(
        m.ptr(raw), raw.size, transform, width, height, m.ptr(out), out.size
    ) != 0:
        raise ValueError("Cannot project raw mask")
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest", required=True, type=Path)
    p.add_argument("--evidence", required=True, type=Path)
    p.add_argument("--replay", required=True, type=Path)
    p.add_argument("--library", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    a = p.parse_args()
    manifest = json.loads(a.manifest.read_text())
    trace = json.loads(a.replay.read_text())
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    if (trace["manifestSha256"] != sha(a.manifest) or
            trace["baselineLibrarySha256"] != sha(a.library)):
        raise ValueError("Replay manifest or baseline library differs")
    lib = m.api(a.library.resolve())
    w, h = manifest["contourCoordinateSize"]
    report = {"diagnosticOnly": True, "roles": {}}
    for role in manifest["sources"]:
        result = {}
        labels = {x["ptsUs"]: x["visibleContour"] for x in manifest["annotations"][role] if "visibleContour" in x}
        for backend in ("cpu", "gpu"):
            prefix = a.evidence / f"{backend}-{role}"
            data = list(frames(prefix.with_suffix(".alpha")))
            observation_path = prefix.with_suffix(".observations.gz")
            row = trace["roles"][role][backend]
            if row["observationSha256"] != sha(observation_path):
                raise ValueError("Observation stream differs from the replay")
            obs = read_observations(observation_path, manifest)
            selected = {x["ptsUs"]: x["selected"] for x in row["baseline"]["trace"]}
            assert [p for p, _ in data] == [o[0] for o in obs]
            affected = pixels = removed = 0
            samples = []
            for (pts, raw), (_, _, masks, transform, _) in zip(data, obs):
                if raw is None:
                    continue
                _, ids, stats, _ = cv2.connectedComponentsWithStats((raw >= 128).astype(np.uint8), connectivity=8)
                sizes = stats[1:, cv2.CC_STAT_AREA]
                total = int(sizes.sum())
                pixels += total
                tiny = np.flatnonzero(sizes < max(1, total * .01)) + 1
                island = np.isin(ids, tiny)
                area = int(island.sum())
                affected += int(area > 0)
                removed += area
                if pts not in labels:
                    continue
                polygon = labels[pts]
                old = contour_metrics(raw, polygon, w, h)
                cleaned = raw.copy()
                cleaned[island] = 0
                new = contour_metrics(cleaned, polygon, w, h)
                sample = {"ptsUs": pts, "actualIou": old["iou"], "actualBoundaryF1": old["boundaryF1"],
                          "tinyRemovalIouDelta": new["iou"] - old["iou"],
                          "tinyRemovalF1Delta": new["boundaryF1"] - old["boundaryF1"]}
                row = selected[pts]
                if row is not None:
                    mask = masks[row["index"]]
                    same_grid = project(lib, mask, transform, raw.shape[1], raw.shape[0])
                    if np.array_equal(raw, same_grid):
                        dense = project(lib, mask, transform, w, h)
                        scores = contour_metrics(dense, polygon, w, h)
                        sample["denseSameMaskIou"] = scores["iou"]
                        sample["denseSameMaskBoundaryF1"] = scores["boundaryF1"]
                samples.append(sample)
            result[backend] = {"affectedFrames": affected, "removedThresholdPixels": removed,
                               "removedFraction": removed / max(1, pixels), "samples": samples}
            print(role, backend, json.dumps(result[backend]), flush=True)
        report["roles"][role] = result
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
