#!/usr/bin/env python3
"""Local M0 reference: real video, pinned LiteRT, shared tracking and canvas math.

Not an app export path: local image sequence and a manifest only. Never
upload input frames, selection evidence, or masks; write to a caller-owned directory.
Requires ai-edge-litert, numpy, opencv-python-headless, ffmpeg and a built FrameKernel.
"""
from __future__ import annotations

import argparse
import ctypes as c
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/rust"))
from abi_smoke import Rect, Transform
from instances_smoke import Spec, Thresholds, Detection
from pixels_smoke import PixelSpec

MODEL_SHA = "ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340"
U8, F32, I64, U32 = c.c_uint8, c.c_float, c.c_int64, c.c_uint32
P8, PF = c.POINTER(U8), c.POINTER(F32)


class Letterbox(c.Structure):
    _fields_ = [(x, F32) for x in ("scale", "width", "height", "left", "top")]


class Quad(c.Structure):
    _fields_ = [(x, F32) for x in ("scale_x", "scale_y", "shift_x", "shift_y")] + [("valid", U32)]


class Weights(c.Structure):
    _fields_ = [(x, F32) for x in ("bounds", "mask", "motion", "direction", "gating_threshold")]


class Options(c.Structure):
    _fields_ = [(x, U32) for x in ("max_missed", "grace", "occlusion_max", "scene_inliers")] + [
        ("minimum_score", F32), ("weights", Weights),
    ] + [(x, F32) for x in ("overlap", "ambiguity", "scene_tolerance", "maximum_shift")] + [
        ("scene_compensation", U32), ("foot_margin", F32), ("erosion_radius", U32),
    ]


def api(path):
    lib = c.CDLL(str(path))
    def bind(name, args, result=c.c_int32):
        f = getattr(lib, "frame_kernel_" + name)
        f.argtypes, f.restype = args, result
    bind("resize_rgba", [P8, c.c_size_t, U32, U32, U32, P8, c.c_size_t, U32, U32, U8, c.POINTER(Letterbox)])
    bind("normalize_pixels", [P8, c.c_size_t, PixelSpec, PF, c.c_size_t])
    bind("decode_instances", [PF, c.c_size_t, PF, c.c_size_t, Spec, Transform, Thresholds,
        c.POINTER(Detection), c.c_size_t, P8, c.c_size_t, c.c_void_p])
    bind("cutout_select", [P8, c.c_size_t, Transform, F32, F32])
    bind("cutout_alpha", [P8, c.c_size_t, Transform, U32, U32, P8, c.c_size_t])
    bind("cutout_quad", [U32, U32, c.c_double, Rect, c.c_double, c.c_double, c.c_double, U32], Quad)
    bind("tracking_defaults", [], Options)
    bind("tracking_create", [Options], c.c_uint64)
    bind("tracking_destroy", [c.c_uint64])
    bind("cutout_track_step", [c.c_uint64, I64, c.c_int32, c.POINTER(Detection), c.c_size_t, P8, c.c_size_t, Transform])
    bind("time_value", [U32, I64, U32, c.c_double, I64], I64)
    bind("frame_index", [c.POINTER(I64), c.c_size_t, I64, I64, I64, U32], I64)
    return lib


def ptr(array, kind=P8):
    return array.ctypes.data_as(kind)


def source_frames(lib, path, limit):
    probe = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height,time_base:stream_side_data=rotation:frame=best_effort_timestamp",
        "-show_frames", "-show_streams", "-of", "json", str(path),
    ]))
    stream = probe["streams"][0]
    if any(int(s.get("rotation", 0)) != 0 for s in stream.get("side_data_list", [])):
        raise ValueError("M0 host probe accepts already display-oriented videos only")
    width, height = stream["width"], stream["height"]
    scale = min(1.0, 768 / max(width, height))
    w, h = max(2, round(width * scale)), max(2, round(height * scale))
    base = Fraction(stream["time_base"])
    if not 0 < base.denominator <= 0xffffffff or base.numerator <= 0:
        raise ValueError("Unsupported source time base")
    times = []
    for frame in probe["frames"][:limit]:
        ticks = int(frame["best_effort_timestamp"]) * base.numerator
        if not 0 <= ticks <= (1 << 63) - 1:
            raise ValueError("Unsupported source timestamp")
        times.append(lib.frame_kernel_time_value(0, ticks, base.denominator, 1.0, 0))
    if not times or times[0] < 0 or times != sorted(set(times)):
        raise ValueError("Source PTS must be nonnegative and strictly increasing")
    process = subprocess.Popen([
        "ffmpeg", "-v", "error", "-noautorotate", "-i", str(path), "-map", "0:v:0",
        "-frames:v", str(len(times)), "-vf", f"scale={w}:{h}", "-fps_mode", "passthrough",
        "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1",
    ], stdout=subprocess.PIPE)
    try:
        for pts in times:
            data = process.stdout.read(w * h * 4)
            if len(data) != w * h * 4:
                raise ValueError("Decoded frames did not match the source PTS index")
            yield pts, np.frombuffer(data, np.uint8).reshape(h, w, 4)
        if process.stdout.read(1) or process.wait():
            raise ValueError("Unexpected decoder output")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait()


def infer(lib, interpreter, rgba, threshold=.25):
    h, w = rgba.shape[:2]
    letterbox = Letterbox()
    packed = np.empty((640, 640, 4), np.uint8)
    assert lib.frame_kernel_resize_rgba(ptr(rgba), rgba.size, w, h, w*4, ptr(packed),
        packed.size, 640, 640, 114, c.byref(letterbox)) == 0
    tensor = np.empty((1, 3, 640, 640), np.float32)
    spec = PixelSpec(640, 640, 640*4, 4, 0, 1, 2, 0, 0, 0, 1/255, 0)
    assert lib.frame_kernel_normalize_pixels(ptr(packed), packed.size, spec, ptr(tensor, PF), tensor.size) == 0
    t = Transform(letterbox.scale, letterbox.left, letterbox.top, w, h, 640, 160)
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], tensor)
    interpreter.invoke()
    head, proto = (interpreter.get_tensor(item["index"]) for item in interpreter.get_output_details())
    if head.shape != (1, 116, 8400) or proto.shape != (1, 32, 160, 160):
        raise ValueError("Unexpected canonical model tensor contract")
    model = Spec(8400, 116, 4, 84, 32, 0, 0, 2)
    detections = (Detection * 256)()
    masks = np.empty((256, 160, 160), np.uint8)
    n = lib.frame_kernel_decode_instances(ptr(head, PF), head.size, ptr(proto, PF), proto.size,
        model, t, Thresholds(threshold, .5, .5), detections, 256, ptr(masks), masks.size, None)
    if not 0 <= n <= 256:
        raise ValueError(f"Instance decode failed: {n}")
    return list(detections[:n]), masks[:n], t


def track(lib, interpreter, path, touch, limit, anchor_index=0):
    frames, anchor, prefix = [], None, []
    sessions = [lib.frame_kernel_tracking_create(lib.frame_kernel_tracking_defaults()) for _ in range(2)]
    def step(session, observed, seed=-1):
        pts, rgba, det, masks, t = observed
        observations = (Detection * len(det))(*det)
        index = lib.frame_kernel_cutout_track_step(session, pts, seed, observations, len(det),
            ptr(masks), masks.size, t)
        if index < -1:
            raise ValueError("Invalid cutout tracking step")
        current = masks[index] if index >= 0 else None
        reason = ("weak_current" if det[index].confidence < .25 else "current") if index >= 0 else "identity_unavailable"
        alpha = None
        if current is not None:
            h, w = rgba.shape[:2]
            alpha = np.empty((h, w), np.uint8)
            assert lib.frame_kernel_cutout_alpha(ptr(current), current.size, t, w, h, ptr(alpha), alpha.size) == 0
        if alpha is not None and not np.any(alpha):
            reason = "empty_alpha"
            alpha = None
        return pts, rgba, alpha, reason
    try:
        if not all(sessions):
            raise ValueError("Could not create tracker")
        forward, reverse = sessions
        for ordinal, (pts, rgba) in enumerate(source_frames(lib, path, limit)):
            det, masks, t = infer(lib, interpreter, rgba, .25 if ordinal == anchor_index else .20)
            observed = pts, rgba, det, masks, t
            if ordinal < anchor_index:
                prefix.append(observed)
                continue
            if ordinal == anchor_index:
                seed = lib.frame_kernel_cutout_select(ptr(masks), masks.size, t, *touch)
                if seed < 0:
                    boxes = [[round(getattr(d.bounds, field)/dim, 3) for field, dim in
                        zip(("left","top","right","bottom"), (t.source_width,t.source_height)*2)] for d in det]
                    raise ValueError(f"Touch was background/ambiguous ({seed}); normalized candidates: {boxes}")
                d = det[seed].bounds
                anchor = Rect(d.left/t.source_width, d.top/t.source_height,
                    d.right/t.source_width, d.bottom/t.source_height)
                first = step(forward, observed, seed)
                backward = step(reverse, observed, seed)
                if first[2] is None or backward[2] is None:
                    raise ValueError("Selection anchor was not retained")
                preceding = [step(reverse, prior) for prior in reversed(prefix)]
                frames.extend(reversed(preceding))
                prefix.clear()
                frames.append(first)
            else:
                frames.append(step(forward, observed))
        if anchor is None:
            raise ValueError("Anchor frame was not decoded")
    finally:
        for session in sessions:
            if session:
                assert lib.frame_kernel_tracking_destroy(session) == 0
    return frames, anchor


def render(lib, canvas, frame, anchor, x, mirror):
    _, rgba, alpha, _ = frame
    if alpha is None:
        return
    h, w = rgba.shape[:2]
    H, W = canvas.shape[:2]
    q = lib.frame_kernel_cutout_quad(W, H, w/h, anchor, x, .88, .7, int(mirror))
    if not q.valid:
        raise ValueError("Invalid fixed canvas placement")
    ww, hh = q.scale_x*W, q.scale_y*H
    left, top = (1+q.shift_x-q.scale_x)*W/2, (1-q.shift_y-q.scale_y)*H/2
    if mirror:
        rgba, alpha = rgba[:, ::-1], alpha[:, ::-1]
    a = alpha.astype(np.float32)/255
    premult = np.dstack((rgba[:, :, :3].astype(np.float32)*a[:, :, None], a))
    transform = np.array([[ww/w, 0, left + (ww/w-1)/2], [0, hh/h, top+(hh/h-1)/2]], np.float64)
    projected = cv2.warpAffine(premult, transform, (W,H), flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT, borderValue=(0,0,0,0))
    canvas[:] = projected[:, :, :3] + canvas*(1-projected[:, :, 3:4])


def point(value):
    x, y = map(float, value.split(","))
    if not 0 <= x < 1 or not 0 <= y < 1:
        raise argparse.ArgumentTypeError("Expected normalized x,y in [0,1)")
    return x, y


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--library", required=True, type=Path)
    p.add_argument("--reference", required=True, type=Path)
    p.add_argument("--target", required=True, type=Path)
    p.add_argument("--reference-point", required=True, type=point)
    p.add_argument("--target-point", required=True, type=point)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--frames", type=int, default=30)
    p.add_argument("--reference-anchor-frame", type=int, default=0)
    p.add_argument("--target-anchor-frame", type=int, default=0)
    p.add_argument("--mirror-target", action="store_true")
    p.add_argument("--require-all", action="store_true", help="fail if either selected subject disappears on any paired frame")
    args = p.parse_args()
    if not 1 <= args.frames <= 120:
        p.error("M0 is deliberately bounded to 1..120 decoded frames per source")
    if not all(0 <= i < args.frames for i in (args.reference_anchor_frame, args.target_anchor_frame)):
        p.error("Anchor frame must lie in the decoded source window")
    if args.output.exists():
        p.error("Output directory must not exist")
    model = ROOT/"models/litert/yolo11n-seg-fp16.tflite"
    if hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA:
        raise ValueError("Canonical model hash mismatch")
    lib = api(args.library)
    interpreter = Interpreter(model_path=str(model), num_threads=4)
    interpreter.allocate_tensors()
    started = time.monotonic()
    reference, ra = track(lib, interpreter, args.reference, args.reference_point, args.frames, args.reference_anchor_frame)
    target, ta = track(lib, interpreter, args.target, args.target_point, args.frames, args.target_anchor_frame)
    times = (I64 * len(target))(*(f[0] for f in target))
    args.output.mkdir(parents=True)
    records = []
    for reference_frame in reference:
        pts = reference_frame[0]
        # M0 assumes identical absolute source time mapping. No nominal FPS lookup.
        if not target[0][0] <= pts <= target[-1][0]:
            continue
        target_index = lib.frame_kernel_frame_index(times, len(times), pts, target[0][0], target[-1][0]+1, 0)
        if target_index < 0:
            continue
        target_frame = target[target_index]
        canvas = np.zeros((540, 960, 3), np.float32)
        render(lib, canvas, reference_frame, ra, .27, False)
        render(lib, canvas, target_frame, ta, .73, args.mirror_target)
        path = args.output/f"frame_{len(records):04d}.png"
        if not cv2.imwrite(str(path), cv2.cvtColor(np.clip(canvas, 0, 255).astype(np.uint8),cv2.COLOR_RGB2BGR)):
            raise ValueError("Could not write canvas frame")
        records.append({"referencePtsUs":pts, "targetPtsUs":target_frame[0],
            "referenceVisible":reference_frame[2] is not None and bool(np.any(reference_frame[2])),
            "targetVisible":target_frame[2] is not None and bool(np.any(target_frame[2])),
            "referenceReason":reference_frame[3], "targetReason":target_frame[3]})
    summary = {"modelSha256":MODEL_SHA, "renderedFrames":len(records), "referenceFrames":len(reference),
        "targetFrames":len(target), "referenceVisible":sum(r["referenceVisible"] for r in records),
        "targetVisible":sum(r["targetVisible"] for r in records),
        "seconds":round(time.monotonic()-started,3), "frames":records}
    (args.output/"manifest.json").write_text(json.dumps(summary,indent=2)+"\n")
    print(json.dumps({k:v for k,v in summary.items() if k!="frames"}))
    if not records or not summary["referenceVisible"] or not summary["targetVisible"]:
        raise SystemExit("No paired foreground output")
    if args.require_all and (summary["referenceVisible"] != len(records) or summary["targetVisible"] != len(records)):
        raise SystemExit("Selected subject missing on one or more paired frames")


if __name__ == "__main__":
    main()
