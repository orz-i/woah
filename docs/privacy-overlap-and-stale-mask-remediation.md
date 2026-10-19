# FULL_BODY overlap + stale-mask remediations — 2026-10-19

Status: **code-level remediation pending Kotlin/Android and final-pixel A/B validation**.
This change must not be described as a passed final-video privacy audit.

## Grounded evidence

Reference build: `178065ae5b7161886b928ec5ca5871885fada7ba`,
OnePlus PLK110 Android 17, final exported 1280×720 and 810×1440 videos.
The local `WoahPrivateReview.zip` contained 50 final-export PNGs.
Only anonymous geometry, times and mask telemetry are recorded here;
the images and other potentially identifying user material are **not** checked in.

- **39.733–39.900s / #1192–1197:** the unprotected protagonist
  (identity root 2) becomes visibly masked along head/hand/shirt.
  A low-confidence (`0.268–0.406`) conservative-unknown fresh
  `SELECTED` detection has source bbox approximately x504–591,
  y280–505; a genuinely observed `UNSELECTED` root 2 corroborates the
  foreground fresh detection. At 39.733, that fresh protagonist has
  confidence `0.794`, normalized foot-depth separation `0.558`,
  98 current foreground-core proof pixels, but only 17 of the 82
  selected proto pixels were actually removed. Its current YOLO box
  later contracts downward (top ~294→344 px), allowing mask bleed into
  unsegmented protagonist regions. The exported images verify the
  visible bleed; neither the source video nor original mask tensors
  are available to prove a complete pixel-level foreground reference.
- **46.000–46.333s / #1380–1390:** the camera points toward ceiling
  lights while grey/ghost privacy regions remain visible. At 46.333
  protected root 1 was **178 actual frames unobserved**, yet in
  `LOST` with a freshly reset `missedFrames=1` following
  `REACQUIRING→LOST`. Its historical canonical Mask supplied 191
  source-visible proto pixels, and the completed GL input still had
  214. Root 0, unobserved only 12 frames, supplied 23; roots 3 and 4
  had no renderable mask. The visual evidence confirms the overall
  ghost error; it cannot alone label every erroneous pixel by Track ID.

## Fix A: observation-age budget, not state-age reset

`PrivacyOcclusionResolver.resolveMasks` now applies the same
`maxFallbackObservationAgeFrames` threshold to identity-selected,
unobserved tracked masks in **both** the fresh-primary and detector-empty
fallback compositions. Root 1 at age 178 no longer becomes renderable
simply because `LOST` state counter was reset to one. Identity slots
and canonical/recovery material are preserved for future credible
reidentification; this change is compositor-only. Root 0 at age 12
continues within the existing 15-frame bounded fallback.

Source Mask audit explicitly reports
`tracked_fallback_mask_expired` and the budget alongside the historical
Mask bytes and actual merged GL input. A historical Track bbox or Mask
may therefore exist for identity/recovery while contributing nothing
to protected output. This avoids changing Kalman/coasting and the
FACE_ONLY-only execution path.

**Risk boundary:** A person undetected for longer than the configured
fallback age could still physically remain visible. Existing detector
fresh-class protection or a new current observation remains essential;
the algorithm cannot infer human visibility merely from a stale track.
Do not label this correction as guaranteed privacy in all scenes.
No generic time-based identity deletion or whole-video protection
disable is introduced.

## Fix B: narrowly corroborated *temporal* protagonist interior

The existing rule accepts foreground carving for a conservative-unknown
`SELECTED` target only when a genuinely **observed nonselected** root
matches its fresh `UNSELECTED` candidate at bbox IoU ≥0.90, and a
current eroded-core/ownership/raw-instance advantage confirms pixels.

A short export-scoped `VerifiedUnselectedForegroundHistory` now keeps
**only two previous, independent original masks** from a continuously
observed nonselected track (prefer the chosen protagonist). The source
bbox must remain consistent (IoU ≥0.60), the samples must be at most
100 ms old, and source/proto sampling contracts must match. The
snapshots are copied, bounded and cleared on a fresh-observation gap;
they are not injected into TrackManager or a new privacy identity.

Only when the **current** conservative-unknown target has
`residualTrackIds=[]`, confidence ≤0.45, the independently observed
foreground has confidence ≥0.65, normalized footY separation ≥0.30,
a nonempty currently verified foreground core, and the previous
history matches the same ID, does the resolver additionally remove
the **eroded intersection** of previous foreground Mask interiors
where *both* previous masks have probability ≥192/255. It never
removes an entire unknown detection or all pixels in its bbox. The
unknown detection remains `SELECTED`, and any independently protected
person may still own the merged output pixel. Existing
`PRIVACY_COVERAGE_DROP` / severe alarms are preserved unchanged.
Only the actual protected output may determine visual efficacy.

This is not a perfect instance segmentation model. Moving silhouettes,
partial source masking and YOLO boundary failures outside the stable
historical foreground interior may remain visible. If a real selected
person changes position into that historical interior, a false
foreground carve is possible despite the narrow checks. Hence
**new actual final-video comparison is mandatory before acceptance**;
if a selected non-protagonist is exposed, roll back this temporal
foreground refinement immediately rather than relaxing privacy gates.

## Validation and deployment boundary

New deterministic Kotlin unit tests cover stale selected root age 178
with reset state counters, recent root age 12, actual new observation,
matched temporal protagonist interior, wrong identity, weak prior
masks, continuity/source mapper mismatch, and historical buffer
independence. A Python static geometry/contract test guards the
provenance and call chain, nonweakened coverage warnings, and
source-first privacy-before-crop behavior.

Mac provides Flutter/Dart and Python regression capability but no
usable local Android JDK/SDK. Native Kotlin tests/APK were **not
executed** in this environment. No original YOLO mask tensors from
the risk frames exist in the diagnostic ZIP, so a passing synthetic
test cannot guarantee actual overlap removal.

Retest both full exports with the new APK on the same original
source and selected IDs. Review:
- **#1191–1197 / 39.700–39.900s:** protagonist's uncovered hair,
  head, hands and white clothing, while all four selected
  non-protagonist people remain protected; watch for a fresh
  `temporal_foreground_core_pixels` diagnostic.
- **#1379–1390 / 45.967–46.333s:** no historical Mask on ceiling or
  lights; retained short-term root 0 coverage must still be
  reasonable. Verify the end-frame source-space GL input is now
  primarily short-age root 0, not old root 1.
- No `FULL_BODY_FRESH_CLASS_CONTRADICTION_FAIL_CLOSED`,
  `FULL_BODY_PRIVACY_MASK_MISSING_FAIL_CLOSED` or
  `PRIVACY_GL_RENDER_ERROR_FAIL_CLOSED` on a healthy export;
  compare horizontal/portrait tracking and class signatures.
- If either mode reveals a protected person uncovered or
  uncertainty remains about visible identity exposure, mark
  `NOT_ACCEPTED`, preserve the saved frame/PTS and reassess.

Never commit/upload raw source footage, human-identifiable PNGs or
biometric tensors in the source repository.
