#!/usr/bin/env python3
"""Host CPU model -> FrameKernel geometry replay (not mobile export or tracking QA).

The fixture's annotated person box selects an instance by IoU, never the face.
For unannotated benchmark frames the largest initial instance is matched by IoU.
Both libraries receive the same decoded detector evidence. Images stay local.
"""
import argparse
import ctypes as C
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

ROOT = Path(__file__).resolve().parents[2]
F, U, S, P = C.c_float, C.c_uint32, C.c_size_t, C.c_void_p


def struct(name, fields):
    return type(name, (C.Structure,), {"_fields_": fields})


Rect = struct("Rect", [(k, F) for k in ("left", "top", "right", "bottom")])
Ellipse = struct("Ellipse", [(k, F) for k in ("x", "y", "rx", "ry")])
Transform = struct("Transform", [(k, F) for k in ("scale", "left", "top")] +
                   [(k, U) for k in ("width", "height", "input", "proto")])
Letterbox = struct("Letterbox", [(k, F) for k in ("scale", "width", "height", "left", "top")])
PixelSpec = struct("PixelSpec", [(k, U) for k in ("width", "height", "stride", "channels",
                   "red", "green", "blue", "flip_rows", "flip_columns", "interleaved")] +
                   [("scale", F), ("bias", F)])
InstanceSpec = struct("InstanceSpec", [(k, U) for k in ("anchors", "attributes", "score",
                      "coefficient", "channels", "head_transposed", "proto_nhwc", "units")])
Thresholds = struct("Thresholds", [(k, F) for k in ("confidence", "bbox_iou", "mask_iou")])
Detection = struct("Detection", [("box", Rect), ("confidence", F)])
MaskSpec = struct("MaskSpec", [("width", U), ("height", U), ("transform", Transform), ("person", Rect)])
Measurement = struct("Measurement", [("region", Ellipse), ("anchor", Rect),
                     ("time", C.c_int64), ("kind", U), ("flags", U)])
State = struct("State", [("output", Ellipse)] + [(k, F) for k in ("rx", "ry", "width", "height")] +
               [("anchor", Rect), ("time", C.c_int64), ("flags", U)])
Head = struct("Head", [("region", Ellipse), ("guided", U)])
RenderState = struct("RenderState", [("time", C.c_int64), ("observed_us", C.c_int64), ("major_radius", F), ("observed_major", F), ("initialized", U)])


def vals(v):
    return [getattr(v, k) for k, _ in v._fields_]


def ptr(a):
    return a.ctypes.data


def check(status):
    if status != 0:
        raise RuntimeError(f"FrameKernel returned {status}")


def load(path):
    lib = C.CDLL(str(path.resolve()))
    specs = {
        "resize_rgba": [P, S, U, U, U, P, S, U, U, C.c_uint8, P],
        "normalize_pixels": [P, S, PixelSpec, P, S],
        "decode_instances": [P, S, P, S, InstanceSpec, Transform, Thresholds, P, S, P, S, P],
        "face_head_plan": [Rect, U, U, P],
        "grid_face_input": [P, S, U, U, U, P, S],
        "grid_face_decode": [P, S, P, S, U, U, F, F, P, S],
        "current_face_owned": [P, S, MaskSpec, P, P, F, F, P],
        "face_select": [P, S, Rect, F, F, F, F, P],
        "detected_face_region": [P, P, F, F, P],
        "current_head_region": [P, S, MaskSpec, P, P],
        "project_face_region": [Rect, Rect, Ellipse, C.c_int64, P],
        "region_step": [P, Measurement, P],
        "current_face_region": [P, S, MaskSpec, Ellipse, P],
    }
    for name, args in specs.items():
        fn = getattr(lib, "frame_kernel_" + name)
        fn.argtypes, fn.restype = args, C.c_int32
        setattr(lib, name, fn)
    if hasattr(lib, "frame_kernel_face_render_size_step"):
        fn = lib.frame_kernel_face_render_size_step
        fn.argtypes, fn.restype = [P, Ellipse, C.c_int64, U, P], C.c_int32
        lib.render_size_step = fn
    return lib


def model(path):
    m = Interpreter(model_path=str(path), num_threads=4)
    m.allocate_tensors()
    return m


def infer(m, a):
    m.set_tensor(m.get_input_details()[0]["index"], a)
    m.invoke()
    return [np.ascontiguousarray(m.get_tensor(x["index"])) for x in m.get_output_details()]


def iou(a, b):
    l, t, r, d = vals(a)
    x, y, z, w = vals(b)
    overlap = max(0, min(r, z)-max(l, x))*max(0, min(d, w)-max(t, y))
    return overlap / ((r-l)*(d-t)+(z-x)*(w-y)-overlap)


def detect_instances(lib, yolo, image):
    a = np.asarray(image.convert("RGBA"))
    h, w = a.shape[:2]
    resized = np.empty((640, 640, 4), np.uint8)
    letter = Letterbox()
    check(lib.resize_rgba(ptr(a), a.nbytes, w, h, w*4, ptr(resized), resized.nbytes, 640, 640, 114, C.byref(letter)))
    tensor = np.empty((1, 3, 640, 640), np.float32)
    check(lib.normalize_pixels(ptr(resized), resized.nbytes, PixelSpec(640,640,2560,4,0,1,2,0,0,0,1/255,0), ptr(tensor), tensor.size))
    head, proto = infer(yolo, tensor)
    transform = Transform(letter.scale, letter.left, letter.top, w, h, 640, 160)
    detections = (Detection * 32)()
    masks = np.empty((32, 160, 160), np.uint8)
    count = lib.decode_instances(ptr(head), head.size, ptr(proto), proto.size,
                                 InstanceSpec(8400,116,4,84,32,0,0,2), transform, Thresholds(.25,.5,.5),
                                 detections, 32, ptr(masks), masks.size, None)
    if count < 0:
        raise RuntimeError(f"Instance decode failed: {count}")
    return transform, [detections[i] for i in range(count)], masks[:count].copy()


def detect_face(lib, face, image, spec, mask, pair=None, explicit_plan=None):
    person = spec.person
    w, h = image.size
    plan = np.empty(6, np.float32)
    if explicit_plan is None:
        check(lib.face_head_plan(person, w, h, ptr(plan)))
    else:
        plan=explicit_plan
    view = Rect(*plan[:4])
    crop = np.asarray(image.convert("RGBA").transform((256,256), Image.Transform.EXTENT, tuple(plan[:4]), Image.Resampling.BILINEAR))
    face_in = np.empty((1,192,192,3), np.float32)
    check(lib.grid_face_input(ptr(crop),crop.nbytes,256,256,1024,ptr(face_in),face_in.size))
    boxes, scores = infer(face, face_in)
    candidates = np.empty((2304,17), np.float32)
    n = lib.grid_face_decode(ptr(boxes),boxes.size,ptr(scores),scores.size,256,256,.35,.3,ptr(candidates),candidates.size)
    if n < 0:
        raise RuntimeError(f"face decode {n}")
    owned = []
    for row in candidates[:n]:
        raw = np.ascontiguousarray(np.r_[row[:5], np.float32(6), row[5:]],dtype=np.float32)
        yes = U()
        check(lib.current_face_owned(ptr(mask) if mask is not None else None,mask.size if mask is not None else 0,spec,ptr(raw),C.byref(view),256,0,C.byref(yes)))
        if yes.value:
            if pair is not None:
                all_masks, index = pair
                status=lib.pair_owned(ptr(all_masks),all_masks.size,index,spec,ptr(raw),view,256)
                if status<0:
                    raise RuntimeError("invalid pair ownership evidence")
                if status!=0:
                    continue
            owned.append(raw)
    measurement = None
    if owned:
        rows = np.ascontiguousarray(owned)
        distance = F()
        sel = lib.face_select(ptr(rows), len(owned), Rect(0,0,256,256),float(plan[4]),float(plan[5]),.22,.04,C.byref(distance))
        if sel >= 0:
            measurement = Ellipse()
            check(lib.detected_face_region(ptr(rows[sel]),C.byref(view),256,0,C.byref(measurement)))
    return measurement


def extract(lib, yolo, face, image, reference):
    transform, detections, masks = detect_instances(lib, yolo, image)
    count = len(detections)
    if not count:
        return None, None, None, count
    index = max(range(count), key=lambda i: iou(detections[i].box, reference) if reference is not None else
                (detections[i].box.right-detections[i].box.left)*(detections[i].box.bottom-detections[i].box.top))
    person = detections[index].box
    if reference is not None and iou(person, reference) < .30:
        return None, None, None, count
    mask = masks[index]
    spec = MaskSpec(160, 160, transform, person)
    return spec, mask, detect_face(lib, face, image, spec, mask), count


def run_geometry(lib, evidence):
    state, render_state, last, output = State(), RenderState(), None, []
    for frame, spec, mask, raw, count in evidence:
        time = frame["time_us"]
        if spec is None:
            # Do not invent a current person or attach another identity.
            state, render_state, last = State(), RenderState(), None
            output.append(dict(index=frame["index"],kind=None,people=count))
            continue
        if raw is not None:
            region, kind = raw, 0
            last = (spec.person, raw, time)
        elif last is not None and time-last[2] <= 150_000:
            region, kind = Ellipse(), 1
            check(lib.project_face_region(spec.person,last[0],last[1],time-last[2],C.byref(region)))
        else:
            head = Head()
            check(lib.current_head_region(ptr(mask),mask.size,spec,None,C.byref(head)))
            region, kind = head.region, 2
        stabilized = Ellipse()
        check(lib.region_step(C.byref(state), Measurement(region,spec.person,time,kind,1 | (16 if kind==0 else 0)), C.byref(stabilized)))
        rendered = stabilized
        if kind == 0:
            rendered = Ellipse()
            check(lib.current_face_region(ptr(mask),mask.size,spec,stabilized,C.byref(rendered)))
        required = rendered
        if hasattr(lib, "render_size_step"):
            rendered = Ellipse()
            check(lib.render_size_step(C.byref(render_state), required, time, kind, C.byref(rendered)))
            assert rendered.x == required.x and rendered.y == required.y
            assert rendered.rx >= required.rx and rendered.ry >= required.ry
        # Mirror the native square geometry, not GPU rasterization.
        half = max(8,1.02*max(rendered.rx,rendered.ry))
        width = min(spec.transform.width,rendered.x+half)-max(0,rendered.x-half)
        height = min(spec.transform.height,rendered.y+half)-max(0,rendered.y-half)
        error, head_covered = None, None
        if "head_x" in frame:
            dx, dy = rendered.x-frame["head_x"], rendered.y-frame["head_y"]
            error = math.hypot(dx, dy)
            head_covered = (dx/rendered.rx)**2 + (dy/rendered.ry)**2 <= 1
        output.append(dict(index=frame["index"],kind=kind,people=count,raw=vals(region),
                           stabilized=vals(stabilized),required=vals(required),rendered=vals(rendered),
                           width=width,height=height,head_center_error=error,annotated_head_point_covered=head_covered))
    return output


def summary(rows):
    def ratio(key, subset):
        values = [r[key] for r in subset]
        return max(values)/min(values)
    valid = [r for r in rows if r["kind"] is not None]
    sample = [r for r in rows if r["index"]%3==0 or r is rows[-1]]
    valid_sample = [r for r in sample if r["kind"] is not None]
    head_checks = [r["annotated_head_point_covered"] for r in valid if r["annotated_head_point_covered"] is not None]
    return dict(frames=len(rows),resolved=len(valid),detected=sum(r["kind"]==0 for r in rows),
                projected=sum(r["kind"]==1 for r in rows),estimated=sum(r["kind"]==2 for r in rows),
                head_point_covered=sum(head_checks),head_point_checked=len(head_checks),
                unresolved=[r["index"] for r in rows if r["kind"] is None],
                width_ratio=ratio("width",valid) if valid else None,
                height_ratio=ratio("height",valid) if valid else None,
                sampled_width_ratio=ratio("width",valid_sample) if valid_sample else None,
                sampled_height_ratio=ratio("height",valid_sample) if valid_sample else None,
                sampled_widths=[round(r["width"],2) if "width" in r else None for r in sample])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline",type=Path,required=True)
    p.add_argument("--candidate",type=Path)
    p.add_argument("--fixture",type=Path,default=ROOT/"testdata/face_dynamic_smoke/dynamic_manifest.json")
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--drop-detections",default="",help="Comma-separated zero-based frame indices; fault injection, not natural observations")
    args=p.parse_args()
    dropped={int(v) for v in args.drop_detections.split(",") if v}
    baseline=load(args.baseline)
    yolo=model(ROOT/"models/litert/yolo11n-seg-fp16.tflite")
    face=model(ROOT/"testdata/models/face/blaze_face_full_range.tflite")
    fixture=json.loads(args.fixture.read_text())
    frames=fixture["frames"]
    fps=fixture.get("fps",fixture.get("source_fps",30))
    if dropped-set(range(len(frames))):
        p.error("drop indices outside fixture")
    evidence=[]
    reference=None
    for index, frame in enumerate(frames):
        frame=dict(frame,index=index,time_us=round(index*1e6/fps))
        asset=args.fixture.parent/frame["asset"]
        assert hashlib.sha256(asset.read_bytes()).hexdigest()==frame["sha256"], asset
        with Image.open(asset) as image:
            annotated=Rect(*frame["person_bbox"]) if "person_bbox" in frame else reference
            spec, mask, raw, count=extract(baseline,yolo,face,image,annotated)
        if spec is not None:
            reference=spec.person
        evidence.append((frame,spec,mask,None if index in dropped else raw,count))
    report={"scope":"host geometry only; no production tracker, native AVC export, or privacy acceptance",
            "fixture_sha256":hashlib.sha256(args.fixture.read_bytes()).hexdigest(),
            "model_sha256": {name:hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
                             for name,path in [("person","models/litert/yolo11n-seg-fp16.tflite"),("face","testdata/models/face/blaze_face_full_range.tflite")]},
            "dropped_detections":sorted(dropped)}
    for name, lib in [("baseline",baseline)]+([("candidate",load(args.candidate))] if args.candidate else []):
        rows=run_geometry(lib,evidence)
        report[name]={"summary":summary(rows),"frames":rows,
                      "render_size_state":hasattr(lib,"render_size_step")}
        print(name,json.dumps(report[name]["summary"]),flush=True)
    args.output.write_text(json.dumps(report,indent=2)+"\n")


if __name__=="__main__":
    main()
