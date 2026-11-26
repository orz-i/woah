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

import numpy as np
import torch
import ultralytics

import cutout_m0 as m
from compare_dance_identity import observe
from probe_instance_models import CANONICAL, load_torch
from probe_selected_refinement import sha
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


def run(lib, detail_runner, path, manifest, source, role, captured):
    anchor = round(manifest["anchorUs"]*manifest["cadence"]/1e6)
    obs,observation_seconds = captured
    start = time.monotonic()
    _,_,raw,t,_ = obs[anchor]
    seed = lib.frame_kernel_cutout_select(m.ptr(raw),raw.size,t,*source["seed"])
    if seed < 0:
        records = [(o[0],None) for o in obs]
        timing = {"attempts":0,"accepted":0,"seconds":0.,"skipped":0}
    else:
        records,timing = replay(lib,detail_runner,path,obs,anchor,seed,True,True)
    annotations = manifest["annotations"][role]
    stats = assess(records,annotations,manifest["durationUs"])
    lookup = dict(records)
    stats["contours"] = [{"ptsUs":x["ptsUs"],**contour_metrics(
        lookup[x["ptsUs"]],x["visibleContour"],*manifest["contourCoordinateSize"])}
        for x in annotations if "visibleContour" in x]
    return records,{**stats,**timing,"seedIndex":seed,
        "observationSeconds":observation_seconds,
        "totalSeconds":observation_seconds+time.monotonic()-start}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--media",type=Path,required=True)
    p.add_argument("--library",type=Path,required=True)
    p.add_argument("--small-weights",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--role",choices=("reference","target","both"),default="both")
    p.add_argument("--mode",choices=("replacement","fallback","both"),default="both",
                   help="full model replacement, small model only for existing detail requests, or both")
    a = p.parse_args()
    manifest = json.loads(a.manifest.read_text())
    if sha(CANONICAL) != manifest["modelSha256"] or sha(CANONICAL) != m.MODEL_SHA:
        raise ValueError("Canonical model mismatch")
    lib = m.api(a.library)
    bind_detail(lib)
    torch.set_num_threads(4)
    canonical = m.Interpreter(model_path=str(CANONICAL),num_threads=4)
    canonical.allocate_tensors()
    small = TorchRunner(load_torch(a.small_weights))
    result = {"hostOnly":True,"manifestSha256":sha(a.manifest),
              "librarySha256":sha(a.library),"canonicalSha256":sha(CANONICAL),
              "smallWeightsSha256":sha(a.small_weights),
              "torch":torch.__version__,"ultralytics":ultralytics.__version__,
              "roles":{}}
    for role,source in manifest["sources"].items():
        if a.role not in ("both",role):
            continue
        path = a.media/source["file"]
        if sha(path) != source["testedMp4Sha256"]:
            raise ValueError("Source hash mismatch")
        captured = observations(lib,canonical,path,manifest)
        baseline,b = run(lib,canonical,path,manifest,source,role,captured)
        row = result["roles"][role] = {"canonical":b}
        print(role,"canonical",{k:b[k] for k in
            ("visible","checkpointHits","distractorHits","attempts","totalSeconds")},flush=True)
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
