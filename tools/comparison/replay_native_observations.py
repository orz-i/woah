#!/usr/bin/env python3
"""Replay exact isolated Android QA detector/appearance records on two FrameKernel builds.

Full-frame association only: ROI is deliberately not replayed because the stream
does not store raw source pixels or local inference inputs. The original native
alpha output is separate evidence. This is an association diagnostic, not an
end-to-end device performance measurement.
"""
from __future__ import annotations

import argparse
import ctypes as c
import gzip
import hashlib
import json
from pathlib import Path
import struct

import numpy as np

import cutout_m0 as m
from compare_dance_identity import bind
from review_real_dance import assess
from review_holdout import contour_metrics

BINS = 29
MAX_RECORD = 8 * 1024 * 1024
MAX_STREAM = 512 * 1024 * 1024


def read_exact(stream, n):
    data = stream.read(n)
    if len(data) != n:
        raise ValueError("Truncated observation stream")
    return data


def read_observations(path, manifest):
    records = {}
    total = 0
    with gzip.open(path, "rb") as stream:
        magic, count, bins = struct.unpack(">III", read_exact(stream, 12))
        if magic != 0x574F4431 or bins != BINS or count != manifest["framesPerRole"]:
            raise ValueError("Unexpected observation stream format/count")
        for _ in range(count):
            n, = struct.unpack(">I", read_exact(stream, 4))
            total += n
            if not 40 <= n <= MAX_RECORD or total > MAX_STREAM:
                raise ValueError("Observation exceeds bounds")
            data = read_exact(stream, n)
            pts, scale, left, top, sw, sh, inp, proto, columns = struct.unpack_from(">q7fI", data)
            if columns % 5 or columns > 1280 or proto != 160 or inp != 640:
                raise ValueError("Invalid observation layout")
            rows = columns // 5
            if len(data) != 40 + columns * 4 + rows * (160 * 160 + BINS * 4):
                raise ValueError("Invalid observation length")
            box = np.frombuffer(data, ">f4", columns, 40).astype(np.float32).reshape(rows, 5)
            masks = np.frombuffer(data, np.uint8, rows * 160 * 160, 40 + columns * 4).reshape(rows,160,160)
            f = np.frombuffer(data, ">f4", rows * BINS, 40 + columns * 4 + masks.size).astype(np.float32).reshape(rows,BINS)
            t = m.Transform(scale,left,top,int(sw),int(sh),int(inp),int(proto))
            ds = [m.Detection(m.Rect(*b[:4]), b[4]) for b in box]
            if pts in records:
                raise ValueError("Duplicate PTS")
            records[pts] = (pts, ds, masks, t, f)
        if stream.read(1):
            raise ValueError("Trailing observation bytes")
    expected = [i * 1_000_000 // manifest["cadence"] for i in range(count)]
    if sorted(records) != expected:
        raise ValueError("PTS stream differs from fixture index")
    return [records[p] for p in expected]


def selected_records(lib, records, manifest, role):
    bind(lib)
    anchor = manifest["anchorUs"]
    index = next(i for i, row in enumerate(records) if row[0] == anchor)
    _, ds, masks, t, features = records[index]
    seed = lib.frame_kernel_cutout_select(m.ptr(masks), masks.size, t, *manifest["sources"][role]["seed"])
    if seed < 0:
        raise ValueError("Seed not selectable")
    positive = features[seed]
    negative = np.delete(features, seed, axis=0)
    result = {}
    details = {}
    def describe(j, ds, t, f):
        if j < 0:
            return None
        b = ds[j].bounds
        extent = max(b.right-b.left, b.bottom-b.top)
        return {"index":j,"bounds":[b.left/t.source_width,b.top/t.source_height,b.right/t.source_width,b.bottom/t.source_height],
                "extent":extent,"confidence":float(ds[j].confidence),
                "anchorSimilarity":float(np.sqrt(positive*f[j]).sum()),
                "maxInitialCompetitorSimilarity":float(np.sqrt(negative*f[j]).sum(axis=1).max()) if len(negative) else None}
    checks = {x["ptsUs"]:x for x in manifest["annotations"][role]}
    for order in (range(index,len(records)), range(index,-1,-1)):
        handle = lib.frame_kernel_dance_cutout_create()
        if not handle:
            raise ValueError("Cannot create tracker")
        try:
            for i in order:
                pts,ds,masks,t,f = records[i]
                rows=(m.Detection*len(ds))(*ds)
                j=lib.frame_kernel_dance_cutout_step(handle,pts,seed if i==index else -1,
                    rows,len(ds),m.ptr(masks),masks.size,t,m.ptr(f,m.PF),f.size)
                if j < -1:
                    raise ValueError(f"Invalid selected index at PTS {pts}")
                alpha=None
                if j >= 0:
                    w=max(1,round(t.source_width*384/max(t.source_width,t.source_height)))
                    h=max(1,round(t.source_height*384/max(t.source_width,t.source_height)))
                    alpha=np.empty((h,w),np.uint8)
                    if lib.frame_kernel_cutout_alpha(m.ptr(masks[j]),masks[j].size,t,w,h,m.ptr(alpha),alpha.size)!=0:
                        raise ValueError("Cannot project current mask")
                    if not alpha.any():alpha=None
                result[pts]=(pts,alpha)
                details[pts]={"ptsUs":pts,"selected":describe(j,ds,t,f)}
                if pts in checks:
                    point=checks[pts]["selected"]
                    raw=lib.frame_kernel_cutout_select(m.ptr(masks),masks.size,t,*point)
                    details[pts]["rawAtAnnotatedPoint"]=describe(raw,ds,t,f)
        finally:
            if lib.frame_kernel_dance_cutout_destroy(handle)!=0:
                raise ValueError("Cannot close tracker")
    scored=assess([result[p] for p in sorted(result)],manifest["annotations"][role],manifest["durationUs"])
    if "contourCoordinateSize" in manifest:
        w,h=manifest["contourCoordinateSize"]
        scored["contours"]=[{"ptsUs":a["ptsUs"],**contour_metrics(result[a["ptsUs"]][1],a["visibleContour"],w,h)}
             for a in manifest["annotations"][role] if "visibleContour" in a]
    scored["trace"]=[details[p] for p in sorted(details)]
    return scored


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence",type=Path,required=True)
    parser.add_argument("--manifest",type=Path,required=True)
    parser.add_argument("--baseline-library",type=Path,required=True)
    parser.add_argument("--library",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    manifest=json.loads(args.manifest.read_text())
    libs={k:m.api(p.resolve()) for k,p in (("baseline",args.baseline_library),("candidate",args.library))}
    report={"fullFrameOnly":True,
            "manifestSha256":hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
            "baselineLibrarySha256":hashlib.sha256(args.baseline_library.read_bytes()).hexdigest(),
            "candidateLibrarySha256":hashlib.sha256(args.library.read_bytes()).hexdigest(),"roles":{}}
    for role in manifest["sources"]:
        report["roles"][role]={}
        for backend in ("cpu","gpu"):
            path=args.evidence/f"{backend}-{role}.observations.gz"
            if not path.is_file():continue
            records=read_observations(path,manifest)
            result={k:selected_records(lib,records,manifest,role) for k,lib in libs.items()}
            result["observationSha256"]=hashlib.sha256(path.read_bytes()).hexdigest()
            report["roles"][role][backend]=result
            print(role,backend,{k:{field:row[field] for field in ("visible","checkpointHits","distractorHits","longestMissingUs")} for k,row in result.items() if k in libs},flush=True)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+"\n")


if __name__=="__main__":
    main()
