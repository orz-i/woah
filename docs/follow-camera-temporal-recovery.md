# Follow camera and Kalman identity safety

## Product rules

Woah applies face/full-body privacy in original video coordinates before the
portrait follow crop. Camera following must not change privacy-selected IDs,
identity-protected tracks, privacy masks, or protected-identity association.

Camera-only negative identity history is kept for the whole export: a track
that was ever observed concurrently with the selected protagonist may not
inherit its camera identity, even if it later overlaps the same bbox.
This excludes historical ID 8 in the PLK110 clip. The short-window strict
camera handoff and 1.1–2.6-second bounded temporal handoff retain their
strict checks and co-observation exclusion. When identity is ambiguous the
camera **HOLDS** the last crop, even if the protagonist is stationary.

## Why the Kalman fix is necessary

On PLK110 with commit `e37311c` (October 18, 2026), two exports of the
same 1391-frame source used different output profiles:

- Portrait follow export: ID 2 was last observed at 17.867 s. Its
  LOST prediction shrank from about 261 px to about 53 px between 17.867
  and 18.367 s, even though YOLO still detected nearby body geometry.
- Non-follow landscape export: ID 2 remained observed until 19.967 s.
- Two CPU shadow tracking runs remained deterministic to 23.533 s.

The previous `e37311c` process covariance calibration improved box shape
response *while observations were committed*, but did not stop the learned
negative height velocity (`vh`) from being extrapolated every frame after
the root entered LOST. This is not evidence that the protagonist left
the scene, and fewer HOLD samples is not an acceptance goal.

## Corrected LOST prediction

`KalmanFilter.predict` now accepts an explicit
`coastUnobservedShape` argument. **Only when a track was already LOST**
does the tracker enable this option. LOST prediction sets shape/aspect
velocities (`vh`, `va`) to zero and gradually damps center velocities.
It keeps the current size estimate rather than repeatedly reducing the
bbox toward zero. Covariance still propagates and grows normally; a
future *genuine* observation can correct the prediction. Normal ACTIVE
predictions and Kalman measurement updates remain unchanged.

Both the detector-bearing `TrackManager.update` path and the
prediction-only path use the same LOST coasting policy. Protected masks
and identity confidence gates are neither relaxed nor reassigned.
Kotlin deterministic tests cover the PLK110 collapse geometry, continued
size preservation, subsequent genuine size measurement, and normal
observed motion.

## Follow/landscape tracking parity

The last camera-specific production-tracking workaround was removed:
`FollowReservedLostRecovery` (commits `1190aac`, `f196634`).
It allowed a follow selection to recover a reserved detector observation
through a special TrackManager lane; it caused source-track histories to
diverge between portrait-follow and non-follow exports despite identical
inference inputs. The helper class, associated special commits/telemetry,
tests, Python source contract, and ExportPipeline opt-in are removed.

Earlier temporary workarounds (group edge hysteresis and weak group
reservation yielding) were already removed in `e37311c`. The
production TrackManager now has no camera follow-target setting; the
camera-only safety and temporal recovery logic remains in ExportPipeline,
with strict historical identity exclusions. The selected protagonist is
never promoted to a privacy-protected root merely to keep the crop moving.

A Python regression asserts this mode separation and reproduces
negative-height-velocity coasting. Identical *input detection streams*
should therefore take the same original tracking path regardless of the
chosen output crop. A separate Android device run is still required to
confirm actual per-frame production tracking parity.

## Validation boundaries

Use existing Flutter/Dart and Python regression suites on Mac. Mac has
no usable Android JDK/SDK; these results are **not** evidence of Kotlin
JVM test execution or a native APK build.

When a Windows Android workstation is available, build Debug from this
commit and export the existing PLK110 video once per desired mode.
Inspect 17–23 s for correct protagonist identity and camera framing.
No camera identity handoff to historical ID 8 is acceptable. Check
privacy results independently, including the prior five
`PRIVACY_COVERAGE_DROP` events at approximately 30.833, 34.900 and
39.733–39.800 s. Crop clarity shader strength remains **0.55**,
without Neural SR, SAM2, or ONNX restoration.
