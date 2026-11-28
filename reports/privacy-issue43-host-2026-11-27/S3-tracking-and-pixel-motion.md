# S3 — tracked CPU replay and current-pixel motion bridge

Status: **S3 host tracking/geometry candidate complete; S4 coverage/independent-motion validation not proven. Mobile integration not started.**

## Implementation

- Use the production FrameKernel tracking session with the unchanged tracker configuration and one initial selected ID. Replay every YOLO observation and real miss; annotations are audit-only after checking the initial ID. Decode the full snapshot and compare its byte hash across the baseline, size-only, and candidate libraries.
- In the original JPEG fixture the selected ID is observed in 15/18 frames. Three real misses retain the canonical ID but the predicted body box lags the current movement. The previous size-only candidate had maximum relative x motion error 104.8 px (75 px limit).
- New experimental FrameKernel C ABI `frame_kernel_face_pixel_bridge` accepts the previous direct face view, current gray view, measured region, already-required render region and current foreign boxes. Bounded normalized patch correlation uses an immutable 150 ms direct seed, 15x15 samples, correlation >= 0.85 and distinct-peak gap >= 0.06. Flat/ambiguous/foreign/expired evidence is rejected. A result is motion-only, not ownership, renderability, or direct-observation renewal.
- Return the translated measured and covered ellipses separately, preserving their respective radii. Feeding an expanded render envelope back into detector-temporal state caused a reacquisition size regression in an early local experiment and is not retained.
- The host adapter follows the original direct lease and applies the bridge only to an already retained selected track with a prior direct observation. It is not a port of all native face-pipeline scheduling, pixel matching, fallback or class rules.

## Measured host results

Baseline is master 173d80b1; size-only is 8a438f73. All variants use identical LiteRT CPU YOLO output and tracking inputs. Every replay has identical tracking snapshot hashes across variants, including mask bytes and identity/class state.

| Case | Frames rendered | Base max/min width | Size-only | S3 | S3 pixel bridges | S3 max relative motion x/y |
|---|---:|---:|---:|---:|---:|---:|
| Original JPEG affine fixture | 18/18 | 1.821 | 1.247 | 1.246 | 3 | 34.54 / 25.78 px |
| FFmpeg AVC input round-trip | 18/18 | 1.954 | 1.159 | 1.159 | 1 | 41.30 / 19.54 px |
| Original with direct-face dropout at indices 6,7,8 | 18/18 | 1.810 | 1.261 | 1.257 | 6 | 33.48 / 25.82 px |
| Independent real dance, first 90 frames | 90/90 | 4.234 | 2.592 | 2.592 | 10 | no head annotations |

The first three cases pass the unchanged numerical width/height ratio <= 1.35 and relative x/y motion <= 75 px on the host geometry replay. The FFmpeg input round-trip is not Android MediaCodec output testing. The independent 3-second excerpt has no independently labeled identity or head coverage and the ratio is descriptive only.

## Approximate spatial audit and remaining flaw

On the original low-resolution blurry affine source, visually marked inclusive rectangles approximate the selected and neighboring heads. OpenCV ECC independently registers each frame from the source image; it must exceed 0.99 registration correlation and match the manifest head point within 1px. Neither the audit rectangles nor registration feed tracking or rendering. Rasterized region overlap is checked for the estimated native square placement and the ellipse separately.

All 18 approximate target envelopes are inside the candidate square in each of the three original-source cases. The worst ellipse overlap is 99.946% in the JPEG and dropout variants. The candidate square also intersects the neighboring approximate head in some frames: worst overlap 8.35% in JPEG, 14.31% after AVC input encoding, 10.09% in dropout. The bridge increases the number of overlap frames compared with size-only in these cases; it does not eliminate overprotection. This is not ground-truth face-pixel labeling or native compositing proof.

The independent clip's large size change starts when a small head fallback transitions to a direct-face mask envelope. An explicitly opt-in experiment applies the current-face envelope to estimated current-mask heads too: ratio 2.592 -> 1.319, but visual inspection shows enlarged regions covering shoulders. **Do not adopt this blanket expansion as a default.** Next work must improve head support topology/coverage boundary, rather than inflate fallback size or cap genuine required coverage. Test selected-face coverage and neighboring exemptions on independent annotated crossing/occlusion/scale-change data.

## Reproduction

Build three distinct dynamic libraries from base 173d80b1, size-only 8a438f73 and this branch using the instructions in README.md. Use Python dependencies from S2 and OpenCV for the independent affine audit.

```sh
python tools/privacy/replay_face_tracking.py --baseline BASE_LIB --size-only SIZE_LIB --candidate NEW_LIB --output original.json
python tools/privacy/replay_face_tracking.py --baseline BASE_LIB --size-only SIZE_LIB --candidate NEW_LIB --drop-faces 6,7,8 --output drop.json
python tools/privacy/audit_face_tracking_geometry.py --replay original.json --output original-audit.json
cargo test --locked --manifest-path crates/frame-kernel/Cargo.toml
```

AVC input: encode original dynamic_frame_%03d.jpg at 30 fps with libx264, 3 Mbit/s, GOP 30, yuv420p; decode all 18 frames to PNG. Make a fixture manifest with the original per-frame geometry labels and SHA-256s of the decoded images. Use --fixture to select it. For the independent clip, use the existing local real_reference.mp4 recorded in testdata/comparison/core-matrix-v1.json, normalize to 30 fps and extract the first 90 JPEG frames (ffmpeg -vf fps=30 -frames:v 90 -start_number 0 -q:v 2). Use a manifest with fps 30, asset names and SHA-256s, --target-id 0; no head labels. The explicitly rejected fallback experiment is reproduced with --cover-fallback-experiment.

The raw media remains local. Aggregate metrics, registration/audit evidence and candidate per-frame trace are recorded in s3-summary.json.

## Release scope

No Android/iOS adapter or device changes, no new privacy or identity threshold changes, no merge or issue closure. The native render-size and pixel-bridge integration must wait for S4.
