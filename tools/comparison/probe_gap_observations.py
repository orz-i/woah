#!/usr/bin/env python3
"""Host-only missing-span diagnosis and two-sided current-observation experiment.

Canonical tracking runs once unchanged. Examine only missing source samples
bounded by original accepted strong observations. No synthetic/interpolated
mask, no new tracker, no re-seeding, no state changes or changes to prior
alpha. Two-sided geometry and appearance corroborate an actual current
instance. Sparse labels score the output, never select gap candidates.
"""
from __future__ import annotations

import argparse
import collections
import ctypes as c
import json
from pathlib import Path

import cv2
import numpy as np

import cutout_m0 as m
from compare_dance_identity import observe
from probe_selected_refinement import sha, source_samples
from probe_touch_selection import bind_touch, select, TouchRows
from replay_adaptive_detail import bind_detail, replay
from review_real_dance import assess, missing_spans, compare

# Fixed hypothesis; do not search thresholds against the holdouts.
MAX_BRACKET_US = 1_200_000
MIN_CONFIDENCE = .25
MIN_ENDPOINT_SIMILARITY = .90
MIN_ANCHOR_SIMILARITY = .82
MIN_COMPETITOR_MARGIN = .08
MAX_CENTER_ERROR = .25
MIN_EXTENT_RATIO = .65
MAX_EXTENT_RATIO = 1.55
MIN_BOX_OVERLAP = .40


def box(d):
    b=d.bounds
    return np.array([b.left,b.top,b.right,b.bottom],np.float64)


def similarity(a,b):
    return float(np.sqrt(a*b).sum())


def iou(a,b):
    ext=np.maximum(0,np.minimum(a[2:],b[2:])-np.maximum(a[:2],b[:2]))
    area=float(np.prod(ext))
    union=float(np.prod(a[2:]-a[:2])+np.prod(b[2:]-b[:2])-area)
    return area/union if union>0 else 0.


def extent(b):
    return float(np.max(b[2:]-b[:2]))


def center(b):
    return (b[:2]+b[2:])*.5


def geometry(a,b,fraction):
    return a+(b-a)*fraction


def candidate_metrics(d,feature,expected,left,right,seed,negatives):
    b=box(d)
    sz=extent(expected)
    ea,eb=similarity(feature,left),similarity(feature,right)
    anchor=similarity(feature,seed)
    competitor=max((similarity(feature,n) for n in negatives),default=0.)
    return {"confidence":float(d.confidence),"leftSimilarity":ea,"rightSimilarity":eb,
            "anchorSimilarity":anchor,"negativeSimilarity":competitor,
            "margin":min(ea,eb,anchor)-competitor,
            "centerError":float(np.linalg.norm(center(b)-center(expected))/max(sz,1.)),
            "extentRatio":extent(b)/max(sz,1.),"expectedIoU":iou(b,expected)}


def accepted(q):
    return (q["confidence"]>=MIN_CONFIDENCE and
            min(q["leftSimilarity"],q["rightSimilarity"])>=MIN_ENDPOINT_SIMILARITY and
            q["anchorSimilarity"]>=MIN_ANCHOR_SIMILARITY and
            q["margin"]>=MIN_COMPETITOR_MARGIN and
            q["centerError"]<=MAX_CENTER_ERROR and
            MIN_EXTENT_RATIO<=q["extentRatio"]<=MAX_EXTENT_RATIO and
            q["expectedIoU"]>=MIN_BOX_OVERLAP)


def choose(o,expected,left,right,seed,negatives):
    _,ds,masks,t,f=o
    metrics=[candidate_metrics(d,a,expected,left,right,seed,negatives) for d,a in zip(ds,f)]
    eligible=[i for i,q in enumerate(metrics) if accepted(q) and np.any(masks[i]>=128)]
    # A competing mask near the expected subject vetoes rescue even if its
    # appearance does not clear the positive gate. Never resolve via ordering.
    if len(eligible)!=1:
        return -1,metrics,"no_supported_candidate" if not eligible else "ambiguous"
    j=eligible[0]
    for k,d in enumerate(ds):
        if k==j or d.confidence<.20:
            continue
        if iou(box(ds[j]),box(d))>=.35:
            a,b=masks[j]>=128,masks[k]>=128
            intersection=int((a&b).sum())
            if intersection/max(1,min(int(a.sum()),int(b.sum())))>=.25:
                return -1,metrics,"competing_mask"
    return j,metrics,"accepted"


def project(lib,o,j):
    _,_,raw,t,_=o
    w=max(1,round(t.source_width*384/max(t.source_width,t.source_height)))
    h=max(1,round(t.source_height*384/max(t.source_width,t.source_height)))
    a=np.empty((h,w),np.uint8)
    if lib.frame_kernel_cutout_alpha(m.ptr(raw[j]),raw[j].size,t,w,h,m.ptr(a),a.size)!=0:
        raise ValueError("Cannot project selected mask")
    return a if a.any() else None


def bracketed(records,traces,obs,seed_idx,lib):
    """Freeze endpoints before any additions; all output is current raw alpha."""
    added=list(records)
    seed=obs[seed_idx]
    # Caller supplied anchor index into the exact shared observation.
    anchor_j=traces[seed_idx][0]
    if anchor_j<0:raise ValueError("Invalid original anchor")
    positive=seed[4][anchor_j]
    negatives=np.delete(seed[4],anchor_j,axis=0)
    report=[]
    missing=[i for i,(_,a) in enumerate(records) if a is None]
    for i in missing:
        l=i-1
        while l>=0 and records[l][1] is None:l-=1
        r=i+1
        while r<len(records) and records[r][1] is None:r+=1
        j,o,state=traces[i]
        item={"ptsUs":int(o[0]),"fullCount":len(obs[i][1]),"mergedCount":len(o[1]),"detailState":state}
        report.append(item)
        if l<0 or r==len(records) or records[r][0]-records[l][0]>MAX_BRACKET_US:
            item["reason"]="unbounded_bracket";continue
        lj,lo,_=traces[l];rj,ro,_=traces[r]
        if lj<0 or rj<0 or min(lo[1][lj].confidence,ro[1][rj].confidence)<MIN_CONFIDENCE:
            item["reason"]="weak_bracket";continue
        lf,rf=lo[4][lj],ro[4][rj]
        if similarity(lf,rf)<MIN_ENDPOINT_SIMILARITY:
            item["reason"]="inconsistent_bracket";continue
        lb,rb=box(lo[1][lj]),box(ro[1][rj])
        if not MIN_EXTENT_RATIO<=extent(rb)/max(1.,extent(lb))<=MAX_EXTENT_RATIO:
            item["reason"]="scale_bracket";continue
        if float(np.linalg.norm(center(rb)-center(lb)))>max(extent(lb),extent(rb))*.8:
            item["reason"]="motion_bracket";continue
        expected=geometry(lb,rb,(records[i][0]-records[l][0])/(records[r][0]-records[l][0]))
        j,metrics,reason=choose(o,expected,lf,rf,positive,negatives)
        item.update(reason=reason,bracket=[int(records[l][0]),int(records[r][0])],metrics=metrics)
        if j<0:continue
        a=project(lib,o,j)
        if a is None:continue
        added[i]=(records[i][0],a)
        item["candidateIndex"]=j
        item["source"]="full" if j<len(obs[i][1]) else "local"
    return added,report


# A separate, preregistered temporal hypothesis. A gap can be filled only
# as a whole when both incumbent endpoints connect through a unique chain
# of adjacent strong, current instance masks. No endpoint color threshold
# is weakened; this is additional time-local evidence, never track state.
MAX_SAMPLE_DELTA_US = 80_000
MIN_ADJACENT_MASK_IOU = .55
MIN_ADJACENT_BOX_IOU = .55
MIN_ADJACENT_APPEARANCE = .88
MAX_COMPETING_CONTAINMENT = .25


def mask_iou(a,b):
    common=int((a&b).sum())
    return common/max(1,int((a|b).sum()))


def same_frame_clear(o,j):
    _,ds,raw,_,_=o
    a=raw[j]>=128
    for k,d in enumerate(ds):
        if k==j or d.confidence<.20:
            continue
        if iou(box(ds[j]),box(d))<.35:
            continue
        b=raw[k]>=128
        if int((a&b).sum())/max(1,min(int(a.sum()),int(b.sum())))>=MAX_COMPETING_CONTAINMENT:
            return False
    return True


def linked(a,aj,b,bj):
    pa,da,ma,ta,fa=a
    pb,db,mb,tb,fb=b
    if not 0<pb-pa<=MAX_SAMPLE_DELTA_US:
        return False
    if (ta.source_width,ta.source_height,ta.input_size,ta.proto_size,ta.scale,ta.pad_left,ta.pad_top)!=(
            tb.source_width,tb.source_height,tb.input_size,tb.proto_size,tb.scale,tb.pad_left,tb.pad_top):
        return False
    if min(da[aj].confidence,db[bj].confidence)<MIN_CONFIDENCE:
        return False
    if iou(box(da[aj]),box(db[bj]))<MIN_ADJACENT_BOX_IOU:
        return False
    if similarity(fa[aj],fb[bj])<MIN_ADJACENT_APPEARANCE:
        return False
    return mask_iou(ma[aj]>=128,mb[bj]>=128)>=MIN_ADJACENT_MASK_IOU


def chain_indices(observations,left_index,right_index):
    """No branching in either direction, and no intermediate mask ownership ambiguity."""
    chosen=[left_index]
    for a,b in zip(observations,observations[1:]):
        j=chosen[-1]
        if not same_frame_clear(a,j):
            return None,"competing_mask"
        hits=[k for k in range(len(b[1])) if linked(a,j,b,k)]
        if len(hits)!=1:
            return None,"no_adjacent_match" if not hits else "ambiguous_adjacent_match"
        k=hits[0]
        back=[u for u in range(len(a[1])) if linked(a,u,b,k)]
        if back!=[j]:
            return None,"ambiguous_reverse_match"
        if not same_frame_clear(b,k):
            return None,"competing_mask"
        chosen.append(k)
    if chosen[-1]!=right_index:
        return None,"endpoint_disagreement"
    return chosen,"accepted"


class GapRows(c.Structure):
    _fields_=[("pts",m.I64),("observed",TouchRows),("selected",c.c_int32)]


def shared_chain(lib,observations,left_index,right_index):
    """Return the host-feature core result; independently check Python oracle."""
    reference,reason=chain_indices(observations,left_index,right_index)
    fn=lib.frame_kernel_dance_probe_gap
    fn.argtypes=[c.POINTER(GapRows),c.c_size_t,c.POINTER(c.c_int32),c.c_size_t]
    fn.restype=c.c_int32
    n=len(observations)
    arrays=[]
    frames=[]
    for i,(p,ds,masks,t,f) in enumerate(observations):
        rows=(m.Detection*len(ds))(*ds)
        arrays.append(rows)
        selected=left_index if i==0 else right_index if i==n-1 else -1
        frames.append(GapRows(p,TouchRows(rows,len(ds),m.ptr(masks),masks.size,
                                        m.ptr(f,m.PF),f.size,t),selected))
    inp=(GapRows*n)(*frames)
    output=(c.c_int32*n)(*([-99]*n))
    rc=fn(inp,n,output,n)
    if rc==0:
        actual=list(output)
        if actual!=reference:
            raise ValueError("Shared gap path differs from Python oracle")
        return actual,"accepted"
    if list(output)!=[-99]*n:
        raise ValueError("Rejected gap wrote output indices")
    if rc not in (-1,-2,-3,-4,-5,-6):
        raise ValueError(f"Unknown shared gap status {rc}")
    if reference is not None and rc!=-3:
        raise ValueError("Shared gap rejection differs from Python oracle")
    if rc==-3:
        return None,"invalid_or_budget"
    if reference is None:
        return None,reason
    raise ValueError("Shared gap result differs from Python oracle")


def continuity(records,traces,obs,lib,end):
    result=list(records)
    reports=[]
    index={p:i for i,(p,_) in enumerate(records)}
    if not records or end<=records[-1][0]:
        raise ValueError("Invalid source interval end")
    for start,stop in missing_spans(records,end):
        l=index[start]-1
        r=index.get(stop,len(records))
        row={"span":[start,stop],"added":[]}
        reports.append(row)
        if l<0 or r>=len(records) or records[r][0]-records[l][0]>MAX_BRACKET_US:
            row["reason"]="unbounded_bracket"
            continue
        lj,lo,_=traces[l];rj,ro,_=traces[r]
        # Do not use previous output-only weak matches as identity evidence.
        if lj<0 or rj<0:
            row["reason"]="unavailable_exact_bracket"
            continue
        if min(lo[1][lj].confidence,ro[1][rj].confidence)<MIN_CONFIDENCE:
            row["reason"]="weak_bracket"
            continue
        lane=[traces[i][1] for i in range(l,r+1)]
        selected,reason=shared_chain(lib,lane,lj,rj)
        row["reason"]=reason
        if selected is None:
            continue
        current=[]
        for i,j in zip(range(l+1,r),selected[1:-1]):
            a=project(lib,traces[i][1],j)
            if a is None:
                break
            current.append((i,a,j))
        if len(current)!=r-l-1:
            row["reason"]="empty_projection"
            continue
        for i,a,j in current:
            result[i]=(records[i][0],a)
            row["added"].append({"ptsUs":records[i][0],"source":"full" if j<len(obs[i][1]) else "local"})
    return result,reports


def review(path,manifest,baseline,candidate,out):
    pts=[p for (p,a),(_,b) in zip(baseline,candidate) if a is None and b is not None]
    if not pts:return
    frames=source_samples(path,manifest,pts)
    lookup=dict(candidate)
    rows=[]
    for p in pts:
        im=cv2.resize(cv2.cvtColor(frames[p],cv2.COLOR_RGBA2BGR),(320,180),interpolation=cv2.INTER_AREA)
        a=cv2.resize(lookup[p],(320,180)).astype(np.float32)[:,:,None]/255.
        overlay=(im*(1-.4*a)+np.full_like(im,(110,210,90))*.4*a).astype(np.uint8)
        cut=(im*a+np.full_like(im,35)*(1-a)).astype(np.uint8)
        row=np.concatenate((im,overlay,cut),axis=1)
        cv2.putText(row,f"{p/1e6:.3f}s",(6,18),cv2.FONT_HERSHEY_SIMPLEX,.5,(255,255,255),1)
        rows.append(row)
    out.mkdir(parents=True,exist_ok=True)
    for i in range(0,len(rows),4):
        cv2.imwrite(str(out/f"added-{i//4:02d}.jpg"),np.concatenate(rows[i:i+4],axis=0))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--media",type=Path,required=True)
    p.add_argument("--library",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--role",choices=("reference","target","both"),default="both")
    p.add_argument("--touch-pts",type=int,help="alternate explicit user touch at an annotated PTS; labels after that are scoring only")
    a=p.parse_args()
    manifest=json.loads(a.manifest.read_text())
    model=m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    if sha(model)!=manifest["modelSha256"] or sha(model)!=m.MODEL_SHA:raise ValueError("Model mismatch")
    lib=m.api(a.library);bind_detail(lib);bind_touch(lib)
    if not hasattr(lib,"frame_kernel_dance_probe_gap"):
        p.error("Build the isolated core with --features host-model-probe")
    runner=m.Interpreter(model_path=str(model),num_threads=4);runner.allocate_tensors()
    a.output.mkdir(parents=True,exist_ok=True)
    result={"hostOnly":True,"manifestSha256":sha(a.manifest),"librarySha256":sha(a.library),
            "modelSha256":sha(model),"maxBracketUs":MAX_BRACKET_US,"roles":{}}
    for role,s in manifest["sources"].items():
        if a.role not in ("both",role):continue
        path=a.media/s["file"]
        if sha(path)!=s["testedMp4Sha256"]:raise ValueError("Media mismatch")
        at=manifest["anchorUs"] if a.touch_pts is None else a.touch_pts
        n=manifest["framesPerRole"]
        anchor=round(at*manifest["cadence"]/1e6)
        obs=[(p,d,r.copy(),t,f) for p,d,r,t,f in observe(lib,runner,path,n,anchor)]
        if obs[anchor][0]!=at:raise ValueError("Anchor PTS mismatch")
        if a.touch_pts is None:
            touch=s["seed"]
        else:
            touch=next(x["selected"] for x in manifest["annotations"][role] if x["ptsUs"]==at)
        image=source_samples(path,manifest,[at])[at]
        seed,o,info=select(lib,runner,image,obs[anchor],*touch,expand_truncated=True)
        if seed<0:
            result["roles"][role]={"anchorUnavailable":True,"seed":info}
            print(role,"anchor unavailable",flush=True)
            continue
        obs[anchor]=o
        traces={}
        def trace(i,j,o,state):
            traces[i]=(j,o,state)
        records,timing=replay(lib,runner,path,obs,anchor,seed,True,True,trace=trace)
        candidate,diagnostic=bracketed(records,traces,obs,anchor,lib)
        connected,chains=continuity(records,traces,obs,lib,manifest["durationUs"])
        baseline=assess(records,manifest["annotations"][role],manifest["durationUs"])
        enhanced=assess(candidate,manifest["annotations"][role],manifest["durationUs"])
        chained=assess(connected,manifest["annotations"][role],manifest["durationUs"])
        delta=compare(records,candidate)
        chain_delta=compare(records,connected)
        for d in (delta,chain_delta):
            if d["gpuMissingVsCpu"] or d["exactAlphaFrames"]!=baseline["visible"]:
                raise ValueError("Original visible output changed")
        report={"anchorUs":at,"seed":info,"canonical":baseline,"candidate":enhanced,
                "alphaComparison":delta,"continuity":chained,"continuityComparison":chain_delta,
                "primaryTiming":timing,"missing":diagnostic,"chains":chains}
        result["roles"][role]=report
        review(path,manifest,records,candidate,a.output/role/"endpoint")
        review(path,manifest,records,connected,a.output/role/"continuity")
        print(role,"visible",baseline["visible"],"->",enhanced["visible"],"-> chain",chained["visible"],
              "longest",baseline["longestMissingUs"],"->",chained["longestMissingUs"],
              "checks",baseline["checkpointHits"],"->",chained["checkpointHits"],
              "chains",dict(collections.Counter(x["reason"] for x in chains)),flush=True)
    (a.output/"report.json").write_text(json.dumps(result,indent=2)+"\n")


if __name__=="__main__":main()
