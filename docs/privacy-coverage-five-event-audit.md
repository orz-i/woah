# Five FULL_BODY privacy coverage drops — PLK110 source-space audit

Baseline: `9502027d412985a6c7bb23609fde61a4821bd1f0` (Android 17,
OnePlus PLK110, 2026-10-18). The two independently completed exports had
identical 1391/1391/1391 decode/render/encode frame counts.

## Source and job isolation

The two diagnostics ZIPs share session `20261018_144754_30492f72`.
Their JSONL shards contain historical events; **do not count all events
within the second archive as belonging to its export**.

| Export target | Job | Relevant warning frames |
|---|---|---|
| 810×1440 portrait post-crop | `job_1792334883122` | 5 |
| 1280×720 uncropped landscape | `job_1792335324731` | 5 |

Both have `selected_ids=[0,1,3,4]`, `face_only_ids=[]`; track ID 2 is
not protected. Filter the logs using their tagged
`FULL_BODY_PRODUCTION_TRACK_SIGNATURE` / `FULL_BODY_PRIVACY_INPUT_SIGNATURE`
and the export wall-time boundaries. The second ZIP includes the first five
warnings as historical JSONL plus five from its own job.

There are no full-resolution exported video frames, selected/candidate
per-pixel prototype masks at these times, or post-shader RGBA readbacks in the
diagnostics. The early inference RGBA and tensor captures cover only initial
frames. A warning does **not** prove an exposed human pixel, and geometry
cannot prove absence of exposure.

## Event fields and common mechanism

All five current-export events have `state=ACTIVE` because the resolver uses
current **fresh YOLO detections as synthetic IDs** (`Int.MIN_VALUE + index`)
for class-primary composition. The target is
`PrivacySelectionClass.SELECTED` with `conservativeUnknown=true`.
It is *not* an identity commitment. The candidate is
`UNSELECTED`, `conservativeUnknown=false`, and its source-space box matches
a **currently observed, unprotected production track ID 2** (IoU ≥ 0.999
after diagnostic quantization). Every accepted subtraction uses
`FRESH_DEPTH_CORE`, with a fresh eroded unselected soft mask and positive
foot-Y depth evidence.

The `PRIVACY_COVERAGE_DROP` ratio is measured **per selected synthetic
instance**, *before* unioning remaining selected instances. It is
`effective_area/dilated_area`, using mask bytes >128, and its trigger is
`0.65 <= ratio < 0.85` (`PRIVACY_SEVERE_UNDERCOVERAGE` for <0.65).
For these FULL_BODY tightMask exports `raw_area=dilated_area`.
`PRIVACY_COVERAGE_DROP` currently has INFO log level despite its warning-style
name; severe undercoverage uses WARN. Both events remain enabled.
This metric measures a deliberate foreground cut from a target mask. It does
not establish whether the cut was correctly owned by foreground ID 2 or
whether final source or cropped pixels were protected.

| PTS (s) | Unknown det | Foreground det | Pre → effective | Removed | Ratio | bbox overlap | fresh depth-core | probability ownership |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 30.833 | 2 | 3 | 79 → 58 | 21 | .7342 | .8610 | 32 | 65 |
| 34.900 | 3 | 2 | 134 → 92 | 42 | .6866 | .6550 | 62 | 138 |
| 39.733 | 2 | 3 | 82 → 59 | 23 | .7195 | .7819 | 97 | 133 |
| 39.767 | 2 | 3 | 142 → 100 | 42 | .7042 | .6867 | 83 | 99 |
| 39.800 | 2 | 3 | 161 → 129 | 32 | .8012 | .5477 | 39 | 28 |

The `probability ownership` column is the full candidate ownership pixel
count, not an intersection certificate for the actual cut. Before this
change, **positive fresh depth core alone could authorize a cut even when
individual removed pixels lacked a raw unselected probability advantage**.
The renderer's union and shader-side occluder prevent some overlapping-mask
artifacts, but this does not validate the raw carved pixels.

In a ±0.5 s window around each timestamp there are 31 frame PTS values.
For 30.833 s, the window has 9 accepted unknown-vs-unselected candidate
pairs, 70 group reservations, and 2 generic reacquire starts; at 34.900 s,
4 accepted pairs, 50 reservations, and 1 start. For the 39.733–39.800
cluster, the shared three frames are one continuous synthetic target-2 /
candidate-3 event. In the 39.733-centered window there are 6 matching
accepted pairs, 28 reservations, and **no** reacquire start/timeout.
The cluster must not be treated as three independent identities or
as three independent causes.

The diagnostic logs establish the same **decision mechanism** at all five
timestamps; they cannot individually classify the resulting output pixels
as real exposure versus valid front-person occlusion. There is no evidence
that portrait-crop mapping initiated these warnings: source-space signatures
and mask/area decisions are identical in uncropped landscape.

## Safety correction

For a *fresh conservative-unknown target only*, the resolver now requires:

1. A fresh, confidently `UNSELECTED` foreground-class candidate;
2. An independently observed unprotected track with the same source-space
   candidate box (`bbox IoU ≥ 0.90`); this **does not** promote or transfer
   the unknown protected identity to that track;
3. Agreement of the eroded candidate core, existing probability ownership
   mask, and raw unselected-vs-unknown pixel probability advantage ≥0.10;
4. Compatible original source dimensions, proto-mask dimensions and
   sampling rectangles. A mismatch cannot authorize subtraction.

Unsupported pixels remain protected; the candidate still cuts clearly
foreground pixels when evidence agrees. No dilation increase, Kalman update,
follow-camera handoff, or crop coordinate change is involved.

An observed FULL_BODY root with a null, invalid-sized, or mask with no
bytes >=128 (including all-zero/too-weak) is now a critical
`FULL_BODY_PRIVACY_MASK_MISSING_FAIL_CLOSED` export error, rather than
silently rendering its exposed observation. FACE_ONLY unresolved handling
was already fail-closed and remains unchanged. The coverage thresholds and
both existing events remain unchanged; coverage loss that persists after
these conservative cuts is still logged.

The root guard uses the actual 128-byte FULL_BODY tight-mask cutoff; a
non-null soft mask below that cutoff would otherwise become empty at the
final GL texture upload. The existing per-target coverage area diagnostic
still counts bytes >128, a small threshold-boundary distinction.

The coverage events now include `job_id`, source detection index,
conservative-unknown flag and number of accepted occluders. Accepted-pair
events also include job ID, match ID and the number of corroborated pixels
for future device verification.

## Validation and remaining gates

The Android Kotlin synthetic suite
`PrivacyUnknownForegroundSafetyTest` replays all five source-space boxes
with synthetic soft-mask probabilities. It checks ambiguous pixels,
positive foreground ownership, missing/recent observation, REACQUIRING,
wrong class, wrong mask dimensions, duplicate selected detections and the
observed-root null-mask gate. Its raw mask probabilities are **synthetic**,
not recovered from the device ZIPs.

`tools/test_privacy_coverage_contract.py` checks the five real recorded
area ratios, candidate-to-ID2 bbox matches, source-level proof gates and
preservation of genuine area-loss warnings. Existing Python crop-first
contract tests validate source-protection-before-crop ordering.

Validation on this Mac: 35 applicable Python unittest cases passed;
`flutter test --no-pub` passed 75 app, 5 dance_native and 27 dance_domain
Dart/Flutter tests. `flutter analyze --no-pub` passed for those three
packages. The existing standalone dance_ui template package cannot resolve
flutter_test/flutter_lints and has stale Calculator tests; its test/analyze
gates were not clean and this unrelated package was not changed. The
full Python discovery suite has one import error in the optional prototype
model-tools test due to the host lacking numpy; the 35 applicable tests passed.

Mac Python/Flutter checks are not Kotlin, JVM or APK execution. A Windows
Android build and instrumented Kotlin tests, then new PLK110 exports of
both modes and a frame/pixel review around 30.833, 34.900 and 39.733–39.800 s
remain necessary. Review genuine protected people (including behind ID 2),
protagonist false blocking, mask holes, selected identity class continuity
and the compositor RGBA; do not pass privacy from the absence of warnings.

Remaining uncertainties: zero-area masks of *non-root fresh inferred* selected
detections, missing fresh masks for detections that cannot be classified without a mask,
long-unobserved root tracks, and the actual visibility of partly occluded
people. The new observed-null-mask export guard does not prove those cases
safe. Further device evidence is required before final privacy acceptance.
