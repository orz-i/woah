#!/usr/bin/env python3
"""Apply research-only continuous raw tracklet consensus and current cap geometry.

The prior dense pass never reads later labels. This second pass neither changes
a canonical identity nor modifies already rendered frames. Source review is a
separate step. Output contains geometric placement proposals, not native masks.
"""
import argparse
import ctypes as C
import copy
import json
from pathlib import Path
import numpy as np
from PIL import Image
from evaluate_body_reid import PERSON_MODEL, FACE_MODEL, MODEL_FILES, sha
from evaluate_pose_parts import PoseKernel, POSE_FILE, POSE_SHA, pose_points
from replay_face_geometry import P,S,U,MaskSpec,Ellipse,Rect,Transform,check,detect_instances,detect_face,model,ptr,struct,vals
from replay_face_tracking import I64,HeadSticker

Node=struct("ResearchTrackletNode",[("previous",U),("next",U),("flags",U)])
NONE=2**32-1

def select(kernel,frames,protected,target,prior):
    nodes=[];counts=[];times=[]
    for row,old in zip(frames,prior):
        n=len(row["boxes"]);counts.append(n);times.append(row["time_us"])
        target_observed=old["track"] is not None and old["track"]["observed"]
        for j,path in enumerate(row["paths"]):
            positive=any(v["root"]==target and v["raw"]==j for v in row["votes"])
            single=any(v["root"]==target and v["raw"]==j for v in row.get("single_votes",[]))
            conflict=(j in row["quarantined"] or
                      any(v["root"]!=target and v["raw"]==j for v in row["votes"]+row.get("single_votes",[])) or
                      any(v["root"]==target and v["raw"]!=j for v in row.get("single_votes",[])) or
                      any(owner!=target and owner in protected for owner in row["canonical_owners"][j]) or
                      (target_observed and target not in row["canonical_owners"][j]))
            nodes.append(Node(path[0] if path is not None else NONE,path[2] if path is not None else NONE,
                              int(positive)+2*int(single)+4*int(conflict)))
    fn=kernel.lib.frame_kernel_research_tracklet_consensus
    fn.argtypes=[P,P,S,P,S,P];fn.restype=C.c_int32
    out=(U*len(nodes))()
    check(fn((U*len(counts))(*counts),(I64*len(times))(*times),len(counts),
             (Node*len(nodes))(*nodes),len(nodes),out))
    selected={};at=0
    for i,n in enumerate(counts):
        for j in range(n):
            if out[at+j]:
                if i in selected:raise RuntimeError("multiple identity candidates")
                selected[i]=j
        at+=n
    return selected

def detail_recovery(kernel,detector,face,pose,im,tr,ds,masks,j,fs,ear,detail):
    lib=kernel.lib
    plan=lib.frame_kernel_research_tracklet_detail_views
    plan.argtypes=[MaskSpec,P];plan.restype=C.c_int32
    owner=lib.frame_kernel_research_tracklet_detail_owner
    owner.argtypes=[P,S,P,S,S,S,U,S,P,P];owner.restype=C.c_int32
    finish=lib.frame_kernel_research_tracklet_detail_finish
    finish.argtypes=[P,S,S,U,MaskSpec,P,P,P];finish.restype=C.c_int32
    project=lib.frame_kernel_reproject_mask
    project.argtypes=[P,S,Transform,Transform,Rect,P,S];project.restype=C.c_int32
    spec=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
    views=(Rect*2)()
    rc=plan(spec,views)
    if rc<0:raise RuntimeError("invalid detail plan")
    if rc: return None
    caps=[];detail["detail_views"]=[]
    for view in views:
        v=tuple(int(q) for q in vals(view));x0,y0,x1,y1=v
        ci=im.crop(v);lt,ld,lm=detect_instances(lib,detector,ci)
        evidence={"view":list(v),"local_count":len(ld)}
        detail["detail_views"].append(evidence)
        if not ld or len(ds)+len(ld)-1>32:return None
        projected=np.zeros((len(ds),lt.proto,lt.proto),np.uint8)
        scene_view=Rect(-x0,-y0,im.width-x0,im.height-y0)
        for k,m in enumerate(masks):
            check(project(ptr(m),m.size,tr,lt,scene_view,ptr(projected[k]),projected[k].size))
        local_index=U()
        confidences=np.asarray([d.confidence for d in ld],np.float32)
        rc=owner(ptr(projected),projected.size,ptr(lm),lm.size,len(ds),len(ld),j,lm[0].size,ptr(confidences),C.byref(local_index))
        evidence["owner_status"]=rc
        if rc<0:raise RuntimeError("invalid detail owner evidence")
        if rc:return None
        k=local_index.value
        packed=np.ascontiguousarray(np.stack([lm[k]]+[m for q,m in enumerate(projected) if q!=j]+[m for q,m in enumerate(lm) if q!=k]))
        local_faces=[detect_face(lib,face,ci,MaskSpec(lt.proto,lt.proto,lt,d.box),lm[q]) or Ellipse() for q,d in enumerate(ld)]
        rivals=[Ellipse(f.x-x0,f.y-y0,f.rx,f.ry) if f.rx>0 and f.ry>0 else Ellipse() for q,f in enumerate(fs) if q!=j]
        witnesses=[local_faces[k]]+rivals+[f for q,f in enumerate(local_faces) if q!=k]
        kp=np.ascontiguousarray(pose_points(pose,ci,lt,ld[k],lm,k)[[3,4,5,6]],np.float32)
        head=HeadSticker()
        rc=ear(ptr(packed),packed.size,len(packed),0,MaskSpec(lt.proto,lt.proto,lt,ld[k].box),ptr(kp),(Ellipse*len(witnesses))(*witnesses),C.byref(head))
        evidence["cap_status"]=rc
        if rc<0:raise RuntimeError("invalid detail cap")
        if rc:return None
        b=Rect(head.bounds.left+x0,head.bounds.top+y0,head.bounds.right+x0,head.bounds.bottom+y0)
        evidence.update(local_raw=k,bounds=vals(b),rows=head.rows)
        head.bounds=b
        head.square=Ellipse(head.square.x+x0,head.square.y+y0,head.square.rx,head.square.ry)
        caps.append(head)
    out=HeadSticker()
    rc=finish(ptr(masks),masks.size,len(ds),j,spec,(HeadSticker*2)(*caps),(Ellipse*len(ds))(*fs),C.byref(out))
    detail["detail_finish_status"]=rc
    if rc<0:raise RuntimeError("invalid detail consensus")
    return out if rc==0 else None


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for n in ("library","fixture","reference-replay","dense","model-dir","output"):
        ap.add_argument("--"+n,type=Path,required=True)
    ap.add_argument("--ear-cap",action="store_true",help="Candidate-only current ear-level cap with mapped pose flip corroboration")
    ap.add_argument("--detail-cap",action="store_true",help="Two current body detail views after all scene cap attempts fail")
    a=ap.parse_args()
    if a.detail_cap and not a.ear_cap:ap.error("--detail-cap requires --ear-cap")
    fixture=json.loads(a.fixture.read_text());base=json.loads(a.reference_replay.read_text());dense=json.loads(a.dense.read_text())
    if base["fixture_sha256"]!=sha(a.fixture) or dense["fixture_sha256"]!=sha(a.fixture) or dense["reference_replay_sha256"]!=sha(a.reference_replay):
        raise ValueError("mismatched input traces")
    if (base["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)} or dense["detector_hashes"]!=base["model_sha256"]
            or dense["pose_sha256"]!=POSE_SHA or sha(a.model_dir/POSE_FILE)!=POSE_SHA
            or dense["target_id"]!=base["target_id"] or dense["protected_roots"]!=base["identity_protected_roots"]):
        raise ValueError("model mismatch")
    frames=dense["frames"];target=base["target_id"];prior=base["candidate"]["frames"]
    if dense["library_sha256"]!=sha(a.library) or dense["reid_weights_sha256"]!={k:v for k,v in MODEL_FILES.items() if "0287" in k}:
        raise ValueError("dense evidence library or ReID weight mismatch")
    if target not in set(base["identity_protected_roots"]):
        raise ValueError("target is not a protected initial root")
    if len(prior)!=len(frames) or len(prior)!=len(fixture["frames"]) or any(r["index"]!=i or r["time_us"]!=prior[i]["time_us"] or r["boxes"]!=prior[i]["raw_boxes"] for i,r in enumerate(frames)):
        raise ValueError("raw evidence mismatch")
    kernel=PoseKernel(a.library);selected=select(kernel,frames,set(base["identity_protected_roots"]),target,prior)
    detector=model(PERSON_MODEL);face=model(FACE_MODEL);pose=model(a.model_dir/POSE_FILE)
    head=kernel.lib.frame_kernel_research_tracklet_head;head.argtypes=[P,S,S,U,MaskSpec,P,P,P];head.restype=C.c_int32
    if a.ear_cap:
        ear=kernel.lib.frame_kernel_research_tracklet_ear_cap
        flip=kernel.lib.frame_kernel_research_tracklet_flip_ear_cap
        for fn in (ear,flip):fn.argtypes=head.argtypes;fn.restype=C.c_int32
    output=copy.deepcopy(prior);attempts=[]
    for i,j in selected.items():
        if prior[i]["render"] is not None or prior[i]["track"] is None or prior[i]["track"]["observed"]:
            continue
        meta=fixture["frames"][i];f=a.fixture.parent/meta["asset"]
        if sha(f)!=meta["sha256"]:raise ValueError("source frame mismatch")
        im=Image.open(f).convert("RGB");tr,ds,masks=detect_instances(kernel.lib,detector,im)
        if [vals(d.box) for d in ds]!=frames[i]["boxes"]:raise ValueError("fresh raw evidence mismatch")
        kp=pose_points(pose,im,tr,ds[j],masks,j)
        points=np.ascontiguousarray(kp[[5,6]],np.float32)
        fs=[]
        for k,d in enumerate(ds):
            f=detect_face(kernel.lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),masks[k])
            fs.append(f if f is not None else Ellipse())
        placement=HeadSticker();spec=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
        rc=head(ptr(masks),masks.size,len(ds),j,spec,ptr(points),(Ellipse*len(ds))(*fs),C.byref(placement))
        if rc<0:raise RuntimeError("invalid current cap evidence")
        detail={"index":i,"raw":j,"status":rc}
        if a.ear_cap and rc!=0:
            q=np.ascontiguousarray(kp[[3,4,5,6]],np.float32)
            rc=ear(ptr(masks),masks.size,len(ds),j,spec,ptr(q),(Ellipse*len(ds))(*fs),C.byref(placement))
            if rc<0:raise RuntimeError("invalid ear-level cap evidence")
            detail["ear_status"]=rc
            if rc==0:
                detail["lane"]="ear_cap"
            else:
                qf=pose_points(pose,im,tr,ds[j],masks,j,flip=True)
                both=np.ascontiguousarray(np.stack([q,qf[[3,4,5,6]]]),np.float32)
                rc=flip(ptr(masks),masks.size,len(ds),j,spec,ptr(both),(Ellipse*len(ds))(*fs),C.byref(placement))
                if rc<0:raise RuntimeError("invalid flip-cap evidence")
                detail["flip_status"]=rc
                if rc==0:detail["lane"]="flip_ear_cap"
            detail["status"]=rc
        if a.detail_cap and rc!=0:
            local=detail_recovery(kernel,detector,face,pose,im,tr,ds,masks,j,fs,ear,detail)
            if local is not None:
                placement=local;rc=0;detail.update(lane="detail_cap",status=0)
        if rc==0:
            detail.update(sticker=vals(placement.square),bounds=vals(placement.bounds),rows=placement.rows)
            output[i].update(kind=7,render=vals(placement.square),sticker=vals(placement.square),
                             sticker_required_bounds=vals(placement.bounds),topology=placement.topology,
                             topology_rows=placement.rows,offline_tracklet_reentry=True)
        attempts.append(detail)
    spans=[]
    for i,j in selected.items():
        if spans and i==spans[-1]["end"]+1:
            spans[-1]["end"]=i;spans[-1]["raw"].append(j)
        else:spans.append({"start":i,"end":i,"raw":[j]})
    if any(x["track"]!=y["track"] or x["tracks"]!=y["tracks"] for x,y in zip(prior,output)):
        raise RuntimeError("research pass changed canonical identity")
    if any(x["render"] is not None and x!=y for x,y in zip(prior,output)):
        raise RuntimeError("research pass changed existing coverage")
    out={"scope":__doc__,"fixture_sha256":sha(a.fixture),"target_id":target,
         "dense_sha256":sha(a.dense),"reference_replay_sha256":sha(a.reference_replay),
         "library_sha256":sha(a.library),"ear_cap":a.ear_cap,"detail_cap":a.detail_cap,"spans":spans,"attempts":attempts,
         "baseline":{"frames":prior,"rendered":sum(r["render"] is not None for r in prior)},"candidate":{"frames":output,"rendered":sum(r["render"] is not None for r in output)}}
    a.output.write_text(json.dumps(out,separators=(",",":"))+"\n")
    print("spans",spans,"attempts",attempts,flush=True)
if __name__=="__main__":main()
