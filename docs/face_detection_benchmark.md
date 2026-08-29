# Face-only anonymization benchmark

## Status

- SAM2: **BLOCKED**. Do not advance or unhide it.
- YOLO: the only stable person detection/segmentation and identity baseline.
- Production anonymization: unchanged full-body path.
- Face detector: instrumentation benchmark only; no preview/export runtime call site exists.

## Goal

Measure whether a lightweight face locator can be added as a positional sidecar
without creating a second identity tracker. YOLO/`TrackManager` remains the sole
owner of person identity. Face detection is now evaluated as a **source-resolution
person-head ROI operation**, not as another full-frame detector.

The first backend is MediaPipe BlazeFace full-range on CPU. The benchmark backend
is hidden behind `FaceBenchmarkBackend`, so an ML Kit implementation can later be
run against the identical decoded frames and reporting schema.

On the OnePlus PLK110 running Android 16, `RunningMode.VIDEO` initialized normally
and completed ten calls in roughly 9-20 ms each, then synchronously stalled on the
11th `detectForVideo()` call. This project does not need MediaPipe-owned temporal
identity, so the benchmark uses stateless `RunningMode.IMAGE` instead. YOLO and
`TrackManager` remain the only temporal/identity authority.

## Isolation guarantees

`com.google.mediapipe:tasks-vision:1.0.0` is declared with
`androidTestImplementation` only. The model and image fixtures are configured as
`androidTest` assets only. No production `implementation` dependency, profile,
Pigeon DTO, preview path, export path, renderer, or shader is changed by this stage.

## Model fixture

The benchmark uses the official BlazeFace full-range float16 model at:

`testdata/models/face/blaze_face_full_range.tflite`

Expected SHA-256:

`3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`

The instrumentation test checks the packaged asset hash before initialization.

## Full-frame control result

The original `01_sample.mp4` is 3000x6534 FMP4. A OnePlus/Android 16
`MediaMetadataRetriever` could read its metadata but returned no decoded frames,
so codec support would contaminate an inference benchmark. The committed fixture
therefore contains deterministic 640x640 JPEG letterbox frames extracted from all
30 source frames under `testdata/face_benchmark_frames`.

The 30-frame 640x640 fixture is still useful as a control. MediaPipe
`RunningMode.IMAGE` completed all 30 calls on the PLK110. After dropping three
warm-up calls, the observed CPU latency was approximately:

- mean: **9.49 ms**
- p50: **8.96 ms**
- p95: **11.71 ms**
- max: **15.18 ms**

However it detected **0 faces in all 30 frames**. The known YOLO person boxes in
the letterboxed input are only about 20-26 px wide and 51-66 px tall, so a face is
reduced to roughly single-digit/low-teen pixels. Full-frame 640 face detection is
therefore rejected as the production architecture for distant dancers.

The YOLO-only control also completed all 30 fixture frames. One instrumentation
run contained a ~107 s GPU outlier at frame 15 before resuming. This does not match
the already validated production YOLO export behavior, so instrumentation YOLO
latency is treated as diagnostic only and is not used to redefine the stable YOLO
baseline.

## Source-resolution ROI benchmark

The proposed face path keeps YOLO as identity/geometry authority, then preserves
face detail by cropping from the **original source texture** before downscaling:

1. YOLO/`TrackManager` provides the target person bbox and ID.
2. `FaceHeadRoiPlanner` plans a square upper-body/head source crop using the
   real-device validated geometry: width factor 2.20, height factor 0.90, head
   center Y ratio 0.22.
3. A future dedicated GL ROI renderer will sample that source rect directly from
   the original OES texture into `InferenceFbo(256)`.
4. Only the 256x256 ROI is read back and sent to MediaPipe IMAGE/CPU.
5. `FaceRoiCandidateSelector` accepts only a face near the target's planned head
   anchor (default max distance 0.22 of ROI size). Face-detector confidence alone
   never owns identity.
6. No acceptable face means privacy falls back to the YOLO-derived head region;
   detector failure must never remove protection.

The current real-device fixture simulates step 3 using deterministic 256x256 crops
from the original 3000x6534 first frame. Four known people were tested with three
crop sizes:

| ROI mode | Any face found | Target face selected |
| --- | ---: | ---: |
| tight | 1/4 (25%) | 1/4 (25%) |
| medium | 3/4 (75%) | 3/4 (75%) |
| **upper** | **4/4 (100%)** | **4/4 (100%)** |

Two upper ROIs contained a neighboring face as well. The central-anchor selector
still selected the target candidate in both cases; the four selected upper target
anchor distances were approximately 0.106, 0.098, 0.103, and 0.111, all well
inside the 0.22 ownership gate.

### PLK110 / Android 16 latency

MediaPipe IMAGE-only full-frame control was stable for 30/30 calls. For 256x256
head ROIs, two connected-device runs showed significant device-state variance:

- earlier/cool run: mean about **8.00 ms**, p50 **8.24 ms**, p95 **8.92 ms**
- later sustained-test run: mean **17.93 ms**, p50 **18.37 ms**, p95 **24.91 ms**

The later run is the safer planning number. Until longer thermal runs exist, use
approximately **25 ms p95 per FACE_ONLY person ROI** as the conservative PLK110
budget rather than treating the 8 ms cold result as guaranteed performance.

## Test fixtures and paths

For the full-frame control, each committed 640x640 JPEG can be presented to two
**separate** isolation tests:

1. YOLO-only converts it to bottom-to-top RGBA to emulate the existing GL FBO
   readback convention and calls `segmentGlReadbackRgbaSync()`.
2. MediaPipe-only converts the same fixture to normal top-to-bottom RGBA and calls
   the stateless IMAGE/CPU detector.

The two runtimes are intentionally not interleaved in the promotion benchmark.
This keeps the face-detector result independent from instrumentation-only GPU
lifecycle artifacts and keeps production YOLO stability as an existing baseline,
not something this research benchmark is allowed to redefine.

The source-resolution ROI fixtures and their hashes are recorded in
`testdata/face_benchmark_frames/face_roi_manifest.json`.

## Current benchmark surfaces

`FaceRuntimeIsolationInstrumentedTest` contains isolated 30-frame YOLO-only and
MediaPipe IMAGE-only controls. `FaceRoiBenchmarkInstrumentedTest` is the current
promotion-oriented benchmark and writes `face_roi_benchmark.json` plus a
`FACE_ROI_BENCHMARK_REPORT=` log line.

The ROI report includes:

- MediaPipe CPU mean/p50/p95/min/max.
- face count per ROI and detector confidences.
- selected target candidate, confidence, and anchor distance.
- per-mode any-face and selected-target coverage proxies.
- a hard gate that all four reviewed `upper` fixtures select a target-owned face.

## Running on a connected device

From `mobile/app/android` with a valid JDK 17:

```text
.\gradlew.bat :dance_native:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.danceanon.native.benchmark.FaceRoiBenchmarkInstrumentedTest
```

This test is still a reviewed-fixture benchmark rather than a general recall claim.
Its current hard gate proves the chosen upper ROI and anchor ownership rule on four
known distant people in the source frame; broader video ground truth is still
required before production enablement.

## Promotion gates before production integration

Do not connect face detection to preview/export yet. Before that step, benchmark a
representative set covering distant dancers, side profiles, bowed heads, fast
turns, motion blur, hand/arm face occlusion, crossings, partial exits, portrait
video, and multiple simultaneous faces.

Initial promotion targets:

- Target-face ownership >= 99% on manually reviewed ROI samples.
- No ambiguous association may commit to the wrong selected identity.
- Preserve source-resolution detail by sampling the planned ROI before downscale;
  do not run face detection on the existing full-frame YOLO 640 input.
- Treat ~25 ms p95/ROI as the current conservative PLK110 CPU budget and measure
  sustained thermal behavior before selecting final cadence.
- Add manual face ground truth before claiming detector recall. For privacy, an
  effective face-coverage miss must fall back to the YOLO-derived head region;
  detector failure must never make a face unmasked.
- Prove a dedicated OES/2D `FaceRoiRenderer` against deterministic ROI fixtures
  before changing `ExportPipeline` or existing FBO/Y-flip coordinate code.

Only after those measurements should the runtime design proceed to per-person
`NONE / FACE_ONLY / FULL_BODY` policy and a multi-face R8 privacy mask.
