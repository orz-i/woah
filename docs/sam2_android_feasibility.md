# SAM2 Hiera Tiny Temporal Mask Tracking on Android: Validated ONNX Architecture

## 1. Reference Architecture & Implementation

The production SAM2 temporal tracking backend on Android is powered by **ONNX Runtime** (`com.microsoft.onnxruntime:onnxruntime-android:1.18.0`):

```
[Frame 0: MediaCodec Decode / GL Texture]
           ↓
[YOLO Detector (ONNX)] → [Sort Left-to-Right: IDs 0, 1, 2...]
           ↓ (First-frame Prompt Boxes: x1, y1, x2, y2)
[SAM2 Image Encoder (ONNX)] ──→ Multi-scale features (top, high_res_0, high_res_1)
           ↓
[SAM2 Init Step (ONNX)] ──────→ Frame 0 Mask, Conditioning Memory & Object Pointer
           ↓
[Persistent Video State (Sam2OnnxVideoState)]
           ↓
[Continuous Video Frames (t >= 1)]
           ↓ (No YOLO Bounding Box Prompts)
[SAM2 State Selector (Kotlin)] ──→ Assembles 7 memories (1 cond + 6 recent) + <=16 obj pointers
           ↓
[SAM2 Temporal Step (ONNX)] ───→ High-res Mask, Memory Features & Object Pointer
           ↓
[Sigmoid & Strict BBox Extraction] ──→ NativeMask & TrackedPerson
           ↓
[GlRenderer (Alpha Compositing & Shader Anonymization)]
```

## 2. Model Computation Graphs & Tensor Contracts

SAM2 Hiera Tiny is exported into 3 self-contained ONNX computation graphs (Opset 17):

1. **`sam2_image_features.onnx`** (~104.3 MB):
   - **Input**: `image [1, 3, 1024, 1024]` (FLOAT)
   - **Outputs**: `top_vision_feature [1, 256, 64, 64]`, `top_vision_pos_enc [1, 256, 64, 64]`, `high_res_feature_0 [1, 32, 256, 256]`, `high_res_feature_1 [1, 64, 128, 128]`
2. **`sam2_init_step.onnx`** (~22.4 MB):
   - **Inputs**: `top_vision_feature`, `high_res_feature_0`, `high_res_feature_1`, `point_coords [1, N, 2]`, `point_labels [1, N]`
   - **Outputs**: `high_res_mask [1, 1, 1024, 1024]`, `obj_ptr [1, 256]`, `memory_features [1, 64, 64, 64]`, `memory_pos_enc [1, 64, 64, 64]`
3. **`sam2_temporal_step.onnx`** (~49.5 MB):
   - **Inputs**: `current_top_feature`, `current_top_pos`, `current_high_res_0`, `current_high_res_1`, `selected_memory_features [num_mem, 1, 64, 64, 64]`, `selected_memory_pos [num_mem, 1, 64, 64, 64]`, `memory_tpos_indices [num_mem]`, `selected_obj_ptrs [num_ptrs, 1, 256]`
   - **Outputs**: `high_res_mask [1, 1, 1024, 1024]`, `obj_ptr [1, 256]`, `memory_features [1, 64, 64, 64]`, `memory_pos_enc [1, 64, 64, 64]`

## 3. Key Invariants & Parity Metrics

- **Rotary Position Embedding (RoPE)**: Converted to portable real-part arithmetic $(ac-bd) + (ad+bc)i$ without native complex types, verified with $\text{maxAbsError} = 0.00$.
- **Temporal Memory Selection**: Exact semantic parity between PyTorch `SAM2VideoPredictor` and Kotlin `Sam2OnnxStateSelector`.
- **Golden Video Parity (40 Frames)**:
  - Python ORT Mean Mask IoU: **0.9832**
  - Android ORT Mean Mask IoU: **0.9843**
  - 3-Frame Divergence ($IoU < 0.5$): **0 (None)**
  - Mean BBox Center Drift: **4.61 px**

