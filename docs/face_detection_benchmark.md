# Face-only anonymization benchmark

## Status

- SAM2: **BLOCKED**. Do not advance or unhide it.
- YOLO: the only stable person detection/segmentation and identity baseline.
- Production anonymization: unchanged full-body path.
- Face detector: instrumentation benchmark only; no preview/export runtime call site exists.

## Goal

Measure whether a lightweight face locator can be added as a positional sidecar
without creating a second identity tracker. YOLO/`TrackManager` remains the sole
owner of person identity; each face detection is associated back to an existing
YOLO track by `FacePersonAssociator`.

The first backend is MediaPipe BlazeFace full-range on CPU. The benchmark backend
is hidden behind `FaceBenchmarkBackend`, so an ML Kit implementation can later be
run against the identical decoded frames and reporting schema.

## Isolation guarantees

`com.google.mediapipe:tasks-vision:1.0.0` is declared with
`androidTestImplementation` only. The model and video fixtures are configured as
`androidTest` assets only. No production `implementation` dependency, profile,
Pigeon DTO, preview path, export path, renderer, or shader is changed by this stage.

## Model fixture

The benchmark uses the official BlazeFace full-range float16 model at:

`testdata/models/face/blaze_face_full_range.tflite`

Expected SHA-256:

`3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`

The instrumentation test checks the packaged asset hash before initialization.

## Frame path under test

For each sampled frame from `testdata/videos/01_sample.mp4`:

1. Decode a source bitmap with `MediaMetadataRetriever`.
2. Letterbox it to the existing YOLO 640x640 geometry using
   `ModelCoordinateMapper` and the same RGB(114,114,114) padding.
3. Materialize a bottom-to-top RGBA buffer to emulate the existing GL FBO
   readback convention.
4. Send that buffer through the stable YOLO
   `segmentGlReadbackRgbaSync()` path and `TrackManager`.
5. Flip rows into a reusable top-to-bottom RGBA workspace. This measures the
   extra copy required because MediaPipe `MPImage` expects normal row order; it
   does **not** require a second GPU readback.
6. Run MediaPipe Face Detector in `RunningMode.VIDEO` with CPU delegate.
7. Map each face box from 640-model coordinates back to source coordinates.
8. Associate faces to existing YOLO tracks with `FacePersonAssociator`.

The synthetic letterbox staging itself is outside the latency metrics because in
production the 640x640 FBO buffer already exists for YOLO.

## Reported metrics

The test writes `face_detection_benchmark.json` to the instrumentation app's
external-files directory and emits a `FACE_BENCHMARK_REPORT=` log line.

Reported values include:

- `face_latency`: MediaPipe CPU mean/p50/p95/min/max after warm-up.
- `row_flip_latency`: extra CPU row-order conversion cost.
- `yolo_wall_latency`: same-frame YOLO wall latency for context.
- `total_face_detections` and `total_matched_faces`.
- `matched_face_rate`: fraction of detected faces that can be safely assigned to
  exactly one existing person track.
- `face_per_person_frame_proxy`: matched faces divided by tracked person-frames.
  This is only a coverage proxy, **not face recall**, because the current fixture
  has no manually annotated face ground truth.
- Per-frame face/person counts, unmatched tracks/faces, and association scores.

## Running on a connected device

From `mobile/app/android` with a valid JDK 17:

```text
.\gradlew.bat :dance_native:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.danceanon.native.benchmark.FaceDetectionBenchmarkInstrumentedTest
```

The test has only smoke assertions at this stage: at least five frames must decode,
YOLO must observe persons, and BlazeFace must detect at least one face. Quality is
reported rather than hard-gated until face ground truth is annotated.

## Promotion gates before production integration

Do not connect face detection to preview/export yet. Before that step, benchmark a
representative set covering distant dancers, side profiles, bowed heads, fast
turns, motion blur, hand/arm face occlusion, crossings, partial exits, portrait
video, and multiple simultaneous faces.

Initial promotion targets:

- Detected-face -> YOLO-track association >= 99% on manually reviewed samples.
- No ambiguous association may commit to the wrong selected identity.
- Row-flip p95 <= 2 ms on the target Android device class.
- Face-detector CPU p95 should remain within an agreed export-frame budget; start
  investigation if it exceeds 15 ms on the target device.
- Add manual face ground truth before claiming detector recall. For privacy, an
  effective face-coverage miss must fall back to the YOLO-derived head region;
  detector failure must never make a face unmasked.

Only after those measurements should the runtime design proceed to per-person
`NONE / FACE_ONLY / FULL_BODY` policy and a multi-face R8 privacy mask.
