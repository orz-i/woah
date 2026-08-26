# SAM2 Hiera Tiny Temporal Mask Tracking on Android: Feasibility & Reference Architecture

## 1. Desktop Reference Analysis (Corgiac/dance-anonymizer)

Based on review of `desktop/src/tracker.py`, `desktop/src/engine.py`, `desktop/src/pipeline.py`, and `desktop/config.yaml`:

1. **First-frame Initialization Only via YOLO**:
   - YOLO (`yolo11s-seg.pt` or `yolo11n-seg`) is invoked solely on Frame 0 to detect all person bounding boxes.
   - First-frame detections are sorted left-to-right (`xCenter` ascending) to assign stable IDs `0, 1, 2...`.
2. **SAM2 Object Registration**:
   - Each detected person is registered into `SAM2VideoPredictor` with `frame_idx = 0`, `obj_id = track_id`, `box = [x1, y1, x2, y2]`.
3. **Persistent Temporal Propagation**:
   - For all subsequent frames ($t \ge 1$), masks are generated sequentially through `predictor.propagate_in_video(inference_state)`.
   - The model maintains internal persistent temporal memory embeddings (`num_maskmem` slots, spatial memory bank, object pointers).
4. **Mask-Driven Bounding Boxes**:
   - Soft alpha mask is obtained via `sigmoid(mask_logits)`.
   - Bounding boxes are dynamically derived from mask threshold (`mask > 0.15`) with a conservative 5% expansion (`expand_ratio = 0.05`).
   - Kalman filter does NOT warp or dictate mask placement.

## 2. Android Architectural Target

```
[Frame 0: MediaCodec Decode]
           ↓
[YOLO Detector (ONNX)] → [Sort Left-to-Right: IDs 0, 1, 2...]
           ↓ (First-frame Prompt Boxes: x1, y1, x2, y2)
[SAM2 Video Tracker Engine (ExecuTorch)]
    ├── Image Encoder (Hiera Tiny)
    ├── Memory Attention & Memory Encoder
    └── Mask Decoder
           ↓
[Continuous Video Frames (t >= 1)]
           ↓
[SAM2 Temporal Propagation] → Persistent Memory Bank (obj_id)
           ↓
[Soft Alpha Mask ([0, 1])] ──→ [GlRenderer (Alpha Compositing)]
           ↓
[Dynamic Bounding Box Extraction (threshold=0.15, expand=0.05)]
```

## 3. Submodule & Tensor Contract Strategy

To execute SAM2 on Android via ExecuTorch without Python generator state:
- Submodules are separated into distinct tensor execution graphs:
  1. `ImageEncoder`: raw normalized frame `[1, 3, H, W]` → multi-scale image embeddings.
  2. `PromptMaskDecoder`: frame 0 box prompt + image embeddings → initial mask logits & object pointer.
  3. `MemoryEncoder`: current frame mask + features → memory features & memory positional encodings.
  4. `MemoryAttention`: historical memory bank + current frame features → conditioned vision features.
  5. `StepMaskDecoder`: conditioned features → next frame mask logits & object pointer.
