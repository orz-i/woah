#!/usr/bin/env python3
"""Evaluate sparse source-reviewed head envelopes independently of the detector.

Never changes a track or renders a sticker; approximate labels are not semantic
ground truth. Input frames are identified by the local fixture manifest hash
and their individual SHA-256s.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit(replay, fixture, labels, fixture_path):
    if sha(fixture_path) != labels["manifest_sha256"] or replay["fixture_sha256"] != labels["manifest_sha256"]:
        raise ValueError("Replay/label fixture mismatch")
    if replay["target_id"] != labels["target_id"]:
        raise ValueError("Replay/label selected ID mismatch")
    for label in labels["labels"]:
        f = fixture["frames"][label["index"]]
        if f["sha256"] != label["asset_sha256"] or sha(fixture_path.parent/f["asset"]) != label["asset_sha256"]:
            raise ValueError("Reviewed frame hash mismatch")
    result = {}
    for name in ("baseline", "candidate"):
        rows = []
        for label in labels["labels"]:
            i = label["index"]
            row = replay[name]["frames"][i]
            if row["index"] != i:
                raise ValueError("Replay frame order mismatch")
            l, top, r, bottom = label["head_rect"]
            xx, yy = np.meshgrid(np.arange(l, r)+.5, np.arange(top, bottom)+.5)
            covered = 0.0
            torso = False
            neighbors = [0.0 for _ in label.get("neighbor_rects", [])]
            if row["render"] is not None:
                x, y, rx, ry = row.get("sticker", row["render"])
                half = max(8, 1.02*max(rx, ry))
                covered = float(np.mean((np.abs(xx-x)<=half)&(np.abs(yy-y)<=half)))
                tx, ty = label["torso_probe"]
                torso = max(abs(tx-x), abs(ty-y)) <= half
                for j,(nl,nt,nr,nb) in enumerate(label.get("neighbor_rects",[])):
                    nx,ny=np.meshgrid(np.arange(nl,nr)+.5,np.arange(nt,nb)+.5)
                    neighbors[j]=float(np.mean((np.abs(nx-x)<=half)&(np.abs(ny-y)<=half)))
            rows.append(dict(index=i, head_coverage=covered, torso_probe_covered=torso,
                             neighbor_rect_overlaps=neighbors))
        result[name] = dict(samples=len(rows),
            full_head_envelopes=sum(r["head_coverage"]==1 for r in rows),
            min_head_coverage=min(r["head_coverage"] for r in rows),
            torso_probes_covered=sum(r["torso_probe_covered"] for r in rows),
            neighbor_rects_checked=sum(len(r["neighbor_rect_overlaps"]) for r in rows),
            max_neighbor_rect_overlap=max((v for r in rows for v in r["neighbor_rect_overlaps"]),default=None),
            rows=rows)
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--replay",type=Path,required=True)
    p.add_argument("--fixture",type=Path,required=True)
    p.add_argument("--labels",type=Path,required=True)
    p.add_argument("--case",required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    labels=json.loads(a.labels.read_text())["cases"][a.case]
    result=audit(json.loads(a.replay.read_text()),json.loads(a.fixture.read_text()),labels,a.fixture)
    out=dict(scope=__doc__,case=a.case,labels_sha256=sha(a.labels),replay_sha256=sha(a.replay),results=result)
    a.output.write_text(json.dumps(out,indent=2)+"\n")
    for name,r in result.items():
        print(name,json.dumps({k:v for k,v in r.items() if k!="rows"}))


if __name__=="__main__":
    main()
