# SAM2 ONNX Runtime Temporal Tracking

This workspace contains the complete ONNX export, validation, and runtime pipeline for SAM2 Hiera Tiny.

## Architecture

1. **sam2_image_features.onnx**: Runs image encoder backbone and FPN level projections (conv_s0, conv_s1).
2. **sam2_init_step.onnx**: Processes Frame 0 with YOLO bbox prompt and encodes initial conditioning memory.
3. **sam2_temporal_step.onnx**: Performs memory attention across past frames and propagates object pointers without new bbox prompts.

## Directory Structure

- \xport/\: Scripts for model inspection, RoPE equivalence, export, and parity checks.
- untime/\: State manager, Python video runner, and semantic trace tools.
- eports/\: Parity test results and runtime metrics.
