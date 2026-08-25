"""
Golden Reference Dataset Generator
Extracts first-frame detections, bounding boxes, masks, and thumbnails using desktop YOLO pipeline.
"""
import os
import sys
import json
import argparse
from pathlib import Path
import cv2
import numpy as np

# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# Add desktop to python path
project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root / "desktop"))

from ultralytics import YOLO

def generate_golden_for_video(video_path: Path, output_dir: Path, model_name="yolo11n-seg.pt", conf_thresh=0.3):
    print(f"\n[GoldenGen] Processing {video_path.name}...")
    output_dir.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        print(f"[ERROR] Could not open video: {video_path}")
        return False

    ret, frame = cap.read()
    cap.release()
    if not ret or frame is None:
        print(f"[ERROR] Could not read first frame from: {video_path}")
        return False

    h, w = frame.shape[:2]
    # Save original first frame image
    frame_path = output_dir / "frame_000000.png"
    cv2.imwrite(str(frame_path), frame)

    # Load YOLO model
    model_path = project_root / "models" / "pytorch" / model_name
    if not model_path.exists():
        model = YOLO(model_name)
    else:
        model = YOLO(str(model_path))

    results = model.predict(
        source=frame,
        conf=conf_thresh,
        classes=[0],  # person only
        imgsz=640,
        verbose=False,
    )

    result = results[0]
    detections = []

    if result is not None and result.boxes is not None and len(result.boxes) > 0:
        for i in range(len(result.boxes)):
            box = result.boxes.xyxy[i].cpu().numpy().astype(float)
            x1, y1, x2, y2 = box[0], box[1], box[2], box[3]
            conf = float(result.boxes.conf[i].item())

            mask_data = None
            if result.masks is not None and len(result.masks.data) > i:
                m = result.masks.data[i].cpu().numpy()
                if m.shape != (h, w):
                    m = cv2.resize(m.astype(np.float32), (w, h), interpolation=cv2.INTER_LINEAR)
                mask_data = (m > 0.5).astype(np.uint8) * 255

            detections.append({
                "x1": x1,
                "y1": y1,
                "x2": x2,
                "y2": y2,
                "conf": conf,
                "mask": mask_data,
                "center_x": (x1 + x2) / 2.0,
            })

    # Sort left to right by center X
    detections.sort(key=lambda d: d["center_x"])

    persons_json = []
    for idx, d in enumerate(detections):
        d["id"] = idx
        norm_left = float(d["x1"] / w)
        norm_top = float(d["y1"] / h)
        norm_right = float(d["x2"] / w)
        norm_bottom = float(d["y2"] / h)

        # Save individual mask
        mask_filename = f"mask_person_{idx}.png"
        if d["mask"] is not None:
            cv2.imwrite(str(output_dir / mask_filename), d["mask"])

        # Generate thumbnail (10% expanded bbox crop)
        bw = d["x2"] - d["x1"]
        bh = d["y2"] - d["y1"]
        crop_x1 = max(0, int(d["x1"] - bw * 0.1))
        crop_y1 = max(0, int(d["y1"] - bh * 0.1))
        crop_x2 = min(w, int(d["x2"] + bw * 0.1))
        crop_y2 = min(h, int(d["y2"] + bh * 0.1))

        thumb_crop = frame[crop_y1:crop_y2, crop_x1:crop_x2]
        thumb_filename = f"thumb_{idx}.webp"
        if thumb_crop.size > 0:
            thumb_resized = cv2.resize(thumb_crop, (160, 240))
            cv2.imwrite(str(output_dir / thumb_filename), thumb_resized)

        persons_json.append({
            "id": idx,
            "left": norm_left,
            "top": norm_top,
            "right": norm_right,
            "bottom": norm_bottom,
            "confidence": d["conf"],
            "thumbnail": thumb_filename,
            "mask": mask_filename,
        })

    metadata = {
        "video": video_path.name,
        "width": w,
        "height": h,
        "persons": persons_json,
    }

    with open(output_dir / "bbox.json", "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2, ensure_ascii=False)

    print(f"  ✅ Saved {len(persons_json)} persons to {output_dir}")
    return True

def main():
    parser = argparse.ArgumentParser(description="Generate Golden Reference data for test videos.")
    parser.add_argument("--videos-dir", default=str(project_root / "testdata" / "videos"))
    parser.add_argument("--expected-dir", default=str(project_root / "testdata" / "expected"))
    parser.add_argument("--model", default="yolo11n-seg.pt")
    args = parser.parse_args()

    videos_path = Path(args.videos_dir)
    expected_path = Path(args.expected_dir)

    video_files = list(videos_path.glob("*.mp4")) + list(videos_path.glob("*.mov"))
    if not video_files:
        print(f"[INFO] No video files found in {videos_path}. Put test videos into testdata/videos/ to generate golden data.")
        return

    for v_file in video_files:
        out_subdir = expected_path / v_file.stem
        generate_golden_for_video(v_file, out_subdir, model_name=args.model)

if __name__ == "__main__":
    main()
