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

### OES ROI render/readback contract

`FaceRoiRendererInstrumentedTest` now validates the exact GL path intended for a
future runtime integration without touching `ExportPipeline`:

- the same visual `sourceRect` rendered from a normal 2D texture and an OES
  `SurfaceTexture` produces matching pixels on the PLK110;
- `glReadPixels` row 0 is semantic visual top for this renderer, so the 256x256
  RGBA buffer can be passed directly to the MediaPipe IMAGE/CPU backend without a
  CPU row flip or channel conversion;
- a known face ROI survived that direct OES -> FBO -> readback -> MediaPipe path
  for **40/40 measured iterations**.

The fixed 256x256 OES crop + readback cost is small compared with face inference.
Across two PLK110 runs, p95 was approximately **0.34-1.06 ms**; the latest 60-call
run measured mean **0.31 ms**, p50 **0.31 ms**, p95 **0.34 ms**, and max **0.40 ms**.

The combined single-ROI sidecar path on the known face fixture measured:

- mean: **18.67 ms**
- p50: **19.62 ms**
- p95: **25.10 ms**
- max: **27.12 ms**
- detected: **40/40**

This confirms the current conservative ~25 ms p95/ROI planning budget and shows
that the dominant cost is MediaPipe CPU inference, not the source-texture crop or
GPU readback.

### Bundled ML Kit same-ROI A/B

The bundled Android ML Kit face detector (`com.google.mlkit:face-detection:16.1.7`)
was also run on the exact same twelve reviewed 256x256 ROI fixtures on PLK110 /
Android 16. The dependency remains `androidTestImplementation` only. Both FAST and
ACCURATE modes disabled landmarks, contours, classification, and tracking, and used
an intentionally permissive `minFaceSize=0.05` so small distant target faces were
not rejected by configuration alone.

Results:

| Backend | Upper ROI any face | Upper target selected | p50 across runs | p95 across runs |
| --- | ---: | ---: | ---: | ---: |
| MediaPipe BlazeFace full-range IMAGE/CPU | **4/4** | **4/4** | ~18-20 ms sustained | ~25 ms sustained |
| ML Kit bundled FAST | 1/4 | 1/4 | 10.14-13.17 ms | 11.00-16.27 ms |
| ML Kit bundled ACCURATE | 2/4 | 1/4 | 15.68-30.90 ms | 19.13-35.20 ms |

Two ML Kit runs at different device thermal/background states produced materially
different latency but the **same target-coverage result**. In the later run FAST
measured mean 12.72 ms / p50 13.17 ms / p95 16.27 ms, while ACCURATE measured
mean 30.76 ms / p50 30.90 ms / p95 35.20 ms. This reinforces that detector choice
should be based on privacy-target coverage first, not the best single latency run.

In ACCURATE mode one additional upper ROI produced only a neighboring face. The
existing anchor ownership gate correctly rejected that detection instead of
assigning it to the target person. ML Kit therefore does not improve target-face
coverage on the reviewed distant-dancer fixture even with the smaller 0.05 minimum
face size; its speed advantage is not useful when the privacy target is usually
missed.

**Decision:** retain MediaPipe BlazeFace full-range IMAGE/CPU as the current face
locator candidate. Keep the ML Kit benchmark as reproducible negative evidence,
but do not add an RGBA-to-`InputImage` production adapter or use ML Kit tracking IDs.
YOLO/`TrackManager` remains the only identity authority.

### Dormant production locator boundary

The validated MediaPipe locator has now been promoted from benchmark-only code to
an internal production-packaged component:

- `FaceLocator` exposes only positional `FaceObservation` values and inference
  latency; it has no identity or tracking-ID field.
- `MediaPipeFaceLocator` is fixed to CPU + stateless IMAGE mode and consumes the
  same top-down RGBA contract already proven by the OES ROI path.
- `FaceLocatorProvider.createOrNull()` defaults to `enabled=false`; there is no
  current `ExportPipeline`, preview, Pigeon, or Flutter call site that opts in.
- Gradle copies the pinned BlazeFace model into `models/face/` production assets
  and verifies SHA-256 before compilation.

This stage changes package contents but **not runtime anonymization behavior**.
The next integration step must explicitly supply FACE_ONLY policy before the
locator can execute during export.

Verification on the connected PLK110 / Android 16 device passed using the
production `MediaPipeFaceLocator` class and packaged production model asset on the
reviewed distant-face ROI. `FaceLocatorProvider` also has a unit gate proving its
default path returns `null`. The generated debug AAR contains:

- `FaceLocator`, `MediaPipeFaceLocator`, and `FaceLocatorProvider` classes;
- `assets/models/face/blaze_face_full_range.tflite` with SHA-256
  `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`.

A main-source call-site audit finds no caller of `FaceLocatorProvider`; therefore
the existing full-body export path cannot instantiate this locator in the current
revision.

The PLK110 same-process coexistence smoke also passed with the sequence
`YOLO -> MediaPipe -> YOLO`: both YOLO calls returned non-empty person detections.
This removes the earlier instrumentation concern that simply loading/running
MediaPipe IMAGE/CPU might invalidate the stable LiteRT YOLO runtime. It does not
yet prove sustained mixed scheduling over a full export, which remains a later
integration gate.

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

`MlKitFaceRoiBenchmarkInstrumentedTest` is the benchmark-only Android alternative
used for the same-ROI FAST/ACCURATE comparison. It is not a production backend.

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
- Keep the OES ROI renderer's visual-top readback contract; do not add an extra
  CPU Y-flip/conversion stage unless a future source texture proves it necessary.
- Add manual face ground truth before claiming detector recall. For privacy, an
  effective face-coverage miss must fall back to the YOLO-derived head region;
  detector failure must never make a face unmasked.
- Prove a dedicated OES/2D `FaceRoiRenderer` against deterministic ROI fixtures
  before changing `ExportPipeline` or existing FBO/Y-flip coordinate code.

Only after those measurements should the runtime design proceed to per-person
`NONE / FACE_ONLY / FULL_BODY` policy and a multi-face R8 privacy mask.

## FaceRoiRenderer coordinate proof

The isolated renderer proof is now implemented in:

- `render/FaceRoiRenderer.kt`
- `render/FaceRoiRendererInstrumentedTest.kt`

It has **no `ExportPipeline`, preview, renderer-compositor, Pigeon, or UI call
site**. The test creates a deterministic 320x240 coordinate-gradient source,
plans a source-space head crop, then renders the same visual crop into a 256x256
`InferenceFbo` through both:

1. a normal `GL_TEXTURE_2D` bitmap texture, and
2. a real `SurfaceTexture` / `GL_TEXTURE_EXTERNAL_OES` producer surface.

The first PLK110 run intentionally exposed a coordinate error: the 2D output's
visual top sampled the source bottom (`G=230` where the coordinate gradient
expected about `24`). This proved that a visual top-left crop cannot be passed
directly into the existing full-frame texture matrix.

The corrected contract follows `docs/coordinate_systems.md` explicitly:

`screenGlY = 1 - visualY`

The renderer now converts the planned visual crop to screen-GL UV first and only
then applies the caller-provided source texture matrix. The retry passed on
PLK110 / Android 16 for both texture types. The instrumentation test verifies:

- expected source X/R and visual Y/G values at a 3x3 output sample grid,
- visual top has smaller source Y than visual bottom (no vertical inversion),
- OES and 2D rendered crops have mean sampled RGB absolute delta <= 5.

This closes the isolated **crop/Y/texture-type coordinate proof**. It does not yet
prove export-time scheduling, sustained multi-person cost, privacy-mask rendering,
or fallback behavior, and therefore still does not enable FACE_ONLY in production.

## Face privacy mask output proof

The privacy output layer is now isolated and testable without changing production
call sites:

- `privacy/FacePrivacyRegionResolver.kt` converts an accepted ROI-local face into
  a conservatively expanded source-space ellipse.
- If the detector has no accepted target candidate (miss or ambiguity), it
  immediately emits a YOLO-bbox-derived head ellipse instead of returning a
  transparent region.
- `privacy/FacePrivacyMaskBuilder.kt` rasterizes one or more source-space ellipses
  into a binary 0/255 **160x160 YOLO-proto-compatible `NativeMask`**.
- Multiple FACE_ONLY regions use pixelwise union; combining with a full-body
  privacy mask is allowed only when texture dimensions, source frame dimensions,
  and `samplingRect` contracts match exactly.

Unit coverage verifies detected-face expansion, detector-miss fallback, visual-top
letterbox mapping, multi-face union, frame-edge clipping, compatible body+face
union, and fail-closed rejection of incompatible mask contracts.

`FacePrivacyMaskCompositorInstrumentedTest` then feeds such a face mask through the
**unchanged production `GlRenderer` / `PrivacyOcclusionResolver` path** on PLK110.
The test renders both a detector-owned face ellipse and a fallback head ellipse,
checks that both centers receive the solid privacy effect, and verifies that their
vertically mirrored lower-body points remain untouched. This proves that the
existing single `uMaskTexture` path can carry FACE_ONLY regions; no additional
shader sampler or `uStickerRect` privacy path is required.

This still does not enable FACE_ONLY. The next architecture step is to adapt each
tracked person's effective privacy mask according to internal
`NONE / FACE_ONLY / FULL_BODY` policy **before** the existing occlusion resolver,
so clear unselected foreground keeps using the already-tested occluder logic.

### Per-person privacy policy adapter

`privacy/PersonPrivacyPolicyAdapter.kt` now proves that this policy can be expressed
without creating a second compositor or tracker:

- `NONE`: preserve the original YOLO person/mask but do not select it for privacy;
  the person therefore remains available to the existing foreground-occluder logic.
- `FULL_BODY`: preserve the existing tracked person and body mask unchanged.
- `FACE_ONLY`: preserve the exact YOLO track ID, bbox, state, age, occlusion links,
  and footY while substituting only the effective privacy mask with the generated
  face mask.

The adapter is fail-closed. If a requested FACE_ONLY mask is missing or incompatible
with the current YOLO mask coordinate contract, it escalates that track to the
available full-body mask for the frame. If neither a face mask nor a body mask is
available, the adaptation is explicitly marked `readyForRender=false` with the
unresolved track ID; a future runtime integration must stop/hold rather than render
that privacy target transparently.

Unit tests cover FACE_ONLY substitution, FULL_BODY escalation, incompatible-mask
escalation, unresolved selected tracks, preservation of NONE persons as occlusion
evidence, and a mixed FACE_ONLY + FULL_BODY + NONE frame passed directly through
the existing `PrivacyOcclusionResolver`. The mixed test confirms the FACE_ONLY
target's lower body stays clear while the FULL_BODY target remains private.
