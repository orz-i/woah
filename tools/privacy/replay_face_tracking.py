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
    RenderState, Head, check, detect_instances, detect_face, iou, load, model,
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


def pixel_bind(lib):
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
    if hasattr(lib,"frame_kernel_face_recovery_probe"):
        fn=lib.frame_kernel_face_recovery_probe
        fn.argtypes=[RecoveryAnchor,P,S,P,S,P]
        fn.restype=C.c_int32
        lib.recovery_probe=fn
        fn=lib.frame_kernel_face_recovery_finish
        fn.argtypes=[RecoveryAnchor,P,S,P,S,U,P,S,MaskSpec,Ellipse,P]
        fn.restype=C.c_int32
        lib.recovery_finish=fn
        if hasattr(lib,"frame_kernel_face_recovery_continuation_probe"):
            fn=lib.frame_kernel_face_recovery_continuation_probe
            fn.argtypes=[RecoveryAnchor,RecoveryPrevious,P,S,P,S,P]
            fn.restype=C.c_int32
            lib.recovery_continuation_probe=fn
            fn=lib.frame_kernel_face_recovery_continuation_finish
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
    def __init__(self, lib, target, protected_roots=None):
        tracker_bind(lib)
        self.lib, self.target = lib, target
        self.handle = lib.tracking_create(lib.tracking_defaults())
        if not self.handle:
            raise RuntimeError("tracking_create")
        ids = (I64 * 1)(target)
        roots = sorted(set(protected_roots or ()) | {target})
        protected = (I64 * len(roots))(*roots)
        check(lib.tracking_selection(self.handle, protected, len(roots), None, 0, ids, 1, 0))

    def step(self, transform, detections, masks, time, first):
        n = len(detections)
        rows = (Observation * n)(*(Observation(d.box, d.confidence, d.box.bottom,
                                               i*160*160, 160, 160, transform)
                                   for i, d in enumerate(detections)))
        action = 0 if first else (1 if n else 2)
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
    if continuation:
        gray,view=gray_view(image)
        status=lib.recovery_continuation_finish(anchor,previous,ptr(previous_gray),previous_gray.size,previous_view,
                                                ptr(gray),gray.size,view,bodies,len(bodies),foreign_ptr,len(competitors),
                                                j,ptr(mask),mask.size,spec,measured,C.byref(placement))
    else:
        status=lib.recovery_finish(anchor,bodies,len(bodies),foreign_ptr,len(competitors),
                                   j,ptr(mask),mask.size,spec,measured,C.byref(placement))
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
                coverage_only_recovered=sum(r["kind"] in (3,4) for r in rows),
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


def replay(lib, face, evidence, target, drops, cover_fallback=False, protected_roots=None):
    tracking = Tracking(lib,target,protected_roots)
    pixel_bind(lib)
    state, render_state, sticker_state, trusted, pixel_seed = State(), RenderState(), RenderState(), None, None
    sticker_seed = None
    recovery_previous = None
    reappearance_cache = {}
    first_head = None
    report = []
    try:
        for frame, image, transform, detections, masks in evidence:
            time, index = frame["time_us"], frame["index"]
            prior_recovery = recovery_previous
            recovery_previous = None  # no history across any other lane, rejected frame, or gap
            snapshot = tracking.step(transform,detections,masks,time,index==0)
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
            direct = detect_face(lib,face,image,spec,mask) if track["observed"] and mask is not None and index not in drops else None
            if direct is not None:
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
            else:
                recovery=recover_current(lib,face,image,transform,detections,masks,snapshot,track,trusted,time,target,row,prior_recovery) if index not in drops else None
                coverage_kind=3
                if recovery is None and index not in drops:
                    recovery=reappear_current(lib,face,evidence,evidence[index],report,snapshot,track,trusted,target,row,reappearance_cache)
                    coverage_kind=4
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
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    base, candidate=load(args.baseline),load(args.candidate)
    yolo=model(ROOT/"models/litert/yolo11n-seg-fp16.tflite")
    face=model(ROOT/"testdata/models/face/blaze_face_full_range.tflite")
    fixture=json.loads(args.fixture.read_text())
    fps=fixture.get("fps",fixture.get("source_fps",30))
    evidence=[]
    for i,f in enumerate(fixture["frames"]):
        path=args.fixture.parent/f["asset"]
        if hashlib.sha256(path.read_bytes()).hexdigest()!=f["sha256"]:
            raise ValueError(f"Fixture hash mismatch: {path}")
        image=Image.open(path).convert("RGB")
        transform,detections,masks=detect_instances(base,yolo,image)
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
            "model_sha256": {name:hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
                             for name,path in [("person","models/litert/yolo11n-seg-fp16.tflite"),("face","testdata/models/face/blaze_face_full_range.tflite")]}}
    libs=[("baseline",base)]
    if args.size_only:
        libs.append(("size_only",load(args.size_only)))
    libs.append(("candidate",candidate))
    for name,lib in libs:
        report[name]=replay(lib,face,evidence,args.target_id,drops,cover_fallback=args.cover_fallback_experiment and name=="candidate",protected_roots=roots)
        print(name,json.dumps(report[name]["summary"]),flush=True)
    for name in [n for n,_ in libs if n!="baseline"]:
        for a,b in zip(report["baseline"]["frames"],report[name]["frames"],strict=True):
            if a["tracking_snapshot_sha256"]!=b["tracking_snapshot_sha256"] or any(a[k]!=b[k] for k in ("tracks","class_evidence","motion_evidence")):
                raise AssertionError(f"Tracker state changed in {name} at frame {a['index']}")
    report["tracking_snapshot_equal"]=True
    args.output.write_text(json.dumps(report,indent=2)+"\n")


if __name__ == "__main__":
    main()
