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
   anchor (default max distance 0.22 of ROI size). If the two closest accepted
   faces are separated by less than 0.04 of ROI size, the observation is treated
   as ambiguous and rejected; the caller falls back to the YOLO-derived head
   region. Face-detector confidence alone never owns identity.
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

### Request policy schema staging

The Pigeon request schema now stages an optional `faceOnlyPersonIds` field on both
preview and export requests. It is appended as a nullable field, so existing source
callers do not need to provide it and retain the historical meaning of
`selectedPersonIds` as FULL_BODY targets.

`PersonPrivacyModeResolver` converts the two lists into the internal tri-state
policy. If a track appears in both lists, FULL_BODY wins, matching the original
desktop behavior. This stage only establishes request semantics; the export and
preview loops still do not call the face locator.

### Resolved privacy group merge

FULL_BODY and FACE_ONLY cannot share the current fresh-class resolver input
directly: fresh selected YOLO evidence would expand a FACE_ONLY target back to a
full-body mask. They are therefore designed as independently resolved privacy
groups. `PrivacyOcclusionResolver.mergeResolvedMasks()` unions their already-carved
effective privacy, reconstructs each group's pre-carve support from its safe render
occluder, and then rebuilds exactly one global safe occluder. Unit coverage proves
that a foreground hole approved for one selected target cannot carve privacy still
owned by another selected target.

`GlRenderer.render()` now accepts an optional `additionalResolvedPrivacy` value.
When it is null (all existing callers), the original resolver path is unchanged.
This is the compositor hook intended for FACE_ONLY export integration.

### Track identity/privacy-class split

`TrackManager` now distinguishes durable identity protection from full-body
privacy classification. The legacy `setProtectedTrackIds()` method still updates
both sets and therefore preserves existing behavior. FACE_ONLY integration can
instead protect `FULL_BODY ∪ FACE_ONLY` identities while classifying only
FULL_BODY IDs as fresh selected privacy. This prevents fresh YOLO full-body masks
from silently expanding FACE_ONLY targets back to full-body privacy.

### Face-only frame processor

`FaceOnlyPrivacyFrameProcessor` now owns the per-frame FACE_ONLY sidecar sequence:
source ROI render/readback -> MediaPipe positional detection -> target-anchor
selection -> detected/fallback privacy ellipse -> 160x160 face mask -> independent
`PrivacyOcclusionResolver` pass. It reports detected, fallback, escalated, and
unresolved track IDs plus detector time. Missing requested tracks are explicitly
`readyForRender=false`; detector or ROI failures fall back to the YOLO head region
rather than removing privacy. In mixed FACE_ONLY + FULL_BODY frames, FULL_BODY
tracks keep identity/geometry but their masks are removed from the secondary
FACE_ONLY resolver, so the primary full-body target cannot be mistaken for an
unselected occluder and carve a selected face.

`ExportPipeline` now activates that processor only when the optional request
`faceOnlyPersonIds` is non-empty. Legacy requests keep the historical
`setProtectedTrackIds()` path and do not instantiate MediaPipe or add ROI/readback
work. In FACE_ONLY mode, identity-protected IDs are `FULL_BODY ∪ FACE_ONLY` while
fresh selected privacy classification remains FULL_BODY-only; the resolved
FACE_ONLY result is merged through `GlRenderer.additionalResolvedPrivacy`.

The full request-gated export path has now passed a PLK110 / Android 16 smoke test.
The instrumentation test creates a deterministic 720x1280 AVC clip on-device,
runs the real `ExportPipeline` with `selectedPersonIds=[]` and
`faceOnlyPersonIds=[0]`, and decodes the output for pixel comparison. All **12/12**
frames were decoded, latched, rendered, and encoded. The output contained a
material new solid-red privacy region whose vertical extent stayed below 45% of
the frame, while a reviewed lower-body patch stayed close to the input image;
this verifies that the request reached FACE_ONLY composition rather than silently
expanding back to FULL_BODY.

The first version of this export smoke hardcoded FACE_ONLY ID 0 without an
analysis cache. That turned out to be a test-fixture identity bug rather than a
face-detector quality result: production YOLO detections are sorted left-to-right,
and `TrackManager.initialize()` assigns IDs in that order. On this crop the
reviewed dancer is the **second** raw detection on frame 0 (centers approximately
75 px and 323 px; reviewed dancer IoU ~0.88 at index 1). All quality numbers below
therefore use the corrected target track ID 1; the earlier low-coverage ID-0 run
must not be used to tune MediaPipe confidence or the anchor gate.

The processor therefore caps MediaPipe work to **one detector call per output
frame** and a per-track detector interval of **66 ms** (about 15 Hz for one target
in a 30 fps video). Between trusted detections it reprojects the padded face
ellipse through the current YOLO-owned person bbox for at most 150 ms / two stale
observation frames. A real detector miss, ambiguity, LOST track, or expired cache
immediately falls back to the conservative YOLO head ellipse. With multiple
FACE_ONLY targets, uncached/oldest tracks are serviced first so acquisition is
staggered instead of multiplying synchronous ROI readbacks in one frame.

With the corrected target ID, the 30 fps static smoke completed 18/18 frames with
**9 DETECTED / 9 PREDICTED / 0 FALLBACK**, 9/9 detector calls containing an
accepted target, and no zero-result or rejected calls. `faceDetectorCpu` p95 was
about **13 ms** and the full `faceOnlyPrivacy` p95 about **29 ms** on PLK110. The
dynamic motion smoke also completed 18/18 with **9 DETECTED / 9 PREDICTED /
0 FALLBACK**; all 9 detector calls accepted the target, with detector p95 about
**16 ms**. Its complete FACE_ONLY stage still showed an occasional first-use
outlier (p95 ~79 ms), so cold-path timing remains a performance item, but it is no
longer a localization-coverage blocker.

For dynamic validation, a separate androidTest-only fixture derives 18 frames
from the reviewed real-person crop using deterministic affine motion (horizontal
translation, vertical translation, mild scale, and mild rotation). The manifest
stores each frame hash plus the transformed reference head point. The device test
encodes those frames to AVC, runs the real 30 fps FACE_ONLY export, and checks
multiple decoded output frames for nonzero local privacy, non-full-body vertical
extent, and privacy-region motion consistent with the known affine displacement.
The export summary additionally separates raw detector observations, zero-result
detector calls, and nonempty detector results rejected by the YOLO-owned anchor
gate, so low DETECTED coverage can be attributed before changing model or gate
thresholds.

Two additional androidTest-only controls isolate the corrected result. First,
ground-truth affine person boxes bypass YOLO/TrackManager and run production
MediaPipe 0.35 plus the production anchor selector over four ROI scales. Current
1.00x, 0.80x, and 1.20x all selected the target on **18/18** frames; an overly
tight 0.65x crop fell to **10/18**. There is therefore no evidence to change the
current production ROI scale.

Second, production raw YOLO was compared with the same affine ground-truth boxes
before TrackManager. A correct raw person detection was available on **15/18**
frames. On normal detected frames bbox IoU p50 was ~**0.84**, derived head-anchor
error p50 ~**0.020 ROI** and p95 ~**0.030 ROI**, far inside the 0.22 face-candidate
gate. Frame 0 specifically confirmed the reviewed target is raw detection index 1.
The remaining raw-detection misses are a YOLO/tracking robustness case, not a
reason to loosen face identity association. Current decision: keep MediaPipe 0.35,
the existing ROI geometry, strict anchor gate, and fail-closed YOLO-head fallback.

### Long-running PLK110 stability and ColorOS test-process freezing

Long-running instrumentation initially appeared to stall after roughly 5 seconds
of repeated FACE_ONLY work. Per-frame logs first made this look like a detector or
direct-buffer lifetime problem because the final visible frame varied between
runs. Device system logs later showed the real trigger: ColorOS
`OplusHansManager` froze `com.danceanon.dance_native.test` when the instrumentation
process had no foreground Activity. A run that stopped after `frame_start=272`,
for example, was followed immediately by a Hans `freeze uid` entry for the test
package.

The stable regression therefore uses an **androidTest-only foreground host
Activity** for the duration of the stress test. This Activity is declared only in
`src/androidTest/AndroidManifest.xml`; it is not packaged into the production AAR
or app behavior. With the host Activity active, the unchanged production
`FaceOnlyPrivacyFrameProcessor` completed **300/300 frames** on PLK110 / Android
16 with:

- **150 DETECTED / 150 PREDICTED / 0 FALLBACK / 0 unresolved** frames;
- exactly **150 MediaPipe detector calls** at the existing 66 ms cadence;
- detector p95 approximately **18.22 ms** on the final verification run;
- native heap allocation decreasing from about **37.4 MB to 16.2 MB** across the
  measured interval, and PSS decreasing from about **109.5 MB to 94.5 MB**.

The run reached `frame 299`, `loop_done`, processor close, and foreground-host
close successfully. Hans did not freeze the dance-native test process while it
owned the foreground Activity. Therefore the experimental direct-buffer pooling
change was **not retained**: the original production implementation is stable
under a test setup that is not artificially suspended by the device OS.

### Preview integration

`PreviewRequestDto.faceOnlyPersonIds` is now consumed by the native
`PreviewPipeline`. Preview resolves the same FULL_BODY-wins tri-state policy as
Export. When the face-only set is empty, Preview keeps the original renderer path
and does not create a face sidecar. When FACE_ONLY is requested, the
already-stable analysis/preview person IDs remain authoritative; the uploaded
bitmap texture is cropped with
`FaceOnlyPrivacyFrameProcessor` using the existing YOLO mask mapper and the bitmap
texture matrix, then merged through `GlRenderer.additionalResolvedPrivacy`.

The PLK110 / Android 16 preview smoke passed end-to-end using the reviewed dancer:
an unselected baseline frame was rendered first, then FACE_ONLY ID 1 produced a
material local red privacy region while the reviewed lower-body patch remained
close to the baseline. A subsequent request for nonexistent FACE_ONLY ID 999
raised a native render error instead of returning an unprotected preview. The
existing static and dynamic FACE_ONLY Export device tests remained passing after
the Preview change.

Before exposing the mode in Flutter, mixed-mode acceptance was added on PLK110:

- Preview verified FULL_BODY ID 0 and FACE_ONLY ID 1 in the same frame while the
  FACE_ONLY target's reviewed lower-body patch remained unmodified;
- Preview also verified that a request containing the same ID in both sets uses
  FULL_BODY, matching the native/domain conflict rule;
- Export then passed all **3/3** end-to-end tests, including an independent
  FULL_BODY ID 0 + FACE_ONLY ID 1 mixed export.

### Flutter project model and user-visible selection

Flutter now persists `DanceProject.faceOnlyPersonIds` in addition to the legacy
`selectedPersonIds` FULL_BODY set. Missing `faceOnlyPersonIds` in older project
JSON defaults to an empty set, so existing projects retain historical behavior.
`DanceProject.privacyModeForPerson()` is the canonical Dart-side resolver and,
like native, gives FULL_BODY priority when an ID is present in both sets.
`privacyTargetIds` is the union used by result/preview counts.

`DanceNativeClient`, `NativeProcessingRepository`, Effect Editor preview, Frame
Preview, and Export now pass the face-only set through to the existing Pigeon
request field. The person-selection state maintains mutually exclusive FULL_BODY
and FACE_ONLY sets and saves `PersonTrack.selected` only as the legacy FULL_BODY
mirror.

The person-selection screen now exposes two explicit controls for each person:

- **全身** -> FULL_BODY;
- **仅人脸** -> FACE_ONLY.

Tapping the large person card preserves the previous behavior and toggles
FULL_BODY, rather than implicitly cycling three states. Tapping the active mode
again clears that person's privacy mode. `全选` intentionally converts every
person to FULL_BODY and clears FACE_ONLY; `清空` clears both sets. Header, page
indicators, action count, preview summary, and export result counts treat either
privacy mode as a protected person.

The dedicated Flutter widget/controller regression starts from a persisted
FACE_ONLY project and verifies FACE_ONLY -> FULL_BODY -> FACE_ONLY mutual
exclusion plus `buildConfiguredProject()` persistence. The final app verification
passed all Flutter tests, `flutter analyze` with zero issues, and
`flutter build apk --debug` successfully produced the integrated Android debug
APK.

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
