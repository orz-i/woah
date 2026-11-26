#!/usr/bin/env python3
"""Compare legacy and shared dance-specific FrameKernel association on the same
raw decoded frames. No video, descriptors or masks are written to the repo;
local review contact sheets are optional. Sparse annotations are not ground truth.
"""
from __future__ import annotations

import argparse
import ctypes as c
import hashlib
import json
from pathlib import Path
import time

import numpy as np
from ai_edge_litert.interpreter import Interpreter
import cutout_m0 as m
from review_real_dance import assess, sheets

BINS=29


def bind(lib):
    lib.frame_kernel_dance_cutout_create.argtypes=[]
    lib.frame_kernel_dance_cutout_create.restype=c.c_uint64
    lib.frame_kernel_dance_cutout_destroy.argtypes=[c.c_uint64]
    lib.frame_kernel_dance_cutout_destroy.restype=c.c_int32
    lib.frame_kernel_dance_appearance_rgba.argtypes=[
        m.P8,c.c_size_t,m.U32,m.P8,c.c_size_t,c.c_size_t,m.Transform,m.PF,c.c_size_t]
    lib.frame_kernel_dance_appearance_rgba.restype=c.c_int32
    lib.frame_kernel_dance_cutout_step.argtypes=[
        c.c_uint64,m.I64,c.c_int32,c.POINTER(m.Detection),c.c_size_t,m.P8,c.c_size_t,
        m.Transform,m.PF,c.c_size_t]
    lib.frame_kernel_dance_cutout_step.restype=c.c_int32


def observe(lib,runner,path,frames,anchor):
    for i,(pts,rgba) in enumerate(m.source_frames(lib,path,frames)):
        detections,masks,t=m.infer(lib,runner,rgba,.25 if i==anchor else .20)
        packed=np.empty((640,640,4),np.uint8);lb=m.Letterbox()
        assert lib.frame_kernel_resize_rgba(m.ptr(rgba),rgba.size,rgba.shape[1],rgba.shape[0],
            rgba.shape[1]*4,m.ptr(packed),packed.size,640,640,114,c.byref(lb))==0
        assert np.isclose(lb.scale,t.scale) and np.isclose(lb.left,t.pad_left) and np.isclose(lb.top,t.pad_top)
        features=np.empty((len(detections),BINS),np.float32)
        assert lib.frame_kernel_dance_appearance_rgba(m.ptr(packed),packed.size,0,m.ptr(masks),
            masks.size,len(detections),t,m.ptr(features,m.PF),features.size)==0
        yield pts,detections,masks,t,features


def replay(lib,runner,path,frames,anchor,point):
    old=[lib.frame_kernel_tracking_create(lib.frame_kernel_tracking_defaults()) for _ in range(2)]
    new=[lib.frame_kernel_dance_cutout_create() for _ in range(2)]
    assert all(old+new)
    results=[[],[]];prefix=[]
    def step(o,dir_,seed=-1,save=True):
        pts,ds,masks,t,features=o
        raw=(m.Detection*len(ds))(*ds)
        a=lib.frame_kernel_cutout_track_step(old[dir_],pts,seed,raw,len(ds),m.ptr(masks),masks.size,t)
        b=lib.frame_kernel_dance_cutout_step(new[dir_],pts,seed,raw,len(ds),m.ptr(masks),
            masks.size,t,m.ptr(features,m.PF),features.size)
        assert a>=-1 and b>=-1
        if seed>=0:assert a==b==seed
        if not save:return
        w=max(1,round(t.source_width*256/max(t.source_width,t.source_height)))
        h=max(1,round(t.source_height*256/max(t.source_width,t.source_height)))
        for j,index in enumerate((a,b)):
            alpha=None
            if index>=0:
                alpha=np.empty((h,w),np.uint8)
                assert lib.frame_kernel_cutout_alpha(m.ptr(masks[index]),masks[index].size,t,w,h,
                    m.ptr(alpha),alpha.size)==0
                if not alpha.any():alpha=None
            results[j].append((pts,alpha))
    try:
        for ordinal,o in enumerate(observe(lib,runner,path,frames,anchor)):
            if ordinal<anchor:
                prefix.append(o);continue
            if ordinal==anchor:
                index=lib.frame_kernel_cutout_select(m.ptr(o[2]),o[2].size,o[3],*point)
                if index<0:raise ValueError("Ambiguous selected point")
                step(o,0,index);step(o,1,index,False)
                for prior in reversed(prefix):step(prior,1)
                prefix.clear()
            else:step(o,0)
    finally:
        for id_ in old:lib.frame_kernel_tracking_destroy(id_)
        for id_ in new:lib.frame_kernel_dance_cutout_destroy(id_)
    return [sorted(r,key=lambda x:x[0]) for r in results]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--library",required=True,type=Path)
    p.add_argument("--manifest",required=True,type=Path)
    p.add_argument("--media",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--sheets",action="store_true")
    args=p.parse_args()
    manifest=json.loads(args.manifest.read_text())
    frames=manifest["framesPerRole"];end=manifest["durationUs"]
    if not 1<=frames<=1800 or end!=frames*1_000_000//manifest["cadence"]:
        raise ValueError("Unsupported fixture")
    anchor=round(manifest["anchorUs"]*manifest["cadence"]/1e6)
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    assert hashlib.sha256(model.read_bytes()).hexdigest()==manifest["modelSha256"]==m.MODEL_SHA
    lib=m.api(args.library);bind(lib)
    runner=Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    args.output.mkdir(parents=True,exist_ok=True)
    report={"manualPointsAreNotGroundTruth":True,"roles":{}}
    for role,source in manifest["sources"].items():
        start=time.monotonic()
        path=args.media/source["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest()!=source["testedMp4Sha256"]:
            raise ValueError(f"{role}: fixture bytes differ")
        checks=manifest["annotations"][role]
        legacy,dance=replay(lib,runner,path,frames,anchor,source["seed"])
        assert len(legacy)==len(dance)==frames
        row={"legacy":assess(legacy,checks,end),"dance":assess(dance,checks,end),
             "seconds":round(time.monotonic()-start,3)}
        report["roles"][role]=row
        if args.sheets:
            for name,data in (("legacy",legacy),("dance",dance)):
                sheets(path,data,args.output,role,name,checks,end)
        (args.output/"report.json").write_text(json.dumps(report,indent=2)+"\n")
        print(role,{name:{k:v[k] for k in ("visible","checkpointHits","distractorHits","longestMissingUs")}
            for name,v in row.items() if isinstance(v,dict)},flush=True)


if __name__=="__main__":
    main()
