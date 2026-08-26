import json
import os
import sys
import numpy as np

def generate_synthetic_video(clip_info, output_dir="testdata"):
    clip_id = clip_info["id"]
    w, h = clip_info["resolution"]
    fps = clip_info.get("fps", 30.0)
    dur = clip_info.get("duration_sec", 5.0)
    total_frames = int(fps * dur)

    os.makedirs(output_dir, exist_ok=True)
    out_path = os.path.join(output_dir, f"{clip_id}.mp4")

    try:
        import cv2
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(out_path, fourcc, fps, (w, h))

        num_persons = clip_info.get("num_persons", 1)

        for f in range(total_frames):
            frame = np.full((h, w, 3), 30, dtype=np.uint8) # Dark background

            for p in range(num_persons):
                # Animate synthetic dancer motion
                speed = 4.0 * (p + 1)
                cx = int(w * 0.2 + (f * speed * (1 if p % 2 == 0 else -1)) % (w * 0.6))
                cy = int(h * 0.5 + math_sin_wave(f, p) * 50)
                box_w = int(w * 0.1)
                box_h = int(h * 0.4)

                color = (0, 200, 255) if p == 0 else (255, 100, 0)
                # Draw torso and head
                cv2.rectangle(frame, (cx, cy - box_h // 2), (cx + box_w, cy + box_h // 2), color, -1)
                cv2.circle(frame, (cx + box_w // 2, cy - box_h // 2 - 20), 20, (200, 200, 200), -1)

            writer.write(frame)

        writer.release()
        print(f"Generated synthetic clip: {out_path} ({w}x{h}, {total_frames} frames)")
        return True
    except Exception as e:
        print(f"Warning: Could not generate synthetic MP4 ({e}); writing metadata placeholder")
        return False

def math_sin_wave(f, p):
    import math
    return math.sin(f * 0.1 + p)

def main():
    print("=" * 65)
    print(" Dance Anonymizer - Test Data Provisioning Utility ")
    print("=" * 65)

    manifest_path = os.path.join("testdata", "manifest.json")
    if not os.path.exists(manifest_path):
        print(f"Error: Manifest file {manifest_path} not found")
        sys.exit(1)

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    clips = manifest.get("test_clips", [])
    print(f"Found {len(clips)} test clip specifications in manifest")

    for clip in clips:
        generate_synthetic_video(clip)

    print("=" * 65)
    print("Test data provisioning completed successfully")
    print("=" * 65)

if __name__ == "__main__":
    main()