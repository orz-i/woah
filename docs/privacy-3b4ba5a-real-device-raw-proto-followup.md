# 3b4ba5a Android 17: raw YOLO support follow-up and bounded motion feasibility

Status (2026-10-20): **39.733–39.900s NOT FIXED / NOT VISUALLY ACCEPTED.** The raw top-support Debug probe was later retired from the native runtime after real-device evidence did not support safe restoration; the following fields describe archived `3b4ba5a` exports and remain readable by the offline numeric analyzer.
Source space FULL_BODY behavior and GL composition were **not modified** in this follow-up. This report contains anonymous integers only. No source video, unmasked screenshot, RGBA/YUV diagnostic buffer, TFLite tensors, or raw segmentation masks were copied into this workspace or committed.

## Exact real-device evidence

Both ordinary Debug diagnostic ZIP manifests attest APK git SHA `3b4ba5a8259e6fae94ff140e27b5277c3b5079a5`. The jobs share original source 1280x720 @30fps:

| job | final output | decoded/rendered/encoded | effective YOLO |
| --- | --- | --- | --- |
| `job_1792482003863` | 1280x720 | 1391/1391/1391 | GPU; no fallback |
| `job_1792481557459` | 810x1440 | 1391/1391/1391 | GPU; no fallback |

Job-ID-scoped source-space comparison of both JSONL bundles:
- **1391/1391 exact match** for `FULL_BODY_PRODUCTION_TRACK_SIGNATURE`;
- **1391/1391 exact match** for `FULL_BODY_PRIVACY_INPUT_SIGNATURE`;
- **41/41 exact match** for `PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT` including raw-proto top-band counts;
- one identical `PRIVACY_COVERAGE_DROP`, at **39.733333s**: raw selected=82, effective=65, ratio **0.79268295**;
- 11/11 final-tail GL-input audits have equivalent source-space mask values. `crop_clarity_scale=1` vs `2` is the intentional mode difference.

**New current UNSELECTED detection index 3:** original model-prototype response in the up-to-12-row band *above its decoded box support* (this is NOT a mask of the protagonist):

| PTS seconds | sampled proto cells | raw >=128 | raw >=192 | raw >=224 |
| ---: | ---: | ---: | ---: | ---: |
| 39.733 | 300 | 0 | 0 | 0 |
| 39.767 | 288 | 7 | 2 | 0 |
| 39.800 | 288 | 34 | 15 | 1 |
| 39.833 | 288 | 34 | 15 | 4 |
| 39.867 | 288 | 25 | 8 | 0 |
| 39.900 | 288 | 37 | 10 | 0 |

Across all 41 unknown-person overlap audits, the **fresh UNSELECTED candidate** top-support probe has a nonzero outside >=224 count on just 3 events (11 cells total, maximum six in one event). In all six focus frames the conservative-unknown **SELECTED target index 2** has **zero** >=128 response *above its own box*. These distinct boxes/bands cannot be spatially equated, and no captured per-pixel truth proves foreground ownership.

Current privacy composition continues to protect the unknown:
- selected raw proto pixel counts at 39.733–39.900s: **81, 141, 160, 149, 142, 140**;
- retained effective selected counts: **64, 125, 157, 149, 142, 140**;
- selected target accepted-carve counts: **17, 16, 3, 0, 0, 0**;
- the previously counted both-foreground >=224 intersections: **6, 2, 0, 0, 0, 0**;
- other selected-mask veto inventory is complete in each focus frame (5 or 6 independent selected masks). A zero strong selected-mask-veto count is **not** proof that a protected person's physical pixels are absent.

The **only justified verdict** is that bounding-box-only expansion cannot reliably restore 39.8s protagonist hair, arm or shirt. The decoder does discard *some* positive raw logits at the contracted box top, but only **1–4** >=224 proto cells in two risk frames, and 0 in the other four risk frames. The origin of those few cells remains ambiguous. There is no basis for arbitrary bbox expansion, confidence lowering, stale-mask extrapolation or selected-ID1 downgrading.

## 46-second safety regression

Across both exports at 46.333333s, selected root ID1 is 178 frames unobserved; its old Mask is marked `tracked_fallback_mask_expired=true` and contributes **nothing** to the effective GL upload. Actual GL-upload source-visible sampled privacy proto pixels = **23**, matching recent root ID0. The prior ceiling ghost repair shows no **LOG-LEVEL** regression. There is no final-output RGB in the read JSONL; do not call this fresh visual acceptance.

## Independent motion/appearance research gate

The existing mobile `FacePixelMotionTracker` implements short-lived NCC-style appearance matching for a **face center**, not a full-body segmentation or independent per-pixel body identity. TrackManager's Kalman bbox/occlusion group can gate search windows and root continuity but cannot authorize unmasking a pixel.

The new `tools/diagnostics/research_full_body_motion_correspondence.py` is a **pure Python, synthetic-only research tool**. It has no source-video file reader, image serializer, Android caller, exported silhouette or GL hook. It tests one sparse seed point using:
- two prior independently observed samples of the **same** protagonist root (as an explicit external precondition);
- bounded source PTS gap and same source-gray geometry;
- texture-energy and normalized correlation score;
- unique displacement peak, reverse-frame correlation and a local photometric check;
- a current conservative-unknown-target ROI restriction, independently protected Mask one-cell veto, and independently observed protected bbox veto (a box can only reject, never approve unmasking);
- an explicit requirement that the independent protected-occupancy census is complete; missing or invalid evidence rejects the correspondence.

Synthetic tests demonstrate that a distinctive moving patch can be matched and that uniform clothing, repeated textures, missing timeline evidence and independently protected overlap are rejected. **Crucial counterexample:** a new non-protagonist bearing an **identical** patch can pass NCC, uniqueness and forward/backward checks if an external caller incorrectly claims protected occupancy is complete while omitting that person's bbox and selected Mask. When occupancy is marked incomplete, or a live protected bbox is supplied even without a Mask, the synthetic gate correctly rejects. The hard part is **independently proving the census is complete in real occlusions**; no such proof exists in these diagnostics. This is an unavoidable proof gap for optical flow alone, even with short Kalman continuity. The harness explicitly classifies an accepted match as `SHADOW_POINT_MATCH_ONLY`, never as safe foreground/cleared privacy.

**Decision:** retain this research gate as an adversarial falsification harness, not a proposed FULL_BODY carve. Do not add it to Android export, generate new device video captures, or infer an output privacy PASS from synthetic tests. Before exploring a real source-luma shadow probe, require an externally validated same-frame protected occupancy/occlusion census that can justify `protected_occupancy_complete` despite under-segmentation and overlapping dancers. If such a claim cannot be justified, the verifier must return `INCOMPLETE_PROTECTED_EVIDENCE` for the entire candidate. Validate this against adversarial identical-clothing crossings. If that veto blocks the entire current collision, that is preferable to releasing protected pixels. Any change to production masks still needs actual final-video privacy review and independent pixel-ownership evidence.

The 39.733s visible grey overlay remains **NOT_ACCEPTED**, while the 46s old-mask fix remains untouched.
