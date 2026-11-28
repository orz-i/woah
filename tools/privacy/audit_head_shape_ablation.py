#!/usr/bin/env python3
"""Compare geometric head shapes on the same current cap and source envelopes.

This is a host geometric/pixel-grid diagnostic, not a native renderer or
semantic-head ground truth. No label selects a raw proposal or a shape.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

from replay_face_geometry import detect_instances, load, model, vals
from evaluate_body_reid import PERSON_MODEL, sha


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ("library","fixture","replay","labels","output"):
        ap.add_argument("--"+name,type=Path,required=True)
    ap.add_argument("--case",required=True)
    a=ap.parse_args()
    fixture=json.loads(a.fixture.read_text());replay=json.loads(a.replay.read_text())
    labels=json.loads(a.labels.read_text())["cases"][a.case]
    if sha(a.fixture)!=labels["manifest_sha256"] or replay["fixture_sha256"]!=labels["manifest_sha256"] or replay["target_id"]!=labels["target_id"]:
        raise ValueError("source/identity mismatch")
    lib=load(a.library);person=model(PERSON_MODEL)
    rows={mode:[] for mode in ("square","rectangle","outer_ellipse","square_mask","square_exclusive_mask")}
    for q in labels["labels"]:
        i=q["index"];r=replay["candidate"]["frames"][i]
        meta=fixture["frames"][i];f=a.fixture.parent/meta["asset"]
        if meta["sha256"]!=q["asset_sha256"] or sha(f)!=q["asset_sha256"]:
            raise ValueError("frame hash mismatch")
        if not r.get("sticker") or not r.get("sticker_required_bounds"):
            raise ValueError("shape ablation needs an actual current cap")
        im=Image.open(f).convert("RGB");tr,ds,ms=detect_instances(lib,person,im)
        if [vals(d.box) for d in ds]!=r["raw_boxes"]:
            raise ValueError("raw evidence mismatch")
        hits=[j for j,b in enumerate(r["raw_boxes"]) if r.get("track") and r["track"]["observed"] and b==r["track"]["bounds"]]
        index=r.get("fragment_head_repair",{}).get("raw")
        if index is None:
            if len(hits)!=1:raise ValueError("unproven current owner")
            index=hits[0]
        if not 0<=index<len(ds):raise ValueError("raw index")
        own=ms[index]>=128
        pad=np.pad(own,1,constant_values=False)
        dilated=np.logical_or.reduce([pad[y:y+tr.proto,x:x+tr.proto] for y in range(3) for x in range(3)])
        rival=np.any(np.delete(ms,index,axis=0)>=128,axis=0)
        l,t,right,bottom=r["sticker_required_bounds"]
        cx,cy=(l+right)/2,(t+bottom)/2
        rx,ry=(right-l)*.51,(bottom-t)*.51
        sx,sy,srx,sry=r["sticker"]
        half=max(8,1.02*max(srx,sry))
        def tests(x,y):
            sq=(abs(x-sx)<=half)&(abs(y-sy)<=half)
            rect=(abs(x-cx)<=rx)&(abs(y-cy)<=ry)
            ell=((x-cx)/(math.sqrt(2)*rx))**2+((y-cy)/(math.sqrt(2)*ry))**2<=1
            u=np.floor((x*tr.scale+tr.left)*tr.proto/tr.input).astype(int)
            v=np.floor((y*tr.scale+tr.top)*tr.proto/tr.input).astype(int)
            ok=(u>=0)&(u<tr.proto)&(v>=0)&(v<tr.proto)
            u=u.clip(0,tr.proto-1);v=v.clip(0,tr.proto-1)
            support=dilated[v,u]&ok
            return dict(square=sq,rectangle=rect,outer_ellipse=ell,square_mask=sq&support,
                        square_exclusive_mask=sq&support&~rival[v,u])
        def region(b):
            l,t,r,bot=b
            x,y=np.meshgrid(np.arange(l,r)+.5,np.arange(t,bot)+.5)
            return {m:float(v.mean()) for m,v in tests(x,y).items()}
        head=region(q["head_rect"]);neighbors=[region(b) for b in q.get("neighbor_rects",[])]
        tx,ty=q["torso_probe"];torso=tests(np.array(tx),np.array(ty))
        for m in rows:
            rows[m].append({"index":i,"head_coverage":head[m],"neighbor_rect_overlaps":[n[m] for n in neighbors],"torso_probe_covered":bool(torso[m])})
    result={}
    for m,v in rows.items():
        result[m]={"samples":len(v),"full":sum(r["head_coverage"]==1 for r in v),
            "min_head_coverage":min(r["head_coverage"] for r in v),
            "max_neighbor_overlap":max((x for r in v for x in r["neighbor_rect_overlaps"]),default=None),
            "torso_probes":sum(r["torso_probe_covered"] for r in v),"rows":v}
    out={"scope":__doc__,"fixture_sha256":sha(a.fixture),"replay_sha256":sha(a.replay),"labels_sha256":sha(a.labels),
         "library_sha256":sha(a.library),"detector_sha256":sha(PERSON_MODEL),"results":result}
    a.output.write_text(json.dumps(out,indent=2)+"\n")
    print(json.dumps({m:{k:v for k,v in r.items() if k!="rows"} for m,r in result.items()}))
if __name__=="__main__":main()
