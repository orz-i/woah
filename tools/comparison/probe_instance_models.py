#!/usr/bin/env python3
"""Host-only paired instance-observation comparison (canonical LiteRT, PyTorch n/s).

Annotations are used solely for oracle measurement, not production selection.
An eligible person mask hits the marked selected point and none of the
marked distractors. This cannot establish temporal association, full identity
or device speed. Candidate weights are explicit local files, never fetched
or committed by this tool. Use a trusted source: PyTorch model loading can
execute code.
"""
from __future__ import annotations

import argparse
import ctypes as c
import json
from pathlib import Path
import time

import cv2
import numpy as np
import torch
from ultralytics import YOLO
import ultralytics

import cutout_m0 as m
from probe_selected_refinement import sha, source_samples, score

CANONICAL = m.ROOT/"models/litert/yolo11n-seg-fp16.tflite"
THRESHOLD = .20
STRONG_THRESHOLD = .25


def prepare(lib, rgba):
    """Use identical bounded host source size and core preprocessing for n/s."""
    h, w = rgba.shape[:2]
    scale = min(1., 768/max(h,w))
    if scale < 1:
        rgba = cv2.resize(rgba, (round(w*scale), round(h*scale)), interpolation=cv2.INTER_AREA)
    rgba = np.ascontiguousarray(rgba)
    h, w = rgba.shape[:2]
    lb = m.Letterbox()
    packed = np.empty((640,640,4), np.uint8)
    tensor = np.empty((1,3,640,640), np.float32)
    if lib.frame_kernel_resize_rgba(m.ptr(rgba), rgba.size, w, h, w*4, m.ptr(packed),
                                    packed.size, 640, 640, 114, c.byref(lb)) != 0:
        raise ValueError("Core letterbox failed")
    spec = m.PixelSpec(640,640,640*4,4,0,1,2,0,0,0,1/255,0)
    if lib.frame_kernel_normalize_pixels(m.ptr(packed), packed.size, spec,
                                        m.ptr(tensor,m.PF), tensor.size) != 0:
        raise ValueError("Core normalize failed")
    return tensor, m.Transform(lb.scale,lb.left,lb.top,w,h,640,160)


def decode(lib, head, proto, transform, units):
    head = np.ascontiguousarray(head, dtype=np.float32)
    proto = np.ascontiguousarray(proto, dtype=np.float32)
    if head.shape != (1,116,8400) or proto.shape != (1,32,160,160):
        raise ValueError("Unexpected instance tensor contract")
    spec = m.Spec(8400,116,4,84,32,0,0,units)
    detections = (m.Detection*256)()
    raw = np.empty((256,160,160),np.uint8)
    n = lib.frame_kernel_decode_instances(m.ptr(head,m.PF),head.size,m.ptr(proto,m.PF),
        proto.size,spec,transform,m.Thresholds(THRESHOLD,.5,.5),detections,256,
        m.ptr(raw),raw.size,None)
    if not 0 <= n <= 256:
        raise ValueError(f"Instance decode failed: {n}")
    w = max(1,round(transform.source_width*384/max(transform.source_width,transform.source_height)))
    h = max(1,round(transform.source_height*384/max(transform.source_width,transform.source_height)))
    masks=[]
    for mask in raw[:n]:
        alpha=np.empty((h,w),np.uint8)
        if lib.frame_kernel_cutout_alpha(m.ptr(mask),mask.size,transform,w,h,
                                        m.ptr(alpha),alpha.size)!=0:
            raise ValueError("Core alpha projection failed")
        masks.append(alpha)
    return list(detections[:n]), masks


def oracle(detections, masks, annotation, size):
    candidates = [{"confidence":float(d.confidence), **score(a,annotation,size)}
                  for d,a in zip(detections,masks)]
    result = {"candidates": candidates}
    for key, threshold in (("weak",THRESHOLD),("strong",STRONG_THRESHOLD)):
        eligible = [x for x in candidates if x["confidence"]>=threshold and x["selectedHit"]
                    and x["distractorHit"] is not True and not any(x["additionalDistractorHits"])]
        result[key] = {"eligibleCount":len(eligible),
            "highestConfidence":max(eligible,key=lambda x:x["confidence"]) if eligible else None}
        if "visibleContour" in annotation:
            best = max(eligible,key=lambda x:x["contour"]["iou"]) if eligible else None
            result[key]["bestContour"] = best["contour"] if best else score(None,annotation,size)["contour"]
    return result


def load_torch(path):
    # Caller must trust these explicit local weight files.
    model=YOLO(str(path)).model.eval().float()
    model.model[-1].export=True
    model.model[-1].format="torchscript"
    return model


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--media",type=Path,required=True)
    p.add_argument("--library",type=Path,required=True)
    p.add_argument("--nano-weights",type=Path,required=True)
    p.add_argument("--small-weights",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    manifest=json.loads(a.manifest.read_text())
    if sha(CANONICAL)!=m.MODEL_SHA or sha(CANONICAL)!=manifest["modelSha256"]:
        raise ValueError("Canonical model mismatch")
    torch.set_num_threads(4)
    runner=m.Interpreter(model_path=str(CANONICAL),num_threads=4)
    runner.allocate_tensors()
    nano,small=load_torch(a.nano_weights),load_torch(a.small_weights)
    lib=m.api(a.library)
    result={"manifestSha256":sha(a.manifest),"librarySha256":sha(a.library),
            "versions":{"torch":torch.__version__,"ultralytics":ultralytics.__version__},
            "models":{k:{"sha256":sha(path),"bytes":path.stat().st_size}
                for k,path in (("canonical",CANONICAL),("nano",a.nano_weights),("small",a.small_weights))},
            "thresholds":[THRESHOLD,STRONG_THRESHOLD],"roles":{}}
    def infer(key,tensor,transform):
        start=time.monotonic()
        if key=="canonical":
            runner.set_tensor(runner.get_input_details()[0]["index"],tensor)
            runner.invoke()
            head,proto=(runner.get_tensor(item["index"]) for item in runner.get_output_details())
            units=2
        else:
            with torch.inference_mode():
                head,proto=(nano if key=="nano" else small)(torch.from_numpy(tensor))
                head,proto=head.numpy(),proto.numpy()
            units=0 # PyTorch export head coordinates are pixels, not normalized.
        ds,masks=decode(lib,head,proto,transform,units)
        return ds,masks,(time.monotonic()-start)*1000
    # Warm each host model once; timings include shared decode, not preprocessing.
    dummy,t=prepare(lib,np.zeros((540,960,4),np.uint8))
    keys=("canonical","nano","small")
    for key in keys:
        infer(key,dummy,t)
    for role,source in manifest["sources"].items():
        video=a.media/source["file"]
        if sha(video)!=source["testedMp4Sha256"]:
            raise ValueError("Source hash mismatch")
        annotations={x["ptsUs"]:x for x in manifest["annotations"][role]}
        images=source_samples(video,manifest,annotations)
        rows=[]
        for index,(pts,annotation) in enumerate(annotations.items()):
            tensor,t=prepare(lib,images[pts])
            row={"ptsUs":pts,"models":{}}
            # Rotate execution order rather than always running one model first.
            for key in keys[index%3:]+keys[:index%3]:
                ds,masks,ms=infer(key,tensor,t)
                row["models"][key]={"inferenceDecodeMs":ms,
                    **oracle(ds,masks,annotation,manifest["contourCoordinateSize"])}
            rows.append(row)
        result["roles"][role]=rows
        print(role,{key:sum(r["models"][key]["strong"]["eligibleCount"]>0 for r in rows)
                    for key in keys},flush=True)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,indent=2)+"\n")


if __name__=="__main__":
    main()
