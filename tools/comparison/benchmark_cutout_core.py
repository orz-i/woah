#!/usr/bin/env python3
"""Same-observation identity/performance and alpha projection fidelity A/B.

The two binaries receive exactly the same detections and appearance
features. This measures the shared core (not total model inference).
The dense alpha reference is generated from the SAME raw model mask:
projection fidelity is not ground-truth matting accuracy. No media or
observation caches are committed.
"""
from __future__ import annotations

import argparse
import ctypes as c
import hashlib
import json
from pathlib import Path
import statistics
import time

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

import cutout_m0 as m
from compare_dance_identity import bind, observe


def observation_args(o):
    pts, ds, masks, t, features = o
    raw=(m.Detection*len(ds))(*ds)
    return pts, raw, len(ds), m.ptr(masks), masks.size, t, m.ptr(features,m.PF), features.size


def run(lib, arguments, anchor, seed):
    indexes=[-1]*len(arguments)
    seconds=0.
    for order in (range(anchor+1,len(arguments)),range(anchor-1,-1,-1)):
        lane=lib.frame_kernel_dance_cutout_create()
        assert lane
        try:
            start=time.perf_counter()
            for index in (anchor,*order):
                pts,*args=arguments[index]
                value=lib.frame_kernel_dance_cutout_step(lane,pts,seed if index==anchor else -1,*args)
                assert value>=-1
                indexes[index]=value
            seconds+=time.perf_counter()-start
        finally:
            assert lib.frame_kernel_dance_cutout_destroy(lane)==0
    return indexes,seconds*1000


def alpha(lib,mask,t,w,h):
    out=np.empty((h,w),np.uint8)
    assert lib.frame_kernel_cutout_alpha(m.ptr(mask),mask.size,t,w,h,m.ptr(out),out.size)==0
    return out


def measure_projection(old,new,observations,selected):
    paired=[]
    # All selected frames are checked at the original grid. For fidelity,
    # sample every eighth visible source index in the same two clips.
    project_ms=[0.,0.,0.]
    dense_metrics=[]
    for i,(o,idx) in enumerate(zip(observations,selected)):
        if idx<0:continue
        _,_,masks,t,_=o
        mask=masks[idx]
        sw,sh=t.source_width,t.source_height
        def dims(edge):
            return max(1,round(sw*edge/max(sw,sh))),max(1,round(sh*edge/max(sw,sh)))
        w,h=dims(256);w2,h2=dims(384)
        for j,(lib,ww,hh) in enumerate(((old,w,h),(new,w,h),(new,w2,h2))):
            start=time.perf_counter();v=alpha(lib,mask,t,ww,hh);project_ms[j]+=(time.perf_counter()-start)*1000
            if j==0:a=v
            elif j==1:
                assert np.array_equal(v,a),f"Fixed-grid alpha changed at {i}"
            else:b=v
        paired.append(i)
        if i%8!=0:continue
        dw,dh=dims(1024)
        ref=alpha(old,mask,t,dw,dh)
        aa=cv2.resize(a,(dw,dh),interpolation=cv2.INTER_LINEAR)
        bb=cv2.resize(b,(dw,dh),interpolation=cv2.INTER_LINEAR)
        # Boundary defined by non-binary values in the dense reference
        # or either interpolated alpha. Compare the exact same region.
        edge=((ref>0)&(ref<255))|((aa>0)&(aa<255))|((bb>0)&(bb<255))
        if not edge.any():continue
        x=ref.astype(np.int16)
        dense_metrics.append([float(np.abs(aa.astype(np.int16)-x)[edge].mean()),
                              float(np.abs(bb.astype(np.int16)-x)[edge].mean())])
    return {"exact256Frames":len(paired),"projectionMsOld256":round(project_ms[0],3),
            "projectionMsNew256":round(project_ms[1],3),"projectionMsNew384":round(project_ms[2],3),
            "edgeSamples":len(dense_metrics),
            "edgeMae256":float(np.mean(dense_metrics,axis=0)[0]),
            "edgeMae384":float(np.mean(dense_metrics,axis=0)[1])}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-library",required=True,type=Path)
    parser.add_argument("--library",required=True,type=Path)
    parser.add_argument("--manifest",required=True,type=Path)
    parser.add_argument("--media",required=True,type=Path)
    parser.add_argument("--output",required=True,type=Path)
    parser.add_argument("--rounds",type=int,default=5)
    args=parser.parse_args()
    manifest=json.loads(args.manifest.read_text())
    count=manifest["framesPerRole"];anchor=round(manifest["anchorUs"]*manifest["cadence"]/1e6)
    assert 1<=count<=1800 and 0<=anchor<count and 1<=args.rounds<=20
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    assert hashlib.sha256(model.read_bytes()).hexdigest()==manifest["modelSha256"]==m.MODEL_SHA
    old=m.api(args.baseline_library);new=m.api(args.library);bind(old);bind(new)
    runner=Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    report={"baselineSha256":hashlib.sha256(args.baseline_library.read_bytes()).hexdigest(),
            "candidateSha256":hashlib.sha256(args.library.read_bytes()).hexdigest(),
            "rounds":args.rounds,"roles":{},"projectionNotGroundTruth":True}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    for role,src in manifest["sources"].items():
        path=args.media/src["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest()==src["testedMp4Sha256"]
        # Build once on baseline preprocessing; no repeated inference.
        obs=list(observe(old,runner,path,count,anchor))
        assert len(obs)==count
        argv=[observation_args(o) for o in obs]
        _,_,masks,t,_=obs[anchor]
        seed=old.frame_kernel_cutout_select(m.ptr(masks),masks.size,t,*src["seed"])
        assert seed>=0
        baseline,_=run(old,argv,anchor,seed)
        candidate,_=run(new,argv,anchor,seed)
        assert baseline==candidate,f"{role}: selected raw detection indices changed"
        timings=[[],[]]
        for trial in range(args.rounds):
            for k in (range(2) if trial%2==0 else (1,0)):
                indexes,elapsed=run((old,new)[k],argv,anchor,seed)
                assert indexes==baseline
                timings[k].append(elapsed)
        row={"frames":count,"visible":sum(i>=0 for i in baseline),"exactSelectedIndices":True,
             "oldTrackerMs":statistics.median(timings[0]),"newTrackerMs":statistics.median(timings[1]),
             "trialsMs":timings,**measure_projection(old,new,obs,baseline)}
        report["roles"][role]=row
        args.output.write_text(json.dumps(report,indent=2)+"\n")
        print(role,json.dumps(row),flush=True)


if __name__=="__main__":main()
