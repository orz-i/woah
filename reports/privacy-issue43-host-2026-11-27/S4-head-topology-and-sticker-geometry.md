# S4 — head topology and compact sticker placement

Status: **host algorithm candidate; improved original regression and independent sample coverage, remaining right-person scale instability. No mobile integration.**

## Algorithm

- Added FrameKernel's experimental `frame_kernel_head_sticker` C ABI for an opaque square sticker. It returns required source extents and a separate square placement, not a mask ellipse. The existing conservative face-mask API and identity tracker retain their prior behavior.
- Trace an upper connected foreground run near the current owned head seed; reject competing origins, disconnected/skinny runs, or unreliable shoulder transitions. Require sustained widening to stop before the shoulder/arm branch. A partial upper cap needs a direct face observation; estimated heads require the full shape.
- Preserve direct measured and stabilized region extents plus qualified current support. Estimated body-box seed radii are localization priors, not direct coverage evidence: the stabilized region and qualified cap are used, preserving raw extent at the clipped top edge. This avoids undoing the existing weak-evidence size bound when a dancer raises their arms.
- Calculate the minimum enclosing axis-aligned square directly, instead of circumscribing the combined requirements by a circle and then the circle by a square. Prefer the legacy conservative center in the feasible center interval. Maintain sampling margin and independent temporal size state. Weak pixel motion translates the previously accepted square; it does not change identity or renew the direct observation lease.
- Preserve legacy direct support or the stabilized estimated seed when topology is not qualified. No subtraction using a neighboring mask or unknown ownership is introduced. The API is only used by the host replay at this stage, not by mobile production code.

## Replays

Baseline: c38f3e54 (S3). All inputs use the same CPU LiteRT model tensors and production FrameKernel tracking session. Every baseline/candidate tracking snapshot byte hash matches. The original numerical width/height ratio <=1.35 and relative x/y motion <=75 px are unchanged.

| Case | Rendered frames | S3 width ratio | S4 width ratio | S4 max relative X/Y | S4 qualified cap frames |
|---|---:|---:|---:|---:|---:|
| Original JPEG | 18/18 | 1.2463 | 1.3150 | 15.70 / 37.42 px | 14 |
| FFmpeg AVC input round-trip | 18/18 | 1.1590 | 1.1834 | 23.03 / 25.78 px | 17 |
| Direct-face dropout 6,7,8 | 18/18 | 1.2573 | 1.3150 | 13.46 / 39.61 px | 11 |
| Real reference, left ID 0 | 90/90 | 2.5924 | 1.2926 | not annotated | 59 |
| Real target, right ID 1 | 90/90 | 1.5457 | 1.4490 | not annotated | 74 |

Original 54 frame-evaluations: all target approximate head envelopes are completely inside the candidate square; neighboring approximate head intersection drops to 0 in all three variants (previous maxima 8.35%, 14.31%, 10.09%). Ellipse mask geometry is unchanged (worst original overlap 99.946% for the JPEG/dropout cases); the square audit is NOT a claim about real composited pixels.

Two additional local clips, first 90 frames each, different selected dancers, are identified by SHA-256 in s4-reviewed-head-samples.json. They may share choreography, not an independent external dataset. Eight visually marked source head rectangles and torso probes in each clip (not derived from runtime regions) were verified against fixture hashes. Left candidate 8/8 full approximate head rectangles (S3 7/8), torso probes 2/8 (S3 6/8). Right candidate 8/8 (S3 8/8), torso probes 0/8 (S3 6/8). This is sparse approximate geometry evidence, not full-frame face/identity ground truth.

Rejected iterations: V1 midpoint placement yielded AVC relative x error 82.69 px. Feasible-center selection restored motion accuracy. V3 holdout ratio regressed to 1.991 because a raw estimated body-box radius reentered the sticker requirement. Excluding unmeasured raw extent after the existing weak-evidence temporal bound (while retaining current cap/edge support) reduced it to 1.449. No blanket enlargement or fixed-size cap was adopted.

## Verification and reproduction

181 Rust tests/doc-tests passed, 0 failed, 1 ignored. Seven new focused Rust tests cover shoulder cutoff, mirrored raised arm, ambiguous cap origins, direct-only partial cap, weak radius inflation, minimal placement, and invalid ABI atomicity. ABI v1 smoke and formatting passed. Python scripts compile.

Use distinct S3/candidate libraries as documented for S3. From the repository:

```sh
python tools/privacy/replay_face_tracking.py --baseline S3_LIB --candidate S4_LIB --output original.json
python tools/privacy/audit_face_tracking_geometry.py --replay original.json --output original-audit.json
python tools/privacy/replay_face_tracking.py --baseline S3_LIB --candidate S4_LIB --fixture FIXTURE_MANIFEST --target-id 0 --output independent.json
python tools/privacy/audit_face_sticker_samples.py --replay independent.json --fixture FIXTURE_MANIFEST --labels reports/privacy-issue43-host-2026-11-27/s4-reviewed-head-samples.json --case reference_left --output independent-audit.json
cargo test --locked --manifest-path crates/frame-kernel/Cargo.toml
```

The other local clip uses case target_right and target ID 1. The fixture extraction is the same 30 fps, first 90 JPEG frames as S3. Source clip, manifest and individual labeled-frame hashes are recorded; no media is committed. All replay summary metrics, candidate traces and reviewed audits are in s4-summary.json.

## Remaining work

- Right-dancer holdout ratio 1.449 remains above the original affine-fixture bound; real size changes have not been separated from anomalous motion with full head labels.
- More complete independently labeled crossing, occlusion, scale-change, and clipping data are required. No assertion of privacy acceptance is made from rectangles, sparse probes, or host geometry alone.
- Later integrate sticker placement separately from ellipse mask/fill and verify actual opaque sticker compositing, cache lifetime, pixel bridge and renderer bounds on mobile. No Android/iOS app or device changes were made here.
- Prior iOS CI failure was at CocoaPods deployment install (dance_native spec checksum changed versus Podfile.lock); dependency repair is deliberately outside this host-algorithm stage. PR stays draft and Issue #43 open.
