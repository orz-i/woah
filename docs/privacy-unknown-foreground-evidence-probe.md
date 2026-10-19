# Woah: 39.733s protagonist-overlap ownership evidence (Debug only)

**Stage: instrumented, not visually accepted.** The baseline is
`8e003ca8018318edd1e0e5717b56a5d5a2952fe2`, which retained a
privacy visual failure at 39.733–39.900 seconds. The prior
`c259eb4` patch fixed the 46.233s ceiling ghost but its short
temporal foreground hypothesis removed only two additional original
Mask pixels at 39.767 and 39.800s. The last source-space trace has:

| PTS | Fresh protagonist confidence | Verified foreground core | Additional temporal |
| --- | ---: | ---: | ---: |
| 39.733 | 0.794 | 98 | 0 |
| 39.767 | 0.765 | 63 | 2 |
| 39.800 | 0.713 | 15 | 2 |
| 39.867 | 0.771 | 12 | 0 |
| 39.900 | 0.732 | 14 | 0 |

The protected root (ID 1) is independently current/observed at
39.733s. The unprotected protagonist is independently observed as
root ID 2. A low-confidence conservative-unknown fresh `SELECTED`
detection overlaps the fresh `UNSELECTED` protagonist instance.
Pixel ownership cannot be inferred from bbox IoU or confidence alone.
The old diagnostic ZIPs have per-pair statistics but do **not** contain
the actual segmented probabilities needed to determine the origin of
the visible grey block.

## New instrumentation

The Debug-only native `PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT` event is
recorded for a narrow, proven type of overlap:

- Fresh-primary FULL_BODY selection (current fresh YOLO detections).
- The target is a conservative-unknown `SELECTED` detection with
  confidence ≤0.45.
- The candidate is fresh `UNSELECTED` with confidence ≥0.65,
  foot-depth separation ≥0.30, and a genuinely observed unprotected
  Track root whose current bbox IoU to the candidate is ≥0.90.
- At most one matching target/candidate pair per rendered frame.
  The preceding `c259eb4` trace had 38 qualifying accepted pairs
  throughout the 46.3-second clip, including all five risk frames.

The event records no decoded video frames, no RGB, no raw Mask arrays
and no exact per-pixel ownership coordinates. Only source-normalized
**4×4 grids of nonnegative integer proto-pixel counts**, covering the
current protected target bbox, plus generic job ID, PTS and anonymous
detection/Track IDs.

Each grid has fixed top-to-bottom, left-to-right row-major order. The
independently computed categories include:

- selected target raw Mask pixels;
- current fresh protagonist Mask pixels and overlap with selected;
- actually observed protagonist Track Mask overlap;
- weak protagonist Mask evidence between bytes 39 and 127;
- current raw protagonist-vs-selected probability advantage;
- current ownership, fresh eroded core, accepted carve and verified
  temporal carve;
- selected target pixels **still retained** after per-target carving;
- those retained pixels that overlap a fresh protagonist Mask,
  or only weak protagonist Mask probabilities;
- pixels still protected by an **independent selected instance**;
- merged privacy pixels in the same source ROI, including merged
  protection that also overlaps the fresh protagonist.

For models whose instance soft mask extends outside its own detection
bbox, the event additionally stores **five whole-mask aggregate counts
outside that bbox** (raw selected, shared with current foreground,
retained effective selected, approved carve and temporal carve). This
prevents falsely concluding all protagonist masking was inside the
selected person's detector box. There is no pixel-address list or
shape raster in this data.

This distinguishes three materially different causes of visual
false masking, without changing any actual render decision:

1. **Missing foreground segmentation:** selected Mask remains where
   the current protagonist Mask is absent/weak; increasing current
   foreground carve there is not justified by existing evidence.
2. **Unresolved per-pixel ownership:** both instance masks cover
   the same proto region, but the probability margin or interior core
   proof rejects carving. A new instance-specific ground truth or
   stronger independently verified foreground signal is needed.
3. **Competing selected person:** the target may be carved correctly,
   but another selected instance still supplies protection at that
   source pixel. Removing the conservative-unknown mask alone cannot
   eliminate the grey block. Avoid clearing legitimate protected
   people behind the protagonist.

The evidence event is a **numeric diagnostic, not an ownership
classification or privacy validation**. In particular, 4×4 counts do
not identify which exact output pixel should be unmasked. No renderer,
shader, tracker, temporal evidence, recovery policy, texture upload or
post-crop operation has been changed in this stage. The
`PRIVACY_COVERAGE_DROP` and severe undercoverage alarms are unchanged.

## Reproduce on another machine

Build a **Debug APK containing this commit**, install it, and export
the same source media once in both original landscape and portrait
settings, keeping selected IDs 0, 1, 3, 4 and protagonist ID 2.
Save both regular Woah diagnostic ZIPs.

The numeric-only report can be produced without the original video:

```powershell
py -3 tools\diagnostics\analyze_privacy_overlap_evidence.py `
  --landscape "D:\Diag\landscape.zip" `
  --portrait "D:\Diag\portrait.zip" `
  --output "D:\Diag\overlap-ownership-report.json"
```

The analyzer strictly scopes every JSONL event by the current manifest
`pipeline_lifecycle_job_id`, deduplicates repeated events, validates
each bin total, compares source-coordinate grids between both output
modes, and surfaces all probes from 39.600–39.910s. It never claims
a privacy pass: the report always writes
`visual_privacy_acceptance = NOT_PROVEN`.
For a pre-instrumentation ZIP it reports
`NO_FOCUS_AUDIT_NOT_ACCEPTED`, **not a false PASS**.

To finish verification, also run
`tools/diagnostics/privacy_final_frame_review.py` on the two new
**actual final exported videos**, supplying the optional full Git
commit SHA, and compare the #1192–1197 frames side by side.
The source-space bin report determines what can be investigated
safely next; final-video RGB still determines visible quality,
identity exposure and the product decision.

Do not commit the exported PNGs, user's original video, or biometric
Mask tensors. This step needs only source-free numerical diagnostics
and, separately, the minimum user-approved final protected frames.

## Acceptance boundaries

- Both exports still decode/render/encode 1391 frames.
- No newly exposed non-protagonist, no global broadening of Mask
  carve, and no regression of the 46.233s stale-Mask fix.
- Source-coordinate numeric diagnostics remain equal between both
  output modes when their inference inputs are equal.
- The final 39.7–39.9s protagonist video overlap **remains
  NOT_ACCEPTED until actually reviewed after a subsequent fix**.
- Kotlin/native unit tests and APK compilation are unverified on this
  Mac (no installed Android JDK/SDK), regardless of passing Python and
  Flutter/Dart regression tests.
