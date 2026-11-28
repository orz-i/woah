#!/usr/bin/env python3
"""Local CPU detector + production FrameKernel tracking session replay.

Only the first frame establishes the selected ID. Later annotations are audit
inputs, never a way to choose or repair an identity. This is a deliberately
bounded host face-geometry adapter, not the Android/iOS face pipeline.
"""
import argparse
import ctypes as C
import hashlib
import json
import math
import struct as binary
from pathlib import Path

import numpy as np
from PIL import Image

from replay_face_geometry import (
    ROOT, F, U, S, P, Rect, Ellipse, Transform, MaskSpec, Measurement, State,
    RenderState, Head, Detection, check, detect_instances, detect_face, iou, load, model,
    ptr, struct, vals,
)

I64 = C.c_int64
U64 = C.c_uint64
Weights = struct("Weights", [(k, F) for k in ("bounds", "mask", "motion", "direction", "gating")])
Options = struct("TrackingOptions", [(k, U) for k in ("max_missed", "grace", "occlusion_max", "scene_inliers")] +
                 [("minimum", F), ("weights", Weights)] +
                 [(k, F) for k in ("overlap", "ambiguity", "scene_tolerance", "maximum_shift")] +
                 [("scene_compensation", U), ("foot_margin", F), ("erosion_radius", U)])
Observation = struct("Observation", [("bounds", Rect), ("confidence", F), ("foot", F),
                     ("offset", U64), ("width", U), ("height", U), ("transform", Transform)])
GrayView = struct("GrayView", [("width", U), ("height", U), ("stride", U), ("source", Rect)])
PixelMotion = struct("PixelMotion", [(key,F) for key in ("dx","dy","correlation","uniqueness")])
PixelBridge = struct("PixelBridge", [("motion",PixelMotion),("measurement",Ellipse),("required",Ellipse)])
HeadSticker = struct("HeadSticker", [("bounds",Rect),("square",Ellipse),("topology",U),("rows",U)])
RecoveryAnchor = struct("RecoveryAnchor", [("root_body",Rect),("root_face",Ellipse),("predicted_body",Rect),("age_us",I64)])
RecoveryPrevious = struct("RecoveryPrevious", [("body",Rect),("face",Ellipse),("root_age_us",I64)])
Garment = struct("Garment", [("rgb",F*512),("chroma",F*512),("samples",U)])
ReappearanceCandidate = struct("ReappearanceCandidate", [("garment",Garment),("body",Rect),("face",Ellipse),("eligible",U)])
ReappearanceDecision = struct("ReappearanceDecision", [("index",U),("similarity",F),("rival_root",F),("rival_current",F)])

MergedEdgeAnchor = struct("MergedEdgeAnchor", [("cap",Rect),("rival_body",Rect),("rival_face",Ellipse),("cap_age_us",I64),("rival_age_us",I64)])

DuplicateProposal = struct("DuplicateProposal", [("body",Rect),("face",Ellipse),("confidence",F),("foreign",U)])


def pixel_bind(lib):
    if hasattr(lib,"frame_kernel_crowded_face_views"):
        for name,args in [("crowded_face_views",[P,S,S,U,MaskSpec,Ellipse,P]),("crowded_face_accept",[P,S,S,U,MaskSpec,Ellipse,P]),("crowded_weak_conflict",[P,S,S,U,MaskSpec,Ellipse,I64])]:
            fn=getattr(lib,"frame_kernel_"+name)
            fn.argtypes,fn.restype=args,C.c_int32
            setattr(lib,name,fn)
    if hasattr(lib,"frame_kernel_face_duplicate_pair"):
        for name,args in [("duplicate_probe",[P,S,MaskSpec,P,S]),("duplicate_pair",[P,S,MaskSpec,P,S,P,P,S]),("duplicate_plan",[Ellipse,Transform,P]),("duplicate_sticker",[P,S,MaskSpec,P,S,U,U,Ellipse,P])]:
            fn=getattr(lib,"frame_kernel_face_"+name)
            fn.argtypes,fn.restype=args,C.c_int32
            setattr(lib,name,fn)
    if hasattr(lib,"frame_kernel_face_merged_edge_views"):
        for name,args in [("merged_edge_views",[MaskSpec,MergedEdgeAnchor,P]),("merged_edge_finish",[P,S,MaskSpec,MergedEdgeAnchor,P,Ellipse,P])]:
            fn=getattr(lib,"frame_kernel_face_"+name)
            fn.argtypes,fn.restype=args,C.c_int32
            setattr(lib,name,fn)
        fn=lib.frame_kernel_reproject_mask
        fn.argtypes,fn.restype=[P,S,Transform,Transform,Rect,P,S],C.c_int32
        lib.reproject_mask=fn
    if hasattr(lib,"frame_kernel_face_pair_edge_sticker"):
        for name,args in [("pair_edge_sticker",[P,S,U,MaskSpec,P]),("pair_owned",[P,S,U,MaskSpec,P,Rect,F])]:
            fn=getattr(lib,"frame_kernel_face_"+name)
            fn.argtypes,fn.restype=args,C.c_int32
            setattr(lib,name,fn)
    if hasattr(lib,"frame_kernel_face_garment"):
        fn=lib.frame_kernel_face_garment
        fn.argtypes=[P,S,P,S,S,S,MaskSpec,Ellipse,P]
        fn.restype=C.c_int32
        lib.garment=fn
        fn=lib.frame_kernel_face_reappearance_choose
        fn.argtypes=[Rect,Ellipse,I64,P,S,P,S,P,S,P]
        fn.restype=C.c_int32
        lib.reappearance_choose=fn
        fn=lib.frame_kernel_face_reappearance_sticker
        fn.argtypes=[P,S,MaskSpec,Ellipse,P]
        fn.restype=C.c_int32
        lib.reappearance_sticker=fn
    if hasattr(lib,"frame_kernel_face_recovery_finish_exclusive"):
        fn=lib.frame_kernel_face_recovery_probe
        fn.argtypes=[RecoveryAnchor,P,S,P,S,P]
        fn.restype=C.c_int32
        lib.recovery_probe=fn
        fn=lib.frame_kernel_face_recovery_finish_exclusive
        fn.argtypes=[RecoveryAnchor,P,S,P,S,U,P,S,MaskSpec,Ellipse,P]
        fn.restype=C.c_int32
        lib.recovery_finish=fn
        if hasattr(lib,"frame_kernel_face_recovery_continuation_finish_exclusive"):
            fn=lib.frame_kernel_face_recovery_continuation_probe
            fn.argtypes=[RecoveryAnchor,RecoveryPrevious,P,S,P,S,P]
            fn.restype=C.c_int32
            lib.recovery_continuation_probe=fn
            fn=lib.frame_kernel_face_recovery_continuation_finish_exclusive
            fn.argtypes=[RecoveryAnchor,RecoveryPrevious,P,S,GrayView,P,S,GrayView,P,S,P,S,U,P,S,MaskSpec,Ellipse,P]
            fn.restype=C.c_int32
            lib.recovery_continuation_finish=fn
    if hasattr(lib,"frame_kernel_head_sticker"):
        fn=lib.frame_kernel_head_sticker
        fn.argtypes=[P,S,MaskSpec,Ellipse,Ellipse,U,P]
        fn.restype=C.c_int32
        lib.head_sticker=fn
    if not hasattr(lib,"frame_kernel_face_pixel_bridge"):
        return
    fn=lib.frame_kernel_face_pixel_bridge
    fn.argtypes=[P,S,GrayView,P,S,GrayView,Ellipse,Ellipse,I64,P,S,P]
    fn.restype=C.c_int32
    lib.pixel_motion=fn


def gray_view(image):
    pixels=np.ascontiguousarray(image.convert("L"))
    h,w=pixels.shape
    return pixels,GrayView(w,h,w,Rect(0,0,w,h))


def tracker_bind(lib):
    for name, args, result in [
        ("defaults", [], Options), ("create", [Options], U64), ("destroy", [U64], C.c_int32),
        ("selection", [U64, P, S, P, S, P, S, U], C.c_int32),
        ("step", [U64, U, I64, P, S, P, S, P, S], C.c_int32),
        ("snapshot", [U64, P, S], I64),
    ]:
        fn = getattr(lib, "frame_kernel_tracking_" + name)
        fn.argtypes, fn.restype = args, result
        setattr(lib, "tracking_" + name, fn)


class Snapshot:
    """Read every documented v1 field, including non-identity motion evidence."""
    def __init__(self, data):
        self.data, self.pos = data, 0

    def read(self, fmt):
        fmt = "<" + fmt
        result = binary.unpack_from(fmt, self.data, self.pos)
        self.pos += binary.calcsize(fmt)
        return result

    def u(self):
        return self.read("I")[0]

    def ids(self):
        return list(self.read("q" * self.u()))

    def rect(self):
        return Rect(*self.read("4f"))

    def decode(self):
        if self.u() != 1:
            raise ValueError("Unexpected tracking snapshot ABI")
        tracks = []
        for _ in range(self.u()):
            ident, state, missed, since, age, observed = self.read("q5I")
            bounds = self.rect()
            confidence, foot = self.read("2f")
            occluders = self.ids()
            mask, transform = None, None
            if self.u():
                w, h = self.read("2I")
                transform = Transform(*self.read("3f4I"))
                n = self.u()
                if n != w*h:
                    raise ValueError("Invalid mask size")
                mask = np.frombuffer(self.data, dtype=np.uint8, count=n, offset=self.pos).reshape(h, w)
                self.pos += n
            canonical, dormant = self.rect(), self.u()
            tracks.append(dict(id=ident, state=state, missed=missed, since=since, age=age,
                               observed=bool(observed), bounds=bounds, confidence=confidence,
                               foot=foot, occluders=occluders, mask=mask, transform=transform,
                               canonical=canonical, dormant=bool(dormant)))
        def classes():
            result = []
            for _ in range(self.u()):
                selection, index = self.read("2I")
                ids = self.ids()
                result.append(dict(selection=selection,index=index,ids=ids,unknown=bool(self.u())))
            return result
        body, face = classes(), classes()
        suppressed = self.ids()
        hard = [self.read("2I") for _ in range(self.u())]
        motion = [self.read("qI3fq") for _ in range(self.u())]
        if self.pos != len(self.data):
            raise ValueError("Unconsumed snapshot bytes")
        return dict(tracks=tracks, body=body, face=face, suppressed=suppressed, hard=hard, motion=motion)


class Tracking:
    def __init__(self, lib, target, protected_roots=None, pair_continuity=False, crowd_clothing=False):
        tracker_bind(lib)
        self.lib, self.target = lib, target
        self.handle = lib.tracking_create(lib.tracking_defaults())
        if not self.handle:
            raise RuntimeError("tracking_create")
        if pair_continuity:
            fn=lib.frame_kernel_tracking_pair_continuity
            fn.argtypes=[U64,U];fn.restype=C.c_int32
            check(fn(self.handle,1))
        self.crowd_clothing=crowd_clothing and len(protected_roots or ())>=3
        if self.crowd_clothing:
            self.clothing_step=lib.frame_kernel_tracking_clothing_step
            self.clothing_step.argtypes=[U64,U,I64,P,S,P,S,P,S,Transform];self.clothing_step.restype=C.c_int32
        ids = (I64 * 1)(target)
        roots = sorted(set(protected_roots or ()) | {target})
        protected = (I64 * len(roots))(*roots)
        check(lib.tracking_selection(self.handle, protected, len(roots), None, 0, ids, 1, 0))

    def step(self, transform, detections, masks, time, first, image=None):
        n = len(detections)
        rows = (Observation * n)(*(Observation(d.box, d.confidence, d.box.bottom,
                                               i*160*160, 160, 160, transform)
                                   for i, d in enumerate(detections)))
        action = 0 if first else (1 if n else 2)
        if self.crowd_clothing and n:
            rgb=np.ascontiguousarray(image.convert("RGB"))
            check(self.clothing_step(self.handle,action,time,rows,n,ptr(masks),masks.size,ptr(rgb),rgb.size,transform))
        else:
            check(self.lib.tracking_step(self.handle, action, time, rows if n else None, n,
                                         ptr(masks) if n else None, masks.size, None, 0))
        size = self.lib.tracking_snapshot(self.handle, None, 0)
        if size <= 0:
            raise RuntimeError("snapshot size")
        out = (C.c_uint8 * size)()
        if self.lib.tracking_snapshot(self.handle, out, size) != size:
            raise RuntimeError("snapshot copy")
        data=bytes(out)
        snapshot=Snapshot(data).decode()
        snapshot["sha256"]=hashlib.sha256(data).hexdigest()
        return snapshot

    def close(self):
        check(self.lib.tracking_destroy(self.handle))


def recover_current(lib,face,image,transform,detections,masks,snapshot,track,trusted,time,target,row,previous_recovery):
    # The prior direct clock and canonical tracker remain immutable. In
    # particular no recovery result is ever cached as a directly owned face.
    if not hasattr(lib,"recovery_probe") or trusted is None or track["observed"]:
        return None
    anchor=RecoveryAnchor(trusted[0],trusted[1],track["bounds"],time-trusted[2])
    bodies=(Rect*len(detections))(*(d.box for d in detections))
    foreign=[]
    for other in snapshot["tracks"]:
        if other["id"]==target or other["dormant"]:
            continue
        foreign.append(other["bounds"])
        if not other["observed"] and vals(other["canonical"])!=vals(other["bounds"]):
            foreign.append(other["canonical"])
    competitors=(Rect*len(foreign))(*foreign)
    index=U()
    foreign_ptr=competitors if len(competitors) else None
    continuation=(anchor.age_us>800_000 and previous_recovery is not None
                  and hasattr(lib,"recovery_continuation_probe"))
    if continuation:
        previous,previous_gray,previous_view=previous_recovery
        status=lib.recovery_continuation_probe(anchor,previous,bodies if len(bodies) else None,len(bodies),
                                               foreign_ptr,len(competitors),C.byref(index))
    else:
        status=lib.recovery_probe(anchor,bodies if len(bodies) else None,len(bodies),
                                  foreign_ptr,len(competitors),C.byref(index))
    row["recovery_attempt"]={"age_us":anchor.age_us,"probe_status":status,"continuation":continuation}
    if status<0:
        # A historical face centered outside its body is not a valid root;
        # report the rejected recovery, do not change old rendering.
        return None
    if status!=0:
        return None
    j=index.value
    spec=MaskSpec(160,160,transform,detections[j].box)
    mask=masks[j]
    measured=detect_face(lib,face,image,spec,mask)
    row["recovery_attempt"].update(candidate_index=j,current_detector=measured is not None)
    if measured is None:
        return None
    placement=HeadSticker()
    all_masks=np.ascontiguousarray(np.stack(masks))
    if continuation:
        gray,view=gray_view(image)
        status=lib.recovery_continuation_finish(anchor,previous,ptr(previous_gray),previous_gray.size,previous_view,
                                                ptr(gray),gray.size,view,bodies,len(bodies),foreign_ptr,len(competitors),
                                                j,ptr(all_masks),all_masks.size,spec,measured,C.byref(placement))
    else:
        status=lib.recovery_finish(anchor,bodies,len(bodies),foreign_ptr,len(competitors),
                                   j,ptr(all_masks),all_masks.size,spec,measured,C.byref(placement))
    row["recovery_attempt"]["finish_status"]=status
    if status!=0:
        return None
    required=Ellipse()
    check(lib.current_face_region(ptr(mask),mask.size,spec,measured,C.byref(required)))
    # Only an immediately preceding root-only success can serve as a
    # continuation seed. A terminal continuation is never reusable.
    next_previous=None
    if not continuation:
        gray,view=gray_view(image)
        next_previous=(RecoveryPrevious(spec.person,measured,anchor.age_us),gray,view)
    return measured,required,placement,next_previous


def garment_candidates(lib,face,entry,known=None):
    frame,image,transform,detections,masks=entry
    rgb=np.ascontiguousarray(image.convert("RGB"))
    result=[]
    for j,d in enumerate(detections):
        if d.confidence < .25:
            continue
        spec=MaskSpec(160,160,transform,d.box)
        measured=known[1] if known is not None and j==known[0] else detect_face(lib,face,image,spec,masks[j])
        if measured is None:
            continue
        descriptor=Garment()
        status=lib.garment(ptr(rgb),rgb.size,ptr(masks),masks.size,len(detections),j,spec,measured,C.byref(descriptor))
        if status<0:
            raise RuntimeError("invalid garment input")
        if status==0:
            result.append((j,spec,measured,descriptor))
    return result


def garment_roots(lib,face,evidence,report,trusted):
    # No current or future annotation enters these references. Require at least
    # two separate exact direct observations spaced by 100ms; freeze during gap.
    roots,rivals,indices=[],[],[]
    last=trusted[2]+100_000
    for old in reversed(report[:-1]):
        if old["kind"]!=0 or not old["track"]["observed"] or not 0<=trusted[2]-old["time_us"]<=500_000:
            continue
        if last-old["time_us"]<100_000:
            continue
        entry=evidence[old["index"]]
        ds=entry[3]
        found=[j for j,d in enumerate(ds) if iou(d.box,Rect(*old["track"]["bounds"]))>.99]
        if len(found)!=1:
            continue
        j=found[0]
        candidates=garment_candidates(lib,face,entry,(j,Ellipse(*old["raw"])))
        selected=next((x for x in candidates if x[0]==j),None)
        if selected is None:
            continue
        roots.append(selected[3])
        rivals.extend(x[3] for x in candidates if x[0]!=j)
        indices.append(old["index"])
        last=old["time_us"]
        if len(roots)==3:
            break
    return roots,rivals,indices


def reappear_current(lib,face,evidence,entry,report,snapshot,track,trusted,target,row,cache):
    if not hasattr(lib,"reappearance_choose") or trusted is None or track["observed"]:
        return None
    age=row["time_us"]-trusted[2]
    if not 950_000<age<=2_000_000:
        return None
    # Reappearance is restricted to a retained track with actual occlusion
    # state after the immutable direct root; ordinary lost tracks do not qualify.
    if track["state"] not in (2,3) or not any(
        q["time_us"]>trusted[2] and q.get("track") and q["track"]["state"]==2
        for q in report[:-1]
    ):
        return None
    if cache.get("time")!=trusted[2]:
        roots,rivals,indices=garment_roots(lib,face,evidence,report,trusted)
        cache.update(time=trusted[2],roots=roots,rivals=rivals,indices=indices)
    roots,rivals=cache["roots"],cache["rivals"]
    row["reappearance_attempt"]={"age_us":age,"root_indices":cache["indices"],"rival_count":len(rivals)}
    if len(roots)<2 or not rivals:
        return None
    frame,image,transform,detections,masks=entry
    candidates=garment_candidates(lib,face,entry)
    rows=[]
    foreign=[t for t in snapshot["tracks"] if t["id"]!=target and t["observed"]]
    for j,spec,f,g in candidates:
        # A committed foreign observation is a negative example, not a
        # recovery candidate. Require raw current face and body evidence.
        conflict=any(iou(spec.person,t["bounds"])>.9 or
                     (t["mask"] is not None and np.array_equal(masks[j],t["mask"])) for t in foreign)
        rows.append(ReappearanceCandidate(g,spec.person,f,int(not conflict and detections[j].confidence>=.5)))
    a=(Garment*len(roots))(*roots);b=(Garment*len(rivals))(*rivals)
    c=(ReappearanceCandidate*len(rows))(*rows)
    decision=ReappearanceDecision()
    status=lib.reappearance_choose(trusted[0],trusted[1],age,a,len(a),b,len(b),c if len(c) else None,len(c),C.byref(decision))
    row["reappearance_attempt"].update(status=status,candidates=len(c),candidate_eligible=[x.eligible for x in rows])
    if status!=0:
        return None
    j,spec,measured,g=candidates[decision.index]
    mask=masks[j]
    placement=HeadSticker()
    check(lib.reappearance_sticker(ptr(mask),mask.size,spec,measured,C.byref(placement)))
    required=Ellipse()
    check(lib.current_face_region(ptr(mask),mask.size,spec,measured,C.byref(required)))
    row["reappearance_attempt"].update(candidate_index=j,similarity=decision.similarity,
                                        rival_root=decision.rival_root,rival_current=decision.rival_current)
    # Do not renew the root, set a body observation, or seed any continuation.
    return measured,required,placement,None


def duplicate_reappearance(lib,face,evidence,report,snapshot,track,target,roots,row,cache,drops):
    """Coverage only. A contaminated direct hit cannot become a garment
    root unless the original current raw mask passes the exclusive sampler.
    Raw duplicate proposals are coalesced only inside this one recovery lane.
    """
    if len(roots or ())<3 or track["observed"] or track["dormant"] or track["state"] not in (2,3):return None
    if not any(q.get("track") and q["track"]["state"]==2 for q in report[:-1]):return None
    entry=evidence[row["index"]];frame,image,tr,ds,masks=entry
    if len(ds)<2 or len(ds)>32:return None
    # Find last *qualified* direct root. Cache the immutable garment result
    # per direct frame, including rejection, rather than learning on a gap.
    now=row["time_us"]
    bank=cache.setdefault("direct",{})
    anchor=None
    for old in reversed(report[:-1]):
        age=now-old["time_us"]
        if age>2_000_000:break
        if old.get("kind")!=0 or old["index"] in drops or not old["track"]["observed"]:continue
        if old["index"] not in bank:
            prev=evidence[old["index"]];pds=prev[3];pm=prev[4]
            match=[j for j,d in enumerate(pds) if vals(d.box)==old["track"]["bounds"]]
            candidate=None
            if len(match)==1:
                j=match[0];sp=MaskSpec(160,160,prev[2],pds[j].box);rgb=np.ascontiguousarray(prev[1].convert("RGB"));g=Garment();f=Ellipse(*old["raw"])
                status=lib.garment(ptr(rgb),rgb.size,ptr(pm),pm.size,len(pds),j,sp,f,C.byref(g))
                if status<0:raise RuntimeError("invalid historical garment evidence")
                if status==0:candidate=(sp.person,f,old["time_us"])
            bank[old["index"]]=candidate
        if bank[old["index"]] is not None:
            anchor=bank[old["index"]];break
    if anchor is None or not 950_000<now-anchor[2]<=2_000_000:return None
    if not any(q["time_us"]>anchor[2] and q.get("track") and q["track"]["state"]==2 for q in report[:-1]):return None
    # All current raw proposals, including weak ones, remain rivals until
    # the shared Rust pair test certifies same-face redundancy.
    foreign=[t for t in snapshot["tracks"] if t["id"]!=target and t["observed"]]
    rows=[]
    for j,d in enumerate(ds):
        conflict=any(iou(d.box,t["bounds"])>.9 or (t["mask"] is not None and np.array_equal(masks[j],t["mask"])) for t in foreign)
        rows.append(DuplicateProposal(d.box,Ellipse(),d.confidence,int(conflict)))
    raw=(DuplicateProposal*len(rows))(*rows);sp=MaskSpec(160,160,tr,ds[0].box)
    status=lib.duplicate_probe(ptr(masks),masks.size,sp,raw,len(raw))
    if status<0:raise RuntimeError("invalid duplicate geometry evidence")
    if status!=0:return None
    faces=[]
    for j,d in enumerate(ds):
        f=detect_face(lib,face,image,MaskSpec(160,160,tr,d.box),masks[j]);faces.append(f)
        raw[j].face=f or Ellipse()
    pair=(U*2)();union=np.zeros_like(masks[0])
    status=lib.duplicate_pair(ptr(masks),masks.size,sp,raw,len(raw),pair,ptr(union),union.size)
    attempt=dict(root_age_us=now-anchor[2],root_index=next(i for i,v in bank.items() if v is anchor),pair_status=status,current_face_calls=len(ds))
    row["duplicate_reappearance_attempt"]=attempt
    if status<0:raise RuntimeError("invalid duplicate pair evidence")
    if status!=0:return None
    a,b=pair;attempt["pair"]=[a,b]
    if cache.get("root_time")!=anchor[2]:
        g,r,inds=garment_roots(lib,face,evidence,report,anchor)
        cache.update(root_time=anchor[2],roots=g,rivals=r,indices=inds)
    gr,rv=cache["roots"],cache["rivals"]
    attempt.update(root_indices=cache["indices"],rival_count=len(rv))
    if len(gr)<2 or not rv:return None
    # Union only the certified pair. All other masks, even those without a
    # current garment descriptor, continue to participate as mask negatives.
    indices=[j for j in range(len(ds)) if j!=b]
    merged=np.ascontiguousarray(np.stack([union if j==a else masks[j] for j in indices]))
    rgb=np.ascontiguousarray(image.convert("RGB"));candidates=[];primary=None
    for j,k in enumerate(indices):
        f=faces[k]
        if f is None:continue
        spk=MaskSpec(160,160,tr,ds[k].box);g=Garment();status=lib.garment(ptr(rgb),rgb.size,ptr(merged),merged.size,len(indices),j,spk,f,C.byref(g))
        if status<0:raise RuntimeError("invalid current garment evidence")
        if status==0:
            if k==a:primary=len(candidates)
            candidates.append(ReappearanceCandidate(g,spk.person,f,int(k==a)))
    if primary is None:return None
    ga=(Garment*len(gr))(*gr);ra=(Garment*len(rv))(*rv);ca=(ReappearanceCandidate*len(candidates))(*candidates);decision=ReappearanceDecision()
    status=lib.reappearance_choose(anchor[0],anchor[1],now-anchor[2],ga,len(ga),ra,len(ra),ca,len(ca),C.byref(decision));attempt["garment_status"]=status
    if status!=0 or decision.index!=primary:return None
    attempt.update(similarity=decision.similarity,rival_root=decision.rival_root,rival_current=decision.rival_current)
    sp=MaskSpec(160,160,tr,ds[a].box);plan=np.empty(6,np.float32)
    status=lib.duplicate_plan(faces[a],tr,ptr(plan))
    if status!=0:return None
    local=detect_face(lib,face,image,sp,union,explicit_plan=plan);attempt["current_face_calls"]+=1
    if local is None:return None
    attempt["local_face"]=vals(local)
    placement=HeadSticker();status=lib.duplicate_sticker(ptr(masks),masks.size,sp,raw,len(raw),a,b,local,C.byref(placement));attempt["finish_status"]=status
    if status<0:raise RuntimeError("invalid duplicate finish evidence")
    if status!=0:return None
    required=Ellipse();check(lib.current_face_region(ptr(union),union.size,sp,local,C.byref(required)))
    return local,required,placement,None


def merged_edge_current(lib,yolo,face,history,snapshot,track,entry,spec,target,roots,row,drops):
    """Only a current single merged mask, retained occluded rival, and two
    previously observed distinct identities qualify. History plans crops,
    never supplies coverage. All actual inference is on current source pixels
    except the one explicitly frozen rival anchor observation."""
    frame,image,transform,ds,masks=entry
    if len(roots or ())!=2 or len(snapshot["tracks"])!=2 or len(ds)!=1 or not track["observed"] or track["mask"] is None:
        return None
    if ds[0].confidence<.50 or vals(ds[0].box)!=vals(track["bounds"]) or not np.array_equal(masks[0],track["mask"]):
        return None
    rival=next(t for t in snapshot["tracks"] if t["id"]!=target)
    if rival["id"] not in roots or target not in roots or rival["observed"] or rival["dormant"] or rival["state"] not in (2,3):
        return None
    now=frame["time_us"]
    prior_cap=None
    prior_rival=None
    for prev,matches,cap in reversed(history):
        old=prev[0]
        age=now-old["time_us"]
        if age>150_000: break
        if age<=0 or set(matches)!=set(roots): continue
        if prior_cap is None and cap is not None:
            prior_cap=(Rect(*cap),age,old["index"])
        if prior_rival is None and old["index"] not in drops:
            prior_rival=(prev,matches[rival["id"]],age,old["index"])
        if prior_cap and prior_rival: break
    if prior_cap is None or prior_rival is None: return None
    prev,j,rival_age,rival_index=prior_rival
    prev_spec=MaskSpec(160,160,prev[2],prev[3][j].box)
    prior_face=detect_face(lib,face,prev[1],prev_spec,prev[4][j],pair=(prev[4],j))
    attempt=dict(prior_cap_index=prior_cap[2],prior_rival_index=rival_index,anchor_face_calls=1,segmentation_calls=0,current_face_calls=0)
    row["merged_edge_attempt"]=attempt
    if prior_face is None: return None
    a=MergedEdgeAnchor(prior_cap[0],prev_spec.person,prior_face,prior_cap[1],rival_age)
    views=(Rect*3)()
    status=lib.merged_edge_views(spec,a,views)
    attempt["plan_status"]=status
    if status<0: raise RuntimeError("invalid merged edge view plan")
    if status!=0: return None
    attempt["views"]=[vals(v) for v in views]
    planes=[masks[0]];confidence=[]
    for v in views:
        crop=image.crop(tuple(int(x) for x in vals(v)))
        local,ds2,ms2=detect_instances(lib,yolo,crop)
        attempt["segmentation_calls"]+=1
        if len(ds2)!=1 or ds2[0].confidence<.50:
            attempt["local_instances"]=len(ds2)
            return None
        mapped=np.zeros((160,160),np.uint8)
        check(lib.reproject_mask(ptr(ms2[0]),ms2[0].size,local,transform,v,ptr(mapped),mapped.size))
        planes.append(mapped);confidence.append(ds2[0].confidence)
    attempt["local_confidence"]=confidence
    current=detect_face(lib,face,image,MaskSpec(160,160,transform,a.rival_body),planes[3])
    attempt["current_face_calls"]=1
    if current is None:return None
    attempt["current_rival_face"]=vals(current)
    packed=np.ascontiguousarray(np.stack(planes));conf=np.asarray(confidence,np.float32)
    output=HeadSticker()
    status=lib.merged_edge_finish(ptr(packed),packed.size,spec,a,ptr(conf),current,C.byref(output))
    attempt["finish_status"]=status
    if status<0:raise RuntimeError("invalid merged edge finish evidence")
    return output if status==0 else None


def summarize(rows):
    usable = [r for r in rows if r["render"] is not None]
    sample = [r for r in usable if r["index"] % 3 == 0 or r["index"] == rows[-1]["index"]]
    def ratio(values):
        return max(values)/min(values) if values and min(values)>0 else None
    motion_audits=[r["relative_motion_error"] for r in usable if r.get("relative_motion_error") is not None]
    return dict(frames=len(rows), tracked=sum(r["track"] is not None for r in rows),
                body_observed=sum(r["track"] is not None and r["track"]["observed"] for r in rows),
                direct_face=sum(r["kind"]==0 for r in rows), projected_face=sum(r["kind"]==1 for r in rows),
                estimated_face=sum(r["kind"]==2 for r in rows),
                coverage_only_recovered=sum(r["kind"] in (3,4,5) for r in rows),
                garment_reappearance=sum(r["kind"]==4 for r in rows),
                terminal_current_continuations=sum(r["kind"]==3 and r.get("recovery_attempt",{}).get("continuation",False) for r in rows),
                rendered=len(usable), missing=[r["index"] for r in rows if r["render"] is None],
                pixel_bridged=sum(r.get("pixel_motion") is not None for r in rows),
                topology_frames=sum(r.get("topology") in (1,2) for r in rows),
                max_relative_motion_error_x=max((abs(e[0]) for e in motion_audits),default=None),
                max_relative_motion_error_y=max((abs(e[1]) for e in motion_audits),default=None),
                relative_motion_75_pass=(len(motion_audits)==len(rows) and all(max(map(abs,e))<=75 for e in motion_audits)) if motion_audits else None,
                head_point_covered=sum(r.get("head_covered") is True for r in usable),
                head_point_checked=sum(r.get("head_covered") is not None for r in usable),
                head_mask_point_covered=sum(r.get("head_mask_covered") is True for r in usable),
                head_mask_point_checked=sum(r.get("head_mask_covered") is not None for r in usable),
                width_ratio=ratio([r["width"] for r in usable]),
                height_ratio=ratio([r["height"] for r in usable]),
                sampled_width_ratio=ratio([r["width"] for r in sample]),
                sampled_height_ratio=ratio([r["height"] for r in sample]),
                sampled_widths=[round(r["width"],2) for r in sample])


def replay(lib, face, evidence, target, drops, cover_fallback=False, protected_roots=None, multiperson_recovery=False, pair_continuity=False, pair_head=False, merged_edge_yolo=None, crowded_recovery=False, crowded_consensus=False, crowd_clothing=False):
    tracking = Tracking(lib,target,protected_roots,pair_continuity,crowd_clothing)
    pixel_bind(lib)
    state, render_state, sticker_state, trusted, pixel_seed = State(), RenderState(), RenderState(), None, None
    sticker_seed = None
    recovery_previous = None
    reappearance_cache = {}
    merged_history = []
    duplicate_cache = {}
    rejected_direct_at = None
    first_head = None
    report = []
    try:
        for frame, image, transform, detections, masks in evidence:
            time, index = frame["time_us"], frame["index"]
            if merged_edge_yolo is not None:
                merged_history=[q for q in merged_history if 0<time-q[0][0]["time_us"]<=150_000]
            prior_recovery = recovery_previous
            recovery_previous = None  # no history across any other lane, rejected frame, or gap
            snapshot = tracking.step(transform,detections,masks,time,index==0,image)
            track = next((t for t in snapshot["tracks"] if t["id"]==target),None)
            row = dict(index=index,time_us=time,detected_persons=len(detections),
                       raw_boxes=[vals(d.box) for d in detections],
                       tracks=[{k:(vals(v) if isinstance(v,Rect) else v) for k,v in t.items()
                                if k not in ("mask","transform")} for t in snapshot["tracks"]],
                       class_evidence=snapshot["face"],motion_evidence=snapshot["motion"],
                       tracking_snapshot_sha256=snapshot["sha256"],
                       track=None,kind=None,render=None,pixel_motion=None)
            report.append(row)
            if index==0 and (track is None or not track["observed"]):
                raise RuntimeError("Target ID has no initial observed identity")
            if track is None or track["dormant"]:
                state, render_state, sticker_state, trusted, pixel_seed = State(), RenderState(), RenderState(), None, None
                sticker_seed = None
                reappearance_cache.clear()
                merged_history.clear()
                duplicate_cache.clear()
                rejected_direct_at = None
                continue
            row["track"] = next(t for t in row["tracks"] if t["id"]==target)
            if "person_bbox" in frame:
                row["audit_track_iou"] = iou(track["bounds"],Rect(*frame["person_bbox"]))
                if index==0 and row["audit_track_iou"]<.30:
                    raise RuntimeError("Initial target ID does not match fixture annotation")
            # Never use a warped/predicted mask as a current ownership witness.
            mask = track["mask"] if track["observed"] else None
            spec = MaskSpec(160,160,track["transform"] or transform,track["bounds"])
            pixel_required = None
            # Research requires a complete pair of current raw masks and an
            # exact canonical current observation (no warped or merged guess).
            pair=None
            edge=None
            if pair_head and mask is not None and len(snapshot["tracks"])==2 and len(detections)==2 and len(protected_roots or ())==2:
                matches=[j for j,d in enumerate(detections) if vals(d.box)==vals(spec.person) and np.array_equal(masks[j],mask)]
                if len(matches)==1:
                    pair=(masks,matches[0])
                    edge=HeadSticker()
                    status=lib.pair_edge_sticker(ptr(masks),masks.size,matches[0],spec,C.byref(edge))
                    if status<0: raise RuntimeError("invalid edge head evidence")
                    if status!=0: edge=None
            direct = detect_face(lib,face,image,spec,mask,pair=pair) if track["observed"] and mask is not None and index not in drops else None
            crowded_index=None
            if crowded_consensus and mask is not None and 3<=len(detections)<=32 and len(protected_roots or ())>=3:
                matches=[j for j,d in enumerate(detections) if vals(d.box)==vals(spec.person) and np.array_equal(masks[j],mask)]
                if len(matches)==1:crowded_index=matches[0]
            if direct is not None and crowded_index is not None:
                plans=np.empty((2,6),np.float32)
                status=lib.crowded_face_views(ptr(masks),masks.size,len(detections),crowded_index,spec,direct,ptr(plans))
                if status<0:raise RuntimeError("invalid crowded face evidence")
                if status==0:
                    local=[detect_face(lib,face,image,spec,mask,explicit_plan=plan) for plan in plans]
                    packed=(Ellipse*2)(*(f or Ellipse() for f in local))
                    status=lib.crowded_face_accept(ptr(masks),masks.size,len(detections),crowded_index,spec,direct,packed)
                    if status<0:raise RuntimeError("invalid crowded face corroboration")
                    row["crowded_face_consensus"]={"primary":vals(direct),"local":[vals(f) if f is not None else None for f in local],"accepted":status==0}
                    if status!=0:
                        direct=None
                        rejected_direct_at=time
            if direct is not None:
                rejected_direct_at=None
                raw, kind = direct, 0
                trusted = (spec.person, raw, time)
            elif trusted is not None and 0 <= time-trusted[2] <= 150_000:
                raw, kind = Ellipse(), 1
                if pixel_seed is not None and hasattr(lib,"pixel_motion"):
                    old_gray,old_view,observed,covered,seed_time=pixel_seed
                    gray,view=gray_view(image)
                    foreign=[t["bounds"] for t in snapshot["tracks"] if t["id"]!=target and t["observed"]]
                    # Also veto raw detections without an observed selected owner.
                    # Unknown competing current people are not positive identity evidence.
                    for detection in detections:
                        if not track["observed"] or iou(detection.box,track["bounds"])<.90:
                            if not any(iou(detection.box,b)>.90 for b in foreign):
                                foreign.append(detection.box)
                    boxes=(Rect*len(foreign))(*foreign)
                    bridge=PixelBridge()
                    status=lib.pixel_motion(ptr(old_gray),old_gray.size,old_view,ptr(gray),gray.size,view,
                                            observed,covered,time-seed_time,boxes if foreign else None,len(foreign),C.byref(bridge))
                    if status<0:
                        raise RuntimeError(f"pixel motion returned {status}")
                    if status==0:
                        # Keep detector/temporal radii separate from the final
                        # render envelope: feeding an expanded cover back into
                        # RegionState would enlarge the next mask support search.
                        raw=bridge.measurement
                        pixel_required=bridge.required
                        row["pixel_motion"]=dict(zip(("dx","dy","correlation","uniqueness"),vals(bridge.motion)))
                if row["pixel_motion"] is None:
                    check(lib.project_face_region(spec.person,trusted[0],trusted[1],time-trusted[2],C.byref(raw)))
            elif track["observed"] and mask is not None:
                fallback = Head()
                check(lib.current_head_region(ptr(mask),mask.size,spec,None,C.byref(fallback)))
                raw,kind=fallback.region,2
                if crowded_index is not None and rejected_direct_at is not None and 0<=time-rejected_direct_at<=150_000:
                    status=lib.crowded_weak_conflict(ptr(masks),masks.size,len(detections),crowded_index,spec,raw,time-rejected_direct_at)
                    if status<0:raise RuntimeError("invalid crowded weak evidence")
                    if status==0:
                        row["crowded_unresolved_weak"]={"estimate":vals(raw),"age_us":time-rejected_direct_at}
                        continue  # no new trusted observation or false weak head; unresolved visibility
            else:
                recovery=recover_current(lib,face,image,transform,detections,masks,snapshot,track,trusted,time,target,row,prior_recovery) if multiperson_recovery and index not in drops else None
                coverage_kind=3
                if multiperson_recovery and recovery is None and index not in drops:
                    recovery=reappear_current(lib,face,evidence,evidence[index],report,snapshot,track,trusted,target,row,reappearance_cache)
                    coverage_kind=4
                if crowded_recovery and recovery is None and index not in drops:
                    recovery=duplicate_reappearance(lib,face,evidence,report,snapshot,track,target,protected_roots,row,duplicate_cache,drops)
                    coverage_kind=5
                if recovery is None:
                    continue
                raw,required,placement,recovery_previous=recovery
                sticker=placement.square
                half=max(8,1.02*max(sticker.rx,sticker.ry))
                row.update(kind=coverage_kind,raw=vals(raw),required=vals(required),render=vals(required),
                           sticker=vals(sticker),topology=placement.topology,topology_rows=placement.rows,
                           sticker_required_bounds=vals(placement.bounds),
                           width=min(transform.width,sticker.x+half)-max(0,sticker.x-half),
                           height=min(transform.height,sticker.y+half)-max(0,sticker.y-half),
                           head_covered=None)
                if "head_x" in frame:
                    dx,dy=frame["head_x"]-sticker.x,frame["head_y"]-sticker.y
                    row["head_covered"]=max(abs(dx),abs(dy))<=half
                    row["head_mask_covered"]=((frame["head_x"]-required.x)/required.rx)**2+((frame["head_y"]-required.y)/required.ry)**2<=1
                    if first_head is None:
                        first_head=(sticker.x,sticker.y,frame["head_x"],frame["head_y"])
                    x0,y0,hx0,hy0=first_head
                    row["relative_motion_error"]=[sticker.x-x0-(frame["head_x"]-hx0),sticker.y-y0-(frame["head_y"]-hy0)]
                continue
            out=Ellipse()
            flags=int(track["observed"]) | (16 if kind==0 else 0)
            if row["pixel_motion"] is not None:
                flags |= 2
            check(lib.region_step(C.byref(state),Measurement(raw,spec.person,time,kind,flags),C.byref(out)))
            required=pixel_required if pixel_required is not None else out
            if (kind==0 or (cover_fallback and kind==2)) and mask is not None:
                required=Ellipse()
                check(lib.current_face_region(ptr(mask),mask.size,spec,out,C.byref(required)))
            render=required
            if hasattr(lib,"render_size_step"):
                render=Ellipse()
                check(lib.render_size_step(C.byref(render_state),required,time,kind,C.byref(render)))
                if render.x!=required.x or render.y!=required.y or render.rx<required.rx or render.ry<required.ry:
                    raise AssertionError("Size continuity lost required current coverage")
            sticker = render
            if hasattr(lib,"head_sticker"):
                if kind in (0,2) and mask is not None:
                    placement=HeadSticker()
                    check(lib.head_sticker(ptr(mask),mask.size,spec,raw,out,kind,C.byref(placement)))
                    sticker_required=placement.square
                    row["topology"]=placement.topology
                    row["topology_rows"]=placement.rows
                    row["sticker_required_bounds"]=vals(placement.bounds)
                elif row["pixel_motion"] is not None and sticker_seed is not None:
                    m=row["pixel_motion"]
                    sticker_required=Ellipse(sticker_seed.x+m["dx"],sticker_seed.y+m["dy"],sticker_seed.rx,sticker_seed.ry)
                else:
                    sticker_required=out
                sticker=Ellipse()
                check(lib.render_size_step(C.byref(sticker_state),sticker_required,time,kind,C.byref(sticker)))
                if kind==0:
                    sticker_seed=sticker
            # Render-only visible cap for a weak observation. Do not feed
            # the new shape back to temporal or identity/detector state.
            if edge is not None and kind in (1,2):
                sticker=edge.square
                row["pair_edge_compact"]=True
                row["pair_edge_bounds"]=vals(edge.bounds)
            if merged_edge_yolo is not None and kind in (1,2) and index not in drops:
                merged=merged_edge_current(lib,merged_edge_yolo,face,merged_history,snapshot,track,evidence[index],spec,target,protected_roots,row,drops)
                if merged is not None:
                    sticker=merged.square
                    row["merged_edge_bounds"]=vals(merged.bounds)
            if merged_edge_yolo is not None and len(protected_roots or ())==2 and len(snapshot["tracks"])==2 and len(detections)==2:
                exact={}
                for person in snapshot["tracks"]:
                    if not person["observed"] or person["dormant"] or person["mask"] is None: break
                    indices=[j for j,d in enumerate(detections) if vals(d.box)==vals(person["bounds"]) and np.array_equal(masks[j],person["mask"])]
                    if len(indices)!=1: break
                    exact[person["id"]]=indices[0]
                if set(exact)==set(protected_roots) and len(set(exact.values()))==2:
                    merged_history.append((evidence[index],exact,row.get("pair_edge_bounds")))
            if kind==0 and hasattr(lib,"pixel_motion"):
                gray,view=gray_view(image)
                pixel_seed=(gray,view,raw,render,time)
            half=max(8,1.02*max(sticker.rx,sticker.ry))
            width=min(transform.width,sticker.x+half)-max(0,sticker.x-half)
            height=min(transform.height,sticker.y+half)-max(0,sticker.y-half)
            row.update(kind=kind,raw=vals(raw),stabilized=vals(out),required=vals(required),render=vals(render),
                       sticker=vals(sticker),width=width,height=height,head_covered=None)
            if "head_x" in frame:
                dx,dy=frame["head_x"]-sticker.x,frame["head_y"]-sticker.y
                row["head_covered"]=max(abs(dx),abs(dy))<=half
                row["head_mask_covered"]=((frame["head_x"]-render.x)/render.rx)**2+((frame["head_y"]-render.y)/render.ry)**2<=1
                row["head_error"]=math.hypot(dx,dy)
                if first_head is None:
                    first_head=(sticker.x,sticker.y,frame["head_x"],frame["head_y"])
                x0,y0,hx0,hy0=first_head
                row["relative_motion_error"]=[sticker.x-x0-(frame["head_x"]-hx0),
                                               sticker.y-y0-(frame["head_y"]-hy0)]
    finally:
        tracking.close()
    return dict(summary=summarize(report),frames=report)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline",type=Path,required=True)
    parser.add_argument("--candidate",type=Path,required=True)
    parser.add_argument("--size-only",type=Path)
    parser.add_argument("--fixture",type=Path,default=ROOT/"testdata/face_dynamic_smoke/dynamic_manifest.json")
    parser.add_argument("--target-id",type=int,default=1)
    parser.add_argument("--drop-faces",default="")
    parser.add_argument("--credible-roots",action="store_true",
                        help="Protect first-frame analysis IDs with confidence >= .60 as in Android FACE_ONLY; does not add privacy targets")
    parser.add_argument("--cover-fallback-experiment",action="store_true")
    parser.add_argument("--multiperson-recovery",action="store_true",help="Opt in to candidate-only local recovery experiments")
    parser.add_argument("--pair-continuity",action="store_true",help="Candidate-only experimental two-person raw-mask association")
    parser.add_argument("--pair-head",action="store_true",help="Candidate-only current pair face ownership and edge cap experiment")
    parser.add_argument("--inject-pair-merge",default="",help="Host diagnostic only: union exactly two current raw masks/boxes at these frame indices for BOTH comparison arms")
    parser.add_argument("--merged-edge",action="store_true",help="Candidate-only bounded current local resegmentation for a merged edge head")
    parser.add_argument("--crowded-recovery",action="store_true",help="Candidate-only duplicate current proposal recovery using verified historical garment roots")
    parser.add_argument("--crowded-consensus",action="store_true",help="Candidate-only contested direct face corroboration and weak head conflict check")
    parser.add_argument("--crowd-clothing",action="store_true",help="Candidate-only experimental clothing-supported crowded association")
    parser.add_argument("--same-base-policy",action="store_true",help="Apply enabled association and recovery flags to baseline too")
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    if args.merged_edge and not (args.pair_head and args.pair_continuity and args.same_base_policy):
        parser.error("--merged-edge requires --pair-head --pair-continuity --same-base-policy for a paired M3 comparison")
    if args.crowded_recovery and not (args.merged_edge and args.multiperson_recovery):
        parser.error("--crowded-recovery requires --merged-edge and --multiperson-recovery for paired M3.1 comparison")
    if args.crowded_consensus and not args.crowded_recovery:
        parser.error("--crowded-consensus requires --crowded-recovery for paired M3.2 comparison")
    if args.crowd_clothing and not args.crowded_consensus:
        parser.error("--crowd-clothing requires --crowded-consensus")
    base, candidate=load(args.baseline),load(args.candidate)
    yolo=model(ROOT/"models/litert/yolo11n-seg-fp16.tflite")
    face=model(ROOT/"testdata/models/face/blaze_face_full_range.tflite")
    fixture=json.loads(args.fixture.read_text())
    fps=fixture.get("fps",fixture.get("source_fps",30))
    evidence=[]
    injected_merges={int(v) for v in args.inject_pair_merge.split(",") if v}
    if 0 in injected_merges or injected_merges-set(range(len(fixture["frames"]))):
        parser.error("merge injection indices must be noninitial fixture frames")
    for i,f in enumerate(fixture["frames"]):
        path=args.fixture.parent/f["asset"]
        if hashlib.sha256(path.read_bytes()).hexdigest()!=f["sha256"]:
            raise ValueError(f"Fixture hash mismatch: {path}")
        image=Image.open(path).convert("RGB")
        transform,detections,masks=detect_instances(base,yolo,image)
        if i in injected_merges:
            if len(detections)!=2:
                parser.error(f"injected merge requires exactly two raw detections at {i}")
            a,b=(d.box for d in detections)
            detections=[Detection(Rect(min(a.left,b.left),min(a.top,b.top),max(a.right,b.right),max(a.bottom,b.bottom)),max(d.confidence for d in detections))]
            masks=np.maximum(masks[0],masks[1])[None,:,:]
        evidence.append((dict(f,index=i,time_us=round(i*1e6/fps)),image,transform,detections,masks))
    drops={int(v) for v in args.drop_faces.split(",") if v}
    if drops-set(range(len(evidence))):
        parser.error("drop indices outside fixture")
    if not evidence:
        parser.error("empty fixture")
    roots=([i for i,d in enumerate(evidence[0][3]) if d.confidence >= .60]
           if args.credible_roots else [])
    roots=sorted(set(roots)|{args.target_id})
    report={"scope":"production Rust tracking + host CPU face geometry; not native export or pixel privacy acceptance",
            "fixture_sha256":hashlib.sha256(args.fixture.read_bytes()).hexdigest(),
            "target_id":args.target_id,"identity_protected_roots":roots,"injected_face_drops":sorted(drops),
            "experimental_cover_fallback":args.cover_fallback_experiment,
            "multiperson_recovery":args.multiperson_recovery,
            "pair_continuity":args.pair_continuity,
            "pair_head":args.pair_head, "merged_edge":args.merged_edge, "crowded_recovery":args.crowded_recovery, "crowded_consensus":args.crowded_consensus,"crowd_clothing":args.crowd_clothing, "same_base_policy":args.same_base_policy, "injected_pair_merges":sorted(injected_merges),
            "model_sha256": {name:hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
                             for name,path in [("person","models/litert/yolo11n-seg-fp16.tflite"),("face","testdata/models/face/blaze_face_full_range.tflite")]}}
    libs=[("baseline",base)]
    if args.size_only:
        libs.append(("size_only",load(args.size_only)))
    libs.append(("candidate",candidate))
    for name,lib in libs:
        report[name]=replay(lib,face,evidence,args.target_id,drops,cover_fallback=args.cover_fallback_experiment and name=="candidate",protected_roots=roots,multiperson_recovery=args.multiperson_recovery and (name=="candidate" or args.same_base_policy),pair_continuity=args.pair_continuity and (name=="candidate" or args.same_base_policy),pair_head=args.pair_head and (name=="candidate" or args.merged_edge),merged_edge_yolo=yolo if args.merged_edge and (name=="candidate" or args.crowded_recovery) else None,crowded_recovery=args.crowded_recovery and (name=="candidate" or args.crowded_consensus),crowded_consensus=args.crowded_consensus and (name=="candidate" or args.crowd_clothing),crowd_clothing=args.crowd_clothing and name=="candidate")
        print(name,json.dumps(report[name]["summary"]),flush=True)
    for name in [n for n,_ in libs if n!="baseline"]:
        if (args.crowd_clothing or (args.pair_continuity and not args.same_base_policy)) and name=="candidate":
            continue  # explicitly testing a canonical association change
        for a,b in zip(report["baseline"]["frames"],report[name]["frames"],strict=True):
            if a["tracking_snapshot_sha256"]!=b["tracking_snapshot_sha256"] or any(a[k]!=b[k] for k in ("tracks","class_evidence","motion_evidence")):
                raise AssertionError(f"Tracker state changed in {name} at frame {a['index']}")
    report["tracking_snapshot_equal"]=all(a["tracking_snapshot_sha256"]==b["tracking_snapshot_sha256"] for a,b in zip(report["baseline"]["frames"],report["candidate"]["frames"],strict=True))
    args.output.write_text(json.dumps(report,indent=2)+"\n")


if __name__ == "__main__":
    main()
