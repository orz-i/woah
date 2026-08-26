import json
import math
import sys
from pathlib import Path


def calculate_iou(box_a, box_b):
    x1 = max(box_a[0], box_b[0])
    y1 = max(box_a[1], box_b[1])
    x2 = min(box_a[2], box_b[2])
    y2 = min(box_a[3], box_b[3])
    inter_w = max(0.0, x2 - x1)
    inter_h = max(0.0, y2 - y1)
    inter_area = inter_w * inter_h
    area_a = (box_a[2] - box_a[0]) * (box_a[3] - box_a[1])
    area_b = (box_b[2] - box_b[0]) * (box_b[3] - box_b[1])
    union = area_a + area_b - inter_area
    return inter_area / union if union > 0 else 0.0


def evaluate_tracking_metrics(gt_tracks, pred_tracks, iou_thresh=0.5):
    """Calculates MOTA, IDF1, False Positives, False Negatives, and ID Switches."""
    total_gt = 0
    total_fp = 0
    total_fn = 0
    total_id_switches = 0
    matches_count = 0

    prev_gt_to_pred = {}

    all_frames = sorted(list(set(list(gt_tracks.keys()) + list(pred_tracks.keys()))))

    for frame_idx in all_frames:
        gt_boxes = gt_tracks.get(frame_idx, [])
        pred_boxes = pred_tracks.get(frame_idx, [])
        total_gt += len(gt_boxes)

        matched_gt = set()
        matched_pred = set()

        for g_idx, (gt_id, g_box) in enumerate(gt_boxes):
            best_iou = 0.0
            best_p_idx = -1
            for p_idx, (pred_id, p_box) in enumerate(pred_boxes):
                if p_idx in matched_pred:
                    continue
                iou = calculate_iou(g_box, p_box)
                if iou >= iou_thresh and iou > best_iou:
                    best_iou = iou
                    best_p_idx = p_idx

            if best_p_idx >= 0:
                pred_id = pred_boxes[best_p_idx][0]
                matched_gt.add(g_idx)
                matched_pred.add(best_p_idx)
                matches_count += 1

                if gt_id in prev_gt_to_pred:
                    if prev_gt_to_pred[gt_id] != pred_id:
                        total_id_switches += 1
                prev_gt_to_pred[gt_id] = pred_id

        total_fn += len(gt_boxes) - len(matched_gt)
        total_fp += len(pred_boxes) - len(matched_pred)

    mota = 1.0 - (total_fn + total_fp + total_id_switches) / max(1, total_gt)
    precision = matches_count / max(1, matches_count + total_fp)
    recall = matches_count / max(1, matches_count + total_fn)
    idf1 = (2 * precision * recall) / max(1e-6, precision + recall)

    return {
        "MOTA": round(mota * 100, 2),
        "IDF1": round(idf1 * 100, 2),
        "ID_Switches": total_id_switches,
        "False_Positives": total_fp,
        "False_Negatives": total_fn,
        "Total_GT": total_gt,
    }


def main():
    print("=" * 60)
    print(" Dance Anonymizer - Multi-Person Tracking Benchmark ")
    print("=" * 60)

    # Mock synthetic benchmark demonstration
    synthetic_gt = {
        0: [(1, [100, 100, 200, 400]), (2, [300, 100, 400, 400])],
        1: [(1, [105, 100, 205, 400]), (2, [295, 100, 395, 400])],
        2: [(1, [110, 100, 210, 400]), (2, [290, 100, 390, 400])],
        3: [(1, [120, 100, 220, 400]), (2, [280, 100, 380, 400])],
        4: [(1, [130, 100, 230, 400]), (2, [270, 100, 370, 400])],
    }

    synthetic_pred = {
        0: [(1, [101, 100, 201, 400]), (2, [299, 100, 399, 400])],
        1: [(1, [106, 100, 206, 400]), (2, [294, 100, 394, 400])],
        2: [(1, [111, 100, 211, 400]), (2, [289, 100, 389, 400])],
        3: [(1, [121, 100, 221, 400]), (2, [279, 100, 379, 400])],
        4: [(1, [131, 100, 231, 400]), (2, [269, 100, 369, 400])],
    }

    metrics = evaluate_tracking_metrics(synthetic_gt, synthetic_pred)
    print(f"MOTA Score:       {metrics['MOTA']}%")
    print(f"IDF1 Score:       {metrics['IDF1']}%")
    print(f"ID Switches:      {metrics['ID_Switches']}")
    print(f"False Positives:  {metrics['False_Positives']}")
    print(f"False Negatives:  {metrics['False_Negatives']}")
    print("=" * 60)


if __name__ == "__main__":
    main()
