#!/usr/bin/env python3
"""Host-only complete source-frame comparison of canonical LiteRT and YOLO11s.

Reuse the current FrameKernel association, current ROI scheduling, same exact
source timestamps and identical user seed. Compare full model replacement or
small-model detail requests with the canonical full-frame observations. Do not use
annotation points to select or repair masks. The candidate is local FP32
PyTorch, not a mobile artifact or a runtime recommendation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time

import cv2
import numpy as np
import torch
import ultralytics

import cutout_m0 as m
from compare_dance_identity import observe
from probe_instance_models import CANONICAL, load_torch
from probe_selected_refinement import sha, source_samples
from replay_adaptive_detail import bind_detail, replay
from review_holdout import contour_metrics
from review_real_dance import assess, compare


class TorchRunner:
    """Interpreter-shaped host adapter for the existing FrameKernel decoder."""

    coordinate_units = 0  # The Torch export head reports pixels.

    def __init__(self, model):
        self.model = model
        self.input = None
        self.outputs = None

    def get_input_details(self):
        return [{"index": 0}]

    def get_output_details(self):
        return [{"index": 1}, {"index": 2}]

    def set_tensor(self, index, tensor):
        if index != 0:
            raise ValueError("Unexpected input tensor index")
        self.input = tensor

    def invoke(self):
        with torch.inference_mode():
            self.outputs = tuple(np.ascontiguousarray(x.numpy(), dtype=np.float32)
                                 for x in self.model(torch.from_numpy(self.input)))

    def get_tensor(self, index):
        if index not in (1, 2):
            raise ValueError("Unexpected output tensor index")
        return self.outputs[index-1]


def observations(lib, runner, path, manifest):
    count = manifest["framesPerRole"]
    anchor = round(manifest["anchorUs"]*manifest["cadence"]/1e6)
    # m.infer allocates a 256-instance arena; retain only the used rows.
    start = time.monotonic()
    obs = [(p,ds,raw.copy(),t,f) for p,ds,raw,t,f in
           observe(lib,runner,path,count,anchor)]
    if [o[0] for o in obs] != [i*1000000//manifest["cadence"] for i in range(count)]:
        raise ValueError("Unexpected source PTS")
    return obs,time.monotonic()-start


def run(lib, detail_runner, path, manifest, source, role, captured,
        local_cache=None, alternate_runner=None):
    anchor = round(manifest["anchorUs"]*manifest["cadence"]/1e6)
    obs,observation_seconds = captured
    start = time.monotonic()
    _,_,raw,t,_ = obs[anchor]
    seed = lib.frame_kernel_cutout_select(m.ptr(raw),raw.size,t,*source["seed"])
    if seed < 0:
        records = [(o[0],None) for o in obs]
        timing = {"attempts":0,"accepted":0,"seconds":0.,"skipped":0,
                  "attemptedPts":[],"acceptedPts":[],"skippedPts":[]}
        if alternate_runner is not None:
            timing.update(alternateBudget=min(24,max(1,(len(obs)*6+99)//100)),
                          alternateEligiblePts=[],alternateAttemptedPts=[],
                          alternateAcceptedPts=[],alternateMs=[])
    else:
        records,timing = replay(lib,detail_runner,path,obs,anchor,seed,True,True,
                                local_cache,alternate_runner)
    annotations = manifest["annotations"][role]
    stats = assess(records,annotations,manifest["durationUs"])
    lookup = dict(records)
    stats["contours"] = [{"ptsUs":x["ptsUs"],**contour_metrics(
        lookup[x["ptsUs"]],x["visibleContour"],*manifest["contourCoordinateSize"])}
        for x in annotations if "visibleContour" in x]
    return records,{**stats,**timing,"seedIndex":seed,
        "observationSeconds":observation_seconds,
        "totalSeconds":observation_seconds+time.monotonic()-start}


def review_added(path, records, added, manifest, output):
    """Local visual review of every newly accepted sample, not annotation truth."""
    output.mkdir(parents=True,exist_ok=True)
    images=source_samples(path,manifest,added)
    lookup=dict(records)
    rows=[]
    for pts in added:
        frame=cv2.cvtColor(images[pts],cv2.COLOR_RGBA2BGR)
        frame=cv2.resize(frame,(320,180),interpolation=cv2.INTER_AREA)
        alpha=cv2.resize(lookup[pts],(320,180),interpolation=cv2.INTER_LINEAR)
        a=alpha.astype(np.float32)[:,:,None]/255.
        green=np.full_like(frame,(110,210,90))
        overlay=(frame*(1-.4*a)+green*(.4*a)).astype(np.uint8)
        cutout=(frame*a+np.full_like(frame,35)*(1-a)).astype(np.uint8)
        row=np.concatenate((frame,overlay,cutout),axis=1)
        cv2.rectangle(row,(0,0),(135,19),(20,20,20),-1)
        cv2.putText(row,f"PTS {pts/1e6:.3f}s",(5,14),cv2.FONT_HERSHEY_SIMPLEX,.43,
                    (255,255,255),1,cv2.LINE_AA)
        rows.append(row)
    for i in range(0,len(rows),4):
        if not cv2.imwrite(str(output/f"added-{i//4:02d}.jpg"),np.concatenate(rows[i:i+4],axis=0)):
            raise ValueError("Could not save review sheet")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--media",type=Path,required=True)
    p.add_argument("--library",type=Path,required=True)
    p.add_argument("--small-weights",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--role",choices=("reference","target","both"),default="both")
    p.add_argument("--mode",choices=("replacement","fallback","both","incremental"),default="both",
                   help="full replacement, small detail instead of canonical, both, or an output-only incremental rescue")
    p.add_argument("--baseline-library",type=Path,help="optional prior core for exact canonical A/B (incremental only)")
    p.add_argument("--save-added",type=Path,help="local review images of newly recovered samples (incremental only)")
    a = p.parse_args()
    manifest = json.loads(a.manifest.read_text())
    if sha(CANONICAL) != manifest["modelSha256"] or sha(CANONICAL) != m.MODEL_SHA:
        raise ValueError("Canonical model mismatch")
    lib = m.api(a.library)
    bind_detail(lib)
    if a.mode=="incremental" and not hasattr(lib,"frame_kernel_dance_probe_alternate_detail"):
        p.error("incremental mode needs a core built with --features host-model-probe")
    if a.mode!="incremental" and (a.baseline_library or a.save_added):
        p.error("baseline-library and save-added are incremental-only")
    previous=m.api(a.baseline_library) if a.baseline_library else lib
    if previous is not lib:
        bind_detail(previous)
    torch.set_num_threads(4)
    canonical = m.Interpreter(model_path=str(CANONICAL),num_threads=4)
    canonical.allocate_tensors()
    small = TorchRunner(load_torch(a.small_weights))
    result = {"hostOnly":True,"manifestSha256":sha(a.manifest),
              "librarySha256":sha(a.library),"canonicalSha256":sha(CANONICAL),
              "smallWeightsSha256":sha(a.small_weights),
              "torch":torch.__version__,"ultralytics":ultralytics.__version__,
              "baselineLibrarySha256":sha(a.baseline_library or a.library),
              "mode":a.mode,"roles":{}}
    for role,source in manifest["sources"].items():
        if a.role not in ("both",role):
            continue
        path = a.media/source["file"]
        if sha(path) != source["testedMp4Sha256"]:
            raise ValueError("Source hash mismatch")
        captured = observations(lib,canonical,path,manifest)
        cache={} if a.mode=="incremental" else None
        baseline,b = run(previous,canonical,path,manifest,source,role,captured,cache)
        row = result["roles"][role] = {"canonical":b}
        print(role,"canonical",{k:b[k] for k in
            ("visible","checkpointHits","distractorHits","attempts","totalSeconds")},flush=True)
        if a.mode=="incremental":
            candidate,c=run(lib,canonical,path,manifest,source,role,captured,cache,small)
            delta=compare(baseline,candidate)
            delta["candidateMissingVsCanonical"]=delta.pop("gpuMissingVsCpu")
            delta["candidateExtraVsCanonical"]=delta.pop("gpuExtraVsCpu")
            if (delta["candidateMissingVsCanonical"] or
                    delta["exactAlphaFrames"]!=b["visible"] or
                    any(b[k]!=c[k] for k in ("attemptedPts","acceptedPts","skippedPts"))):
                raise ValueError("Canonical observations, outputs or detail scheduling changed")
            added=[p for (p,x),(q,y) in zip(baseline,candidate) if x is None and y is not None]
            if added!=sorted(c.get("alternateAcceptedPts",[])):
                raise ValueError("Alternate acceptance does not match the added source positions")
            row["incremental"]=c
            row["incrementalAlphaComparison"]=delta
            row["addedPts"]=added
            if a.save_added and added:
                review_added(path,candidate,added,manifest,a.save_added/role)
            print(role,"incremental",{k:c[k] for k in ("visible","checkpointHits","distractorHits",
                "alternateBudget","alternateEligiblePts","alternateAttemptedPts","alternateAcceptedPts")},flush=True)
            continue
        variants = []
        if a.mode in ("both","fallback"):
            variants.append(("smallDetailOnly",captured))
        if a.mode in ("both","replacement"):
            variants.append(("small",observations(lib,small,path,manifest)))
        for name,data in variants:
            candidate,c = run(lib,small,path,manifest,source,role,data)
            print(role,name,{k:c[k] for k in
                ("visible","checkpointHits","distractorHits","attempts","totalSeconds")},flush=True)
            delta = compare(baseline,candidate)
            delta["candidateMissingVsCanonical"] = delta.pop("gpuMissingVsCpu")
            delta["candidateExtraVsCanonical"] = delta.pop("gpuExtraVsCpu")
            row[name] = c
            row[name+"AlphaComparison"] = delta
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,indent=2)+"\n")


if __name__ == "__main__":
    main()
