# Follow camera, Kalman tracking, and privacy safety

## Scope and invariants

Woah follows a user-selected protagonist after applying all person/face
privacy effects in source coordinates, before portrait cropping. Camera motion
is **not** a privacy identity assignment. The TrackManager's protected roots,
privacy-selected IDs, protected masks, and compositing order may not be
relaxed for more active framing.

- Keep `FollowCameraIdentityHistory`: any ID ever **observed concurrently**
  with the selected root is a negative identity for the entire export. That
  applies to strict camera handoff and both new/cached occlusion proxies.
  Duplicate detections are also ambiguous; they cannot authorize handoff.
- Short-window camera identity handling and
  `FollowCameraTemporalRecovery` remain strictly camera-only. Later handoff
  requires three real observations over at least 250 ms inside the bounded
  1.1–2.6 s window, and cannot use historical co-observed IDs or privacy IDs.
- Keep `FollowReservedLostRecovery` as a bounded fail-closed fallback for
  a recently lost, unprotected root whose real detector box was quarantined
  by a different occlusion group. It requires repeated uniquely supported
  mask/bbox evidence and never exposes the reserved detection to unrelated
  identities. Camera-only continuity after a proven recovery cannot assign
  or modify privacy roots. Retire this remaining fallback **only after** a
  real-device Kalman acceptance run demonstrates it is redundant.
- If identity evidence cannot uniquely prove the protagonist, the camera
  remains **HELD**. A stationary protagonist makes a stable crop reasonable.
  Reducing the count of HOLD samples is not an acceptance goal.

## Diagnosis: PLK110, 2026-10-18

The real source protagonist remained in view around 18 seconds, but ID 2
experienced a detector-box contraction/fragment and later tracking loss.
Follow camera must not hand off to ID 8: the two IDs were observed together
earlier in the video (5.800, 13.400, 13.533, 13.567 s).

The `570d6f9` export provided 529 committed source-space bbox observations
for ID 2. At 18.833 s the detector reported a bbox around **291 px high**,
while the Kalman prediction was only **211 px high**. This was not primarily
a missing YOLO detection: the source-space filter could not keep up with
observed bbox height and center changes. The prior
`KalmanFilter.predict` scaled its **standard deviations** for process
noise directly by `dt` in seconds (roughly 1/30 each frame), making
covariance too small. New measurements then carried insufficient weight
after many frames, allowing stale size/center predictions.

The corrected prediction uses process covariance based on **reference-frame
steps** (`dt * 30`). The process standard deviations scale as
`0.50 * sqrt(dt * 30)`, with velocity represented in pixels/second.
The conservative 0.50 factor smooths short pose noise rather than fully
following every detector fluctuation. This is a model fix for all tracks,
not a follow-specific identity exception.

A self-contained anonymous-geometry replay of the 17.000–18.833 s bbox
height channel shows **37.0 px → 7.8 px RMSE** with the new process
noise calibration. The new replay's last predicted bbox height is
about 281 px, much nearer the 291 px detector observation. An additional
Kotlin JVM test uses the same 54 anonymous bbox heights and centers and
checks prediction stability. This is offline evidence; it is not a real
Android export pass.

## Transient workarounds removed

The following exploratory fixes are removed rather than carried forward:

- One-frame overlap hysteresis (`5924af2`) for occlusion-group edge
  membership, together with its temporary tests. The normal group threshold
  is restored.
- The `FollowCameraWeakGroupOwnership` lane (`570d6f9`), which allowed
  a weak occlusion-group reservation to yield exclusively to the camera
  root. It introduced substantial per-frame special-case geometry and
  bypassed ordinary group reservation ownership. Its class, diagnostics,
  tests, and source contract are removed.

The earlier, **actually proven** bounded LOST recovery is retained as a
safety fallback until real Android evidence shows the calibrated Kalman
filter sustains ordinary tracking without it. The historical co-observation
exclusion remains mandatory. No SAM2, ONNX, Neural SR or new enhancement
strength is introduced. Crop clarity shader strength remains **0.55**.

## Validation

On the development Mac: run `python3 -m unittest
tools.test_kalman_process_noise_contract
tools.test_follow_reserved_lost_recovery_contract
tools.test_follow_camera_recovery_contract -v` and the existing Flutter app,
Native Dart and privacy/crop contract tests.

On the Windows Android workstation, verify Kotlin JVM tests and build a Debug
APK. The same PLK110 video should be exported once to check that source root
ID 2 is still observed around **18–23 s**, its camera does not follow another
person (especially historical ID 8), and protected output remains correct.
Track the events `GLOBAL_ASSIGNMENT_COMMIT`, `GROUP_ASSIGNMENT_COMMIT`,
`FOLLOW_RESERVED_RECOVERY_*`, `AUTO_REFRAME_ID_HANDOFF`,
`AUTO_REFRAME_SAMPLE`, and `PRIVACY_COVERAGE_DROP`. A successful
Android build and actual export are **not** assumed until observed.

The five prior privacy coverage-drop warning frames at about 30.833,
34.900 and 39.733–39.800 s remain a separate **visual privacy acceptance**
item. A/B crop-clarity sample quality is not evidence of full-video privacy.
