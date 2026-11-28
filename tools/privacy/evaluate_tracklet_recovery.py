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
from replay_face_geometry import P,S,U,MaskSpec,Ellipse,check,detect_instances,detect_face,model,ptr,struct,vals
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

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for n in ("library","fixture","reference-replay","dense","model-dir","output"):
        ap.add_argument("--"+n,type=Path,required=True)
    a=ap.parse_args()
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
    output=copy.deepcopy(prior);attempts=[]
    for i,j in selected.items():
        if prior[i]["render"] is not None or prior[i]["track"] is None or prior[i]["track"]["observed"]:
            continue
        meta=fixture["frames"][i];f=a.fixture.parent/meta["asset"]
        if sha(f)!=meta["sha256"]:raise ValueError("source frame mismatch")
        im=Image.open(f).convert("RGB");tr,ds,masks=detect_instances(kernel.lib,detector,im)
        if [vals(d.box) for d in ds]!=frames[i]["boxes"]:raise ValueError("fresh raw evidence mismatch")
        points=np.ascontiguousarray(pose_points(pose,im,tr,ds[j],masks,j)[[5,6]],np.float32)
        fs=[]
        for k,d in enumerate(ds):
            f=detect_face(kernel.lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),masks[k])
            fs.append(f if f is not None else Ellipse())
        placement=HeadSticker();spec=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
        rc=head(ptr(masks),masks.size,len(ds),j,spec,ptr(points),(Ellipse*len(ds))(*fs),C.byref(placement))
        if rc<0:raise RuntimeError("invalid current cap evidence")
        detail={"index":i,"raw":j,"status":rc}
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
         "library_sha256":sha(a.library),"spans":spans,"attempts":attempts,
         "baseline":{"frames":prior,"rendered":sum(r["render"] is not None for r in prior)},"candidate":{"frames":output,"rendered":sum(r["render"] is not None for r in output)}}
    a.output.write_text(json.dumps(out,separators=(",",":"))+"\n")
    print("spans",spans,"attempts",attempts,flush=True)
if __name__=="__main__":main()
