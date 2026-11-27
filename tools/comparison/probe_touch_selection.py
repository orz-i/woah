#!/usr/bin/env python3
"""Host-only touch-centered initial selection experiment with pinned Nano.

Only a canonical background (-1) result can trigger a bounded local crop.
A canonical success, ambiguity, or invalid input is not replaced. Use exact
source pixels, same model and shared core remap/selection. An annotation
provides a simulated user touch; its distractors/contour are evaluation only.
The probe calls the same shared touch ABI used by the native app.
It does not alter the tracking profile or model.
"""
from __future__ import annotations

import argparse
import ctypes as c
import json
from pathlib import Path
import time

import cv2
import numpy as np

import cutout_m0 as m
from compare_dance_identity import observe
from source_sample_evidence import sha, source_samples, score
from replay_adaptive_detail import bind_detail, View, features, replay
from review_real_dance import assess

CROP_FRACTIONS = (.32, .20)
EXPANDED_FRACTION = .50
MAX_EDGE = 768


class TouchRows(c.Structure):
    _fields_=[("rows",c.POINTER(m.Detection)),("count",c.c_size_t),
              ("masks",m.P8),("mask_len",c.c_size_t),("features",m.PF),
              ("feature_len",c.c_size_t),("transform",m.Transform)]


class TouchOutput(c.Structure):
    _fields_=[("rows",c.POINTER(m.Detection)),("capacity",c.c_size_t),
              ("masks",m.P8),("mask_len",c.c_size_t),("features",m.PF),
              ("feature_len",c.c_size_t)]


class TouchResult(c.Structure):
    _fields_=[("index",c.c_int32),("count",c.c_uint32)]


def bind_touch(lib):
    lib.frame_kernel_dance_touch_view.argtypes=[m.Transform,m.F32,m.F32,m.U32]
    lib.frame_kernel_dance_touch_view.restype=View
    lib.frame_kernel_dance_touch_merge.argtypes=[TouchRows,TouchRows,View,m.F32,m.F32,TouchOutput]
    lib.frame_kernel_dance_touch_merge.restype=TouchResult


def merge_touch(lib,full,local,v,x,y):
    def pack(o):
        _,ds,raw,t,f=o
        rows=(m.Detection*len(ds))(*ds)
        return rows,TouchRows(rows,len(ds),m.ptr(raw),raw.size,m.ptr(f,m.PF),f.size,t)
    fr,a=pack(full);lr,b=pack(local)
    cap=min(256,a.count+b.count)
    rows=(m.Detection*cap)()
    raw=np.empty((cap,a.transform.proto_size,a.transform.proto_size),np.uint8)
    f=np.empty((cap,29),np.float32)
    result=lib.frame_kernel_dance_touch_merge(a,b,v,x,y,TouchOutput(rows,cap,
        m.ptr(raw),raw.size,m.ptr(f,m.PF),f.size))
    if result.index<0:
        if result.count!=0:
            raise ValueError("Invalid failure output")
        return result.index,full
    if not a.count<=result.index<result.count<=cap:
        raise ValueError("Invalid recovered seed")
    return result.index,(full[0],list(rows[:result.count]),raw[:result.count],a.transform,f[:result.count])


def crop_rgba(source,t,v):
    sh,sw=source.shape[:2]
    left=round(v.x*sw/t.source_width);right=round((v.x+v.width)*sw/t.source_width)
    top=round(v.top*sh/t.source_height);bottom=round((v.top+v.height)*sh/t.source_height)
    return cv2.resize(source[top:bottom,left:right],(v.width,v.height),
                      interpolation=cv2.INTER_AREA)


def select(lib,runner,source,o,x,y,expand_truncated=False):
    """Return canonical or one safely appended touch candidate, never a bbox hit."""
    pts,rows,masks,t,f=o
    if not (np.isfinite(x) and np.isfinite(y) and 0<=x<1 and 0<=y<1):
        return -3,o,{"reason":"invalid_touch","canonicalIndex":-3,"attempts":[]}
    primary=lib.frame_kernel_cutout_select(m.ptr(masks),masks.size,t,x,y)
    info={"canonicalIndex":primary,"attempts":[]}
    if primary!=-1:
        return primary,o,{**info,"reason":"canonical" if primary>=0 else "canonical_rejected"}
    mode=0
    for attempt in range(2):
        frac=(*CROP_FRACTIONS,EXPANDED_FRACTION)[mode]
        v=lib.frame_kernel_dance_touch_view(t,x,y,mode)
        if v.width<=0 or v.height<=0:
            raise ValueError("Invalid planned ROI")
        local=crop_rgba(source,t,v)
        tick=time.monotonic()
        dr,mr,lt=m.infer(lib,runner,local,.25)
        ff=features(lib,local,mr,lt)
        j,merged=merge_touch(lib,o,(pts,dr,mr,lt,ff),v,x,y)
        row={"fraction":frac,"localRows":len(dr),"mergeIndex":j,
             "ms":(time.monotonic()-tick)*1000}
        info["attempts"].append(row)
        if j>=0:
            row["mappedRows"]=len(merged[1])-len(rows)
            return j,merged,{**info,"reason":"rescued"}
        if j==-1 and attempt==0:
            mode=1
            continue
        if j==-4 and attempt==0 and expand_truncated:
            mode=2
            continue
        if j==-3:
            raise ValueError("Invalid touch merge")
        reason={-1:"no_local_touch",-2:"local_ambiguous",-4:"local_remap_rejected",
                -5:"local_remap_rejected"}[j]
        return (-2 if j==-2 else -1),o,{**info,"reason":reason}
    return -1,o,{**info,"reason":"no_local_touch"}


def observe_frame(lib,runner,rgba,pts):
    h,w=rgba.shape[:2]
    scale=min(1.,MAX_EDGE/max(w,h))
    small=cv2.resize(rgba,(round(w*scale),round(h*scale)),interpolation=cv2.INTER_AREA) if scale<1 else rgba
    ds,raw,t=m.infer(lib,runner,np.ascontiguousarray(small),.25)
    return pts,ds,raw,t,features(lib,small,raw,t)


def alpha(lib,o,i):
    if i<0:return None
    _,_,raw,t,_=o
    w=max(1,round(t.source_width*384/max(t.source_width,t.source_height)))
    h=max(1,round(t.source_height*384/max(t.source_width,t.source_height)))
    out=np.empty((h,w),np.uint8)
    if lib.frame_kernel_cutout_alpha(m.ptr(raw[i]),raw[i].size,t,w,h,m.ptr(out),out.size)!=0:
        raise ValueError("Invalid alpha projection")
    return out


def write_review(source,a,path):
    im=cv2.cvtColor(source,cv2.COLOR_RGBA2BGR)
    im=cv2.resize(im,(480,270),interpolation=cv2.INTER_AREA)
    aa=cv2.resize(a,(480,270),interpolation=cv2.INTER_LINEAR).astype(np.float32)[:,:,None]/255
    overlay=(im*(1-.4*aa)+np.full_like(im,(110,210,90))*.4*aa).astype(np.uint8)
    cutout=(im*aa+np.full_like(im,35)*(1-aa)).astype(np.uint8)
    path.parent.mkdir(parents=True,exist_ok=True)
    if not cv2.imwrite(str(path),np.concatenate((im,overlay,cutout),axis=1)):
        raise ValueError("Review image failed")


def run_manifest(lib,runner,manifest,media,out,track,expand_truncated=False):
    report={}
    for role,s in manifest["sources"].items():
        path=media/s["file"]
        if sha(path)!=s["testedMp4Sha256"]:
            raise ValueError("Source mismatch")
        annotations={a["ptsUs"]:a for a in manifest["annotations"][role]}
        anchor=manifest["anchorUs"]
        requested=set(annotations)|{anchor}
        images=source_samples(path,manifest,requested)
        rows=[]
        for pts,a in annotations.items():
            image=images[pts];o=observe_frame(lib,runner,image,pts)
            j,selected,info=select(lib,runner,image,o,*a["selected"],expand_truncated=expand_truncated)
            old=info["canonicalIndex"]
            old_a=alpha(lib,o,old);new_a=alpha(lib,selected,j)
            if old>=0 and not np.array_equal(old_a,new_a):
                raise ValueError("Canonical touch result changed")
            item={"ptsUs":pts,**info,"selectedIndex":j,
                  "baseline":score(old_a,a,manifest["contourCoordinateSize"]),
                  "candidate":score(new_a,a,manifest["contourCoordinateSize"])}
            if info["reason"]=="rescued":
                write_review(image,new_a,out/role/f"touch-{pts}.jpg")
            rows.append(item)
        a=images[anchor];o=observe_frame(lib,runner,a,anchor)
        j,selected,info=select(lib,runner,a,o,*s["seed"],expand_truncated=expand_truncated)
        result={"points":rows,"anchor":{**info,"selectedIndex":j}}
        # Independently simulate a user touch at each recovered checkpoint.
        # No subsequent annotations are fed to the tracker.
        if track:
            seeds={r["ptsUs"]:annotations[r["ptsUs"]]["selected"] for r in rows if r["reason"]=="rescued"}
            if info["reason"]=="rescued":
                seeds[anchor]=s["seed"]
            result["tracks"]=[]
            for at,touch in seeds.items():
                n=manifest["framesPerRole"]
                k=round(at*manifest["cadence"]/1e6)
                obs=[(p,d,r.copy(),t,f) for p,d,r,t,f in observe(lib,runner,path,n,k)]
                if obs[k][0]!=at:
                    raise ValueError("Seed PTS mismatch")
                j,selected,info2=select(lib,runner,images[at],obs[k],*touch,
                                       expand_truncated=expand_truncated)
                if info2["reason"]=="rescued":
                    obs[k]=selected
                    records,timing=replay(lib,runner,path,obs,k,j,True,True)
                    result["tracks"].append({"seedPtsUs":at,**assess(records,manifest["annotations"][role],
                                              manifest["durationUs"]),**timing})
                else:
                    result["tracks"].append({"seedPtsUs":at,"seedNotReproduced":info2})
        report[role]=result
        print(role,"canonical",sum(x["canonicalIndex"]>=0 for x in rows),
              "rescued",sum(x["reason"]=="rescued" for x in rows),"anchor",result["anchor"]["reason"],
              "track",[(r["seedPtsUs"],r.get("visible")) for r in result.get("tracks",[])],flush=True)
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--media",type=Path,required=True)
    p.add_argument("--library",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--track",action="store_true")
    p.add_argument("--expand-truncated",action="store_true",
                   help="use the second inference for larger context only after an artificial-edge-truncated hit")
    a=p.parse_args()
    manifest=json.loads(a.manifest.read_text())
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    if sha(model)!=m.MODEL_SHA or sha(model)!=manifest["modelSha256"]:
        raise ValueError("Unexpected model")
    runner=m.Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    lib=m.api(a.library);bind_detail(lib);bind_touch(lib)
    a.output.mkdir(parents=True,exist_ok=True)
    result={"manifestSha256":sha(a.manifest),"librarySha256":sha(a.library),
            "modelSha256":sha(model),"cropFractions":CROP_FRACTIONS,
            "expandTruncated":a.expand_truncated,"expandedFraction":EXPANDED_FRACTION,
            "roles":run_manifest(lib,runner,manifest,a.media,a.output,a.track,a.expand_truncated)}
    (a.output/"report.json").write_text(json.dumps(result,indent=2)+"\n")


if __name__=="__main__":
    main()
