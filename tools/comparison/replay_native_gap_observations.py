#!/usr/bin/env python3
"""Offline gap-only replay on saved native observations and selected alphas.

The same-run evidence is preferred. A later alpha capture without raw
observations is explicitly a cross-run exploratory arm, not a same-run or
device measurement. Endpoints are admissible only if exactly one strong
current raw observation projects byte-for-byte to the incumbent alpha.
Never use annotations to choose endpoints or candidates.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import collections

import numpy as np

import cutout_m0 as m
from probe_selected_refinement import sha
from probe_gap_observations import continuity, review, MAX_BRACKET_US
from replay_native_observations import read_observations
from review_real_dance import frames, assess, compare


def exact_endpoint(lib, observed, alpha):
    _, rows, raw, t, _ = observed
    if alpha is None:
        return -1
    h,w=alpha.shape
    matches=[]
    for j,d in enumerate(rows):
        if d.confidence < .25:
            continue
        out=np.empty((h,w),np.uint8)
        if lib.frame_kernel_cutout_alpha(m.ptr(raw[j]),raw[j].size,t,w,h,m.ptr(out),out.size)!=0:
            raise ValueError("Native endpoint projection failed")
        if np.array_equal(alpha,out):
            matches.append(j)
    return matches[0] if len(matches)==1 else -1


def run(lib,manifest,role,obs,baseline):
    if [p for p,_ in baseline]!=[o[0] for o in obs]:
        raise ValueError("PTS mismatch")
    for (_,alpha),o in zip(baseline,obs):
        if alpha is None:
            continue
        t=o[3]
        expected=(max(1,round(t.source_height*384/max(t.source_width,t.source_height))),
                  max(1,round(t.source_width*384/max(t.source_width,t.source_height))))
        if alpha.shape!=expected:
            raise ValueError("Native alpha grid not comparable")
    traces={i:(exact_endpoint(lib,o,alpha),o,"native_full") for i,((_,alpha),o) in enumerate(zip(baseline,obs))}
    result,chains=continuity(baseline,traces,obs,lib,manifest["durationUs"])
    orig=assess(baseline,manifest["annotations"][role],manifest["durationUs"])
    enhanced=assess(result,manifest["annotations"][role],manifest["durationUs"])
    delta=compare(baseline,result)
    if delta["gpuMissingVsCpu"] or delta["exactAlphaFrames"]!=orig["visible"]:
        raise ValueError("Incumbent native alpha changed")
    return result,{"canonical":orig,"continuity":enhanced,"continuityComparison":delta,
                   "exactStrongEndpoints":sum(j>=0 for j,_,_ in traces.values()),"chains":chains}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--media",type=Path,required=True)
    p.add_argument("--evidence",type=Path,required=True)
    p.add_argument("--alpha-evidence",type=Path,help="optional separate capture; explicitly cross-run exploratory")
    p.add_argument("--provenance",type=Path,required=True,help="previous replay verifying the raw observation stream hashes")
    p.add_argument("--library",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    manifest=json.loads(a.manifest.read_text())
    provenance=json.loads(a.provenance.read_text())
    if provenance["manifestSha256"]!=sha(a.manifest):
        raise ValueError("Native observation manifest mismatch")
    lib=m.api(a.library)
    if not hasattr(lib,"frame_kernel_dance_probe_gap"):
        p.error("Build host-model-probe feature")
    alpha_root=a.alpha_evidence or a.evidence
    raw_report=json.loads((a.evidence/"report.json").read_text())
    alpha_report=json.loads((alpha_root/"report.json").read_text())
    for rep in (raw_report,alpha_report):
        if not rep["completed"] or rep["anchorUs"]!=manifest["anchorUs"] or rep["limitUs"]!=manifest["durationUs"]:
            raise ValueError("Native report not comparable")
    if raw_report["device"]!=alpha_report["device"] or raw_report["sdk"]!=alpha_report["sdk"]:
        raise ValueError("Native device provenance mismatch")
    a.output.mkdir(parents=True,exist_ok=True)
    result={"nativeReplayOnly":True,"crossRun":alpha_root.resolve()!=a.evidence.resolve(),
            "manifestSha256":sha(a.manifest),"librarySha256":sha(a.library),
            "rawReportSha256":sha(a.evidence/"report.json"),
            "alphaReportSha256":sha(alpha_root/"report.json"),
            "maxBracketUs":MAX_BRACKET_US,"roles":{}}
    for role,source in manifest["sources"].items():
        video=a.media/source["file"]
        if sha(video)!=source["testedMp4Sha256"]:
            raise ValueError("Source hash mismatch")
        result["roles"][role]={}
        for backend in ("cpu","gpu"):
            raw_path=a.evidence/f"{backend}-{role}.observations.gz"
            alpha_path=alpha_root/f"{backend}-{role}.alpha"
            if provenance["roles"][role][backend]["observationSha256"]!=sha(raw_path):
                raise ValueError("Native raw observation hash mismatch")
            runs=[next(r for r in rep["runs"] if r["requested"].lower()==backend) for rep in (raw_report,alpha_report)]
            if any(r["effective"].lower()!=backend or r["status"]!="complete" for r in runs):
                raise ValueError("Unexpected native backend")
            lanes=[next(l for l in r["lanes"] if l["reference"]==(role=="reference")) for r in runs]
            if any(l["frames"]!=manifest["framesPerRole"] for l in lanes):
                raise ValueError("Native lane count mismatch")
            obs=read_observations(raw_path,manifest)
            baseline=list(frames(alpha_path))
            if sum(x is not None for _,x in baseline)!=lanes[1]["visible"]:
                raise ValueError("Native alpha visible count mismatch")
            recovered,row=run(lib,manifest,role,obs,baseline)
            row.update(observationSha256=sha(raw_path),alphaSha256=sha(alpha_path))
            result["roles"][role][backend]=row
            review(video,manifest,baseline,recovered,a.output/role/backend)
            print(role,backend,"visible",row["canonical"]["visible"],"->",row["continuity"]["visible"],
                  "longest",row["canonical"]["longestMissingUs"],"->",row["continuity"]["longestMissingUs"],
                  "exact endpoints",row["exactStrongEndpoints"],"chains",dict(collections.Counter(x["reason"] for x in row["chains"])),flush=True)
    (a.output/"report.json").write_text(json.dumps(result,indent=2)+"\n")


if __name__=="__main__":
    main()
