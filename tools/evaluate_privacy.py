import json
import sys
import os
import numpy as np

def compute_box_iou(box_a, box_b):
    """Computes IoU between [x1, y1, x2, y2] and [x1, y1, x2, y2]."""
    xa = max(box_a[0], box_b[0])
    ya = max(box_a[1], box_b[1])
    xb = min(box_a[2], box_b[2])
    yb = min(box_a[3], box_b[3])

    inter_area = max(0.0, xb - xa) * max(0.0, yb - ya)
    area_a = max(0.0, box_a[2] - box_a[0]) * max(0.0, box_a[3] - box_a[1])
    area_b = max(0.0, box_b[2] - box_b[0]) * max(0.0, box_b[3] - box_b[1])

    union_area = area_a + area_b - inter_area
    if union_area <= 0:
        return 0.0
    return inter_area / union_area

def compute_box_coverage(gt_box, mask_box):
    """Computes how much of gt_box is enclosed by mask_box (intersection / gt_area)."""
    xa = max(gt_box[0], mask_box[0])
    ya = max(gt_box[1], mask_box[1])
    xb = min(gt_box[2], mask_box[2])
    yb = min(gt_box[3], mask_box[3])

    inter_area = max(0.0, xb - xa) * max(0.0, yb - ya)
    gt_area = max(0.0, gt_box[2] - gt_box[0]) * max(0.0, gt_box[3] - gt_box[1])

    if gt_area <= 0:
        return 1.0
    return inter_area / gt_area

def evaluate_privacy_metrics(frames_data, min_coverage_threshold=0.85):
    """
    Evaluates privacy safety metrics:
    - exposure_frames (Must be 0 for release readiness)
    - mean_coverage_rate
    - false_masking_rate
    """
    total_selected_instances = 0
    exposure_frames = 0
    coverage_scores = []
    unselected_overlap_scores = []

    for frame in frames_data:
        selected_ids = set(frame.get("selected_ids", []))
        ground_truth = frame.get("ground_truth", []) # list of {"id": int, "bbox": [x1,y1,x2,y2]}
        applied_masks = frame.get("applied_masks", []) # list of {"id": int, "bbox": [x1,y1,x2,y2]}

        mask_map = {m["id"]: m["bbox"] for m in applied_masks}

        for gt in ground_truth:
            gt_id = gt["id"]
            gt_box = gt["bbox"]

            if gt_id in selected_ids:
                total_selected_instances += 1
                if gt_id in mask_map:
                    coverage = compute_box_coverage(gt_box, mask_map[gt_id])
                    coverage_scores.append(coverage)
                    if coverage < min_coverage_threshold:
                        exposure_frames += 1
                else:
                    exposure_frames += 1
                    coverage_scores.append(0.0)
            else:
                # Check for false masking on unselected person
                if gt_id in mask_map:
                    overlap = compute_box_coverage(gt_box, mask_map[gt_id])
                    unselected_overlap_scores.append(overlap)
                else:
                    unselected_overlap_scores.append(0.0)

    mean_coverage = float(np.mean(coverage_scores)) if coverage_scores else 1.0
    false_mask_rate = float(np.mean(unselected_overlap_scores)) if unselected_overlap_scores else 0.0

    return {
        "total_selected_instances": total_selected_instances,
        "exposure_frames": exposure_frames,
        "exposure_rate_pct": (exposure_frames / max(1, total_selected_instances)) * 100.0,
        "mean_coverage_rate": mean_coverage,
        "false_masking_rate": false_mask_rate,
        "privacy_safety_passed": (exposure_frames == 0) and (mean_coverage >= min_coverage_threshold)
    }

def run_synthetic_benchmark():
    """Simulates a 60-frame benchmark with occlusions and lost track fallback."""
    frames = []
    # 2 persons crossing, person 0 selected
    for f in range(60):
        # Person 0 moves right
        p0_x = 100.0 + f * 10.0
        p0_box = [p0_x, 150.0, p0_x + 120.0, 550.0]

        # Person 1 moves left
        p1_x = 700.0 - f * 8.0
        p1_box = [p1_x, 150.0, p1_x + 120.0, 550.0]

        # Person 0 mask: dilated/expanded conservative fallback during frame 20-25 (occlusion)
        if 20 <= f <= 25:
            # Conservative expanded fallback mask
            mask0_box = [p0_box[0] - 20, p0_box[1] - 20, p0_box[2] + 20, p0_box[3] + 20]
        else:
            mask0_box = [p0_box[0] - 10, p0_box[1] - 10, p0_box[2] + 10, p0_box[3] + 10]

        frames.append({
            "frame_idx": f,
            "selected_ids": [0],
            "ground_truth": [
                {"id": 0, "bbox": p0_box},
                {"id": 1, "bbox": p1_box}
            ],
            "applied_masks": [
                {"id": 0, "bbox": mask0_box}
            ]
        })

    return evaluate_privacy_metrics(frames)

def main():
    print("=" * 65)
    print(" Dance Anonymizer - Privacy Safety & Exposure Evaluation ")
    print("=" * 65)

    if len(sys.argv) < 2:
        print("Running built-in synthetic privacy benchmark simulation...")
        metrics = run_synthetic_benchmark()
        print(f"Total Selected Instances: {metrics['total_selected_instances']}")
        print(f"Exposure Frames:          {metrics['exposure_frames']} (Goal: 0)")
        print(f"Mean Mask Coverage Rate:  {metrics['mean_coverage_rate'] * 100:.2f}%")
        print(f"False Masking Rate:       {metrics['false_masking_rate'] * 100:.2f}%")
        print(f"Privacy Safety Gate:      {'PASSED' if metrics['privacy_safety_passed'] else 'FAILED'}")
        print("=" * 65)
        sys.exit(0 if metrics['privacy_safety_passed'] else 1)

    log_file = sys.argv[1]
    if not os.path.exists(log_file):
        print(f"Error: Log file not found: {log_file}")
        sys.exit(1)

    with open(log_file, "r") as f:
        data = json.load(f)

    metrics = evaluate_privacy_metrics(data)
    print(f"Total Selected Instances: {metrics['total_selected_instances']}")
    print(f"Exposure Frames:          {metrics['exposure_frames']} (Goal: 0)")
    print(f"Mean Mask Coverage Rate:  {metrics['mean_coverage_rate'] * 100:.2f}%")
    print(f"False Masking Rate:       {metrics['false_masking_rate'] * 100:.2f}%")
    print(f"Privacy Safety Gate:      {'PASSED' if metrics['privacy_safety_passed'] else 'FAILED'}")
    print("=" * 65)
    sys.exit(0 if metrics['privacy_safety_passed'] else 1)

if __name__ == "__main__":
    main()
