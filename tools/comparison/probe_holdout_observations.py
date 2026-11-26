#!/usr/bin/env python3
"""Post-freeze host diagnosis of model recall on manually annotated points.

This is not native backend raw-tensor evidence. Optional fixed ROI is an
exploratory hypothesis test only; it uses a predeclared source-space crop,
never changes production tracking, and is not a new holdout acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

import cutout_m0 as m
from review_holdout import contour_metrics


def detect(lib, runner, frame, annotation, threshold, label_size, crop=None):
    h,w=frame.shape[:2]
    x,y,ww,hh=(0,0,w,h) if crop is None else crop
    if x<0 or y<0 or ww<=0 or hh<=0 or x+ww>w or y+hh>h:
        raise ValueError("Invalid fixed ROI")
    rgba=cv2.cvtColor(frame[y:y+hh,x:x+ww],cv2.COLOR_BGR2RGBA)
    ds,masks,t=m.infer(lib,runner,rgba,threshold)
    px,py=annotation["selected"]
    j=lib.frame_kernel_cutout_select(m.ptr(masks),masks.size,t,(px*w-x)/ww,(py*h-y)/hh)
    row={"rawPointCandidate":int(j),"rawPointConfidence":float(ds[j].confidence) if j>=0 else None}
    if "visibleContour" in annotation:
        lw,lh=label_size
        metrics=[]
        for k,mask in enumerate(masks):
            out=np.empty((hh,ww),np.uint8)
            assert lib.frame_kernel_cutout_alpha(m.ptr(mask),mask.size,t,ww,hh,m.ptr(out),out.size)==0
            alpha=np.zeros((h,w),np.uint8);alpha[y:y+hh,x:x+ww]=out
            metric=contour_metrics(alpha,annotation["visibleContour"],lw,lh)
            metrics.append((metric["iou"],k,metric["recall"],float(ds[k].confidence)))
        best=max(metrics,default=(0.,-1,0.,0.))
        row.update(bestRawContourIou=best[0],bestRawCandidate=best[1],
                   bestRawContourRecall=best[2],bestRawConfidence=best[3])
    return row


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",required=True,type=Path)
    p.add_argument("--media",required=True,type=Path)
    p.add_argument("--library",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--reference-roi",nargs=4,type=int,metavar=("X","Y","W","H"),
                   help="fixed source-pixel ROI for exploratory magnification, not an automatic tracker")
    args=p.parse_args()
    manifest=json.loads(args.manifest.read_text())
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    assert hashlib.sha256(model.read_bytes()).hexdigest()==manifest["modelSha256"]==m.MODEL_SHA
    lib=m.api(args.library);runner=Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    report={"frozenCommit":manifest["frozenCommit"],"hostOnly":True,
            "referenceRoi":args.reference_roi,"roles":{}}
    for role,src in manifest["sources"].items():
        path=args.media/src["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest()==src["testedMp4Sha256"]
        cap=cv2.VideoCapture(str(path))
        rows=[]
        try:
            for annotation in manifest["annotations"][role]:
                pts=annotation["ptsUs"]
                cap.set(cv2.CAP_PROP_POS_FRAMES,pts*manifest["cadence"]//1000000)
                ok,frame=cap.read();assert ok
                threshold=.25 if pts==manifest["anchorUs"] else .20
                row={"ptsUs":pts,"threshold":threshold,
                     "fullFrame":detect(lib,runner,frame,annotation,threshold,manifest["contourCoordinateSize"])}
                if role=="reference" and args.reference_roi:
                    row["fixedRoi"]=detect(lib,runner,frame,annotation,threshold,
                                           manifest["contourCoordinateSize"],args.reference_roi)
                rows.append(row)
                print(role,json.dumps(row),flush=True)
        finally:cap.release()
        report["roles"][role]=rows
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+"\n")


if __name__=="__main__":main()
