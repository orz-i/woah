# FULL_BODY late detector gap: GL-mask evidence and fail-closed threshold

Context: Android 17 / OnePlus PLK110 exports on 2026-10-19.
Baseline HEAD `1b00df6490b12e45d9a3453d5f7e81940fc80be0`.

Source diagnostics (manifest-scoped, using each individual `job_id`):

| Build | Mode | Job ID | Frames (decoded/rendered/encoded) |
|---|---|---|---|
| `3255595` | Landscape 1280×720 | `job_1792368553650` | 1391/1391/1391 |
| `3255595` | Portrait 810×1440 | `job_1792368911196` | 1391/1391/1391 |
| `1b00df6` | Portrait 810×1440 | `job_1792372223211` | 1391/1391/1391 |
| `1b00df6` | Landscape 1280×720 | `job_1792373297603` | 1391/1391/1391 |

All four manifests identify their individual jobs; diagnostic JSONL may contain
historical session data, which is not a second current export. These ZIPs
include no final protected video frames at the risk timestamps.

## 39.733 s

Both current modes retain exactly one `PRIVACY_COVERAGE_DROP` each:
the selected conservative-unknown detection loses 17 of 82 proto-mask
pixels (65 remaining, 79.268%); the current observed, nonprotected ID 2
corroborates the foreground candidate. The pixel proof guard was active.
There is no current evidence that those exact pixels were exposed in the
export. Do not suppress the warning, increase thresholds or alter masks
without output-pixel evidence.

## 45.967–46.333 s

The terminal clip has 11 successive frames with empty fresh YOLO results.
In the 46.333 s source-space trace, protected root 0 is OCCLUDED and 12
frames unobserved; roots 1, 3 and 4 have LOST states with much older
observations. A historical bbox still appearing in the tracking signature
**does not** prove a person is visible and **does not** establish whether a
Mask survived the privacy compositor.

On inference misses, the export invokes TrackManager's prediction/mask
fallback. The privacy resolver uses selected non-null tracked masks when
fresh evidence is unavailable. However, the existing diagnostic did not
report the **actual merged Mask bytes after resolver decisions and tight
contour thresholding**, only tracked bboxes and states. A selected tracked
root can still have a null or a zero-area mask. No native trace or source
code alone can prove that all final pixels are anonymized.

### This change

1. For a **currently observed** FULL_BODY root under fresh-class-primary,
   a matching fresh SELECTED detection must carry a Mask with at least
   one proto pixel **≥128**. The prior guard checked only non-nullability.
   Below-threshold or all-zero fresh selected Mask geometry now trips the
   existing `FULL_BODY_FRESH_CLASS_CONTRADICTION_FAIL_CLOSED` critical
   event, **without** transferring protected identity. The existing
   observed-root invalid-mask gate remains unchanged.
2. For actual detector-inference misses (not intentional inference-stride
   skips), Debug FULL_BODY-only export now samples the source compositor
   on the first 12 consecutive misses and every 30th miss after that.
   Sampling is 0-work on normal observed frames and absent from Release.
3. `FULL_BODY_SOURCE_MASK_UPLOAD_AUDIT` records `job_id`, `pts_us`,
   clarity scale, source/render dimensions, selected root IDs and states,
   last observation ages, source bboxes, each tracked Mask's count of
   pixels ≥128, the **final resolved privacy and occluder mask** counts
   handed to the GL upload call after tight thresholding, along with
   dimensions and the **actual GL sampling rectangle** (including the
   renderer's letterbox fallback). The audit also counts thresholded
   proto-texel **centers inside that source sampling rectangle**, separately
   from all thresholded proto pixels. Thus a nonempty mask entirely outside
   the source sampling region cannot be mistaken for on-screen input support.
   These counts are not exact output pixel coverage: GPU bilinear filtering
   and actual content visibility are not measured. Null, malformed, and
   zero-area masks are represented separately. Neither the source image nor
   any identification data beyond anonymous root IDs and geometry are stored.
4. This event states `GL_TEXTURE_INPUT_ONLY_NO_FINAL_PIXEL_READBACK`.
   GL draw submission or texture-input evidence does **not** verify
   executed shader output or final encoded pixels. No readback/JPEG work
   is added and the protection-before-crop order is unchanged.

Full-body-only coverage is intentional. Mixed FULL_BODY/FACE_ONLY rendering
uses different face and source privacy paths; this change does not modify
their rendering/identity arbitration. LOST and OCCLUDED tracks that are
not freshly observed retain their existing bounded fallback policy; no
global fail-close on an empty scene is inferred from stale boxes.

## Performance diagnosis kept separate

Exact `pipeline_summary` stage statistics from each job:

| Stage, average ms | 3255595 landscape | 3255595 portrait | 1b00df6 landscape | 1b00df6 portrait |
|---|---:|---:|---:|---:|
| renderEffects | 18.51 | 17.25 | 30.45 | 30.40 |
| canonicalYuvToRgba | 45.56 | 46.47 | 81.69 | 78.73 |
| CPU MT4 YOLO LiteRT run | 58.91 | 44.05 | 71.45 | 68.46 |
| GPU YOLO pipeline total | 21.38 | 20.36 | 19.53 | 21.00 |
| privacyClassTracking | 1.73 | 1.52 | 2.60 | 2.68 |

CUDA or native GLES execution time is not measured by the host.
The CPU conversion and the independent CPU MT4 lane also slowed while
GPU YOLO remained broadly stable; this does **not** isolate privacy-mask
composition as the cause. The existing fail-closed check added in
`1b00df6` runs before `renderEffects`. Temperature, CPU frequencies,
throttling and background device load were not captured. Do not infer
definite thermal throttling or revert privacy safety from averages across
different export runs.

## Regression and remaining acceptance

Kotlin synthetic tests include zero-byte, 127-byte and 128-byte selected
masks under a freshly observed protected identity, as well as per-root
and uploaded-mask contour counting, null vs empty distinction, on-source
proto-center counting (including off-source-only masks), source
mapping and sampling cadence. Python contracts assert the Kotlin guard,
Debug-only GL telemetry, existing area-loss alarms and
source-protection-before-crop order.

Mac regression in this round: **39** applicable Python unit/contract tests
passed, **14** diagnostics tests ran successfully (**4 skipped** by their
optional-data preconditions), Flutter App **75**, dance_native Dart **5**,
and dance_domain Dart **27** tests passed. Static analysis of these three
Flutter packages reported no issues. The added Kotlin unit tests were
written but **not executed**.

Mac Flutter/Dart tests and Python contracts cannot verify Kotlin/JVM or APK
build success; there is no usable local Android JDK/SDK. Final device
verification requires a Debug APK of this revision and a single new export
per mode. For the last 11 frames check:
- each `FULL_BODY_SOURCE_MASK_UPLOAD_AUDIT` record for the actual job;
- which selected root masks are nonempty; whether the final merged
  protection input is null or has zero pixels;
- source-to-output mask mapping consistency; and
- **actual video pixels** at 39.700–39.833 s and 45.900–46.333 s.

A final video visual check is still required even if the GL input mask
is nonempty and all security alarms are absent.
