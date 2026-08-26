"""
Automated Mask & Bounding Box Comparison Tool (Python Golden vs Android Output)
"""
import os
import sys
import json
import argparse
from pathlib import Path
import numpy as np
import cv2

# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

def calculate_bbox_iou(box1, box2):
    """
    Calculate IoU between two bounding boxes in [left, top, right, bottom] format.
    """
    x1 = max(box1[0], box2[0])
    y1 = max(box1[1], box2[1])
    x2 = min(box1[2], box2[2])
    y2 = min(box1[3], box2[3])

    intersection_area = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    box1_area = (box1[2] - box1[0]) * (box1[3] - box1[1])
    box2_area = (box2[2] - box2[0]) * (box2[3] - box2[1])
    union_area = box1_area + box2_area - intersection_area

    if union_area <= 0:
        return 0.0
    return intersection_area / union_area

def calculate_mask_iou(mask1_img, mask2_img):
    """
    Calculate IoU and pixel difference between two binary mask images.
    """
    if mask1_img.shape != mask2_img.shape:
        # Resize mask2 to match mask1 if dimensions differ slightly due to letterbox
        mask2_img = cv2.resize(mask2_img, (mask1_img.shape[1], mask1_img.shape[0]), interpolation=cv2.INTER_NEAREST)

    bin1 = (mask1_img > 128).astype(np.uint8)
    bin2 = (mask2_img > 128).astype(np.uint8)

    intersection = np.logical_and(bin1, bin2).sum()
    union = np.logical_or(bin1, bin2).sum()

    if union == 0:
        return 1.0, 0.0
    
    iou = float(intersection) / float(union)
    diff_pixels = np.bitwise_xor(bin1, bin2).sum()
    diff_rate = float(diff_pixels) / float(bin1.size)
    return iou, diff_rate

def compare_tracking(golden_tracking_path, target_tracking_path, max_id_switches=2):
    """
    Compare tracking sequences and compute ID switch count.
    Tracking JSON format:
    [
      { "frame": 0, "tracks": { "0": [x1,y1,x2,y2], "1": [x1,y1,x2,y2] } },
      ...
    ]
    """
    g_path = Path(golden_tracking_path)
    t_path = Path(target_tracking_path)
    if not g_path.exists() or not t_path.exists():
        return True

    with open(g_path, "r", encoding="utf-8") as f:
        g_data = json.load(f)
    with open(t_path, "r", encoding="utf-8") as f:
        t_data = json.load(f)

    print("\n--- Tracking Sequence & ID Switch Comparison ---")
    g_frames = {item["frame"]: item["tracks"] for item in g_data}
    t_frames = {item["frame"]: item["tracks"] for item in t_data}

    id_switches = 0
    mapping = {} # g_id -> t_id

    for frame_idx in sorted(g_frames.keys()):
        if frame_idx not in t_frames:
            continue
        g_tr = g_frames[frame_idx]
        t_tr = t_frames[frame_idx]

        for g_id, g_box in g_tr.items():
            best_iou = 0.0
            best_t_id = None
            for t_id, t_box in t_tr.items():
                iou = calculate_bbox_iou(g_box, t_box)
                if iou > best_iou:
                    best_iou = iou
                    best_t_id = t_id

            if best_iou > 0.5 and best_t_id is not None:
                if g_id in mapping and mapping[g_id] != best_t_id:
                    print(f"  [ID SWITCH] Frame {frame_idx}: Golden ID {g_id} switched from Track {mapping[g_id]} to {best_t_id}")
                    id_switches += 1
                mapping[g_id] = best_t_id

    status = "[PASS]" if id_switches <= max_id_switches else "[FAIL]"
    print(f"  {status} Total ID Switches: {id_switches} (Max Allowed: {max_id_switches})")
    return id_switches <= max_id_switches

def compare_directories(golden_dir, target_dir, min_bbox_iou=0.90, min_mask_iou=0.85, max_id_switches=2):
    golden_path = Path(golden_dir)
    target_path = Path(target_dir)

    if not golden_path.exists():
        print(f"[ERROR] Golden directory not found: {golden_path}")
        return False
    if not target_path.exists():
        print(f"[ERROR] Target directory not found: {target_path}")
        return False

    golden_json = golden_path / "bbox.json"
    target_json = target_path / "bbox.json"

    print(f"\n========================================================")
    print(f"Comparing Golden ({golden_path.name}) vs Target ({target_path.name})")
    print(f"========================================================")

    all_passed = True

    # 1. Compare Bounding Boxes if JSON exists
    if golden_json.exists() and target_json.exists():
        with open(golden_json, "r", encoding="utf-8") as f:
            golden_data = json.load(f)
        with open(target_json, "r", encoding="utf-8") as f:
            target_data = json.load(f)

        print("\n--- Bounding Box Comparison ---")
        g_persons = {p["id"]: p for p in golden_data.get("persons", [])}
        t_persons = {p["id"]: p for p in target_data.get("persons", [])}

        for pid, g_p in g_persons.items():
            if pid not in t_persons:
                print(f"  [FAIL] Person {pid}: Missing in target output")
                all_passed = False
                continue
            t_p = t_persons[pid]
            g_box = [g_p["left"], g_p["top"], g_p["right"], g_p["bottom"]]
            t_box = [t_p["left"], t_p["top"], t_p["right"], t_p["bottom"]]
            iou = calculate_bbox_iou(g_box, t_box)
            status = "[PASS]" if iou >= min_bbox_iou else "[FAIL]"
            if iou < min_bbox_iou:
                all_passed = False
            print(f"  {status} Person {pid} BBox IoU: {iou:.4f} (Threshold: {min_bbox_iou})")

    # 2. Compare Mask Images
    golden_masks = sorted(list(golden_path.glob("mask_*.png")))
    if golden_masks:
        print("\n--- Mask Image Comparison ---")
        for g_mask_file in golden_masks:
            t_mask_file = target_path / g_mask_file.name
            if not t_mask_file.exists():
                print(f"  [FAIL] {g_mask_file.name}: Missing in target output")
                all_passed = False
                continue

            g_img = cv2.imread(str(g_mask_file), cv2.IMREAD_GRAYSCALE)
            t_img = cv2.imread(str(t_mask_file), cv2.IMREAD_GRAYSCALE)

            iou, diff_rate = calculate_mask_iou(g_img, t_img)
            status = "[PASS]" if iou >= min_mask_iou else "[FAIL]"
            if iou < min_mask_iou:
                all_passed = False
            print(f"  {status} {g_mask_file.name} Mask IoU: {iou:.4f}, Diff Rate: {diff_rate*100:.2f}% (Threshold: {min_mask_iou})")

    # 3. Compare Tracking Sequence if present
    g_track_file = golden_path / "tracking.json"
    t_track_file = target_path / "tracking.json"
    if g_track_file.exists() and t_track_file.exists():
        if not compare_tracking(g_track_file, t_track_file, max_id_switches):
            all_passed = False

    print("\n========================================================")
    print(f"Overall Result: {'[PASS] ALL PASSED' if all_passed else '[FAIL] FAILED'}")
    print("========================================================\n")
    return all_passed

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Compare segmentation masks and bounding boxes.")
    parser.add_argument("golden_dir", help="Path to golden reference output directory")
    parser.add_argument("target_dir", help="Path to Android/Native output directory")
    parser.add_argument("--min-bbox-iou", type=float, default=0.90, help="Minimum acceptable BBox IoU")
    parser.add_argument("--min-mask-iou", type=float, default=0.85, help="Minimum acceptable Mask IoU")
    parser.add_argument("--max-id-switches", type=int, default=2, help="Maximum allowable ID switch count")
    args = parser.parse_args()

    success = compare_directories(args.golden_dir, args.target_dir, args.min_bbox_iou, args.min_mask_iou, args.max_id_switches)
    sys.exit(0 if success else 1)
