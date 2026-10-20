# 5491f62 Android export: strict foreground experiment retirement

Scope: diagnosis of 2026-10-20 Android 17 dual-mode FULL_BODY runs. No
source image, final output screenshot, full video, or binary segmentation
tensor is present in this document.

## Verified builds and independent jobs

APK SHA: 5491f623421f54c489ba222c0533d14df50d772d
Portrait job: job_1792462842538 (810x1440)
Landscape job: job_1792462379139 (1280x720)
Original source: 1280x720, 30 fps.
Both completed 1391 decode / 1391 render / 1391 encode frames.
Both production Track signatures and privacy-class input signatures match
each other for all 1391 source-frame PTS. No severe undercoverage or
protected export fail-closed event was found. The single
PRIVACY_COVERAGE_DROP remains at 39.733333s: selected raw 82, effective 65,
coverage ratio 0.79268295. Do not interpret this as a privacy PASS.

At 46.333333s, each output's GL-input audit still shows only 23 source-visible
sampled privacy proto pixels (41 contour proto pixels before source clipping).
The protected root ID1 mask is stale by 178 frames and excluded from actual
upload, so the earlier ceiling ghost fix has no LOG-LEVEL regression.
The new ZIPs do NOT contain final-video RGB pixels to visually revalidate
39.7s or 46s.

## Six risk frames: source-proto evidence

These are anonymized scalar counts, NOT RGB or masks.
The values are identical in both jobs.

| PTS s | Conservative unknown count >=128 in bbox | Both foreground masks >=192 with unknown >=128 | Both foreground masks >=224 with unknown >=128 (after other-protected veto) | Strict gate | Strict new carved pixels |
|------:|------:|------:|------:|:----------:|------:|
| 39.733 | 81 | 24 | 6 | eligible | 0 |
| 39.767 | 141 | 18 | 2 | eligible | 0 |
| 39.800 | 160 | 0 | 0 | eligible | 0 |
| 39.833 | 149 | 0 | 0 | bbox + core fail | 0 |
| 39.867 | 142 | 0 | 0 | core fail | 0 |
| 39.900 | 140 | 0 | 0 | core fail | 0 |

Across the *entire* 46.366s clip, each job produced 41 unknown
foreground-overlap audit events, of which 17 passed the strict geometry,
track-age and confidence gates. The remaining 24 failed one or more
gates. Sum of pre-erosion selected+fresh+observed foreground >=224 pixels
across those 41 frames is only 25; max in any frame is 8, and none are
vetoed by another selected instance at that intensity. The strict
carve required at least 9 strong pixels BEFORE eroding radius one, so
**the strict experimental carve was mathematically incapable of contributing
any additional output pixel in this fixture**. The verified fact that 17
gates passed rules out root ID age or depth as the principal failure.

The source/previous final-video comparison already confirmed the protagonist
remains visibly in the scene at 39.733--39.900s while grey masking covers
upper hair, arms and white clothing. From 39.833 onward even the weaker
192 threshold has ZERO overlap with the unknown-selected instance. This
is a source-frame instance segmentation/protagonist ownership evidence gap,
not a missing class threshold tweak.

## Engineering decision

RETIRE the 2-candidate StrongObservedForegroundCarve code and its unused
success event and Kotlin synthetic tests. Preserve the previously
accepted conservative-unknown mask policy, root identity isolation,
temporal evidence path, GL fail-closed and stale-mask expiry. Restoring
PrivacyOcclusionResolver's pre-experiment composition recovers the
well-understood prior rendering policy; it does not itself resolve the
protagonist overmask, which is still NOT ACCEPTED.

KEEP the Debug-only 4x4 overlap audit and the broader selected/fresh/
observed >=192 and >=224 integer totals, including a veto for other
fresh SELECTED instances. These numbers are useful for selecting a
different, independently corroborated segmentation method and do not
change rendered output. Historical analyzers continue to parse legacy
5491f62 per-gate fields when supplied.

Do NOT lower the 224 threshold, 9-pixel minimum, or other protections
based solely on the present data: that experiment is being removed, not
weakened. Do NOT classify root ID1 as UNSELECTED by proximity, or use
bbox-shaped cutouts. A protected person truly exists nearby.

## Correct future remedy

Any effective correction after 39.833s requires *new*, preferably
source-frame photometric or segmentation instance evidence beyond the
existing YOLO/Track masks. A plausible next direction is separately
validated source-frame optical-flow / appearance-guided protagonist
foreground correspondence and a protected-instance occlusion veto.
Before integrating that into the protected export, test against a
locally reviewed overlap fixture containing clear protagonist vs
protected-person pixels and negative crossings, with exact output-frame
visual review. The implementation must not infer this missing
pixel identity solely from predicted boxes, age, footY or a reused
detection mask. No such model/motion correction is implemented here.

Only a new user-reviewed protected output can promote a visual privacy pass.
