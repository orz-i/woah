import os
import sys
import json
import csv
import cv2
import numpy as np
import torch

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../desktop/vendor"))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor

def compute_bbox_from_mask(mask: np.ndarray, expand_ratio: float = 0.05):
    h, w = mask.shape
    binary = (mask > 0.15).astype(np.uint8)
    rows = np.any(binary, axis=1)
    cols = np.any(binary, axis=0)
    if not rows.any() or not cols.any():
        return (0, 0, w, h), 0.0
    y_indices = np.where(rows)[0]
    x_indices = np.where(cols)[0]
    y1, y2 = int(y_indices[0]), int(y_indices[-1])
    x1, x2 = int(x_indices[0]), int(x_indices[-1])
    bw, bh = x2 - x1, y2 - y1
    expand_w, expand_h = int(bw * expand_ratio), int(bh * expand_ratio)
    x1 = max(0, x1 - expand_w)
    y1 = max(0, y1 - expand_h)
    x2 = min(w, x2 + expand_w)
    y2 = min(h, y2 + expand_h)
    area = float(binary.sum())
    return (x1, y1, x2, y2), area

def generate_golden(video_path: str, output_dir: str, num_frames: int = 40):
    os.makedirs(output_dir, exist_ok=True)
    frames_dir = os.path.join(output_dir, "_temp_frames")
    os.makedirs(frames_dir, exist_ok=True)
    golden_masks_dir = os.path.join(output_dir, "golden_masks")
    os.makedirs(golden_masks_dir, exist_ok=True)
    golden_frames_dir = os.path.join(output_dir, "golden_frames")
    os.makedirs(golden_frames_dir, exist_ok=True)

    cap = cv2.VideoCapture(video_path)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = float(cap.get(cv2.CAP_PROP_FPS))

    print(f"[Golden] Reading {num_frames} frames from {video_path} ({w}x{h} @ {fps:.1f}fps)...")
    frames = []
    for i in range(num_frames):
        ret, frame = cap.read()
        if not ret:
            break
        frames.append(frame)
        cv2.imwrite(os.path.join(frames_dir, f"{i:05d}.jpg"), frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
        cv2.imwrite(os.path.join(golden_frames_dir, f"frame_{i:04d}_rgb.png"), frame)
    cap.release()

    actual_frames = len(frames)
    print(f"[Golden] Extracted {actual_frames} frames.")

    # Detect initial person on Frame 0 (center-focused prompt)
    # For solo_fast_spin or fast_runner, find center box
    # Or use YOLO if available, or approximate standard person box
    first_frame = frames[0]
    # Let's detect using Ultralytics YOLO or fallback bounding box if YOLO weights exist
    first_bbox = [int(w * 0.25), int(h * 0.15), int(w * 0.75), int(h * 0.85)]
    try:
        from ultralytics import YOLO
        yolo = YOLO("yolo11n-seg.pt")
        res = yolo.predict(first_frame, classes=[0], verbose=False)
        if len(res[0].boxes) > 0:
            box = res[0].boxes.xyxy[0].cpu().numpy().astype(int).tolist()
            first_bbox = [box[0], box[1], box[2], box[3]]
            print(f"[Golden] YOLO detected initial person box: {first_bbox}")
    except Exception as e:
        print(f"[Golden] YOLO initial detection exception ({e}), using default person box: {first_bbox}")

    meta = {
        "video_path": video_path,
        "video_width": w,
        "video_height": h,
        "fps": fps,
        "frame_count": actual_frames,
        "object_id": 0,
        "first_bbox": first_bbox,
        "mask_threshold": 0.15,
        "bbox_expand_ratio": 0.05
    }
    with open(os.path.join(output_dir, "golden_meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    # Initialize SAM2 Video Predictor
    ckpt_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../models/pytorch/sam2_hiera_tiny.pt"))
    config_file = "sam2_hiera_t.yaml"
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[Golden] Initializing SAM2 Video Predictor on {device}...")
    predictor = build_sam2_video_predictor(config_file, ckpt_path=ckpt_path, device=device)
    inference_state = predictor.init_state(video_path=frames_dir)

    # Register object 0 with first_bbox
    predictor.add_new_points_or_box(
        inference_state=inference_state,
        frame_idx=0,
        obj_id=0,
        box=[float(first_bbox[0]), float(first_bbox[1]), float(first_bbox[2]), float(first_bbox[3])]
    )

    csv_path = os.path.join(output_dir, "golden_bbox.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow(["frame", "objId", "left", "top", "right", "bottom", "centerX", "centerY", "width", "height", "maskArea"])

        for out_frame_idx, out_obj_ids, out_mask_logits in predictor.propagate_in_video(inference_state):
            idx = int(out_frame_idx)
            for i, obj_id in enumerate(out_obj_ids):
                if int(obj_id) != 0:
                    continue
                mask_logits = out_mask_logits[i]
                soft_mask = torch.sigmoid(mask_logits).cpu().squeeze().numpy().astype(np.float32)
                if soft_mask.shape != (h, w):
                    soft_mask = cv2.resize(soft_mask, (w, h), interpolation=cv2.INTER_LINEAR)
                soft_mask = np.clip(soft_mask, 0.0, 1.0)

                # Save npy and png
                np.save(os.path.join(golden_masks_dir, f"frame_{idx:04d}_mask.npy"), soft_mask)
                cv2.imwrite(os.path.join(golden_masks_dir, f"frame_{idx:04d}_mask.png"), (soft_mask * 255).astype(np.uint8))

                (x1, y1, x2, y2), area = compute_bbox_from_mask(soft_mask, expand_ratio=0.05)
                bw = x2 - x1
                bh = y2 - y1
                cx = (x1 + x2) / 2.0
                cy = (y1 + y2) / 2.0

                writer.writerow([idx, 0, x1, y1, x2, y2, f"{cx:.2f}", f"{cy:.2f}", bw, bh, f"{area:.1f}"])

    # Clean up temp frames dir
    import shutil
    shutil.rmtree(frames_dir, ignore_errors=True)
    print(f"[Golden] Successfully generated {actual_frames} Golden frames at {output_dir}")

if __name__ == "__main__":
    video = "desktop/vendor/Cutie/examples/example.mp4"
    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../tools/sam2_android/reference/golden_dataset"))
    generate_golden(video, out, num_frames=40)


