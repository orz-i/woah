# Woah c259eb4: exported-video visual retest (2026-10-19)

## Evidence identity and limits

This review examines two device diagnostic ZIPs and an *offline final-video
frame review* ZIP. No user video, photo, or mask pixels are placed in the
repository.

- Android 17, OnePlus PLK110; build commit:
  `c259eb46f9a396e1e0ab6a039c2001f5b9d62f0d`.
- Portrait 810×1440: `job_1792398299045`.
- Landscape 1280×720: `job_1792398716580`.
- Both exports completed with 1391 decoded/rendered/encoded frames,
  GPU YOLO, no fallback and no severe undercoverage or fail-closed errors.
- Each mode still has one `PRIVACY_COVERAGE_DROP` at 39.733333s:
  selected conservative-unknown detection #2, 82 pre-carve proto pixels,
  65 effective proto pixels, ratio 0.79268295. **Do not suppress this.**
- Each mode has 1391 source-space production tracking and
  FULL_BODY privacy input signatures. The two modes agree on the
  risk-frame foreground decision: observed unprotected ID 2 corroborates
  the fresh UNSELECTED candidate.

The review ZIP contains 50 valid PNGs (25 per mode; 9 overlap and 16
end-of-video frames each), with frame-index / PTS records and valid ZIP
CRC. Both output-video SHA256 hashes changed from the previous exported
version. Those are identifiers of the provided final videos only:
the MP4s are not cryptographically linked to the diagnostic job IDs.

**Important evidence metadata caveat:** the review ZIP's
`manifest.expected_commit` was still hardcoded to the prior
`178065a` revision, despite both accompanying diagnostic manifests
reporting `c259eb4`. This is a *review-tool provenance-label defect*,
not proof that the APK used the old commit. The reviewer has been
corrected to default `expected_commit=null` unless the operator
explicitly supplies a full 40-character SHA; when supplied the field is
marked `OPERATOR_ASSERTED_NOT_VERIFIED_FROM_VIDEO`.

## Outcome A: stale Mask on ceiling — visual PASS in sampled windows

At 46.333s the new `FULL_BODY_SOURCE_MASK_UPLOAD_AUDIT` shows
23 source-visible proto pixels in the final uploaded privacy Mask,
vs 214 at the previous build. History root ID 1 is 178 true
observation frames old, remains internally present with a stale
191-pixel Mask but has `tracked_fallback_mask_expired=true` and is
excluded from the composed output. Recent root ID 0 (12 frames
unobserved) still supplies 23 source-visible pixels.

At #1387 (46.233333s), the large grey block previously covering
the ceiling and light strip visibly disappears in *both* landscape
and portrait final output. From #1380 through #1390 the fresh output
has no comparable grey ceiling ghost. This provides final-pixel
evidence that the stale full-body Mask resurrection was addressed
in these sampled frames.

## Outcome B: protagonist masking at 39.733–39.900s — FAIL

The exported frames still visibly anonymize portions of the
unselected protagonist's white sleeve/upper torso, arm and hair/hand
region in both modes. The new temporal interior mechanism accepts
only **2 proto pixels** at 39.766667s and **2 proto pixels** at
39.800000s, and zero at 39.733333, 39.866667 and 39.900000s.
This is far too small to repair the visible false masking.

Pixel comparison of the old `178065a` review PNGs against the new
`c259eb4` review PNGs:
- #1189–1192: identical PNG bytes in both modes.
- #1193 (39.766667): 205 landscape / 861 portrait pixels with
  max-channel difference ≥24, only ~0.02% / ~0.07% of each frame.
- #1194 (39.800000): 221 landscape / 881 portrait such pixels,
  only ~0.02% / ~0.08%.
- #1195–1197: no pixel with max-channel difference ≥24; remaining
  deltas are small codec-level values.

Source-coordinate trace remains highly suggestive of a protection
classification/instance separation problem: at 39.733s a current
selected root 1 is observed around source x505–589, y280–501,
overlapping the observed unprotected protagonist root 2; the
conservative-unknown synthetic selected detector index #2 has bbox
around x505–589, y280–501. The current foreground candidate has
confidence ~0.794, the unknown selected target only ~0.268,
but that does **not** by itself prove all overlapping pixels belong
to the protagonist. Do not convert a selected identity to
unprotected based only on proximity, and do not relax unknown-target
privacy globally.

There is no definitive newly observed full-person privacy exposure
in these 50 sampled PNGs, but the privacy guarantee for all source
frames is not proven. In particular, avoiding protagonist false
masking must not expose non-protagonist people behind an overlap.

## Next bounded engineering step

Prioritize additional instance-level foreground-vs-selected-mask
evidence at risk frames, rather than larger unverified temporal
carves. Existing diagnostic ZIPs contain metadata and only
early-export inference tensors; **the actual per-instance 39.733s
probability masks are unavailable**. The next change should capture
a tightly scoped **Debug-only, consented, source-free mask-evidence
snapshot** or introduce deterministic overlap reconstruction from
synthetic mask fixtures. Any new carve must be proven to belong
to the unprotected foreground, preserve all competing selected
instance pixels, and be evaluated against actual final exported frames.

**Stage decision:** ceiling ghost retest passes; overlap retest fails;
overall privacy visual acceptance stays **NOT_ACCEPTED**. Do not
merge/promote the fix as fully accepted solely on the basis of export
completion or unchanged error counters.
