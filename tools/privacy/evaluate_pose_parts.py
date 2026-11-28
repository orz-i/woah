#!/usr/bin/env python3
"""Local head-reduced, pose-conditioned body-part retrieval research.

Landmarks are evaluation-only. Pose keypoints define anonymous sampling
geometry, not identity. No image, pose, or descriptor is written to disk.
"""
import argparse
import ctypes as C
import json
import math
import time
from pathlib import Path

import numpy as np
from PIL import Image

from evaluate_body_reid import (
    Kernel, MODEL_FILES, PERSON_MODEL, FACE_MODEL, observation_rows,
    observed_raw, seed_paths, mean_references, pooled_queries,
    classify, summarize, points_support, normalize, sha,
)
from replay_face_geometry import P, S, Transform, MaskSpec, check, detect_instances, detect_face, model, ptr, vals
from replay_face_tracking import Observation

POSE_FILE = "movenet-lightning-f16-4.tflite"
POSE_SHA = "0fac2226112d0371903ca86e3853cec24ef603a0b2f96f589b180f0ebdd135ab"


def pose_points(net, image, tr, det, masks, j, context=False, flip=False, exclusive=None):
    """Isolate one raw instance before pose inference; letterbox the crop."""
    b=det.box
    w,h=image.size
    side=max(b.right-b.left,b.bottom-b.top)*1.08
    cx,cy=(b.left+b.right)/2,(b.top+b.bottom)/2
    left,top=cx-side/2,cy-side/2
    if side<8:
        return np.zeros((17,3),np.float32)
    # The model receives an isolated source crop with a neutral background.
    xs=left+(np.arange(192)+.5)*side/192
    ys=top+(np.arange(192)+.5)*side/192
    u=np.floor((xs*tr.scale+tr.left)*tr.proto/tr.input).astype(int)
    v=np.floor((ys*tr.scale+tr.top)*tr.proto/tr.input).astype(int)
    valid=(xs[None,:]>=0)&(xs[None,:]<w)&(ys[:,None]>=0)&(ys[:,None]<h)
    valid &= (u[None,:]>=0)&(u[None,:]<tr.proto)&(v[:,None]>=0)&(v[:,None]<tr.proto)
    u=u.clip(0,tr.proto-1);v=v.clip(0,tr.proto-1)
    # The exclusive prototype grid is independent of the crop and target.
    # Gather it after counting owners once, rather than materializing every
    # instance's full 192x192 sampled mask for every pose input.
    if exclusive is None:
        exclusive=(masks>=128).sum(axis=0)==1
    visible=(masks[j,v[:,None],u[None,:]]>=128)&exclusive[v[:,None],u[None,:]]&valid
    z=np.asarray(image.transform((192,192),Image.Transform.EXTENT,(left,top,left+side,top+side),
                     Image.Resampling.BILINEAR,fillcolor=(114,114,114))).copy()
    if not context:
        z[~visible]=114
    if flip:
        z=np.ascontiguousarray(z[:,::-1])
    net.set_tensor(net.get_input_details()[0]["index"],z[None])
    net.invoke()
    p=net.get_tensor(net.get_output_details()[0]["index"]).reshape(17,3)
    out=np.stack([left+(1-p[:,1] if flip else p[:,1])*side,top+p[:,0]*side,p[:,2]],axis=1).astype(np.float32)
    if flip:
        out=out[[0,2,1,4,3,6,5,8,7,10,9,12,11,14,13,16,15]]
    if not np.isfinite(out).all() or (out[:,2]<0).any() or (out[:,2]>1).any():
        raise ValueError("invalid pose output")
    return out


class PoseKernel(Kernel):
    def __init__(self,path):
        super().__init__(path)
        self.parts=self.lib.frame_kernel_research_pose_parts
        self.parts.argtypes=[P,S,P,S,P,S,Transform,P,S,P,S,P,S,P,S]
        self.parts.restype=C.c_int32

    def extract(self, image,tr,ds,masks,points):
        rgb=np.ascontiguousarray(image)
        points=np.ascontiguousarray(points,np.float32)
        vectors=np.zeros((len(ds),2,1024),np.float32)
        patches=np.full((len(ds),2,64,64,3),114,np.uint8)
        support=np.zeros((len(ds),2),np.uint32)
        rows=(Observation*len(ds))(*observation_rows(tr,ds,masks))
        check(self.parts(rows,len(ds),ptr(masks),masks.size,ptr(rgb),rgb.size,tr,
                         ptr(points),points.size,ptr(vectors),vectors.size,ptr(patches),patches.size,ptr(support),support.size))
        return vectors,patches,support


def load_0287(folder):
    import openvino as ov
    for name in ("person-reidentification-retail-0287.xml","person-reidentification-retail-0287.bin"):
        if sha(folder/name)!=MODEL_FILES[name]:
            raise ValueError("ReID weight mismatch")
    compiled=ov.Core().compile_model(str(folder/"person-reidentification-retail-0287.xml"),
                      "CPU",{"INFERENCE_NUM_THREADS":2,"INFERENCE_PRECISION_HINT":"f32"})
    return compiled


def part_embedding(compiled,patch,part):
    # Out-of-domain ablation: preserve a body-shaped input and place each
    # normalized part in its approximate anatomical vertical band.
    canvas=np.full((256,128,3),114,np.uint8)
    lo,hi=(58,128) if part==0 else (128,213)
    canvas[lo:hi,16:112]=np.asarray(Image.fromarray(patch).resize((96,hi-lo),Image.Resampling.BILINEAR))
    inp=np.ascontiguousarray(canvas[:,:,::-1].transpose(2,0,1)[None],np.float32)
    return normalize(np.asarray(compiled([inp])[0]).copy().reshape(-1))


def evaluate(kernel,ref,query,policy,support,ids,frame,stats):
    mats={}
    for name in ref:
        mats[name]=kernel.scores(ref[name],query[name],4 if name=="hist" else 2 if name.startswith("color_") else 1)
    for typ in ("color","embed"):
        a,b=mats[typ+"_torso"],mats[typ+"_lower"]
        both=(a>=-1)&(b>=-1)
        # Both parts required for a cross-part score; single part is a
        # separate diagnostic, not automatic identity confirmation.
        mats[typ+"_both"]=np.where(both,.5*a+.5*b,-2).astype(np.float32)
    for name in ("color_both","embed_both","color_torso","color_lower"):
        a,b=mats["hist"],mats[name]
        mats["fusion_"+name]=np.where((a>=-1)&(b>=-1),.5*a+.5*b,-2).astype(np.float32)
    global_ranks=kernel.rank(mats["hist"])
    for name,mat in list(mats.items()):
        rank=kernel.rank(mat)
        for slot,ident in enumerate(ids):
            stats[policy].setdefault(name,{}).setdefault(str(ident),[]).append(classify(rank[ident],support,slot,frame))
            if name.startswith(("embed_","color_")):
                r=rank[ident]
                # Reuse FrameKernel rank for the unfiltered global pool.
                g=global_ranks[ident]
                primary=None if int(g.index)==2**32-1 else int(g.index)
                global_margin=float(g.row_margin)
                rec=classify(r,support,slot,frame)
                rec["global_raw"]=primary
                rec["global_row_margin"]=global_margin
                rec["accepted"]=bool(r.accepted and rec["top1"]==primary and global_margin>=.05)
                stats[policy].setdefault("corroborated_"+name,{}).setdefault(str(ident),[]).append(rec)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("library","fixture","labels","reference-replay","model-dir","output"):
        p.add_argument("--"+n,type=Path,required=True)
    p.add_argument("--selected-id",type=int,required=True)
    p.add_argument("--partner-id",type=int)
    p.add_argument("--pose-context",action="store_true",help="Use ordinary person crop for pose only; identity samples remain exclusive")
    args=p.parse_args()
    fixture=json.loads(args.fixture.read_text());labels=json.loads(args.labels.read_text())
    trace=json.loads(args.reference_replay.read_text())
    if trace["fixture_sha256"]!=sha(args.fixture) or trace["target_id"]!=args.selected_id:
        raise ValueError("replay fixture mismatch")
    if labels["sources"]["reference"]["testedMp4Sha256"]!=fixture["source_sha256"]:
        raise ValueError("source mismatch")
    if trace["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)}:
        raise ValueError("detector hash mismatch")
    if sha(args.model_dir/POSE_FILE)!=POSE_SHA:
        raise ValueError("pose hash mismatch")
    fps=fixture["fps"];canonical=trace["candidate"]["frames"]
    ids=[args.selected_id]+([] if args.partner_id is None else [args.partner_id])
    landmarks={round(q["ptsUs"]*fps/1e6):q for q in labels["annotations"]["reference"]}
    queries={k for k in landmarks if k>0}
    if not queries or min(queries)<=5 or max(queries)>=len(fixture["frames"])-1 or 0 not in landmarks:
        raise ValueError("invalid disjoint seed/query windows")
    kernel=PoseKernel(args.library);detector=model(PERSON_MODEL);face=model(FACE_MODEL)
    pose=model(args.model_dir/POSE_FILE);reid=load_0287(args.model_dir)
    stats={p:{} for p in ("single","early","early_triplet")}
    support_counts={};pose_ms=[];embed_ms=[];seeds=[];seed_features=[];recent=[];query_paths={}
    for i in sorted(set(range(5))|{q+d for q in queries for d in (-1,0,1)}):
        meta=fixture["frames"][i];f=args.fixture.parent/meta["asset"]
        if sha(f)!=meta["sha256"]:raise ValueError("frame hash mismatch")
        image=Image.open(f).convert("RGB")
        tr,ds,masks=detect_instances(kernel.lib,detector,image)
        if not ds or [vals(d.box) for d in ds]!=canonical[i]["raw_boxes"]:
            raise ValueError("raw evidence changed")
        points=[]
        for j,d in enumerate(ds):
            start=time.perf_counter();points.append(pose_points(pose,image,tr,d,masks,j,args.pose_context))
            pose_ms.append((time.perf_counter()-start)*1000)
        # Blank known faces in the RGB passed to part sampling as well.
        rgb=np.asarray(image).copy();yy,xx=np.ogrid[:image.height,:image.width]
        for j,d in enumerate(ds):
            f=detect_face(kernel.lib,face,image,MaskSpec(tr.proto,tr.proto,tr,d.box),masks[j])
            if f is not None:
                rgb[(abs(xx-f.x)<=1.5*f.rx)&(abs(yy-f.y)<=1.5*f.ry)]=114
        vec,patch,count=kernel.extract(Image.fromarray(rgb),tr,ds,masks,np.array(points))
        features={"hist":kernel.clothing(image,tr,ds,masks),
                  "color_torso":vec[:,0],"color_lower":vec[:,1],
                  "embed_torso":np.zeros((len(ds),256),np.float32),"embed_lower":np.zeros((len(ds),256),np.float32)}
        for j in range(len(ds)):
            for k,name in enumerate(("embed_torso","embed_lower")):
                if count[j,k]:
                    start=time.perf_counter()
                    features[name][j]=part_embedding(reid,patch[j,k],k)
                    embed_ms.append((time.perf_counter()-start)*1000)
        if i in landmarks:
            sup=points_support(tr,masks,landmarks[i])
            support_counts[str(i)]={"raw":len(ds),"torso":int((count[:,0]>0).sum()),
                "lower":int((count[:,1]>0).sum()),"both":int((count>0).all(1).sum()),
                "positives":{str(ident):[{"raw":j,"torso":int(count[j,0]),"lower":int(count[j,1])}
                    for j,v in enumerate(sup) if v[slot]>=128 and v[1-slot]<128] for slot,ident in enumerate(ids)}}
        if i<5:
            seeds.append((tr,ds,masks));seed_features.append(features)
            if i==0:
                for slot,ident in enumerate(ids):
                    if [j for j,v in enumerate(sup) if v[slot]>=128 and v[1-slot]<128]!=[ident]:
                        raise ValueError("initial target not unique")
            if i==4:
                paths=seed_paths(kernel,seeds,canonical,[round(k*1e6/fps) for k in range(5)])
                refs={"single":seed_features[0],"early":{n:mean_references(seed_features,paths,n) for n in features}}
            continue
        recent=(recent+[(i,(tr,ds,masks),features)])[-3:]
        center=i-1
        if center not in queries:continue
        if [r[0] for r in recent]!=[center-1,center,center+1]:raise ValueError("triplet gap")
        frames=[r[1] for r in recent];feats=[r[2] for r in recent]
        path=kernel.triplets(frames,[round(r[0]*1e6/fps) for r in recent])
        pooled,counts=pooled_queries(feats,path,list(features))
        query_paths[str(center)]={"qualified":sum(q is not None for q in path),"pooled_counts":counts}
        tr,_,masks=frames[1]
        support=points_support(tr,masks,landmarks[center])
        for policy,ref,query in (("single",refs["single"],feats[1]),("early",refs["early"],feats[1]),("early_triplet",refs["early"],pooled)):
            evaluate(kernel,ref,query,policy,support,ids,center,stats)
        print("query",center,support_counts[str(center)],flush=True)
    summary={p:{n:{i:summarize(rows) for i,rows in group.items()} for n,group in m.items()} for p,m in stats.items()}
    def timing(v):return {"calls":len(v),"median_ms":float(np.median(v)),"p95_ms":float(np.percentile(v,95))} if v else {"calls":0}
    out={"scope":__doc__,"library_sha256":sha(args.library),"fixture_sha256":sha(args.fixture),"labels_sha256":sha(args.labels),
         "reference_replay_sha256":sha(args.reference_replay),"pose_sha256":POSE_SHA,"pose_context":args.pose_context,"reid_weights_sha256":{n:v for n,v in MODEL_FILES.items() if "0287" in n},
         "detector_hashes":trace["model_sha256"],"early_seed_paths":paths,"part_support":support_counts,
         "query_triplets":query_paths,"summary":summary,"rows":stats,
         "pose_crop_and_infer_ms":timing(pose_ms),"part_crop_and_infer_ms":timing(embed_ms)}
    args.output.write_text(json.dumps(out,indent=2)+"\n")
    print(json.dumps(summary,indent=2))
if __name__=="__main__":main()
