#!/usr/bin/env python3
"""Independent head detector + anonymous reciprocal trajectory experiment.

Model weights stay outside the repository. FrameKernel owns tensor decode,
view fusion, current mask ownership, temporal links, bounded bridge and final
geometry. Source labels are never inputs. Scene evidence is target-independent.
"""
import argparse
import ctypes as C
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image

from evaluate_body_reid import PERSON_MODEL, FACE_MODEL, sha
from replay_face_geometry import (P, S, U, F, Detection, Transform, Rect, Ellipse,
    Letterbox, PixelSpec, struct, check, load, model, detect_instances, ptr, vals)

MODEL_SHA = {
    "yolo-head-nano.onnx": "899aac905efae0b504bab324a7ef3a5444612d6511966d9da1efc3964993b4c5",
    "yolo-head-medium.onnx": "19ae628e67095520fabe7b21eb54d2fd1c1dae93099cae68213b58d52aaa3e63",
}
I = C.c_int32
I64 = C.c_int64
Placement = struct("HeadInstancePlacement",[("required",Rect),("square",Ellipse),("rival_overlap",F)])


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,separators=(",",":"))+"\n")


class HeadKernel:
    def __init__(self,lib):
        self.lib=lib
        for name,args in {
            "decode_heads":[P,S,S,Transform,U,P,S],
            "fuse_heads":[P,S,P,S,P,S],
            "head_links":[P,S,P,S,I64,P],
            "head_owners":[P,S,P,S,P,S,Transform,P],
            "head_path":[P,P,S,I,P],
            "head_instance_placement":[P,S,S,Transform,P],
        }.items():
            f=getattr(lib,"frame_kernel_research_"+name);f.argtypes=args;f.restype=I;setattr(self,name,f)

        for name,args in {
            "head_certified_path":[P,P,S,I,P],
            "head_arbitrate":[P,S,S,Transform,Ellipse,Rect,Ellipse,P,P],
        }.items():
            f=getattr(lib,"frame_kernel_research_"+name,None)
            if f is not None:f.argtypes=args;f.restype=I;setattr(self,name,f)

    def decode(self,tensor,tr,flip):
        output=(Detection*128)();n=self.decode_heads(ptr(tensor),tensor.size,tensor.size//5,tr,flip,output,128)
        if n<0:raise RuntimeError("invalid independent head tensor")
        return list(output)[:n]

    def fuse(self,a,b):
        result=(Detection*128)();n=self.fuse_heads((Detection*len(a))(*a),len(a),(Detection*len(b))(*b),len(b),result,128)
        if n<0:raise RuntimeError("invalid independent view fusion")
        return list(result)[:n]

    def owners(self,heads,ds,masks,tr):
        out=(I*len(heads))()
        check(self.head_owners((Detection*len(heads))(*heads),len(heads),(Rect*len(ds))(*[d.box for d in ds]),len(ds),ptr(masks),masks.size,tr,out))
        return list(out)

    def links(self,a,b,dt):
        out=(I*len(a))()
        check(self.head_links((Detection*len(a))(*a),len(a),(Detection*len(b))(*b),len(b),dt,out))
        return list(out)

    def path(self,times,owners,target,certified=False):
        out=(U*len(times))()
        fn=self.head_certified_path if certified else self.head_path
        check(fn((I64*len(times))(*times),(I*len(times))(*owners),len(times),target,out))
        return list(out)

    def placement(self,heads,j,tr):
        out=Placement()
        rc=self.head_instance_placement((Detection*len(heads))(*heads),len(heads),j,tr,C.byref(out))
        if rc<0:raise RuntimeError("invalid head placement")
        return out if rc==0 else None

    def arbitrate(self,heads,j,tr,row):
        previous=row.get("sticker") or row.get("render")
        b=row.get("sticker_required_bounds")
        face=row.get("raw") if row.get("kind")==0 else None
        out=Placement();mode=U()
        rc=self.head_arbitrate((Detection*len(heads))(*heads),len(heads),j,tr,
            Ellipse(*previous) if previous else Ellipse(),Rect(*b) if b else Rect(),
            Ellipse(*face) if face else Ellipse(),C.byref(out),C.byref(mode))
        if rc<0:raise RuntimeError("invalid head arbitration")
        return (out,mode.value) if rc==0 else (None,0)


def pack_head(d):
    return [*vals(d.box),float(d.confidence)]


def unpack_head(d):
    return Detection(Rect(*d[:4]),d[4])


class Detector:
    def __init__(self,k,path,threads):
        if MODEL_SHA.get(path.name)!=sha(path):raise ValueError("unrecognized head model bytes")
        import openvino as ov
        self.k=k
        self.net=ov.Core().compile_model(str(path),"CPU",{"INFERENCE_NUM_THREADS":threads,"INFERENCE_PRECISION_HINT":"f32"})
        if list(self.net.input(0).shape)!=[1,3,640,640] or list(self.net.output(0).shape)!=[1,5,8400]:
            raise ValueError("unexpected head model shape")
        self.calls=0;self.seconds=0.

    def detect(self,im,flip):
        lib=self.k.lib
        a=np.asarray(im.convert("RGBA"))
        if flip:a=np.ascontiguousarray(a[:,::-1])
        h,w=a.shape[:2];resized=np.empty((640,640,4),np.uint8);letter=Letterbox()
        check(lib.resize_rgba(ptr(a),a.nbytes,w,h,w*4,ptr(resized),resized.nbytes,640,640,114,C.byref(letter)))
        x=np.empty((1,3,640,640),np.float32)
        check(lib.normalize_pixels(ptr(resized),resized.nbytes,PixelSpec(640,640,2560,4,0,1,2,0,0,0,1/255,0),ptr(x),x.size))
        t=time.perf_counter();raw=np.ascontiguousarray(self.net([x])[0]);self.seconds+=time.perf_counter()-t;self.calls+=1
        tr=Transform(letter.scale,letter.left,letter.top,w,h,640,160)
        return self.k.decode(raw,tr,flip)


def produce(a,scene=None,person=None):
    start=time.perf_counter();fixture=json.loads(a.fixture.read_text())
    ref=json.loads(a.reference_replay.read_text());rows=ref["candidate"]["frames"]
    if ref["fixture_sha256"]!=sha(a.fixture) or ref["model_sha256"]!={"person":sha(PERSON_MODEL),"face":sha(FACE_MODEL)}:
        raise ValueError("reference fixture or model mismatch")
    if len(rows)!=len(fixture["frames"]):raise ValueError("reference length mismatch")
    k=HeadKernel(load(a.library));person=person if person is not None else model(PERSON_MODEL)
    detector=Detector(k,a.head_model,a.threads);frames=[];previous=None;person_calls=0
    for i,meta in enumerate(fixture["frames"]):
        f=a.fixture.parent/meta["asset"]
        if sha(f)!=meta["sha256"]:raise ValueError("source frame mismatch")
        if scene is None:
            im=Image.open(f).convert("RGB");tr,ds,ms=detect_instances(k.lib,person,im);person_calls+=1
        else:im,tr,ds,ms=scene(i)
        r=rows[i]
        if r["index"]!=i or [vals(d.box) for d in ds]!=r["raw_boxes"]:raise ValueError("raw scene evidence mismatch")
        primary=detector.detect(im,False)
        flipped=detector.detect(im,True) if a.dual_view else []
        heads=k.fuse(primary,flipped) if a.dual_view else primary
        owners=k.owners(heads,ds,ms,tr)
        rec={"index":i,"time_us":r["time_us"],"asset_sha256":meta["sha256"],"raw_boxes":r["raw_boxes"],
             "transform":vals(tr),"heads":[pack_head(h) for h in heads],"primary":[pack_head(h) for h in primary],
             "flipped":[pack_head(h) for h in flipped],"owners":owners,"links_next":[]}
        if previous is not None:
            last,old_heads=previous;delta=rec["time_us"]-last["time_us"]
            last["links_next"]=k.links(old_heads,heads,delta) if 0<delta<=100_000 else [-1]*len(old_heads)
        previous=(rec,heads);frames.append(rec)
        if i%75==0:print("scene",i,"heads",len(heads),"elapsed",round(time.perf_counter()-start,2),flush=True)
    result={"scope":__doc__,"fixture_sha256":sha(a.fixture),"library_sha256":sha(a.library),
            "head_model":a.head_model.name,"head_model_sha256":sha(a.head_model),"person_model_sha256":sha(PERSON_MODEL),
            "dual_view":a.dual_view,"head_calls":detector.calls,"head_inference_s":detector.seconds,"person_calls":person_calls,
            "elapsed_s":time.perf_counter()-start,"frames":frames}
    write(a.output,result);return result


def raw_root_map(row,roots):
    # Transient body IDs cannot establish an independent long-lived root;
    # however another protected root is an explicit contradictory witness.
    owners={}
    for t in row["tracks"]:
        if t["observed"] and not t["dormant"] and t["id"] in roots:
            hits=[j for j,b in enumerate(row["raw_boxes"]) if b==t["bounds"]]
            if len(hits)==1:
                owners.setdefault(hits[0],[]).append(t["id"])
    return {j:ids[0] for j,ids in owners.items() if len(ids)==1}


def associate(a):
    start=time.perf_counter();fixture=json.loads(a.fixture.read_text());ref=json.loads(a.reference_replay.read_text());stream=json.loads(a.stream.read_text())
    stream_library=getattr(a,"stream_library",None) or a.library
    policy=getattr(a,"policy","head-only")
    if (stream["fixture_sha256"]!=sha(a.fixture) or ref["fixture_sha256"]!=stream["fixture_sha256"]
        or stream["library_sha256"]!=sha(stream_library) or stream["person_model_sha256"]!=sha(PERSON_MODEL)
        or MODEL_SHA.get(stream["head_model"])!=stream["head_model_sha256"]):
        raise ValueError("stream provenance mismatch")
    prior=ref["candidate"]["frames"];frames=stream["frames"];roots=set(ref["identity_protected_roots"]);target=ref["target_id"]
    if len(frames)!=len(prior) or len(prior)!=len(fixture["frames"]) or target not in roots:raise ValueError("stream/target mismatch")
    k=HeadKernel(load(a.library));paths=[];active={};per_frame=[]
    cached_library=sha(stream_library)!=sha(a.library)
    for i,(r,h,m) in enumerate(zip(prior,frames,fixture["frames"])):
        if r["index"]!=i or h["index"]!=i or r["time_us"]!=h["time_us"] or r["raw_boxes"]!=h["raw_boxes"] or h["asset_sha256"]!=m["sha256"] or sha(a.fixture.parent/m["asset"])!=m["sha256"]:
            raise ValueError("stream frame mismatch")
        if cached_library:
            raw=[unpack_head(q) for q in h["primary"]]
            mapped=[unpack_head(q) for q in h["flipped"]]
            actual=k.fuse(raw,mapped) if stream["dual_view"] else raw
            if [pack_head(q) for q in actual]!=h["heads"]:raise ValueError("head fusion changed")
            if i:
                old=frames[i-1];dt=h["time_us"]-old["time_us"]
                links=k.links([unpack_head(q) for q in old["heads"]],actual,dt) if 0<dt<=100_000 else [-1]*len(old["heads"])
                if links!=old["links_next"]:raise ValueError("head link changed")
        owners=raw_root_map(r,roots)
        raw_owners=h["owners"]
        if len(raw_owners)!=len(h["heads"]) or any(j< -1 or j>=len(r["raw_boxes"]) for j in raw_owners):
            raise ValueError("invalid raw owner indices")
        ids=[owners.get(j,-1) for j in raw_owners]
        # A head path does not use face history or the existing sticker.
        path_at={}
        if i:
            for old_j,new_j in enumerate(frames[i-1]["links_next"]):
                if new_j>=0 and old_j in active:
                    if new_j in path_at:raise ValueError("non-unique head link")
                    path_at[new_j]=active[old_j]
        for j,owner in enumerate(ids):
            if j not in path_at:
                path_at[j]=len(paths);paths.append([])
            paths[path_at[j]].append((i,j,owner))
        active=path_at;per_frame.append(path_at)
    selected=[[] for _ in frames]
    for path_id,path in enumerate(paths):
        times=[frames[i]["time_us"] for i,j,o in path];ids=[o for i,j,o in path]
        codes=k.path(times,ids,target,certified=policy=="arbitrate")
        for (i,j,owner),code in zip(path,codes):
            if code:selected[i].append((j,code,path_id))
    import copy
    out=copy.deepcopy(prior);decisions=[];counts={"direct":0,"bridge":0,"ambiguous":0,"replacement":0,"added":0,"retained":0,"modes":{}}
    for i,choices in enumerate(selected):
        if len(choices)>1:counts["ambiguous"]+=1;continue
        if not choices:continue
        j,code,path_id=choices[0];r=prior[i];h=frames[i];heads=[unpack_head(q) for q in h["heads"]]
        if code==2:
            # Never supersede a current body-root anchor on another path.
            if any(raw_root_map(r,roots).get(owner)==target for owner in h["owners"]):continue
            # A currently observed competing protected root is a veto,
            # even if an anonymous tracklet happened to remain linked.
            if raw_root_map(r,roots).get(h["owners"][j],target)!=target:continue
        counts["direct" if code==1 else "bridge"]+=1
        if policy=="arbitrate":
            p,mode=k.arbitrate(heads,j,Transform(*h["transform"]),r)
        else:p,mode=k.placement(heads,j,Transform(*h["transform"])),0
        if p is None:
            counts["retained"]+=1
            continue
        counts["modes"][str(mode)]=counts["modes"].get(str(mode),0)+1
        v=vals(p.square);rec={"index":i,"head_index":j,"path":path_id,"proof":"current_owner" if code==1 else "bounded_path",
            "head":h["heads"][j],"required":vals(p.required),"square":v,"model_rival_overlap":p.rival_overlap,"mode":mode}
        decisions.append(rec)
        old=r.get("sticker") or r.get("render")
        # All independently assigned heads are an explicit evaluation arm.
        # Keep fallback unchanged on unassigned frames; report all regressions.
        out[i].update(sticker=v,sticker_required_bounds=vals(p.required),independent_head_instance=rec["proof"],
            independent_head_index=j,independent_head_mode=mode)
        half=max(8,1.02*max(v[2:]))
        tr=Transform(*h["transform"])
        out[i].update(width=min(tr.width,v[0]+half)-max(0,v[0]-half),height=min(tr.height,v[1]+half)-max(0,v[1]-half))
        if old is None:
            out[i].update(render=v,kind=9);counts["added"]+=1
        counts["replacement"]+=int(old!=v)
    result={"scope":__doc__,"fixture_sha256":sha(a.fixture),"library_sha256":sha(a.library),"reference_replay_sha256":sha(a.reference_replay),
        "stream_sha256":sha(a.stream),"stream_library_sha256":sha(stream_library),"policy":policy,"target_id":target,"counts":counts,"paths":len(paths),"decisions":decisions,
        "elapsed_s":time.perf_counter()-start,"baseline":{"frames":prior},"candidate":{"frames":out}}
    write(a.output,result);print("association",counts,"paths",len(paths),"elapsed",result["elapsed_s"],flush=True);return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);sub=ap.add_subparsers(dest="command",required=True)
    for name in ("produce","associate"):
        p=sub.add_parser(name)
        for arg in ("library","fixture","reference-replay","output"):p.add_argument("--"+arg,type=Path,required=True)
        if name=="produce":
            p.add_argument("--head-model",type=Path,required=True);p.add_argument("--dual-view",action="store_true")
            p.add_argument("--threads",type=int,default=2)
        else:
            p.add_argument("--stream",type=Path,required=True)
            p.add_argument("--stream-library",type=Path,help="Exact earlier producer library; verify fusion and temporal links against current build")
            p.add_argument("--policy",choices=("head-only","arbitrate"),default="head-only")
    a=ap.parse_args();produce(a) if a.command=="produce" else associate(a)

if __name__=="__main__":main()
