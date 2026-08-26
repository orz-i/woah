import os
import sys
import csv
import json
import numpy as np

def compute_mask_iou(mask_a: np.ndarray, mask_b: np.ndarray, threshold: float = 0.15) -> float:
    bin_a = mask_a > threshold
    bin_b = mask_b > threshold
    intersection = np.logical_and(bin_a, bin_b).sum()
    union = np.logical_or(bin_a, bin_b).sum()
    if union == 0:
        return 1.0 if intersection == 0 else 0.0
    return float(intersection) / float(union)

def compare_mask_directories(golden_dir: str, target_dir: str, out_csv: str):
    golden_csv = os.path.join(golden_dir, "golden_bbox.csv")
    if not os.path.exists(golden_csv):
        print(f"Error: {golden_csv} does not exist.")
        return

    # Read golden bboxes
    golden_data = {}
    with open(golden_csv, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            frame = int(row["frame"])
            golden_data[frame] = row

    target_csv = os.path.join(target_dir, "android_bbox.csv")
    target_data = {}
    if os.path.exists(target_csv):
        with open(target_csv, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                frame = int(row["frame"])
                target_data[frame] = row

    results = []
    ious = []
    center_errors = []

    for frame, g_row in golden_data.items():
        g_mask_path = os.path.join(golden_dir, "golden_masks", f"frame_{frame:04d}_mask.npy")
        t_mask_path = os.path.join(target_dir, "masks", f"frame_{frame:04d}_mask.npy")

        iou = 0.0
        if os.path.exists(g_mask_path) and os.path.exists(t_mask_path):
            g_mask = np.load(g_mask_path)
            t_mask = np.load(t_mask_path)
            iou = compute_mask_iou(g_mask, t_mask)
            ious.append(iou)

        t_row = target_data.get(frame, {})
        d_cx = float(g_row["centerX"])
        d_cy = float(g_row["centerY"])
        d_w = float(g_row["width"])
        d_h = float(g_row["height"])

        a_cx = float(t_row.get("centerX", 0.0))
        a_cy = float(t_row.get("centerY", 0.0))
        a_w = float(t_row.get("width", 0.0))
        a_h = float(t_row.get("height", 0.0))
        inference_ms = float(t_row.get("inferenceMs", 0.0))

        err = np.sqrt((d_cx - a_cx) ** 2 + (d_cy - a_cy) ** 2)
        center_errors.append(err)

        area_ratio = (a_w * a_h) / max(1.0, (d_w * d_h))

        results.append({
            "frame": frame,
            "objectId": 0,
            "maskIoU": f"{iou:.4f}",
            "desktopCx": f"{d_cx:.2f}",
            "androidCx": f"{a_cx:.2f}",
            "centerErrorPx": f"{err:.2f}",
            "desktopCy": f"{d_cy:.2f}",
            "androidCy": f"{a_cy:.2f}",
            "desktopW": f"{d_w:.2f}",
            "androidW": f"{a_w:.2f}",
            "desktopH": f"{d_h:.2f}",
            "androidH": f"{a_h:.2f}",
            "areaRatio": f"{area_ratio:.4f}",
            "inferenceMs": f"{inference_ms:.2f}"
        })

    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        fieldnames = ["frame", "objectId", "maskIoU", "desktopCx", "androidCx", "centerErrorPx",
                      "desktopCy", "androidCy", "desktopW", "androidW", "desktopH", "androidH", "areaRatio", "inferenceMs"]
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)

    print(f"[Compare] Mean Mask IoU: {np.mean(ious):.4f}" if ious else "[Compare] No mask pairs evaluated.")
    print(f"[Compare] Mean Center Error: {np.mean(center_errors):.2f} px" if center_errors else "")
    print(f"[Compare] Comparison saved to {out_csv}")

if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Usage: python compare_masks.py <golden_dir> <target_dir> <out_csv>")
    else:
        compare_mask_directories(sys.argv[1], sys.argv[2], sys.argv[3])
