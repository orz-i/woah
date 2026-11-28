#!/usr/bin/env python3
"""Dense within-video retrieval and anonymous reciprocal raw tracklet evidence.
No later source labels enter the computation, no tracker state is changed.
Only scores, raw indices and audit geometry are saved; no crops or vectors.
"""
import argparse
import json
from pathlib import Path
import time
import numpy as np
from PIL import Image

from evaluate_body_reid import (PERSON_MODEL, FACE_MODEL, MODEL_FILES, sha, seed_paths,
    mean_references, pooled_queries, observed_raw)
from evaluate_pose_parts import (PoseKernel, POSE_FILE, POSE_SHA, pose_points,
    load_0287, part_embedding)
from replay_face_geometry import MaskSpec, detect_instances, detect_face, model, vals


def evidence(kernel, detector, face, pose, reid, im):
    tr,ds,ms=detect_instances(kernel.lib,detector,im)
    rgb=np.asarray(im).copy(); yy,xx=np.ogrid[:im.height,:im.width]
    faces=[]
    for j,d in enumerate(ds):
        f=detect_face(kernel.lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),ms[j])
        faces.append(None if f is None else vals(f))
        if f is not None:
            rgb[(abs(xx-f.x)<=1.5*f.rx)&(abs(yy-f.y)<=1.5*f.ry)]=114
    points=np.array([pose_points(pose,im,tr,d,ms,j) for j,d in enumerate(ds)],dtype=np.float32).reshape(-1,17,3)
    vectors,patches,support=kernel.extract(Image.fromarray(rgb),tr,ds,ms,points)
    features={"hist":kernel.clothing(im,tr,ds,ms),
              "torso":np.zeros((len(ds),256),np.float32)}
    for j in range(len(ds)):
        if support[j,0]:
            features["torso"][j]=part_embedding(reid,patches[j,0],0)
    return (tr,ds,ms),features,faces,support


def votes(kernel,ref,query):
    if not len(query["hist"]):return []
    global_ranks=kernel.rank(kernel.scores(ref["hist"],query["hist"],4))
    part_ranks=kernel.rank(kernel.scores(ref["torso"],query["torso"]))
    records=[]
    for root,(g,p) in enumerate(zip(global_ranks,part_ranks)):
        if int(p.index)==2**32-1 or int(g.index)==2**32-1:
            continue
        if p.accepted and int(p.index)==int(g.index) and g.row_margin>=.05:
            records.append({"root":root,"raw":int(p.index),
                            "part_score":float(p.score),"part_row":float(p.row_margin),
                            "part_col":float(p.column_margin),"global_row":float(g.row_margin)})
    return records


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for n in ("library","fixture","reference-replay","model-dir","output"):
        ap.add_argument("--"+n,type=Path,required=True)
    a=ap.parse_args()
    fixture=json.loads(a.fixture.read_text()); trace=json.loads(a.reference_replay.read_text())
    if trace["fixture_sha256"]!=sha(a.fixture) or trace["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)}:
        raise ValueError("source replay mismatch")
    if sha(a.model_dir/POSE_FILE)!=POSE_SHA:
        raise ValueError("pose hash mismatch")
    canonical=trace["candidate"]["frames"]; kernel=PoseKernel(a.library)
    detector=model(PERSON_MODEL); face=model(FACE_MODEL);pose=model(a.model_dir/POSE_FILE); reid=load_0287(a.model_dir)
    seed=[];seedfeat=[];recent=[];frames=[];start=time.perf_counter()
    refs=None
    for i,meta in enumerate(fixture["frames"]):
        f=a.fixture.parent/meta["asset"]
        if sha(f)!=meta["sha256"]:raise ValueError("frame hash mismatch")
        im=Image.open(f).convert("RGB")
        raw,features,faces,support=evidence(kernel,detector,face,pose,reid,im)
        _,ds,ms=raw
        if [vals(d.box) for d in ds]!=canonical[i]["raw_boxes"]:
            raise ValueError(f"raw evidence changed {i}")
        if i<5:
            seed.append(raw);seedfeat.append(features)
            if i==4:
                times=[canonical[k]["time_us"] for k in range(5)]
                n=sum(0<=t-times[0]<=250_000 for t in times)
                if n<3:
                    paths=[[j] for j in range(len(seed[0][1]))]
                else:
                    paths=seed_paths(kernel,seed[:n],canonical,times[:n])
                refs={name:mean_references(seedfeat,paths,name) for name in features}
        rec={"index":i,"time_us":canonical[i]["time_us"],"boxes":[vals(d.box) for d in ds],
             "faces":faces,"torso_support":[int(q) for q in support[:,0]],"paths":[None]*len(ds),
             "votes":[],"canonical_owners":[[] for _ in ds],"quarantined":[]}
        for t in canonical[i]["tracks"]:
            if t["observed"] and not t["dormant"]:
                j=observed_raw(canonical[i],t["id"])
                if j is not None:rec["canonical_owners"][j].append(t["id"])
        rec["quarantined"]=sorted({q[0] for q in canonical[i].get("body_merge_evidence",[])})
        frames.append(rec)
        recent=(recent+[(i,raw,features)])[-3:]
        if i>=6 and len(recent)==3:
            mid=i-1
            paths0=kernel.triplets([q[1] for q in recent],[frames[q[0]]["time_us"] for q in recent])
            pooled,_=pooled_queries([q[2] for q in recent],paths0,list(features))
            frames[mid]["paths"]=paths0
            frames[mid]["votes"]=votes(kernel,refs,pooled)
            frames[mid]["single_votes"]=votes(kernel,refs,recent[1][2])
        if i%25==0:print(i,"raw",len(ds),"time",round(time.perf_counter()-start,2),flush=True)
    out={"scope":__doc__,"fixture_sha256":sha(a.fixture),"reference_replay_sha256":sha(a.reference_replay),
         "library_sha256":sha(a.library),"detector_hashes":trace["model_sha256"],"pose_sha256":POSE_SHA,
         "reid_weights_sha256":{k:v for k,v in MODEL_FILES.items() if "0287" in k},
         "target_id":trace["target_id"],"protected_roots":trace["identity_protected_roots"],"seed_paths":paths,"frames":frames,
         "elapsed_s":time.perf_counter()-start}
    a.output.write_text(json.dumps(out,separators=(",",":"))+"\n")
    print("complete",len(frames),out["elapsed_s"],flush=True)

if __name__=="__main__":main()
