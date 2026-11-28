#!/usr/bin/env python3
"""Research-only current profile head correction on exact observed bodies.

Only projected placements with a current rival-face conflict can be
replaced. Diagnostic direct-frame requests record geometry without edits.
No temporal, tracker, or model state is updated by the second pass.
"""
import argparse
import copy
import ctypes as C
import json
from pathlib import Path

import numpy as np
from PIL import Image

from evaluate_body_reid import PERSON_MODEL, FACE_MODEL, sha
from evaluate_pose_parts import PoseKernel, POSE_FILE, POSE_SHA, pose_points
from replay_face_geometry import P,S,U,MaskSpec,Ellipse,detect_instances,detect_face,model,ptr,vals
from replay_face_tracking import HeadSticker


def intersects(a, b):
    return min(a[2],b[2])>max(a[0],b[0]) and min(a[3],b[3])>max(a[1],b[1])


def square(v):
    x,y,rx,ry=v
    r=max(8,1.02*max(rx,ry))
    return [x-r,y-r,x+r,y+r]


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ("library","fixture","reference-replay","model-dir","output"):
        ap.add_argument("--"+name,type=Path,required=True)
    ap.add_argument("--diagnostic-indices",default="",help="Explicit geometry audit samples; never override direct placements")
    a=ap.parse_args()
    fixture=json.loads(a.fixture.read_text());ref=json.loads(a.reference_replay.read_text())
    if ref["fixture_sha256"]!=sha(a.fixture) or ref["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)} or sha(a.model_dir/POSE_FILE)!=POSE_SHA:
        raise ValueError("fixture or model hash mismatch")
    prior=ref["candidate"]["frames"];target=ref["target_id"]
    if len(prior)!=len(fixture["frames"]):raise ValueError("trace length")
    diagnostic={int(v) for v in a.diagnostic_indices.split(",") if v}
    if diagnostic-set(range(len(prior))):raise ValueError("diagnostic index outside fixture")
    kernel=PoseKernel(a.library);person=model(PERSON_MODEL);face=model(FACE_MODEL);pose=model(a.model_dir/POSE_FILE)
    fn=kernel.lib.frame_kernel_research_profile_head_cap
    fn.argtypes=[P,S,S,U,MaskSpec,P,P,Ellipse,U,P];fn.restype=C.c_int32
    out=copy.deepcopy(prior);attempts=[];counts={"person":0,"face":0,"pose":0}
    for i,r in enumerate(prior):
        track=r.get("track")
        if (not track or not track["observed"] or track["dormant"] or not r.get("sticker")
                or not 3<=len(r["raw_boxes"])<=32 or len(ref["identity_protected_roots"])<3):
            continue
        j0=[k for k,b in enumerate(r["raw_boxes"]) if b==track["bounds"]]
        if len(j0)!=1:continue
        j=j0[0];old=square(r["sticker"])
        # Scheduling preflight only. Rust validates actual detected-face
        # competition, direct-hit absence, and coherent current caps.
        possible=r["kind"]==1 and r.get("crowded_face_consensus",{}).get("accepted") is not False and any(
            k!=j and intersects(old,b) for k,b in enumerate(r["raw_boxes"]))
        if not possible and i not in diagnostic:continue
        meta=fixture["frames"][i];f=a.fixture.parent/meta["asset"]
        if sha(f)!=meta["sha256"]:raise ValueError("frame hash mismatch")
        im=Image.open(f).convert("RGB");tr,ds,ms=detect_instances(kernel.lib,person,im);counts["person"]+=1
        if [vals(d.box) for d in ds]!=r["raw_boxes"]:raise ValueError("current raw evidence mismatch")
        # The exact current raw index is derived from the canonical observed
        # box and remains unique among the raw proposals.
        sp=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
        fs=[detect_face(kernel.lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),ms[k]) or Ellipse() for k,d in enumerate(ds)]
        counts["face"]+=len(ds)
        # The same square overlap rule is also enforced by FrameKernel.
        def rival_conflict():
            for k,f in enumerate(fs):
                if k==j or f.rx<=0 or f.ry<=0:continue
                w=max(0,min(old[2],f.x+f.rx)-max(old[0],f.x-f.rx))
                h=max(0,min(old[3],f.y+f.ry)-max(old[1],f.y-f.ry))
                if w*h>0.10*4*f.rx*f.ry:return True
            return False
        repair=possible and fs[j].rx==0 and fs[j].ry==0 and rival_conflict()
        if not repair and i not in diagnostic:continue
        kp=np.ascontiguousarray(np.stack([pose_points(pose,im,tr,ds[j],ms,j,flip=q)[:7] for q in (False,True)]),np.float32)
        counts["pose"]+=2
        cap=HeadSticker()
        rc=fn(ptr(ms),ms.size,len(ds),j,sp,ptr(kp),(Ellipse*len(fs))(*fs),Ellipse(*r["sticker"]),int(repair),C.byref(cap))
        if rc<0:raise RuntimeError("invalid profile geometry")
        detail={"index":i,"raw":j,"repair":bool(repair),"status":rc}
        if rc==0:
            detail.update(bounds=vals(cap.bounds),sticker=vals(cap.square),rows=cap.rows)
            if repair:
                v=square(vals(cap.square))
                out[i].update(sticker=vals(cap.square),sticker_required_bounds=vals(cap.bounds),
                              topology=cap.topology,topology_rows=cap.rows,profile_head_repair=True,
                              width=min(tr.width,v[2])-max(0,v[0]),height=min(tr.height,v[3])-max(0,v[1]))
        attempts.append(detail)
    changed=[i for i,(x,y) in enumerate(zip(prior,out)) if x!=y]
    if any(prior[i]["kind"]!=1 or prior[i]["track"]!=out[i]["track"] or prior[i]["tracks"]!=out[i]["tracks"] for i in changed):
        raise RuntimeError("profile geometry changed a canonical identity")
    result={"scope":__doc__,"library_sha256":sha(a.library),"fixture_sha256":sha(a.fixture),
            "reference_replay_sha256":sha(a.reference_replay),"target_id":target,"model_calls":counts,
            "attempts":attempts,"changed":changed,"baseline":{"frames":prior},"candidate":{"frames":out}}
    a.output.write_text(json.dumps(result,separators=(",",":"))+"\n")
    print("changed",changed,"calls",counts,"attempts",attempts,flush=True)

if __name__=="__main__":main()
