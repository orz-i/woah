#!/usr/bin/env python3
"""Local evidence review for the opt-in real-dance Android instrumented run.

Uses actual selected-alpha records from the two native backends. The
point annotations are sparse independent visual checks, not ground truth
segmentations. No inference, identity matching, or promotion decision is
performed here. Inputs and rendered contact sheets stay local.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct

import cv2
import numpy as np

MAGIC = 0x574F4131


def frames(path):
    with path.open("rb") as f:
        magic, count = struct.unpack(">II", f.read(8))
        if magic != MAGIC or not 1 <= count <= 1800:
            raise ValueError("Invalid alpha evidence header")
        prior = -1
        for _ in range(count):
            pts, w, h = struct.unpack(">qII", f.read(16))
            if pts <= prior or ((w, h) != (0, 0) and not (1 <= w <= 256 and 1 <= h <= 256)):
                raise ValueError("Invalid selected-alpha record")
            prior = pts
            n = w * h
            data = f.read(n)
            if len(data) != n:
                raise ValueError("Truncated alpha record")
            yield pts, np.frombuffer(data, np.uint8).reshape(h, w) if n else None
        if f.read(1):
            raise ValueError("Trailing bytes in alpha evidence")


def missing_spans(records, end):
    spans = []
    start = None
    for pts, mask in records:
        if mask is None and start is None:
            start = pts
        elif mask is not None and start is not None:
            spans.append([start, pts])
            start = None
    if start is not None:
        spans.append([start, end])
    return spans


def point_check(mask, point):
    if mask is None:
        return False
    h, w = mask.shape
    x, y = point
    ix, iy = min(w-1, int(x*w)), min(h-1, int(y*h))
    # A sparse point check is deliberately tolerant of the 256-pixel alpha
    # grid; it does not assert full contour or even complete limb coverage.
    return bool(np.any(mask[max(0, iy-1):iy+2, max(0, ix-1):ix+2] >= 128))


def assess(records, annotations, end):
    lookup = dict(records)
    points = []
    for a in annotations:
        pts = a["ptsUs"]
        if pts not in lookup:
            raise ValueError(f"Manual checkpoint missing source PTS: {pts}")
        mask = lookup[pts]
        points.append({
            "ptsUs": pts,
            "selectedPointHit": point_check(mask, a["selected"]),
            "distractorHit": point_check(mask, a["distractor"]) if "distractor" in a else None,
            "visibleMask": mask is not None,
        })
    spans = missing_spans(records, end)
    return {
        "frames": len(records),
        "visible": sum(a is not None for _, a in records),
        "missingSpanCount": len(spans),
        "longestMissingUs": max((b-a for a,b in spans), default=0),
        "missingSpansUs": spans,
        "checkpointHits": sum(p["selectedPointHit"] for p in points),
        "checkpointCount": len(points),
        "distractorHits": sum(p["distractorHit"] is True for p in points),
        "distractorChecks": sum(p["distractorHit"] is not None for p in points),
        "checkpoints": points,
    }


def compare(cpu, gpu):
    if len(cpu) != len(gpu):
        raise ValueError("CPU/GPU sample count mismatch")
    ious, maes = [], []
    missing, extra, exact = 0, 0, 0
    for (p, a), (q, b) in zip(cpu, gpu):
        if p != q:
            raise ValueError("CPU/GPU PTS mismatch")
        if a is None:
            extra += b is not None
            continue
        if b is None:
            missing += 1
            continue
        if a.shape != b.shape:
            raise ValueError("Alpha grid mismatch")
        ma, mb = a >= 128, b >= 128
        union = ma | mb
        n = union.sum()
        ious.append(float((ma & mb).sum()/n) if n else 1.)
        maes.append(float(np.abs(a.astype(np.int16)-b.astype(np.int16))[union].mean()) if n else 0.)
        exact += np.array_equal(a,b)
    return {
        "pairedVisible": len(ious), "gpuMissingVsCpu": missing, "gpuExtraVsCpu": extra,
        "exactAlphaFrames": exact, "minIou": min(ious, default=None),
        "meanIou": float(np.mean(ious)) if ious else None,
        "iouBelow90pct": sum(x < .90 for x in ious),
        "foregroundAlphaMae": float(np.mean(maes)) if maes else None,
    }


def sheets(source, records, out, role, backend, annotations, end):
    lookup = dict(records)
    checkpoints = {a["ptsUs"]: a for a in annotations}
    times = sorted(set(range(0,end,2_000_000)) | set(checkpoints))
    cap = cv2.VideoCapture(str(source))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not np.isfinite(fps) or fps <= 0:
        raise ValueError("No video cadence")
    for page, start in enumerate(range(0,len(times),9)):
        sheet = np.zeros((3*192, 3*324, 3), np.uint8)
        for i, pts in enumerate(times[start:start+9]):
            if pts not in lookup:
                raise ValueError(f"Requested checkpoint not in source PTS: {pts}")
            cap.set(cv2.CAP_PROP_POS_FRAMES, round(pts*fps/1e6))
            ok, frame = cap.read()
            if not ok:
                raise ValueError("Cannot decode visual checkpoint")
            mask = lookup[pts]
            if mask is not None:
                alpha = cv2.resize(mask, (frame.shape[1],frame.shape[0]), interpolation=cv2.INTER_LINEAR)/255.
                color = np.zeros_like(frame)
                color[:,:,1] = 255
                a = alpha[:,:,None]*.42
                frame = (frame*(1-a)+color*a).astype(np.uint8)
                outline = cv2.resize((mask>=128).astype(np.uint8), (frame.shape[1],frame.shape[0]), interpolation=cv2.INTER_NEAREST)
                contours,_ = cv2.findContours(outline,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
                cv2.drawContours(frame,contours,-1,(30,255,30),1)
            if pts in checkpoints:
                for label,color in (("selected",(255,255,0)),("distractor",(0,0,255))):
                    if label in checkpoints[pts]:
                        x,y=checkpoints[pts][label]
                        cv2.circle(frame,(round(x*frame.shape[1]),round(y*frame.shape[0])),5,color,2)
            y,x=divmod(i,3)
            sheet[y*192:y*192+180,x*324:x*324+320]=cv2.resize(frame,(320,180))
            text=f"{pts/1e6:g}s {'MASK' if mask is not None else 'MISSING'}"
            cv2.putText(sheet,text,(x*324+4,y*192+190),cv2.FONT_HERSHEY_SIMPLEX,.4,(255,255,255),1,cv2.LINE_AA)
        cv2.imwrite(str(out/f"{role}-{backend}-{page}.jpg"),sheet,[cv2.IMWRITE_JPEG_QUALITY,55])
    cap.release()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("evidence",type=Path)
    p.add_argument("--reference",required=True,type=Path)
    p.add_argument("--target",required=True,type=Path)
    p.add_argument("--annotations",type=Path)
    p.add_argument("--output",required=True,type=Path)
    args=p.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    native=json.loads((args.evidence/"report.json").read_text())
    end=int(native["limitUs"])
    annotation_file=json.loads(args.annotations.read_text()) if args.annotations else {}
    annotations=annotation_file.get("annotations",annotation_file)
    result={"native":native,"roles":{},"manualPointsAreNotGroundTruth":True}
    for role,source in (("reference",args.reference),("target",args.target)):
        samples=annotations.get(role,[])
        available={}
        for backend in ("cpu","gpu"):
            path=args.evidence/f"{backend}-{role}.alpha"
            if not path.is_file():
                continue
            records=list(frames(path))
            available[backend]=records
            result["roles"].setdefault(role,{})[backend]=assess(records,samples,end)
            sheets(source,records,args.output,role,backend,samples,end)
        if "cpu" in available and "gpu" in available:
            result["roles"][role]["backendComparison"]=compare(available["cpu"],available["gpu"])
    (args.output/"review.json").write_text(json.dumps(result,indent=2)+"\n")
    for role,values in result["roles"].items():
        for backend,stats in values.items():
            print(role,backend,json.dumps({k:v for k,v in stats.items() if k not in ("checkpoints","missingSpansUs")}))
            if "missingSpansUs" in stats:
                print("missing spans",stats["missingSpansUs"][:20])


if __name__=="__main__":
    main()
