#!/usr/bin/env python3
"""Host-only identical full-observation A/B for bounded detail recovery.

The full-frame masks/features are computed once. Only a shared Tracker -3
detail request may re-read an exact current source sample and run a local
inference. No manual ROI, no re-seeding, no model/threshold changes. Native
decoder numerics differ, so this is development evidence, not device QA.
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
from compare_dance_identity import bind, observe
from review_holdout import contour_metrics
from review_real_dance import assess, sheets


class View(c.Structure):
    _fields_=[("x",c.c_int32),("top",c.c_int32),("width",c.c_int32),("height",c.c_int32)]


def bind_detail(lib):
    bind(lib)
    lib.frame_kernel_dance_step_or_detail.argtypes=[c.c_uint64,m.I64,c.POINTER(m.Detection),
        c.c_size_t,m.P8,c.c_size_t,m.Transform,m.PF,c.c_size_t,c.POINTER(View)]
    lib.frame_kernel_dance_step_or_detail.restype=c.c_int32
    lib.frame_kernel_dance_finish_detail.argtypes=[c.c_uint64,m.I64,c.c_size_t,c.POINTER(m.Detection),
        c.c_size_t,m.P8,c.c_size_t,m.Transform,m.PF,c.c_size_t]
    lib.frame_kernel_dance_finish_detail.restype=c.c_int32
    lib.frame_kernel_dance_detail_remap.argtypes=[m.Detection,m.P8,c.c_size_t,m.Transform,m.Transform,View,
        c.POINTER(m.Detection),c.c_size_t,m.P8,c.c_size_t,m.P8,c.c_size_t,c.POINTER(m.Detection)]
    lib.frame_kernel_dance_detail_remap.restype=c.c_int32


def features(lib, rgba, masks,t):
    packed=np.empty((640,640,4),np.uint8);lb=m.Letterbox()
    assert lib.frame_kernel_resize_rgba(m.ptr(rgba),rgba.size,rgba.shape[1],rgba.shape[0],rgba.shape[1]*4,
        m.ptr(packed),packed.size,640,640,114,c.byref(lb))==0
    f=np.empty((len(masks),29),np.float32)
    assert lib.frame_kernel_dance_appearance_rgba(m.ptr(packed),packed.size,0,m.ptr(masks),masks.size,
        len(masks),t,m.ptr(f,m.PF),f.size)==0
    return f


def local(lib,runner,cap,i,view,o):
    _,ds,masks,t,f=o
    cap.set(cv2.CAP_PROP_POS_FRAMES,i);ok,frame=cap.read()
    if not ok:raise ValueError("Cannot re-read source sample")
    h,w=frame.shape[:2]
    # ROI is in the full-inference display basis (host downscaled to <=768).
    # Read original source pixels, then map crop to the exact local basis.
    x0=view.x*w/t.source_width;y0=view.top*h/t.source_height
    x1=(view.x+view.width)*w/t.source_width;y1=(view.top+view.height)*h/t.source_height
    region=frame[round(y0):round(y1),round(x0):round(x1)]
    roi=cv2.cvtColor(cv2.resize(region,(view.width,view.height),interpolation=cv2.INTER_AREA),cv2.COLOR_BGR2RGBA)
    rows,raw,lt=m.infer(lib,runner,roi,.25);appearance=features(lib,roi,raw,lt)
    merged=list(ds);mm=masks.copy();ff=f.copy()
    for j,d in enumerate(rows):
        if len(merged)>=256:break
        dst=np.empty((160,160),np.uint8);out=m.Detection()
        full=(m.Detection*len(merged))(*merged)
        rc=lib.frame_kernel_dance_detail_remap(d,m.ptr(raw[j]),raw[j].size,lt,t,view,full,len(merged),
            m.ptr(mm),mm.size,m.ptr(dst),dst.size,c.byref(out))
        assert rc>=0
        if rc==1:
            merged.append(out);mm=np.concatenate((mm,dst[None]));ff=np.concatenate((ff,appearance[j:j+1]))
    return merged,mm,ff


def replay(lib,runner,path,obs,anchor,seed,enabled):
    records={};attempts=[];accepted=[]
    cap=cv2.VideoCapture(str(path))
    budget=min(160,max(1,(len(obs)+3)//4))
    start=time.monotonic()
    try:
        # Same anchor-neighborhood -> suffix -> reverse-prefix order as native.
        handles=[lib.frame_kernel_dance_cutout_create(),lib.frame_kernel_dance_cutout_create()]
        assert all(handles)
        def step(h,i,selected=-1):
            o=obs[i];pts,ds,masks,t,f=o
            raw=(m.Detection*len(ds))(*ds)
            argv=(raw,len(ds),m.ptr(masks),masks.size,t,m.ptr(f,m.PF),f.size)
            if enabled and selected<0 and len(attempts)<budget:
                view=View()
                j=lib.frame_kernel_dance_step_or_detail(h,pts,*argv,c.byref(view))
                if j==-3:
                    attempts.append(int(pts))
                    original_count=len(ds)
                    ds,masks,f=local(lib,runner,cap,i,view,o)
                    raw=(m.Detection*len(ds))(*ds)
                    j=lib.frame_kernel_dance_finish_detail(h,pts,original_count,raw,len(ds),m.ptr(masks),masks.size,
                        t,m.ptr(f,m.PF),f.size)
                    if j>=original_count:accepted.append(int(pts))
            else:j=lib.frame_kernel_dance_cutout_step(h,pts,selected,*argv)
            assert j>=-1,(i,j)
            alpha=None
            if j>=0:
                w=max(1,round(t.source_width*384/max(t.source_width,t.source_height)))
                h_=max(1,round(t.source_height*384/max(t.source_width,t.source_height)))
                alpha=np.empty((h_,w),np.uint8)
                assert lib.frame_kernel_cutout_alpha(m.ptr(masks[j]),masks[j].size,t,w,h_,m.ptr(alpha),alpha.size)==0
            records[i]=(int(pts),alpha if alpha is not None and alpha.any() else None)
        try:
            step(handles[0],anchor,seed);step(handles[1],anchor,seed)
            near=[i for i,o in enumerate(obs) if obs[anchor][0]-500000<=o[0]<obs[anchor][0]+500000]
            first=max(0,near[0]-1) if obs[near[0]][0]>obs[anchor][0]-500000 else near[0]
            last=near[-1]
            for i in range(anchor-1,first-1,-1):step(handles[1],i)
            for i in range(anchor+1,last+1):step(handles[0],i)
            for i in range(last+1,len(obs)):step(handles[0],i)
            for i in range(first-1,-1,-1):step(handles[1],i)
        finally:
            for h in handles:lib.frame_kernel_dance_cutout_destroy(h)
    finally:cap.release()
    return [records[i] for i in range(len(obs))],{"attempts":len(attempts),"accepted":len(accepted),
        "attemptedPts":attempts,"acceptedPts":accepted,"seconds":round(time.monotonic()-start,3)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",required=True,type=Path);p.add_argument("--media",required=True,type=Path)
    p.add_argument("--library",required=True,type=Path);p.add_argument("--output",required=True,type=Path)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    mfest=json.loads(a.manifest.read_text())
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    assert hashlib.sha256(model.read_bytes()).hexdigest()==mfest["modelSha256"]
    lib=m.api(a.library);bind_detail(lib)
    runner=Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    report={"roles":{},"hostOnly":True}
    for role,s in mfest["sources"].items():
        path=a.media/s["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest()==s["testedMp4Sha256"]
        n=mfest["framesPerRole"];anchor=round(mfest["anchorUs"]*mfest["cadence"]/1e6)
        obs=list(observe(lib,runner,path,n,anchor))
        raw=obs[anchor][2];t=obs[anchor][3]
        seed=lib.frame_kernel_cutout_select(m.ptr(raw),raw.size,t,*s["seed"]);assert seed>=0
        row={}
        baseline=None
        for key,enable in (("fullOnly",False),("detail",True)):
            records,timing=replay(lib,runner,path,obs,anchor,seed,enable)
            stats=assess(records,mfest["annotations"][role],mfest["durationUs"])
            if baseline is None:baseline=records
            else:
                # Local fill is output-only. All formerly visible full-frame
                # masks must remain byte-exact; no availability regression.
                retained=[(i,x,y) for i,((p,x),(q,y)) in enumerate(zip(baseline,records)) if x is not None]
                assert all(y is not None and np.array_equal(x,y) for _,x,y in retained), "Detail changed canonical full-frame outputs"
                stats["exactBaselineVisibleFrames"]=len(retained)
            if "contourCoordinateSize" in mfest:
                w,h=mfest["contourCoordinateSize"];lookup=dict(records)
                stats["contours"]=[{"ptsUs":x["ptsUs"],**contour_metrics(lookup[x["ptsUs"]],x["visibleContour"],w,h)}
                    for x in mfest["annotations"][role] if "visibleContour" in x]
            row[key]={**stats,**timing}
            sheets(path,records,a.output,role,key,mfest["annotations"][role],mfest["durationUs"])
            print(role,key,{k:row[key][k] for k in ("visible","checkpointHits","distractorHits","longestMissingUs","attempts","accepted","seconds")},flush=True)
        report["roles"][role]=row
        (a.output/"report.json").write_text(json.dumps(report,indent=2)+"\n")


if __name__=="__main__":main()
