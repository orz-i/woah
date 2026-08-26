# SAM2 Desktop Golden Reference

This directory provides scripts to generate ground-truth temporal tracking masks directly from the official PyTorch SAM2 Video Predictor (`sam2_hiera_tiny.pt`).

## Contents
- `generate_golden.py`: Runs SAM2 video propagation on a video clip (e.g. 40 frames) and exports per-frame soft masks (`.npy`, `.png`), raw RGB input frames, and `golden_bbox.csv`.
- `compare_masks.py`: Evaluates parity metrics (Mask IoU, BBox Center Error, Area Ratio) between Android-generated predictions and Desktop golden ground-truth.
