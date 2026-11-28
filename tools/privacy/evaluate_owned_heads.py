#!/usr/bin/env python3
"""Research-only anatomy-first current head ownership on complete video replays.

The current canonical raw mask and two mapped pose observations establish the
head before face competition. Only a spatially contradictory placement can
be replaced. This pass never changes canonical identity or face history.
"""
import argparse
import copy
import ctypes as C
import json
import time
from pathlib import Path

import numpy as np
from PIL import Image

from evaluate_body_reid import PERSON_MODEL, FACE_MODEL, sha
from evaluate_pose_parts import POSE_FILE, POSE_SHA, PoseKernel, pose_points
from evaluate_profile_head_geometry import canonical_raw, intersects, square
from replay_face_geometry import P, S, U, MaskSpec, Ellipse, Rect, detect_instances, detect_face, model, ptr, vals
from replay_face_tracking import HeadSticker

def run(a, scene=None, models=None, geometry=None):
    start=time.perf_counter()
    fixture=json.loads(a.fixture.read_text())
    ref=json.loads(a.reference_replay.read_text())
    if (ref["fixture_sha256"]!=sha(a.fixture) or ref["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)} or sha(a.model_dir/POSE_FILE)!=POSE_SHA):
        raise ValueError("fixture or model mismatch")
    canonical=ref["candidate"]["frames"]
    prior=canonical if geometry is None else geometry
    if len(prior)!=len(canonical) or any(any(x[k]!=y[k] for k in ("track","tracks","raw_boxes","tracking_snapshot_sha256")) for x,y in zip(canonical,prior)):
        raise ValueError("geometry changed canonical identity")
    if len(prior)!=len(fixture["frames"]):raise ValueError("frame count mismatch")
    lib=PoseKernel(a.library).lib
    person,face,pose=models if models is not None else (model(PERSON_MODEL),model(FACE_MODEL),model(a.model_dir/POSE_FILE))
    cap_fn=lib.frame_kernel_research_anatomical_head
    cap_fn.argtypes=[P,S,S,U,MaskSpec,P,P];cap_fn.restype=C.c_int32
    own_fn=lib.frame_kernel_research_owned_head
    own_fn.argtypes=[P,S,S,U,MaskSpec,P,P,P,Ellipse,Ellipse,P];own_fn.restype=C.c_int32
    counts={"person":0,"face":0,"pose":0}
    out=copy.deepcopy(prior);attempts=[]
    for i,r in enumerate(prior):
        j=canonical_raw(r)
        if j is None or r.get("kind") not in (0,1,2) or not r.get("sticker") or not 3<=len(r["raw_boxes"])<=32 or len(ref["identity_protected_roots"])<3:continue
        if r.get("fragment_head_repair") or r.get("offline_tracklet_reentry"):continue
        old=r["sticker"];b=r["raw_boxes"][j]
        # Only crowded current geometry: another proposal intersects the
        # old placement or the current upper-body extent.
        upper=[b[0],b[1],b[2],b[1]+.35*(b[3]-b[1])]
        if not any(k!=j and (intersects(square(old),box) or intersects(upper,box)) for k,box in enumerate(r["raw_boxes"])):continue
        f=fixture["frames"][i];path=a.fixture.parent/f["asset"]
        if sha(path)!=f["sha256"]:raise ValueError("frame hash mismatch")
        if scene is None:
            im=Image.open(path).convert("RGB")
            tr,ds,ms=detect_instances(lib,person,im);counts["person"]+=1
        else:
            im,tr,ds,ms=scene(i)
        if [vals(d.box) for d in ds]!=r["raw_boxes"]:raise ValueError("raw evidence mismatch")
        sp=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
        kp=np.ascontiguousarray(np.stack([pose_points(pose,im,tr,ds[j],ms,j,flip=v)[:7] for v in (False,True)]),np.float32);counts["pose"]+=2
        cap=HeadSticker();rc=cap_fn(ptr(ms),ms.size,len(ds),j,sp,ptr(kp),C.byref(cap))
        if rc<0:raise RuntimeError("invalid cap")
        rec={"index":i,"raw":j,"cap_status":rc};attempts.append(rec)
        if rc:continue
        rec["bounds"]=vals(cap.bounds)
        cell=tr.input/tr.proto/tr.scale
        if cap.bounds.left-cell<=old[0]<=cap.bounds.right+cell and cap.bounds.top-cell<=old[1]<=cap.bounds.bottom+cell:
            rec["decision"]="center_agrees";continue
        fs=[detect_face(lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),ms[k]) or Ellipse() for k,d in enumerate(ds)]
        counts["face"]+=len(ds)
        measured=Ellipse(*r["raw"]) if r["kind"]==0 else fs[j]
        result=HeadSticker();rc=own_fn(ptr(ms),ms.size,len(ds),j,sp,ptr(kp),(Rect*len(ds))(*[d.box for d in ds]),(Ellipse*len(ds))(*fs),Ellipse(*old),measured,C.byref(result))
        if rc<0:raise RuntimeError("invalid ownership decision")
        rec["decision_status"]=rc;rec["observed"]=vals(measured);rec["rivals"]=[vals(f) for k,f in enumerate(fs) if k!=j and f.rx>0]
        if rc:continue
        v=vals(result.square);bounds=vals(result.bounds);rec.update(sticker=v,required=bounds)
        p=square(v)
        out[i].update(sticker=v,sticker_required_bounds=bounds,topology=result.topology,topology_rows=result.rows,
            owned_head_replacement=True,width=min(tr.width,p[2])-max(0,p[0]),height=min(tr.height,p[3])-max(0,p[1]))
    assert all(all(a[k]==b[k] for k in ("track","tracks","raw_boxes","tracking_snapshot_sha256","kind","render")) for a,b in zip(prior,out))
    result={"scope":__doc__,"library_sha256":sha(a.library),"fixture_sha256":sha(a.fixture),"reference_replay_sha256":sha(a.reference_replay),"target_id":ref["target_id"],"model_calls":counts,"elapsed_s":time.perf_counter()-start,"attempts":attempts,"changed":[i for i,(a,b) in enumerate(zip(prior,out)) if a!=b],"baseline":{"frames":prior},"candidate":{"frames":out}}
    a.output.write_text(json.dumps(result,separators=(",",":"))+"\n")
    print("attempts",len(attempts),"caps",sum(q["cap_status"]==0 for q in attempts),"changed",result["changed"],"calls",counts,"seconds",result["elapsed_s"],flush=True)

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for n in ("library","fixture","reference-replay","model-dir","output"):ap.add_argument("--"+n,type=Path,required=True)
    run(ap.parse_args())

if __name__=="__main__":main()
