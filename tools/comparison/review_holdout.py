#!/usr/bin/env python3
"""Score independent human-reviewed landmarks and approximate visible contours.

The manifest must be frozen before inspecting model outputs. These are sparse
manual annotations, not a dense segmentation benchmark; a missing mask counts
as zero for a visible contour. Compare conditional boundary quality separately
from missing/incorrect identity. All evidence media stays local.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from review_real_dance import frames, assess, sheets, compare


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def contour_metrics(raw, polygon, width, height):
    truth = np.zeros((height, width), np.uint8)
    coords = np.asarray(polygon, np.int32)
    if coords.ndim != 2 or coords.shape[1] != 2 or len(coords) < 3 or np.any(coords < 0):
        raise ValueError("Invalid manual contour")
    if np.any(coords[:, 0] >= width) or np.any(coords[:, 1] >= height):
        raise ValueError("Contour outside source annotation grid")
    cv2.fillPoly(truth, [coords], 1)
    if raw is None:
        return dict(maskPresent=False, iou=0., precision=0., recall=0., boundaryF1=0.,
                    predictedArea=0, referenceArea=int(truth.sum()))
    pred = (cv2.resize(raw, (width, height), interpolation=cv2.INTER_LINEAR) >= 128).astype(np.uint8)
    hit = int((pred & truth).sum())
    p, r = int(pred.sum()), int(truth.sum())
    union = p + r - hit
    # A 4-pixel tolerance at the 640x360 manual labeling grid. It measures
    # agreement with an approximate visual contour, not subpixel edge quality.
    edge = lambda a: a - cv2.erode(a, np.ones((3, 3), np.uint8))
    pe, re = edge(pred), edge(truth)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9,9))
    bp = float((pe & cv2.dilate(re,kernel)).sum()/max(1,pe.sum()))
    br = float((re & cv2.dilate(pe,kernel)).sum()/max(1,re.sum()))
    return dict(maskPresent=True,iou=hit/max(1,union),precision=hit/max(1,p),
                recall=hit/max(1,r),boundaryF1=2*bp*br/max(1e-12,bp+br),
                predictedArea=p,referenceArea=r)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("evidence",type=Path)
    p.add_argument("--manifest",required=True,type=Path)
    p.add_argument("--media",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--baseline",type=Path,help="optional prior native alpha evidence for the same fixture/backend")
    args=p.parse_args()
    manifest=json.loads(args.manifest.read_text())
    duration=manifest["durationUs"];count=manifest["framesPerRole"]
    cadence=manifest["cadence"];w,h=manifest["contourCoordinateSize"]
    native=json.loads((args.evidence/"report.json").read_text())
    assert native["limitUs"]==duration and native["anchorUs"]==manifest["anchorUs"]
    result={"manifestSha256":sha(args.manifest),"frozenCommit":manifest["frozenCommit"],
            "native":native,"manualContoursApproximate":True,"roles":{}}
    if args.baseline:
        prior=json.loads((args.baseline/"report.json").read_text())
        assert prior["limitUs"]==duration and prior["anchorUs"]==manifest["anchorUs"]
        result["baselineNative"]=prior
    args.output.mkdir(parents=True,exist_ok=True)
    for role,source in manifest["sources"].items():
        video=args.media/source["file"]
        assert sha(video)==source["testedMp4Sha256"]
        annotations=manifest["annotations"][role]
        row={}
        records={}
        for backend in ("cpu","gpu"):
            path=args.evidence/f"{backend}-{role}.alpha"
            if not path.is_file():continue
            data=list(frames(path))
            assert len(data)==count and [p for p,_ in data]==[i*1000000//cadence for i in range(count)]
            records[backend]=data
            stats=assess(data,annotations,duration)
            lookup=dict(data)
            contour=[]
            for a in annotations:
                if "visibleContour" not in a:continue
                contour.append({"ptsUs":a["ptsUs"],**contour_metrics(lookup[a["ptsUs"]],a["visibleContour"],w,h)})
            stats["contours"]=contour
            stats["meanContourIouAll"]=float(np.mean([x["iou"] for x in contour]))
            stats["meanBoundaryF1All"]=float(np.mean([x["boundaryF1"] for x in contour]))
            visible=[x for x in contour if x["maskPresent"]]
            stats["meanContourIouVisible"]=float(np.mean([x["iou"] for x in visible])) if visible else None
            stats["meanBoundaryF1Visible"]=float(np.mean([x["boundaryF1"] for x in visible])) if visible else None
            row[backend]=stats
            if args.baseline:
                old=list(frames(args.baseline/f"{backend}-{role}.alpha"))
                delta=compare(old,data)
                delta["candidateMissingVsBaseline"]=delta.pop("gpuMissingVsCpu")
                delta["candidateExtraVsBaseline"]=delta.pop("gpuExtraVsCpu")
                stats["baselineComparison"]=delta
            sheets(video,data,args.output,role,backend,annotations,duration)
            print(role,backend,json.dumps({k:v for k,v in stats.items() if k not in ("checkpoints","missingSpansUs","contours")}),flush=True)
            print("contours",[(x["ptsUs"],round(x["iou"],3),round(x["boundaryF1"],3)) for x in contour],flush=True)
        if len(records)==2:row["backendComparison"]=compare(records["cpu"],records["gpu"])
        result["roles"][role]=row
    (args.output/"review.json").write_text(json.dumps(result,indent=2)+"\n")


if __name__=="__main__":main()
