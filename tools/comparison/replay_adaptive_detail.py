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
    if hasattr(lib,"frame_kernel_dance_step_or_detail_scheduled"):
        lib.frame_kernel_dance_step_or_detail_scheduled.argtypes=lib.frame_kernel_dance_step_or_detail.argtypes
        lib.frame_kernel_dance_step_or_detail_scheduled.restype=c.c_int32
    lib.frame_kernel_dance_finish_detail.argtypes=[c.c_uint64,m.I64,c.c_size_t,c.POINTER(m.Detection),
        c.c_size_t,m.P8,c.c_size_t,m.Transform,m.PF,c.c_size_t]
    lib.frame_kernel_dance_finish_detail.restype=c.c_int32
    if hasattr(lib,"frame_kernel_dance_probe_alternate_detail"):
        lib.frame_kernel_dance_probe_alternate_detail.argtypes=[
            c.c_uint64,m.I64,c.c_size_t,c.c_size_t,c.POINTER(m.Detection),
            c.c_size_t,m.P8,c.c_size_t,m.Transform,m.PF,c.c_size_t]
        lib.frame_kernel_dance_probe_alternate_detail.restype=c.c_int32
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


def replay(lib,runner,path,obs,anchor,seed,enabled,scheduled=False,local_cache=None,
           alternate_runner=None,trace=None):
    records={};attempts=[];accepted=[];skipped=[]
    alternate_attempts=[];alternate_accepted=[];alternate_eligible=[];alternate_ms=[]
    if alternate_runner is not None and (not enabled or not scheduled or
            not hasattr(lib,"frame_kernel_dance_probe_alternate_detail")):
        raise ValueError("Alternate experiment requires scheduled host-model-probe library")
    cap=cv2.VideoCapture(str(path))
    budget=min(160,max(1,(len(obs)+3)//4))
    # Fixed experimental upper bound: 6% of frames, at most 24 per role.
    alternate_budget=min(24,max(1,(len(obs)*6+99)//100))
    start=time.monotonic()
    try:
        # Same anchor-neighborhood -> suffix -> reverse-prefix order as native.
        handles=[lib.frame_kernel_dance_cutout_create(),lib.frame_kernel_dance_cutout_create()]
        assert all(handles)
        def step(h,i,selected=-1):
            o=obs[i];pts,ds,masks,t,f=o
            raw=(m.Detection*len(ds))(*ds)
            argv=(raw,len(ds),m.ptr(masks),masks.size,t,m.ptr(f,m.PF),f.size)
            detail_state="not_requested"
            if enabled and selected<0 and len(attempts)<budget:
                view=View()
                step_detail=lib.frame_kernel_dance_step_or_detail_scheduled if scheduled else lib.frame_kernel_dance_step_or_detail
                j=step_detail(h,pts,*argv,c.byref(view))
                if j==-4:
                    detail_state="backoff"
                    skipped.append(int(pts));j=-1
                if j==-3:
                    detail_state="attempted"
                    attempts.append(int(pts))
                    original_count=len(ds)
                    key=(i,view.x,view.top,view.width,view.height)
                    if local_cache is not None and key in local_cache:
                        ds,masks,f=local_cache[key]
                    else:
                        ds,masks,f=local(lib,runner,cap,i,view,o)
                        if local_cache is not None:local_cache[key]=(ds,masks,f)
                    raw=(m.Detection*len(ds))(*ds)
                    alt_index=-1
                    alt_rows=None
                    if alternate_runner is not None:
                        primary_count=len(ds)
                        probe=lib.frame_kernel_dance_probe_alternate_detail
                        status=probe(h,pts,original_count,primary_count,raw,primary_count,
                                     m.ptr(masks),masks.size,t,m.ptr(f,m.PF),f.size)
                        if status not in (-1,-5):
                            raise ValueError(f"Invalid primary probe: {status}")
                        if status==-5:
                            alternate_eligible.append(int(pts))
                            if len(alternate_attempts)<alternate_budget:
                                alternate_attempts.append(int(pts))
                                tick=time.monotonic()
                                alt_rows=local(lib,alternate_runner,cap,i,view,(pts,ds,masks,t,f))
                                ads,am,af=alt_rows
                                # Keep all canonical full and local rows as competitors.
                                if len(ads)>primary_count:
                                    ar=(m.Detection*len(ads))(*ads)
                                    alt_index=probe(h,pts,original_count,primary_count,ar,len(ads),
                                                    m.ptr(am),am.size,t,m.ptr(af,m.PF),af.size)
                                    if alt_index!=-1 and not primary_count<=alt_index<len(ads):
                                        raise ValueError(f"Invalid alternate probe: {alt_index}")
                                alternate_ms.append((time.monotonic()-tick)*1000)
                    # Only the primary rows enter the one canonical commit.
                    j=lib.frame_kernel_dance_finish_detail(h,pts,original_count,raw,len(ds),m.ptr(masks),masks.size,
                        t,m.ptr(f,m.PF),f.size)
                    if j>=original_count:accepted.append(int(pts))
                    if alt_index>=0:
                        if j!=-1:
                            raise ValueError("Alternate attempted to replace a primary output")
                        alternate_accepted.append(int(pts))
                        ds,masks,f=alt_rows
                        j=alt_index
            else:j=lib.frame_kernel_dance_cutout_step(h,pts,selected,*argv)
            assert j>=-1,(i,j)
            alpha=None
            if j>=0:
                w=max(1,round(t.source_width*384/max(t.source_width,t.source_height)))
                h_=max(1,round(t.source_height*384/max(t.source_width,t.source_height)))
                alpha=np.empty((h_,w),np.uint8)
                assert lib.frame_kernel_cutout_alpha(m.ptr(masks[j]),masks[j].size,t,w,h_,m.ptr(alpha),alpha.size)==0
            records[i]=(int(pts),alpha if alpha is not None and alpha.any() else None)
            if trace is not None:
                trace(i,j,(pts,ds,masks,t,f),detail_state)
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
    stats={"attempts":len(attempts),"accepted":len(accepted),
        "attemptedPts":attempts,"acceptedPts":accepted,"skippedPts":skipped,"skipped":len(skipped),"seconds":round(time.monotonic()-start,3)}
    if alternate_runner is not None:
        stats.update(alternateBudget=alternate_budget,alternateEligiblePts=alternate_eligible,
                     alternateAttemptedPts=alternate_attempts,alternateAcceptedPts=alternate_accepted,
                     alternateMs=alternate_ms)
    return [records[i] for i in range(len(obs))],stats


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",required=True,type=Path);p.add_argument("--media",required=True,type=Path)
    p.add_argument("--library",required=True,type=Path);p.add_argument("--output",required=True,type=Path)
    p.add_argument("--scheduled",action="store_true",help="also compare the bounded failure-backoff policy, sharing exact local observations")
    p.add_argument("--baseline-library",type=Path,help="compare two compatible FrameKernel builds with the scheduled ROI pipeline on identical full observations")
    p.add_argument("--no-sheets",action="store_true",help="skip rendering contact sheets")
    a=p.parse_args()
    if a.baseline_library and a.scheduled:p.error("choose scheduler A/B or two-library association A/B")
    a.output.mkdir(parents=True,exist_ok=True)
    mfest=json.loads(a.manifest.read_text())
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    assert hashlib.sha256(model.read_bytes()).hexdigest()==mfest["modelSha256"]
    lib=m.api(a.library);bind_detail(lib)
    runner=Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    report={"roles":{},"hostOnly":True}
    previous=None
    if a.baseline_library:
        previous=m.api(a.baseline_library);bind_detail(previous)
        report.update(baselineLibrarySha256=hashlib.sha256(a.baseline_library.read_bytes()).hexdigest(),
            candidateLibrarySha256=hashlib.sha256(a.library.read_bytes()).hexdigest(),
            manifestSha256=hashlib.sha256(a.manifest.read_bytes()).hexdigest(),
            associationAB=True)
    for role,s in mfest["sources"].items():
        path=a.media/s["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest()==s["testedMp4Sha256"]
        n=mfest["framesPerRole"];anchor=round(mfest["anchorUs"]*mfest["cadence"]/1e6)
        obs=list(observe(lib,runner,path,n,anchor))
        raw=obs[anchor][2];t=obs[anchor][3]
        seed=lib.frame_kernel_cutout_select(m.ptr(raw),raw.size,t,*s["seed"]);assert seed>=0
        row={}
        baseline=None; prior_detail=None
        variants=[("fullOnly",lib,False,False),("detail",lib,True,False)]
        if a.scheduled:variants.append(("scheduled",lib,True,True))
        if previous is not None:
            variants=[("baseline",previous,True,True),("candidate",lib,True,True)]
        local_cache={} if a.scheduled or previous is not None else None
        for key,engine,enable,scheduled in variants:
            records,timing=replay(engine,runner,path,obs,anchor,seed,enable,scheduled,local_cache)
            stats=assess(records,mfest["annotations"][role],mfest["durationUs"])
            if previous is not None:
                if key=="baseline":prior_detail=records
            elif baseline is None:baseline=records
            else:
                # Local fill is output-only. All formerly visible full-frame
                # masks must remain byte-exact; no availability regression.
                retained=[(i,x,y) for i,((p,x),(q,y)) in enumerate(zip(baseline,records)) if x is not None]
                assert all(y is not None and np.array_equal(x,y) for _,x,y in retained), "Detail changed canonical full-frame outputs"
                stats["exactBaselineVisibleFrames"]=len(retained)
            if key=="detail":prior_detail=records
            if scheduled and key!="baseline":
                assert prior_detail is not None
                shared=[(p,x,y) for (p,x),(q,y) in zip(prior_detail,records) if x is not None]
                stats["priorDetailVisible"]=len(shared)
                stats["priorDetailExact"]=sum(y is not None and np.array_equal(x,y) for _,x,y in shared)
                stats["lostDetailPts"]=[p for p,x,y in shared if y is None]
                stats["changedDetailPts"]=[p for p,x,y in shared if y is not None and not np.array_equal(x,y)]
                stats["newDetailPts"]=[p for (p,x),(q,y) in zip(prior_detail,records) if x is None and y is not None]
            if "contourCoordinateSize" in mfest:
                w,h=mfest["contourCoordinateSize"];lookup=dict(records)
                stats["contours"]=[{"ptsUs":x["ptsUs"],**contour_metrics(lookup[x["ptsUs"]],x["visibleContour"],w,h)}
                    for x in mfest["annotations"][role] if "visibleContour" in x]
            row[key]={**stats,**timing}
            if not a.no_sheets:sheets(path,records,a.output,role,key,mfest["annotations"][role],mfest["durationUs"])
            print(role,key,{k:row[key][k] for k in ("visible","checkpointHits","distractorHits","longestMissingUs","attempts","accepted","seconds")},flush=True)
        report["roles"][role]=row
        (a.output/"report.json").write_text(json.dumps(report,indent=2)+"\n")


if __name__=="__main__":main()
