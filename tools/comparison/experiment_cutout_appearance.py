#!/usr/bin/env python3
"""Host-only cutout association experiment, NOT a mobile runtime implementation.

Compare the current FrameKernel cutout tracker with a selected-instance
appearance/short-term motion association on identical real detector outputs.
No face features, identity labels, or learned embeddings are used. Results
are QA research, not a ground-truth quality score or a backend promotion.
"""
from __future__ import annotations

import argparse
import ctypes as c
import hashlib
import json
from pathlib import Path
import time

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

import cutout_m0 as m
from review_real_dance import assess, compare, sheets


def box(d):
    b=d.bounds
    return np.array([b.left,b.top,b.right,b.bottom],np.float32)


def descriptor(rgb, mask):
    # Absolute value/saturation distribution distinguishes black garments from
    # light shirts; hue is used only for non-neutral pixels.
    pix=rgb[mask>=160]
    if len(pix)<12:
        pix=rgb[mask>=128]
    if len(pix)<8:
        return None
    hsv=cv2.cvtColor(pix.reshape(-1,1,3),cv2.COLOR_RGB2HSV).reshape(-1,3)
    v,s,h=hsv[:,2],hsv[:,1],hsv[:,0]
    bins=np.where(v<48,0,np.where(s<45,1+np.minimum(v//64,3),5+np.minimum(h//23,7)*3+np.minimum(v//86,2)))
    hist=np.bincount(bins,minlength=29).astype(np.float32)
    hist/=hist.sum()
    return hist


def observations(lib, runner, source, frames, anchor):
    for ordinal,(pts,rgba) in enumerate(m.source_frames(lib,source,frames)):
        det,masks,t=m.infer(lib,runner,rgba,.25 if ordinal==anchor else .20)
        # Same top-down letterbox geometry as the decoded prototype mask.
        h,w=rgba.shape[:2]
        model=cv2.warpAffine(rgba[:,:,:3],np.array([[t.scale/4,0,t.pad_left/4],[0,t.scale/4,t.pad_top/4]],np.float32),
            (160,160),flags=cv2.INTER_AREA,borderMode=cv2.BORDER_CONSTANT,borderValue=(114,114,114))
        yield pts,det,masks,t,[descriptor(model,x) for x in masks]


def similarity(a,b):
    return float(np.sqrt(a*b).sum()) if a is not None and b is not None else 0.


def center(b):
    return (b[:2]+b[2:])/2


def iou(a,b):
    lo=np.maximum(a[:2],b[:2]);hi=np.minimum(a[2:],b[2:])
    inter=float(np.maximum(hi-lo,0).prod())
    return inter/max(1.,float((a[2:]-a[:2]).prod()+(b[2:]-b[:2]).prod()-inter))


def mask_iou(a,b):
    a,b=a>=128,b>=128
    return float((a&b).sum()/max(1,(a|b).sum()))


class AppearanceLane:
    """Conservative local prototype. Long-gap reentry requires both a strong
    color match and unique local geometry, not a stale box alone."""
    def __init__(self,o,j):
        self.pts=o[0]
        self.box=box(o[1][j])
        self.mask=o[2][j].copy()
        self.seed=o[4][j]
        self.recent=self.seed.copy() if self.seed is not None else None
        self.velocity=np.zeros(2,np.float32)
        self.last=o[0]

    def step(self,o):
        pts,det,masks,t,descriptors=o
        gap=abs(pts-self.last)/1e6
        if gap<=0:return -1
        if self.seed is None:return -1
        b=self.box
        wh=b[2:]-b[:2]
        scale=max(16.,float(max(wh)))
        dt=min(gap,.25)
        pred=b+np.tile(self.velocity*dt,2)
        candidates=[]
        for j,d in enumerate(det):
            f=descriptors[j]
            if f is None or d.confidence<.20:continue
            bb=box(d)
            app_seed=similarity(self.seed,f)
            app_recent=similarity(self.recent,f)
            app=.35*app_seed+.65*app_recent
            dist=float(np.linalg.norm(center(bb)-center(pred)))/scale
            geom=max(iou(b,bb),iou(pred,bb),mask_iou(self.mask,masks[j]))
            # Don't authorize identity from a color match alone across the scene.
            if gap<=.16:
                if app<.66 or dist>1.0 or (geom<.06 and dist>.42):continue
                score=.54*app+.28*geom+.18*max(0,1-dist)
            else:
                if d.confidence<.25 or app_seed<.66 or app<.76 or dist>min(1.0,.45+gap*.12):continue
                score=.67*app+.16*geom+.17*max(0,1-dist)
            candidates.append((score,j,app,geom,dist))
        if not candidates:return -1
        candidates.sort(reverse=True)
        score,j,app,geom,dist=candidates[0]
        # Ambiguity rejects a current observation rather than using a retained mask.
        if len(candidates)>1 and score-candidates[1][0]<.045:return -1
        new=box(det[j]);velocity=(center(new)-center(b))/max(gap,.04)
        self.velocity=.55*self.velocity+.45*np.clip(velocity,-4*scale,4*scale)
        # Only close, high-confidence observations can adapt the template.
        if gap<=.12 and app>.82 and geom>.30 and det[j].confidence>=.25:
            self.recent=.94*self.recent+.06*descriptors[j]
        self.box=new;self.mask=masks[j].copy();self.last=pts
        return j


def alpha(lib,o,j):
    if j<0:return None
    pts,det,masks,t,_=o
    h=max(1,round(t.source_height*256/max(t.source_width,t.source_height)))
    w=max(1,round(t.source_width*256/max(t.source_width,t.source_height)))
    a=np.empty((h,w),np.uint8)
    assert lib.frame_kernel_cutout_alpha(m.ptr(masks[j]),masks[j].size,t,w,h,m.ptr(a),a.size)==0
    return a if np.any(a) else None


def evaluate(lib,runner,source,frames,anchor,touch):
    prefix=[]
    baseline,experimental=[],[]
    handles=[lib.frame_kernel_tracking_create(lib.frame_kernel_tracking_defaults()) for _ in range(2)]
    def current(handle,o,seed=-1):
        pts,ds,masks,t,_=o
        d=(m.Detection*len(ds))(*ds)
        return lib.frame_kernel_cutout_track_step(handle,pts,seed,d,len(ds),m.ptr(masks),masks.size,t)
    def append(handle,lane,o,seed=-1):
        idx=current(handle,o,seed)
        assert idx>=-1
        baseline.append((o[0],alpha(lib,o,idx)))
        chosen=seed if seed>=0 else lane.step(o)
        experimental.append((o[0],alpha(lib,o,chosen)))
    try:
        for ordinal,o in enumerate(observations(lib,runner,source,frames,anchor)):
            if ordinal<anchor:
                prefix.append(o);continue
            if ordinal==anchor:
                chosen=lib.frame_kernel_cutout_select(m.ptr(o[2]),o[2].size,o[3],*touch)
                if chosen<0:raise ValueError("Ambiguous seed")
                f,b=AppearanceLane(o,chosen),AppearanceLane(o,chosen)
                assert current(handles[1],o,chosen)==chosen
                append(handles[0],f,o,chosen)
                for previous in reversed(prefix):append(handles[1],b,previous)
                prefix.clear()
            else:append(handles[0],f,o)
    finally:
        for handle in handles:lib.frame_kernel_tracking_destroy(handle)
    return sorted(baseline,key=lambda x:x[0]),sorted(experimental,key=lambda x:x[0])


def probe_selected_observations(lib, runner, source, annotations):
    """Check detector availability independently of identity association."""
    cap=cv2.VideoCapture(str(source))
    fps=cap.get(cv2.CAP_PROP_FPS)
    rows=[]
    try:
        for a in annotations:
            pts=a["ptsUs"]
            cap.set(cv2.CAP_PROP_POS_FRAMES,round(pts*fps/1e6))
            ok,frame=cap.read()
            if not ok:raise ValueError("Cannot decode checkpoint")
            detections,masks,t=m.infer(lib,runner,cv2.cvtColor(frame,cv2.COLOR_BGR2RGBA),.25)
            selected=lib.frame_kernel_cutout_select(m.ptr(masks),masks.size,t,*a["selected"])
            other=lib.frame_kernel_cutout_select(m.ptr(masks),masks.size,t,*a["distractor"]) if "distractor" in a else -1
            rows.append({"ptsUs":pts,"selectedCandidate":selected,
                "selectedConfidence":float(detections[selected].confidence) if selected>=0 else None,
                "distractorCandidate":other})
    finally:cap.release()
    return rows


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--reference",required=True,type=Path);p.add_argument("--target",required=True,type=Path)
    p.add_argument("--annotations",required=True,type=Path);p.add_argument("--output",required=True,type=Path)
    p.add_argument("--library",required=True,type=Path)
    p.add_argument("--frames",type=int,default=750);p.add_argument("--anchor",type=int,default=75)
    p.add_argument("--probe-only",action="store_true",help="only check raw detector availability at manual checkpoints")
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    assert hashlib.sha256(model.read_bytes()).hexdigest()==m.MODEL_SHA
    lib=m.api(a.library);runner=Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    annotation_file=json.loads(a.annotations.read_text())
    annotations=annotation_file.get("annotations",annotation_file);report={}
    end=a.frames*40000
    if a.probe_only:
        for role,path in (("reference",a.reference),("target",a.target)):
            report[role]=probe_selected_observations(lib,runner,path,annotations[role])
        (a.output/"observations.json").write_text(json.dumps(report,indent=2)+"\n")
        print(json.dumps(report,indent=2))
        return
    for role,path,point in [("reference",a.reference,(.42,.29)),("target",a.target,(.46,.56))]:
        start=time.monotonic()
        baseline,candidate=evaluate(lib,runner,path,a.frames,a.anchor,point)
        delta=compare(baseline,candidate)
        delta["candidateMissingVsLegacy"]=delta.pop("gpuMissingVsCpu")
        delta["candidateExtraVsLegacy"]=delta.pop("gpuExtraVsCpu")
        report[role]={"seconds":round(time.monotonic()-start,3),"legacy":assess(baseline,annotations[role],end),
            "appearanceExperiment":assess(candidate,annotations[role],end),"differences":delta}
        for label,records in [("legacy",baseline),("appearance",candidate)]:
            sheets(path,records,a.output,role,label,annotations[role],end)
        (a.output/"experiment.json").write_text(json.dumps(report,indent=2)+"\n")
        print(role,json.dumps({k:{a:b for a,b in v.items() if a not in ("missingSpansUs","checkpoints")} if isinstance(v,dict) else v for k,v in report[role].items()}),flush=True)


if __name__=="__main__":main()
