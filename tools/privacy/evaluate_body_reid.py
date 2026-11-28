#!/usr/bin/env python3
"""Local, sparse body-retrieval research on pinned fixtures; no tracker mutation.

Only the initial selected raw index and the first <=5 consecutive real
observations seed references. Source landmarks evaluate retrieval, never
choose a match. Model vectors/crops remain in process memory; JSON includes
only scores, raw indices, and source-audit outcomes. Headblank is a diagnostic
background-retaining ablation, not a no-face or release guarantee.
"""
import argparse
import ctypes as C
import hashlib
import importlib.metadata
import json
import math
import time
from pathlib import Path

import numpy as np
from PIL import Image

from replay_face_geometry import (
    ROOT, F, U, S, P, MaskSpec, Transform, check, detect_face,
    detect_instances, load, model, ptr, struct, vals,
)
from replay_face_tracking import Observation, I64

MODEL_FILES = {
    "person-reidentification-retail-0287.xml": "95125bc630208d9a27370b11bd04d220f93f64b60fe4be5bc576e9aebb69603d",
    "person-reidentification-retail-0287.bin": "8811b3db2f6ebacbf2c05510593783316026ff779d9b048b6ca2581f17baf86c",
    "person-reidentification-retail-0288.xml": "0d5021c772e603dce69d9ef2e326ecf0a43234cc8b4c5bb5e8aa566f090bdb51",
    "person-reidentification-retail-0288.bin": "08e24c2f1dd976ff8e1f667b93db2950ac8db8da09e163e1ca6463b82d1dbc42",
    "dancetrack-osnet-x1_0.safetensors": "fb81ce6174baa711aa2691ed911b1df5478ed8aa9b16d3e34d4ce02bb5436733",
}
PERSON_MODEL = ROOT / "models/litert/yolo11n-seg-fp16.tflite"
FACE_MODEL = ROOT / "testdata/models/face/blaze_face_full_range.tflite"
Rank = struct("Rank", [("index", U), ("score", F), ("row_margin", F),
                       ("column_margin", F), ("accepted", U)])


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def observation_rows(tr, ds, masks, offset=0):
    return [Observation(d.box, d.confidence, d.box.bottom, offset+j*masks[j].size,
                        tr.proto, tr.proto, tr) for j, d in enumerate(ds)]


class Kernel:
    def __init__(self, path):
        self.lib = load(path)
        for name, args in (
            ("research_clothing_vectors", [P, S, P, S, P, S, Transform, P, S]),
            ("research_similarity", [P, S, P, S, S, S, S, S, P]),
            ("research_rank", [P, S, S, S, F, F, P]),
            ("offline_tracklet_window", [P, S, P, S, P, P, S, U, U, P]),
            ("research_triplet_paths", [P,S,P,S,P,P,P,S]),
        ):
            fn = getattr(self.lib, "frame_kernel_"+name)
            fn.argtypes, fn.restype = args, C.c_int32
            setattr(self, name, fn)

    def clothing(self, im, tr, ds, masks):
        rgb = np.ascontiguousarray(im)
        rows = (Observation*len(ds))(*observation_rows(tr, ds, masks))
        out = np.zeros((len(ds), 2048), np.float32)
        check(self.research_clothing_vectors(rows, len(ds), ptr(masks), masks.size,
              ptr(rgb), rgb.size, tr, ptr(out), out.size))
        return out

    def scores(self, a, b, blocks=1):
        a, b = (np.ascontiguousarray(v, dtype=np.float32) for v in (a, b))
        if a.ndim != 2 or b.ndim != 2 or a.shape[1] != b.shape[1]:
            raise ValueError("descriptor shape mismatch")
        out = np.empty((len(a), len(b)), np.float32)
        check(self.research_similarity(ptr(a), a.size, ptr(b), b.size,
              len(a), len(b), a.shape[1], blocks, ptr(out)))
        return out

    def rank(self, mat):
        mat = np.ascontiguousarray(mat, np.float32)
        out = (Rank*len(mat))()
        check(self.research_rank(ptr(mat), mat.size, *mat.shape, .65, .05, out))
        return list(out)

    @staticmethod
    def pack(frames):
        rows, pools, counts, offset = [], [], [], 0
        for tr, ds, masks in frames:
            counts.append(len(ds))
            rows.extend(observation_rows(tr, ds, masks, offset))
            pools.append(masks.reshape(-1))
            offset += masks.size
        pool = np.ascontiguousarray(np.concatenate(pools))
        obs = (Observation*len(rows))(*rows)
        return obs,pool,(U*len(counts))(*counts)

    def path(self, frames, times, left, right):
        obs,pool,counts=self.pack(frames)
        if not all(counts):
            return None
        out=(U*len(frames))()
        rc=self.offline_tracklet_window(obs,len(obs),ptr(pool),pool.size,counts,
             (I64*len(times))(*times),len(frames),left,right,out)
        if rc<0:
            raise RuntimeError("invalid seed tracklet")
        return list(out) if rc==0 else None

    def triplets(self,frames,times):
        obs,pool,counts=self.pack(frames)
        out=np.zeros((counts[1],2),np.uint32)
        check(self.research_triplet_paths(obs,len(obs),ptr(pool),pool.size,counts,
              (I64*3)(*times),ptr(out),out.size))
        return [None if int(v[0])==2**32-1 else [int(v[0]),i,int(v[1])]
                for i,v in enumerate(out)]


def body_crop(im, tr, det, masks, j, faces, exclusive):
    a = np.asarray(im)
    h, w = a.shape[:2]
    b = det.box
    l, r = max(0, math.floor(b.left)), min(w, math.ceil(b.right))
    top, bottom = max(0, math.floor(b.top)), min(h, math.ceil(b.bottom))
    if r-l < 8 or bottom-top < 16:
        return None
    y, x = np.mgrid[top:bottom, l:r]
    # Preserve input layout; blank the top quarter and all detected face
    # envelopes. Face detector misses remain possible: this is head reduction.
    valid = y >= b.top+.25*(b.bottom-b.top)
    if exclusive:
        u = np.floor((x*tr.scale+tr.left)*tr.proto/tr.input).astype(int).clip(0,tr.proto-1)
        v = np.floor((y*tr.scale+tr.top)*tr.proto/tr.input).astype(int).clip(0,tr.proto-1)
        valid &= (masks[j,v,u]>=128)&((masks[:,v,u]>=128).sum(0)==1)
    for f in faces:
        valid &= ~((np.abs(x-f.x)<=1.5*f.rx)&(np.abs(y-f.y)<=1.5*f.ry))
    if valid.sum() < .12*valid.size:
        return None
    z = a[top:bottom,l:r].copy()
    z[~valid] = 114
    return np.asarray(Image.fromarray(z).resize((128,256), Image.Resampling.BILINEAR))


def normalize(v):
    v = np.asarray(v, np.float64).reshape(-1)
    n = np.linalg.norm(v)
    if not np.isfinite(n) or n <= 1e-12 or not np.isfinite(v).all():
        raise ValueError("nonfinite/zero model embedding")
    return (v/n).astype(np.float32)


def load_encoders(folder, precision):
    import openvino as ov
    import torch
    from reid.architectures.osnet import build_osnet
    from safetensors.torch import load_file
    for file, digest in MODEL_FILES.items():
        if sha(folder/file) != digest:
            raise ValueError(f"model SHA-256 mismatch: {file}")
    core = ov.Core()
    out, meta = {}, {}
    for name in ("0287", "0288"):
        config = {"INFERENCE_NUM_THREADS": 2}
        if precision == "f32":
            config["INFERENCE_PRECISION_HINT"] = "f32"
        c = core.compile_model(str(folder/f"person-reidentification-retail-{name}.xml"), "CPU", config)
        if list(c.input(0).shape) != [1,3,256,128] or list(c.output(0).shape) != [1,256]:
            raise ValueError("unexpected OpenVINO model contract")
        def infer_ov(crop, compiled=c):
            inp = np.ascontiguousarray(crop[:,:,::-1].transpose(2,0,1)[None], np.float32)
            return np.asarray(compiled([inp])[0]).copy().reshape(-1)
        out[name] = (256,infer_ov)
        meta[name] = {"dimension": 256,"precision":str(c.get_property("INFERENCE_PRECISION_HINT"))}
    torch.set_num_threads(2)
    net = build_osnet()
    net.load_state_dict(load_file(str(folder/"dancetrack-osnet-x1_0.safetensors")),strict=True)
    net.eval()
    def infer_dance(crop):
        rgb = np.ascontiguousarray(crop.transpose(2,0,1)[None],np.float32)/255.0
        rgb = (rgb-np.array([.485,.456,.406],np.float32)[None,:,None,None])/np.array([.229,.224,.225],np.float32)[None,:,None,None]
        with torch.inference_mode():
            return net(torch.from_numpy(rgb)).numpy().copy().reshape(-1)
    out["dance"] = (512,infer_dance)
    meta["dance"] = {"dimension":512,"precision":"float32","research_license_review_required":True}
    return out, meta


def observed_raw(row, ident):
    track = next((q for q in row["tracks"] if q["id"] == ident and q["observed"]
                  and not q["dormant"]), None)
    if track is None:
        return None
    matches = [j for j,b in enumerate(row["raw_boxes"]) if b == track["bounds"]]
    return matches[0] if len(matches)==1 else None


def seed_paths(kernel, seed, trace, times):
    """Use only already committed initial observations corroborated by Rust.
    Every included sample must agree with its same-root canonical observation.
    No later reference expansion or learning on an occlusion."""
    paths = []
    for i in range(len(seed[0][1])):
        path = [i]
        for end in range(len(seed)-1,1,-1):
            canonical = [observed_raw(trace[q],i) for q in range(end+1)]
            if canonical[0]!=i or any(q is None for q in canonical):
                continue
            proof = kernel.path(seed[:end+1],times[:end+1],i,canonical[-1])
            if proof == canonical:
                path = proof
                break
        paths.append(path)
    return paths


def mean_references(features, paths, name):
    a = features[0][name].copy()
    # A missing initial descriptor cannot gain an identity from later frames.
    for i, path in enumerate(paths):
        if not np.any(a[i]):
            continue
        v = [features[k][name][j] for k,j in enumerate(path)
             if np.any(features[k][name][j])]
        if v:
            a[i] = np.mean(v,axis=0)
    return a


def pooled_queries(features,paths,names):
    out={name:features[1][name].copy() for name in names}
    counts={name:0 for name in names}
    for j,path in enumerate(paths):
        if path is None:
            continue
        for name in names:
            vectors=[features[k][name][q] for k,q in enumerate(path)]
            if all(np.any(v) for v in vectors):
                out[name][j]=np.mean(vectors,axis=0)
                counts[name]+=1
    return out,counts

def points_support(tr, masks, label):
    def at(mask, point):
        x,y=point
        u=math.floor((x*tr.width*tr.scale+tr.left)*tr.proto/tr.input)
        v=math.floor((y*tr.height*tr.scale+tr.top)*tr.proto/tr.input)
        if not (0<=u<tr.proto and 0<=v<tr.proto):
            raise ValueError("landmark out of grid")
        return int(mask[v,u])
    return [[at(m,label["selected"]),at(m,label["distractor"])] for m in masks]


def classify(rank,support,slot,frame):
    positives = [j for j,v in enumerate(support) if v[slot]>=128 and v[1-slot]<128]
    j=int(rank.index)
    if j==2**32-1:
        outcome="no_candidate"
        j=None
    else:
        own,negative=support[j][slot],support[j][1-slot]
        outcome=("correct" if j in positives else "negative" if own<128 and negative>=128
                 else "ambiguous" if own>=128 and negative>=128 else "unconfirmed")
    return {"frame":frame,"positive_raw_indices":positives,"top1":j,"outcome":outcome,
            "score":float(rank.score),"row_margin":float(rank.row_margin),
            "column_margin":float(rank.column_margin),"accepted":bool(rank.accepted)}


def summarize(rows):
    return {"queries":len(rows),"queries_with_positive":sum(bool(r["positive_raw_indices"]) for r in rows),
            "rank1_correct":sum(r["outcome"]=="correct" for r in rows),
            "rank1_negative":sum(r["outcome"]=="negative" for r in rows),
            "accepted_correct":sum(r["accepted"] and r["outcome"]=="correct" for r in rows),
            "accepted_other":sum(r["accepted"] and r["outcome"]!="correct" for r in rows),
            "abstained":sum(not r["accepted"] for r in rows)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("library","fixture","labels","reference-replay","model-dir","output"):
        p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--selected-id",type=int,required=True)
    p.add_argument("--partner-id",type=int,help="Only if the source negative is the same persistent partner")
    p.add_argument("--precision",choices=("f32","auto"),default="f32")
    a=p.parse_args()
    fixture=json.loads(a.fixture.read_text())
    labels=json.loads(a.labels.read_text())
    trace=json.loads(a.reference_replay.read_text())
    if trace["fixture_sha256"]!=sha(a.fixture) or trace["target_id"]!=a.selected_id:
        raise ValueError("reference replay/fixture/identity mismatch")
    if fixture["source_sha256"]!=labels["sources"]["reference"]["testedMp4Sha256"]:
        raise ValueError("source hash mismatch")
    if trace["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)}:
        raise ValueError("detector model hash mismatch")
    canonical=trace["candidate"]["frames"]
    fps=fixture["fps"]
    ids=[a.selected_id]+([a.partner_id] if a.partner_id is not None else [])
    if len(set(ids))!=len(ids) or min(ids)<0:
        raise ValueError("invalid initial IDs")
    landmarks={round(q["ptsUs"]*fps/1e6):q for q in labels["annotations"]["reference"]}
    if 0 not in landmarks or any(0<i<=5 for i in landmarks):
        raise ValueError("require a seed label and disjoint later queries")
    if max(landmarks)>=len(fixture["frames"])-1:
        raise ValueError("landmark outside fixture")
    kernel=Kernel(a.library)
    detector=model(PERSON_MODEL)
    face=model(FACE_MODEL)
    encoders, metadata=load_encoders(a.model_dir,a.precision)
    names=["hist"]+[f"{k}-{mode}" for k in encoders for mode in ("headblank","exclusive")]
    fused=["fusion-"+n for n in names[1:]]
    stats={policy:{n:{str(i):[] for i in ids} for n in names+fused} for policy in ("single","early","early_triplet")}
    health={n:{"norms":[],"active":[],"pairs":0,"near_duplicate_pairs":0} for n in names[1:]}
    latency={k:[] for k in encoders}
    seeds,seed_features=[],[]
    references=None
    paths=None
    query_indices={q for q in landmarks if q>0}
    needed=set(range(5))|{q+d for q in query_indices for d in (-1,0,1)}
    recent=[]
    query_paths={}
    for i in sorted(needed):
        meta=fixture["frames"][i]
        path=a.fixture.parent/meta["asset"]
        if sha(path)!=meta["sha256"]:
            raise ValueError("frame hash mismatch")
        im=Image.open(path).convert("RGB")
        tr,ds,masks=detect_instances(kernel.lib,detector,im)
        if not ds or [vals(d.box) for d in ds]!=canonical[i]["raw_boxes"]:
            raise ValueError(f"raw observation mismatch at {i}")
        faces=[f for j,d in enumerate(ds) if (f:=detect_face(kernel.lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),masks[j])) is not None]
        feats={"hist":kernel.clothing(im,tr,ds,masks)}
        for k,(dim,_) in encoders.items():
            for mode in ("headblank","exclusive"):
                feats[f"{k}-{mode}"]=np.zeros((len(ds),dim),np.float32)
        for j,d in enumerate(ds):
            for mode in ("headblank","exclusive"):
                crop=body_crop(im,tr,d,masks,j,faces,mode=="exclusive")
                if crop is None:
                    continue
                for k,(dim,infer) in encoders.items():
                    start=time.perf_counter()
                    v=infer(crop)
                    latency[k].append((time.perf_counter()-start)*1000)
                    if len(v)!=dim:
                        raise ValueError("model dimension changed")
                    key=f"{k}-{mode}"
                    health[key]["norms"].append(float(np.linalg.norm(v.astype(np.float64))))
                    health[key]["active"].append(int((abs(v)>1e-6).sum()))
                    feats[key][j]=normalize(v)
        for name in names[1:]:
            vec=feats[name][np.any(feats[name]!=0,axis=1)]
            if len(vec):
                dots=kernel.scores(vec,vec)
                health[name]["near_duplicate_pairs"]+=int(np.triu(dots>=.99999,k=1).sum())
                health[name]["pairs"]+=len(vec)*(len(vec)-1)//2
        if i<5:
            seeds.append((tr,ds,masks))
            seed_features.append(feats)
            if i==0:
                support=points_support(tr,masks,landmarks[0])
                for slot,ident in enumerate(ids):
                    positives=[j for j,v in enumerate(support) if v[slot]>=128 and v[1-slot]<128]
                    if positives!=[ident]:
                        raise ValueError("initial target lacks unique source support")
            if i==4:
                paths=seed_paths(kernel,seeds,canonical,[round(k*1e6/fps) for k in range(5)])
                references={"single":seed_features[0],
                            "early":{name:mean_references(seed_features,paths,name) for name in names}}
                print("seed path lengths", [len(path) for path in paths],flush=True)
            continue
        recent=(recent+[(i,(tr,ds,masks),feats)])[-3:]
        center=i-1
        if center not in query_indices:
            continue
        if [q[0] for q in recent]!=[center-1,center,center+1]:
            raise RuntimeError("query triplet is not consecutive")
        frames=[q[1] for q in recent]
        qfeatures=[q[2] for q in recent]
        paths0=kernel.triplets(frames,[round(q[0]*1e6/fps) for q in recent])
        pooled,counts=pooled_queries(qfeatures,paths0,names)
        query_paths[str(center)]={"paths":paths0,"pooled_counts":counts}
        tr,ds,masks=frames[1]
        support=points_support(tr,masks,landmarks[center])
        for policy,ref,query in (("single",references["single"],qfeatures[1]),
                                  ("early",references["early"],qfeatures[1]),
                                  ("early_triplet",references["early"],pooled)):
            mats={name:kernel.scores(ref[name],query[name],4 if name=="hist" else 1) for name in names}
            for name in fused:
                h,d=mats["hist"],mats[name[7:]]
                # Equal-weight fusion is a frozen benchmark ablation, not
                # a new runtime tracker policy or calibrated probability.
                mats[name]=np.where((h>=-1)&(d>=-1),.5*h+.5*d,-2).astype(np.float32)
            for name,mat in mats.items():
                rank=kernel.rank(mat)
                for slot,ident in enumerate(ids):
                    stats[policy][name][str(ident)].append(classify(rank[ident],support,slot,center))
        print("query",center,"raw",len(ds),"triplets",sum(q is not None for q in paths0),flush=True)
    summary={p:{n:{i:summarize(rows) for i,rows in ids.items()} for n,ids in models.items()} for p,models in stats.items()}
    out={"scope":__doc__,"library_sha256":sha(a.library),"fixture_sha256":sha(a.fixture),
         "labels_sha256":sha(a.labels),"reference_replay_sha256":sha(a.reference_replay),
         "detector_hashes":trace["model_sha256"],"weights_sha256":MODEL_FILES,
         "versions":{n:importlib.metadata.version(n) for n in ("numpy","pillow","ai-edge-litert","openvino","torch","reid")},
         "encoders":metadata,"selected_ids":ids,"early_seed_paths":paths,"query_triplets":query_paths,
         "minimum":.65,"reciprocal_margin":.05,"summary":summary,"rows":stats,
         "health":{k:{"norm_min":min(v["norms"]),"norm_median":float(np.median(v["norms"])),
                       "active_min":min(v["active"]),"active_median":float(np.median(v["active"])),
                       "near_duplicate_pairs":v["near_duplicate_pairs"],"pairs":v["pairs"]} for k,v in health.items()},
         "model_only_latency_ms":{k:{"calls":len(v),"median":float(np.median(v)),"p95":float(np.percentile(v,95))} for k,v in latency.items()}}
    a.output.write_text(json.dumps(out,indent=2)+"\n")
    print(json.dumps(summary,indent=2))


if __name__=="__main__":
    main()
