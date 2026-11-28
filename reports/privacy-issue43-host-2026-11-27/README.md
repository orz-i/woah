# Issue #43 — host-first FrameKernel face render stability

Status: **local algorithm candidate; mobile integration and privacy acceptance NOT PROVEN**.

## Implementation
- Replace the discontinuous bounding-box circumscription in current-face coverage with a compact, continuous enclosing region. Keep the original ellipse and all required support corners contained, including after floating-point rounding. No fixed cap on required growth.
- Add caller-owned, per-identity final-render size state to FrameKernel and expose an additive C ABI. Run after current-frame coverage resolution, never in place of identity/ownership or region resolution. Growth is immediate; contraction uses the existing 240 ms time constant. A projected or estimated region cannot renew the direct-observation 150 ms size lease. Seek, discontinuity, identity change and exemption change require state reset. Size state cannot invent renderable regions or move their centers.
- No mobile adapter, tracking policy, detector threshold, or existing instrumented acceptance threshold was changed.

## Host results

Baseline: master 173d80b1. Mac arm64, LiteRT CPU, same decoded evidence passed to both shared libraries. All 168 Rust tests/doc tests passed, zero failures, one ignored. Build, formatting and Python compilation passed.

| Replay | Resolved frames | Baseline width ratio | Candidate width ratio | Candidate annotated head points |
|---|---:|---:|---:|---:|
| Original dynamic fixture | 15/18 | 1.327370 | 1.246282 | 15/15 |
| Inject missing direct faces at indices 6,7,8 | 15/18 | 1.663506 | 1.260649 | 15/15 |
| Independent real reference excerpt | 24/24 | 1.106222 | 1.116813 | not annotated |

Height ratios are equal to width ratios for these resolved frames; the sampled ratios, fixture/model SHA-256s and counts are in summary.json. The independent excerpt is the first 24 frames at 30 fps of the verified real_reference.mp4 identified in testdata/comparison/core-matrix-v1.json; it is a short independent geometry sanity check, not a full motion validation. Independent width stability slightly regressed, though below 1.35.

## Reproduce (macOS)
Build the baseline from a separate clean checkout of 173d80b1 and the candidate from this branch (cargo build --manifest-path crates/frame-kernel/Cargo.toml --release, distinct CARGO_TARGET_DIR values). Use Python with numpy, Pillow, ai-edge-litert (tested 2.5.5, 13.0.0, 2.4.1 respectively). From the repository:
```sh
python tools/privacy/replay_face_geometry.py --baseline /path/to/baseline/libframe_kernel.dylib --candidate /path/to/candidate/libframe_kernel.dylib --output /tmp/issue43-natural.json
python tools/privacy/replay_face_geometry.py --baseline /path/to/baseline/libframe_kernel.dylib --candidate /path/to/candidate/libframe_kernel.dylib --drop-detections 6,7,8 --output /tmp/issue43-drop.json
cargo test --manifest-path crates/frame-kernel/Cargo.toml
```
For independent data, pass --fixture with a JSON manifest containing fps and frames (asset relative to manifest and sha256 for each JPEG; optional person_bbox/head_x/head_y annotations).

## Limitations / next acceptance stages

The original Android AVC export failure ratio 2.025 is not directly comparable to these host results. The host harness selects a person by annotated-box IoU (independent data: initial largest person, then IoU continuity), not the production tracking session. In the natural and injected cases, frames 11–13 are unresolved in both baseline and candidate; they are excluded from the reported ratio and explicitly counted as unresolved, not treated as covered. The head-point check is not a face-pixel or identity/exemption audit. Native rendering/encoding was not run. The injected case is synthetic evidence loss, not a replay of the original Android detector trace.

Before mobile integration: replay full FrameKernel tracking/identity state locally; resolve all 18 frames with verified ownership; add full-face pixel coverage and independent longer motion, occlusion, true scale change and crossing tests; validate rendered dimensions/motion without loosening existing gates. After a stable host result, wire per-track final size state into Android and iOS with reset boundaries, then rerun the original production export gate (width and height ratios <= 1.35) and privacy regressions on devices/simulator as appropriate. Do not close Issue #43 or release from this host-only evidence.
