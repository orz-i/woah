#!/usr/bin/env python3
"""Host microbenchmark of FrameKernel face geometry, excluding model/IO/GPU."""
import argparse
import ctypes as C
import json
import statistics
import time
from pathlib import Path

import numpy as np
from PIL import Image

from replay_face_geometry import (
    ROOT, Ellipse, Rect, MaskSpec, load, model, detect_instances, iou, ptr,
)
from replay_face_tracking import PixelBridge, HeadSticker, pixel_bind, gray_view


def measured(call, output, count):
    # Warmup and preserve result bytes for exact comparison.
    for _ in range(5):
        status = call()
    expected = (status, bytes(output))
    values = []
    for _ in range(count):
        start = time.perf_counter_ns()
        status = call()
        values.append((time.perf_counter_ns()-start)/1e6)
        if (status, bytes(output)) != expected:
            raise RuntimeError("Non-deterministic geometry result")
    return expected, values


def compare(libs, setup, count):
    results, outputs = {}, {}
    for name, lib in libs.items():
        call, out = setup(lib)
        expected, values = measured(call, out, count)
        outputs[name] = expected
        results[name] = {
            "status": expected[0], "p50_ms": statistics.median(values),
            "p95_ms": float(np.percentile(values,95)), "iterations": count,
        }
    if "before" in results:
        results["speedup_p50"] = results["before"]["p50_ms"]/results["after"]["p50_ms"]
        results["status_and_bytes_equal"] = outputs["before"] == outputs["after"]
    return results


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before",type=Path,required=True,
                        help="Same S4.1 geometry, before separable-patch optimization")
    parser.add_argument("--after",type=Path,required=True)
    parser.add_argument("--trace",type=Path,required=True,help="Original affine fixture replay JSON")
    parser.add_argument("--iterations",type=int,default=60)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    libs={"before":load(args.before),"after":load(args.after)}
    for lib in libs.values():
        pixel_bind(lib)
    fixture=ROOT/"testdata/face_dynamic_smoke"
    manifest=json.loads((fixture/"dynamic_manifest.json").read_text())
    trace=json.loads(args.trace.read_text())["candidate"]["frames"]
    images=[Image.open(fixture/f["asset"]).convert("RGB") for f in manifest["frames"]]
    out={"scope":__doc__,"cases":{}}
    # Real raw frames, including large and small search radii.
    for prev,now in [(5,6),(10,11),(10,12),(10,13)]:
        p,pv=gray_view(images[prev])
        q,qv=gray_view(images[now])
        seed=Ellipse(*trace[prev]["raw"])
        covered=Ellipse(*trace[prev]["render"])
        age=round((now-prev)*1e6/30)
        def setup(lib):
            result=PixelBridge()
            return lambda:lib.pixel_motion(ptr(p),p.size,pv,ptr(q),q.size,qv,seed,covered,
                                           age,None,0,C.byref(result)),result
        out["cases"][f"pixel_bridge_{prev}_{now}"]=compare(libs,setup,args.iterations)
    yolo=model(ROOT/"models/litert/yolo11n-seg-fp16.tflite")
    for index in [0,3,10,14]:
        frame=trace[index]
        transform,detections,masks=detect_instances(libs["before"],yolo,images[index])
        track=Rect(*frame["track"]["bounds"])
        chosen=max(range(len(detections)),key=lambda j:iou(track,detections[j].box))
        mask=masks[chosen]
        spec=MaskSpec(160,160,transform,track)
        seed,stable=Ellipse(*frame["raw"]),Ellipse(*frame["stabilized"])
        def setup(lib):
            result=HeadSticker()
            return lambda:lib.head_sticker(ptr(mask),mask.size,spec,seed,stable,0,C.byref(result)),result
        out["cases"][f"head_sticker_{index}"]=compare(libs,setup,args.iterations)
    if not all(c["status_and_bytes_equal"] for c in out["cases"].values()):
        raise RuntimeError("Optimized ABI outputs differ")
    args.output.write_text(json.dumps(out,indent=2)+"\n")
    for name, case in out["cases"].items():
        print(name, json.dumps(case))


if __name__=="__main__":
    main()
