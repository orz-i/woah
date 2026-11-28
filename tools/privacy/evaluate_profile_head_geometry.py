#!/usr/bin/env python3
"""Research-only current profile head correction on exact observed bodies.

Projected placements can be repaired on current rival-face conflict. The
optional bounded full-body window repairs weak fragment geometry using two
canonical body anchors and current dual-scale face measurements.
Diagnostic direct-frame requests record geometry without edits.
No temporal, tracker, or model state is updated by the second pass.
"""
import argparse
import copy
import ctypes as C
import json
from pathlib import Path

import numpy as np
from PIL import Image

from evaluate_body_reid import PERSON_MODEL, FACE_MODEL, I64, sha
from evaluate_pose_parts import PoseKernel, POSE_FILE, POSE_SHA, pose_points
from replay_face_geometry import P,S,U,F,Rect,Transform,struct,MaskSpec,Ellipse,detect_instances,detect_face,model,ptr,vals
from replay_face_tracking import HeadSticker


def intersects(a, b):
    return min(a[2],b[2])>max(a[0],b[0]) and min(a[3],b[3])>max(a[1],b[1])


def square(v):
    x,y,rx,ry=v
    r=max(8,1.02*max(rx,ry))
    return [x-r,y-r,x+r,y+r]


def canonical_raw(row):
    t=row.get("track")
    if not t or not t["observed"] or t["dormant"]:return None
    hits=[j for j,b in enumerate(row["raw_boxes"]) if b==t["bounds"]]
    return hits[0] if len(hits)==1 else None


def full_anchor(row):
    return canonical_raw(row) is not None and row.get("render") is not None and (
        row["kind"]==0 or row.get("topology")==1)


def fragment_windows(rows):
    """Scheduling only: use observed body scale before and after a short gap.
    Never pick a raw alternate by box, face, or an audit label."""
    for left in range(len(rows)-2):
        if not full_anchor(rows[left]):continue
        a=rows[left]["track"]["bounds"];ah=a[3]-a[1]
        for right in range(left+2,min(len(rows),left+8)):
            if rows[right]["time_us"]-rows[left]["time_us"]>250_000:break
            if not full_anchor(rows[right]):continue
            b=rows[right]["track"]["bounds"];bh=b[3]-b[1];h=min(ah,bh)
            if h<=0 or h<.80*max(ah,bh):continue
            fragments=0;valid=True
            for q in rows[left+1:right]:
                t=q.get("track")
                if not t or t["dormant"] or q.get("kind") in (0,3,4,5,6,7,8):
                    valid=False;break
                if t["observed"]:
                    r=t["bounds"]
                    if canonical_raw(q) is None or r[3]-r[1]>=.75*h or r[1]<max(a[1],b[1])+.20*h:
                        valid=False;break
                    fragments+=1
                elif q.get("sticker") is not None:
                    valid=False;break
            if valid and fragments:
                yield left,right
                break


def fragment_recovery(kernel,person,face,pose,fixture,fixture_path,prior,out,target,counts):
    lib=kernel.lib
    proof=lib.frame_kernel_offline_part_window
    proof.argtypes=[P,S,P,S,P,P,S,U,U,P,S,P];proof.restype=C.c_int32
    views=lib.frame_kernel_research_pose_face_views
    views.argtypes=[P,S,S,U,MaskSpec,P,P];views.restype=C.c_int32
    finish=lib.frame_kernel_research_pose_face_finish
    finish.argtypes=[P,S,S,U,MaskSpec,P,P,P];finish.restype=C.c_int32
    attempts=[];used=set()
    for left,right in fragment_windows(prior):
        if any(i in used for i in range(left+1,right)):continue
        rows=prior[left:right+1]
        evidence=[];images=[];features=[]
        for r in rows:
            meta=fixture["frames"][r["index"]];f=fixture_path.parent/meta["asset"]
            if sha(f)!=meta["sha256"]:raise ValueError("fragment frame hash mismatch")
            im=Image.open(f).convert("RGB");tr,ds,ms=detect_instances(lib,person,im);counts["person"]+=1
            if [vals(d.box) for d in ds]!=r["raw_boxes"]:raise ValueError("fragment raw evidence mismatch")
            evidence.append((tr,ds,ms));images.append(im)
            features.append(kernel.clothing(im,tr,ds,ms)[:,:1024])
        if not all(3<=len(v[1])<=32 for v in evidence):continue
        obs,pool,n=kernel.pack(evidence)
        upper=np.ascontiguousarray(np.concatenate(features),np.float32)
        path=(U*len(rows))()
        rc=proof(obs,len(obs),ptr(pool),pool.size,n,(I64*len(rows))(*[r["time_us"] for r in rows]),
            len(rows),canonical_raw(rows[0]),canonical_raw(rows[-1]),ptr(upper),upper.size,path)
        attempt={"left":left,"right":right,"proof_status":rc,"frames":[]};attempts.append(attempt)
        if rc<0:raise RuntimeError("invalid weak body window")
        if rc:continue
        attempt["path"]=list(path)
        # Raw ownership must be unclaimed (or already assigned to target at
        # the endpoints). Never take a body belonging to another observed ID.
        blocked=False
        for k,(r,j) in enumerate(zip(rows,path)):
            if j>=len(r["raw_boxes"]) or any(t["id"]!=target and t["observed"] and
                not t["dormant"] and t["bounds"]==r["raw_boxes"][j] for t in r["tracks"]):
                blocked=True;break
            if any(v[0]==j for v in r.get("body_merge_evidence",[])):
                blocked=True;break
            if 0<k<len(rows)-1 and canonical_raw(r)==j:
                blocked=True;break
        if blocked:attempt["ownership_conflict"]=True;continue
        for k in range(1,len(rows)-1):
            i=left+k;j=path[k];r=prior[i];im=images[k];tr,ds,ms=evidence[k]
            if out[i]!=r:continue  # prior current-geometry repair remains independent
            sp=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
            kp=np.ascontiguousarray(np.stack([pose_points(pose,im,tr,ds[j],ms,j,flip=q)[:7] for q in (False,True)]),np.float32)
            counts["pose"]+=2
            plan=np.empty((2,6),np.float32);rc=views(ptr(ms),ms.size,len(ds),j,sp,ptr(kp),ptr(plan))
            detail={"index":i,"raw":int(j),"view_status":rc};attempt["frames"].append(detail)
            if rc<0:raise RuntimeError("invalid current pose views")
            if rc:continue
            locals=[detect_face(lib,face,im,sp,ms[j],explicit_plan=q) for q in plan];counts["face"]+=2
            detail["local_faces"]=[vals(f) if f else None for f in locals]
            if any(f is None for f in locals):continue
            fs=[detect_face(lib,face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),ms[u]) or Ellipse() for u,d in enumerate(ds)]
            counts["face"]+=len(ds)
            cap=HeadSticker();rc=finish(ptr(ms),ms.size,len(ds),j,sp,(Ellipse*2)(*locals),(Ellipse*len(fs))(*fs),C.byref(cap))
            detail["finish_status"]=rc
            if rc<0:raise RuntimeError("invalid current face consensus")
            if rc:continue
            detail.update(bounds=vals(cap.bounds),sticker=vals(cap.square))
            v=square(vals(cap.square));used.add(i)
            # The current geometry is independent of the fragmented canonical
            # body. Do not change its state, raw detector or observed clock.
            out[i].update(sticker=vals(cap.square),render=r.get("render") or vals(cap.square),
                sticker_required_bounds=vals(cap.bounds),topology=cap.topology,
                topology_rows=cap.rows,fragment_head_repair={"raw":int(j),"left":left,"right":right},
                width=min(tr.width,v[2])-max(0,v[0]),height=min(tr.height,v[3])-max(0,v[1]))
            if r.get("render") is None:out[i]["kind"]=8  # current coverage only, not a face-clock observation
    return attempts,sorted(used)


Competition=struct("HeadCompetition",[("square",Ellipse),("required_overlap",F),
    ("centered_overlap",F),("best_overlap",F),("status",U),("face_count",U),
    ("cap_count",U),("unknown_count",U),("local_count",U)])


def local_negative_heads(kernel,person,face,pose,im,tr,ds,masks,j,cap,counts):
    """Current negative-only local proposals; no identity match or render here."""
    lib=kernel.lib
    views=lib.frame_kernel_research_competition_views
    views.argtypes=[MaskSpec,P];views.restype=C.c_int32
    certify=lib.frame_kernel_research_competition_local_rival
    certify.argtypes=[P,S,S,U,MaskSpec,HeadSticker,P,S,P,S,P,S,P];certify.restype=C.c_int32
    project=lib.frame_kernel_reproject_mask
    project.argtypes=[P,S,Transform,Transform,Rect,P,S];project.restype=C.c_int32
    ear=lib.frame_kernel_research_tracklet_ear_cap
    ear.argtypes=[P,S,S,U,MaskSpec,P,P,P];ear.restype=C.c_int32
    spec=MaskSpec(tr.proto,tr.proto,tr,ds[j].box)
    v=(Rect*2)();rc=views(spec,v)
    if rc<0:raise RuntimeError("invalid competition views")
    if rc:return [],{"view_status":rc}
    data=[]
    for view in v:
        ci=im.crop(tuple(int(q) for q in vals(view)))
        lt,ld,lm=detect_instances(lib,person,ci);counts["person"]+=1
        if not ld:return [],{"view_status":0,"local_counts":[len(ld)]}
        projected=np.zeros((len(ld),tr.proto,tr.proto),np.uint8)
        for q,m in enumerate(lm):
            if project(ptr(m),m.size,lt,tr,view,ptr(projected[q]),projected[q].size)!=0:
                raise RuntimeError("local negative projection failed")
        data.append((view,ci,lt,ld,lm,projected))
    view,ci,lt,ld,lm,projected=data[0];second=data[1]
    sf=np.ascontiguousarray([d.confidence for d in second[3]],np.float32)
    placement=square(vals(cap.square));extras=[];trace={"views":[vals(q) for q in v],
        "local_counts":[len(q[3]) for q in data],"proposals":[]}
    # First schedule nearby upper-body boxes; evaluate all local faces once.
    nearby=[]
    for q,d in enumerate(ld):
        if d.confidence<.35:continue
        b=d.box;top=[b.left+view.left,b.top+view.top,b.right+view.left,b.top+view.top+.35*(b.bottom-b.top)]
        if intersects(placement,top):nearby.append(q)
    if not nearby:return [],trace
    fs=[detect_face(lib,face,ci,MaskSpec(lt.proto,lt.proto,lt,d.box),lm[q]) or Ellipse() for q,d in enumerate(ld)]
    counts["face"]+=len(ld)
    for q in nearby:
        d=ld[q];sp=MaskSpec(lt.proto,lt.proto,lt,d.box)
        kp=np.ascontiguousarray(pose_points(pose,ci,lt,d,lm,q)[3:7],np.float32);counts["pose"]+=1
        local=HeadSticker();rc=ear(ptr(lm),lm.size,len(ld),q,sp,ptr(kp),(Ellipse*len(fs))(*fs),C.byref(local))
        record={"raw":q,"cap_status":rc};trace["proposals"].append(record)
        if rc<0:raise RuntimeError("invalid local negative cap")
        if rc:continue
        b=local.bounds;local.bounds=Rect(b.left+view.left,b.top+view.top,b.right+view.left,b.bottom+view.top)
        f=local.square;local.square=Ellipse(f.x+view.left,f.y+view.top,f.rx,f.ry)
        record["bounds"]=vals(local.bounds)
        if not intersects(placement,vals(local.bounds)):continue
        result=Rect();rc=certify(ptr(masks),masks.size,len(ds),j,spec,local,
            ptr(projected[q]),projected[q].size,ptr(second[5]),second[5].size,
            ptr(sf),len(sf),C.byref(result))
        record["consensus_status"]=rc
        if rc<0:raise RuntimeError("invalid local negative evidence")
        if rc==0:extras.append(result)
    # A surplus of competing witnesses is not truncated into a false pass.
    if len(extras)>2:trace["too_many"]=True;return [],trace
    return extras,trace


def competition(kernel,person,face,pose,im,tr,ds,masks,j,fs,cap,counts,local_retry):
    fn=kernel.lib.frame_kernel_research_head_competition
    fn.argtypes=[P,S,S,U,MaskSpec,Rect,P,P,P,S,P];fn.restype=C.c_int32
    spec=MaskSpec(tr.proto,tr.proto,tr,ds[j].box);boxes=(Rect*len(ds))(*[d.box for d in ds])
    faces=(Ellipse*len(fs))(*fs)
    def run(extras):
        result=Competition()
        rc=fn(ptr(masks),masks.size,len(ds),j,spec,cap.bounds,boxes,faces,
              (Rect*len(extras))(*extras),len(extras),C.byref(result))
        if rc<0:raise RuntimeError("invalid head competition")
        return result if rc==0 else None
    def row(q):
        return {"square":vals(q.square),"required_overlap":q.required_overlap,
                "centered_overlap":q.centered_overlap,"best_overlap":q.best_overlap,"status":q.status,
                "face_count":q.face_count,"cap_count":q.cap_count,"unknown_count":q.unknown_count,"local_count":q.local_count}
    result=run([])
    if result is None:return None,{"status":"invalid_extent"}
    trace={"scene":row(result)}
    # A repair triggered by old competing faces may have no confirmed current
    # rival at its smaller cap. Look for missed head evidence in two views.
    if local_retry and (result.centered_overlap<.0001 or result.unknown_count):
        extras,detail=local_negative_heads(kernel,person,face,pose,im,tr,ds,masks,j,cap,counts)
        trace["local"]=detail
        if extras:
            trace["local_bounds"]=[vals(q) for q in extras]
            proposed=run(extras)
            if proposed is None:return None,trace
            result=proposed
    trace["final"]=row(result)
    return result,trace


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ("library","fixture","reference-replay","model-dir","output"):
        ap.add_argument("--"+name,type=Path,required=True)
    ap.add_argument("--diagnostic-indices",default="",help="Explicit geometry audit samples; never override direct placements")
    ap.add_argument("--fragment-window",action="store_true",help="Use bounded weak full-body evidence for current fragment head geometry")
    ap.add_argument("--fragment-only",action="store_true",help="Evaluate the fragment window without the existing profile repair experiment")
    ap.add_argument("--head-competition",action="store_true",help="Research: minimize fixed required square against current rival head extents")
    a=ap.parse_args()
    if a.fragment_only and not a.fragment_window:ap.error("--fragment-only requires --fragment-window")
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
        if a.fragment_only:break
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
        if a.head_competition and rc!=0 and i in diagnostic:
            # Measure the required extent independently of face competition,
            # retaining all raw-mask vetoes. Diagnostic only: no placement.
            target_only=(Ellipse*len(fs))(*[f if u==j else Ellipse() for u,f in enumerate(fs)])
            tentative=HeadSticker()
            rr=fn(ptr(ms),ms.size,len(ds),j,sp,ptr(kp),target_only,Ellipse(),0,C.byref(tentative))
            if rr==0:
                _,trace=competition(kernel,person,face,pose,im,tr,ds,ms,j,fs,tentative,counts,False)
                detail["rejected_extent"]={"bounds":vals(tentative.bounds),"competition":trace}
        if rc==0:
            detail.update(bounds=vals(cap.bounds),sticker=vals(cap.square),rows=cap.rows)
            if a.head_competition:
                result,trace=competition(kernel,person,face,pose,im,tr,ds,ms,j,fs,cap,counts,repair)
                detail["competition"]=trace
                if repair and result is not None and result.status==0 and result.unknown_count==0 and (result.cap_count or result.local_count) and result.best_overlap+1e-4<result.centered_overlap:
                    cap.square=result.square
                    detail["competition"]["applied"]=True
            if repair:
                v=square(vals(cap.square))
                out[i].update(sticker=vals(cap.square),sticker_required_bounds=vals(cap.bounds),
                              topology=cap.topology,topology_rows=cap.rows,profile_head_repair=True,
                              width=min(tr.width,v[2])-max(0,v[0]),height=min(tr.height,v[3])-max(0,v[1]))
        attempts.append(detail)
    fragment_attempts,fragment_changed=(fragment_recovery(kernel,person,face,pose,fixture,a.fixture,prior,out,target,counts) if a.fragment_window and len(ref["identity_protected_roots"])>=3 else ([],[]))
    changed=[i for i,(x,y) in enumerate(zip(prior,out)) if x!=y]
    if any((prior[i]["kind"]!=1 and i not in fragment_changed) or prior[i]["track"]!=out[i]["track"] or prior[i]["tracks"]!=out[i]["tracks"] for i in changed):
        raise RuntimeError("profile geometry changed a canonical identity")
    result={"scope":__doc__,"library_sha256":sha(a.library),"fixture_sha256":sha(a.fixture),
            "reference_replay_sha256":sha(a.reference_replay),"target_id":target,"model_calls":counts,
            "head_competition":a.head_competition,"attempts":attempts,"changed":changed,"fragment_attempts":fragment_attempts,"fragment_changed":fragment_changed,"baseline":{"frames":prior},"candidate":{"frames":out}}
    a.output.write_text(json.dumps(result,separators=(",",":"))+"\n")
    print("changed",changed,"calls",counts,"attempts",attempts,"fragment_attempts",fragment_attempts,flush=True)

if __name__=="__main__":main()
