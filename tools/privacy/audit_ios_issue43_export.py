#!/usr/bin/env python3
"""Measure actual iOS simulator decoded output, using Issue #43 pixel thresholds.

The approximate source head envelopes are audit probes, not semantic labels.
No output region, detector result or tracked ID is used to position the labels.
"""
import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from audit_face_tracking_geometry import ROOT, FIXTURE, register


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ratio(values):
    return max(values) / min(values) if values and min(values) > 0 else None


def compare(source, output, labels, head_points=None, strict_affine=False):
    src, dst = cv2.VideoCapture(str(source)), cv2.VideoCapture(str(output))
    try:
        fps = src.get(cv2.CAP_PROP_FPS)
        if abs(fps - 30.0) > 0.01 or abs(dst.get(cv2.CAP_PROP_FPS) - fps) > 0.01:
            raise ValueError("Unexpected frame rate; cannot compare by frame index")
        rows = []
        source_height = int(src.get(cv2.CAP_PROP_FRAME_HEIGHT))
        while True:
            ok, before = src.read()
            valid, after = dst.read()
            if not ok or not valid:
                if ok != valid:
                    raise ValueError("Source and output decoded frame counts differ")
                break
            index = len(rows)
            if before.shape != after.shape:
                raise ValueError("Source and output dimensions differ")
            delta = np.abs(after.astype(np.int16) - before.astype(np.int16)).sum(axis=2)
            b, g, r = after.astype(np.int16).transpose(2, 0, 1)
            # Same yellow/changed-pixel definition as the original Android
            # FaceOnlyExportPipelineInstrumentedTest.
            yellow = (r >= 170) & (g >= 120) & (b <= 120) & (r-b >= 70) & (g-b >= 35) & (delta >= 90)
            ys, xs = np.nonzero(yellow)
            row = dict(index=index, yellow_count=int(len(xs)))
            if len(xs):
                row.update(width=int(xs.max()-xs.min()+1),height=int(ys.max()-ys.min()+1),
                           center=[float((xs.max()+xs.min())/2),float((ys.max()+ys.min())/2)],
                           bounds=[int(xs.min()),int(ys.min()),int(xs.max()),int(ys.max())])
            else:
                row.update(width=0,height=0,center=None,bounds=None)
            if index in labels:
                target, neighbor = labels[index]
                tx, ty = target
                tx, ty = tx.astype(int), ty.astype(int)
                row["approx_target_changed_ge90"] = float(np.mean(delta[ty, tx] >= 90))
                row["approx_target_yellow_fraction"] = float(np.mean(yellow[ty, tx]))
                if neighbor is not None:
                    nx, ny = neighbor
                    nx, ny = nx.astype(int), ny.astype(int)
                    if len(nx):
                        row["approx_neighbor_changed_ge90"] = float(np.mean(delta[ny, nx] >= 90))
                        row["approx_neighbor_yellow_fraction"] = float(np.mean(yellow[ny, nx]))
            if head_points and index in head_points:
                hx, hy = head_points[index]
                if row["center"] is not None:
                    row["head_point_in_yellow_bounds"] = bool(row["bounds"][0] <= hx <= row["bounds"][2]
                                                            and row["bounds"][1] <= hy <= row["bounds"][3])
                ix, iy = int(round(hx)), int(round(hy))
                row["head_point_changed_ge90"] = bool(delta[iy, ix] >= 90)
            rows.append(row)
        if not rows:
            raise ValueError("No decoded frames")
        samples = [r for r in rows if r["index"] % 3 == 0 or r["index"] == len(rows)-1]
        width = ratio([r["width"] for r in samples])
        height = ratio([r["height"] for r in samples])
        coverage = [r["approx_target_changed_ge90"] for r in rows if "approx_target_changed_ge90" in r]
        neighbors = [r["approx_neighbor_changed_ge90"] for r in rows if "approx_neighbor_changed_ge90" in r]
        motion_x, motion_y, x_span = None, None, None
        if head_points and all(r["center"] is not None for r in samples):
            x0,y0 = samples[0]["center"]
            hx0,hy0 = head_points[0]
            errors = [(abs(r["center"][0]-x0-(head_points[r["index"]][0]-hx0)),
                       abs(r["center"][1]-y0-(head_points[r["index"]][1]-hy0))) for r in samples]
            motion_x = max(x[0] for x in errors)
            motion_y = max(x[1] for x in errors)
            centers = [r["center"][0] for r in samples]
            x_span = max(centers)-min(centers)
        acceptance = None
        if strict_affine:
            acceptance = (len(rows)==18 and width is not None and height is not None
                          and width<=1.35 and height<=1.35 and motion_x is not None and motion_x<=75
                          and motion_y is not None and motion_y<=75 and x_span>=55
                          and all(r["yellow_count"]>=300 and r["height"]<=int(source_height*.45)
                                  for r in samples))
        return dict(source_sha256=sha(source),output_sha256=sha(output),frames=len(rows),
                    sampled_indices=[r["index"] for r in samples],
                    sampled_widths=[r["width"] for r in samples],sampled_heights=[r["height"] for r in samples],
                    sampled_width_ratio=width,sampled_height_ratio=height,
                    all_width_ratio=ratio([r["width"] for r in rows]),
                    all_height_ratio=ratio([r["height"] for r in rows]),
                    sample_motion_error_x=motion_x,sample_motion_error_y=motion_y,sample_x_span=x_span,
                    affine_original_numeric_gate=acceptance,
                    approximate_target_changed_min=min(coverage,default=None),
                    approximate_neighbor_changed_max=max(neighbors,default=None),
                    rows=rows)
    finally:
        src.release()
        dst.release()


def audit_native_trace(run, name, labels, target_id, frames):
    """Check actual per-frame source-space geometry, independently of pixels."""
    traces = []
    for path in (run / "crop-traces").glob("*.jsonl"):
        data = [json.loads(line) for line in path.read_text().splitlines()]
        if data and data[0].get("output_basename") == name + ".mp4":
            traces.append(data)
    if len(traces) != 1:
        raise ValueError(f"Expected exactly one crop trace for {name}")
    header, *rows = traces[0]
    if len(rows) != frames:
        raise ValueError("Native trace frame count mismatch")
    out = []
    for i, row in enumerate(rows):
        if row["frame_index"] != i or abs(row["t_us"] - round(i * 1e6 / 30)) > 1:
            raise ValueError("Native trace timestamps do not match decoded frame indices")
        if row["source_crop_normalized"] != [0, 0, 1, 1]:
            raise ValueError("Unexpected native source crop")
        regions = [r for r in row["face_regions"] if r["id"] == target_id]
        value = dict(index=i, selected_region_count=len(regions),
                     all_region_ids=[r["id"] for r in row["face_regions"]])
        if len(regions) == 1:
            f = regions[0]
            x, y = f["center"]
            rx, ry = f["radii"]
            value.update(center=f["center"], radii=f["radii"], source=f["source"])
            if i in labels:
                target, neighbor = labels[i]
                tx, ty = target
                value["approx_target_ellipse"] = float(np.mean(((tx-x)/rx)**2+((ty-y)/ry)**2<=1))
                if neighbor is not None and len(neighbor[0]):
                    nx, ny = neighbor
                    value["approx_neighbor_ellipse"] = float(np.mean(((nx-x)/rx)**2+((ny-y)/ry)**2<=1))
        out.append(value)
    return dict(frames=len(out), selected_region_frames=sum(r["selected_region_count"]==1 for r in out),
                extra_region_frames=sum(any(i!=target_id for i in r["all_region_ids"]) for r in out),
                min_approx_target_ellipse=min((r["approx_target_ellipse"] for r in out if "approx_target_ellipse" in r),default=None),
                fully_covered_approx_target_ellipses=sum(r.get("approx_target_ellipse")==1 for r in out),
                max_approx_neighbor_ellipse=max((r["approx_neighbor_ellipse"] for r in out if "approx_neighbor_ellipse" in r),default=None),
                rows=out)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sources",type=Path,required=True)
    p.add_argument("--run",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    fixture=json.loads(FIXTURE.read_text())
    registered={i:tuple(masks) for i,_,_,masks in register()}
    head_points={f["index"]:(f["head_x"],f["head_y"]) for f in fixture["frames"]}
    independent=json.loads((ROOT/"reports/privacy-issue43-host-2026-11-27/s4-reviewed-head-samples.json").read_text())
    configs=[
        ("affine_selected_sticker","issue43-affine.mp4",registered,head_points,True),
    ]
    for name,source,key in [("reference_selected_sticker","issue43-reference.mp4","reference_left"),
                            ("target_selected_sticker","issue43-target.mp4","target_right")]:
        labels={}
        for l in independent["cases"][key]["labels"]:
            x1,y1,x2,y2=l["head_rect"]
            xx,yy=np.meshgrid(np.arange(x1,x2),np.arange(y1,y2))
            labels[l["index"]]=((xx.ravel(),yy.ravel()),None)
        configs.append((name,source,labels,None,False))
    out={"scope":__doc__,"cases":{}}
    for name,source,labels,points,strict in configs:
        file=a.run/"outputs"/(name+".mp4")
        if not file.is_file():
            out["cases"][name]={"error":"No exported output"}
            continue
        try:
            out["cases"][name]=compare(a.sources/source,file,labels,points,strict)
            target_id = 0 if name.startswith("reference") else 1
            out["cases"][name]["native_trace"] = audit_native_trace(
                a.run,name,labels,target_id,out["cases"][name]["frames"])
            # The analyzed selected ID and bbox must match the intended
            # target's reviewed initial position, never a later output ROI.
            result = json.loads((a.run/"results.json").read_text())
            case = next(c for c in result["cases"] if c["case"]==name)
            if case["selected_ids"] != [target_id]:
                raise ValueError("Selected identity mismatch")
            person = next(p for p in case["analysis_persons"] if p["id"]==target_id)
            out["cases"][name]["initial_selected_bbox_normalized"]=person["bbox_normalized"]
        except ValueError as error:
            out["cases"][name]={"error":str(error)}
        summary = {k:v for k,v in out["cases"][name].items() if k not in ("rows","native_trace")}
        if "native_trace" in out["cases"][name]:
            summary["native_trace"] = {k:v for k,v in out["cases"][name]["native_trace"].items() if k!="rows"}
        print(name,json.dumps(summary),flush=True)
    a.output.write_text(json.dumps(out,indent=2)+"\n")


if __name__=="__main__":
    main()
