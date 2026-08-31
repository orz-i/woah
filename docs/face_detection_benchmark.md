# Face-only anonymization benchmark

## Status

- SAM2: **BLOCKED**. Do not advance or unhide it.
- YOLO: the only stable person detection/segmentation and identity baseline.
- FULL_BODY production behavior remains on the existing YOLO/TrackManager compositor path.
- Android FACE_ONLY is now production-wired for Preview and Export behind explicit
  per-person policy. MediaPipe remains positional evidence only; it never owns identity.
- FACE_ONLY currently renders the stabilized head privacy region as an opaque built-in
  sticker instead of reusing the FULL_BODY fill color/effect.
- iOS native Preview/Export remains unsupported; current FACE_ONLY processing validation
  is Android-only.

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

### FACE_ONLY temporal stabilization and sticker rendering

Real-video validation with five simultaneous FACE_ONLY targets exposed a visual
size discontinuity between precise face detections and the conservative YOLO-head
fallback. The diagnostic export covered 751 frames and reported 369 detected,
1107 predicted, and 2279 fallback track-frames, so the visible "large/small"
oscillation was primarily a geometry-state transition problem rather than a
renderer stall or a need to relax detector/anchor thresholds.

`FacePrivacyTemporalStabilizer` now owns a per-track display/privacy geometry
state. Trusted detections update the state with bounded center/scale movement;
recovery from a larger fallback shrinks gradually instead of snapping. During a
detector miss or ambiguity, precise detector location evidence is still discarded
immediately, but fallback keeps the recent trusted size as a lower bound while
re-centering on the current YOLO-owned head position. This preserves fail-closed
privacy without reusing a stale face position.

FACE_ONLY rendering now uses a sticker-only overlay rather than the selected
FULL_BODY fill effect. The native processor emits `FaceStickerPlacement` records
from the stabilized ellipses. `GlRenderer` draws one overlay per FACE_ONLY track
after the primary compositor, using the same effective letterbox mask sampling
rect as the privacy compositor so foreground occluder carving remains intact.
The overlay shader uses visual source-space coordinates before the OES/bitmap
texture matrix; this avoids the vertical-coordinate mismatch that occurs on the
2D preview path.

The sticker pass consumes both halves of the resolved FACE_ONLY compositor result:
the privacy mask supplies sticker support and the separate foreground occluder mask
is applied as `privacyAlpha *= (1 - occluderAlpha)`. This matches the existing main
privacy compositor contract, so hands/arms or other accepted foreground occluders
can remain visually in front of the face sticker instead of being painted over.

The built-in fallback sticker is the existing generated sunglasses-face bitmap.
The texture cache now distinguishes the initial transparent 1x1 placeholder from
an actually loaded default `null` sticker asset; otherwise `ensureStickerTexture`
could incorrectly return the transparent placeholder forever. Within the resolved
FACE_ONLY privacy mask the sticker pass is forced opaque, so transparent pixels in
a future custom sticker cannot create a privacy hole. FULL_BODY targets continue
to use the configured fill effect independently.

PLK110 / Android 16 acceptance after this change:

- temporal stabilizer unit tests: **5/5** passed;
- sticker renderer/unit tests plus Android-test compilation passed;
- `FaceOnlyPrivacyFrameProcessorInstrumentedTest`: **7/7** passed;
- Preview FACE_ONLY sticker acceptance passed with privacy-mask clipping enabled,
  no material residual solid-red block, FULL_BODY conflict priority, fail-closed
  missing-ID behavior, and two simultaneous FACE_ONLY targets each receiving an
  independent sticker;
- Export FACE_ONLY acceptance: **3/3** passed for static, dynamic affine, and
  mixed FULL_BODY + FACE_ONLY output;
- dynamic sticker width and height peak/min ratios are hard-gated at **<= 1.35**;
- sticker shader unit coverage explicitly requires resolved foreground-occluder
  sampling/carving;
- the final Preview regression passed **1/1** and Export regression passed **3/3**
  on PLK110 after the occluder-carve integration;
- the foreground-host production stress test again completed **300/300 frames**.

This HEAD remains a candidate build pending user validation on real dance videos;
the historical user-validated rollback baseline is unchanged.

### Five-person motion follow and mixed-mode correction

The first real-video retest of the sticker build used one FULL_BODY target
(`selected_ids=[4]`) together with five FACE_ONLY targets
(`face_only_ids=[1,2,3,5,6]`) over 751 frames at 1920x1080/60 fps. The user
reported three remaining defects: the sticker was much larger than the face,
moving faces could outrun the sticker and become exposed, and mixed mode could
place a full-body mask on the wrong dancer.

The diagnostic bundle confirmed that the moving-face issue was not a renderer
stall. Across 3,755 FACE_ONLY track-frames it reported **381 DETECTED / 1,148
PREDICTED / 2,226 FALLBACK**, so only about 10.1% of per-person frames had a fresh
face detection while about 59.3% were using fallback. The old scheduler allowed
only one face ROI call per output frame and skipped detector work whenever a track
was temporarily unobserved, including the exact REACQUIRING intervals where
position needed refreshing most.

The follow-up implementation therefore changes the FACE_ONLY scheduler and
geometry rather than loosening the detector ownership gate:

- the default face-detector budget is raised from one to **two ROI calls per
  output frame**;
- ACTIVE cached tracks keep the 66 ms cadence, while uncached, OCCLUDED,
  REACQUIRING, or temporarily unobserved tracks are treated as urgent with a
  **33 ms** retry interval;
- REACQUIRING/OCCLUDED tracks may use the current TrackManager-predicted bbox for
  a detector ROI; only LOST/REMOVED tracks are excluded;
- the precise-cache observation-age allowance rises from two to six frames but
  remains hard-capped by the existing 150 ms wall-clock age;
- temporal stabilization no longer delays the face **center** at all. The newest
  detected/predicted/YOLO-owned head center is used immediately, while only size
  is smoothed. This removes the visual trailing introduced by center EMA at 60 fps.

The sticker-size issue was also traced to geometry rather than PNG dimensions.
The previous detected ellipse used the full detected face width/height as inputs
to radius multipliers of 0.90/1.05, producing roughly 1.8x face width and 2.1x
face height before sticker overscan. Detected radii are now 0.66/0.74 with a
smaller vertical shift, fallback head geometry is materially tighter, and visual
sticker overscan is reduced from 1.08 to **1.02**. Unit gates now cap detected
privacy width at 1.35x the raw detector face width, detected height at 1.50x, and
the no-history fallback diameter at conservative head-scale bounds rather than
the previous oversized head-and-shoulders footprint.

The same bundle showed substantial identity ambiguity during the mixed export:
503 association-ambiguity events, 108 reacquire starts, and 51 reacquire timeouts.
The selected FULL_BODY track 4 itself had 26 ambiguity events, 10 reacquire starts,
and 10 reacquire timeouts. In addition, the main QUALITY compositor was still
allowed to use temporal raw-detection privacy classes that deliberately carry no
exact person ID. That is useful in the historical FULL_BODY-only pipeline but is
unsafe in an explicit mixed-mode policy because a SELECTED temporal class can
land on a nearby FACE_ONLY dancer.

For mixed FULL_BODY + FACE_ONLY export, temporal fresh-class evidence is therefore
disabled as a FULL_BODY primary source. The main full-body compositor follows the
exact TrackManager-owned user-selected ID only; pure FULL_BODY exports retain the
legacy fresh-class behavior unchanged. Global Hungarian assignment now also
applies the same absolute bbox/mask evidence gate already used for protected
occlusion-group commits, so a unique but weak detection cannot silently take over
a protected selected identity. A new unit case specifically proves that a
protected selected track rejects a globally unique bbox-IoU 0.25 candidate even
when its bbox-only Hungarian score exceeds the ordinary minimum.

A second audit of FULL_BODY id 4 found one additional concrete recovery loophole:
of 92 group-assignment commits in the failing export, the only commit from
`LOST` used bbox IoU **0.458** and mask IoU **0.20**. The group helper previously
treated `LOST` like ACTIVE, so that edge was accepted even though the dedicated
protected LOST-recovery path requires bbox IoU 0.50 or mask IoU 0.45. `LOST` now
uses those same strict recovery thresholds in group/global association as well;
focused crossing/recovery tests confirm normal strong reacquisition still works.

The next diagnostic summary additionally records detector calls,
DETECTED/PREDICTED/FALLBACK frame counts, and sticker min/max width/height **per
FACE_ONLY track ID**. This makes future five-person bundles able to expose an
individual starved or unstable target directly rather than relying only on
aggregate counts.

After the PLK110 reconnected, the second-round moving/mixed candidate completed
its device gates. `FaceOnlyPrivacyFrameProcessorInstrumentedTest` passed **8/8**,
including the two-call detector budget and urgent refresh of an unobserved
REACQUIRING FACE_ONLY track. Two legacy assertions initially sampled fixed pixels
above the newly tightened detector ellipse; they were replaced with contract-level
checks that the resolved mask covers the actual sticker/privacy center and that a
predicted center follows the translated person geometry. Preview passed **1/1**,
Export passed **3/3** including mixed FULL_BODY + FACE_ONLY, and the foreground
production stress completed **300/300 frames**. The higher detector budget did not
introduce a long-running stall in this regression.

Local verification after these corrections:

- focused FACE_ONLY geometry/stabilizer/identity tests: **17/17 passed**;
- complete `dance_native` JVM unit suite: **all passed**;
- Android instrumentation sources compile successfully;
- Flutter app tests: **14/14 passed**;
- `flutter analyze`: **0 issues**.

That candidate was subsequently verified after PLK110 reconnected: processor,
Preview, mixed Export, 300-frame stress, full native unit tests, Flutter tests,
and `flutter analyze` all passed. The same real dance video nevertheless still
showed a large right-side startup face sticker plus intermittent drifting face
and full-body privacy, so device fixture success alone was not treated as user
validation.

### Third real-video correction: acquisition, prediction bounds, and mixed carve

The next complete real-video bundle came from commit
`2dcebfb231c4e5b4018da8c4b41d9729bc5a8741` using the same one-FULL_BODY plus
five-FACE_ONLY 751-frame / 60 fps clip. Detector throughput improved substantially:
**1,322 detector calls**, **860 DETECTED**, **1,177 PREDICTED**, and **1,718
FALLBACK** track-frames. However the more aggressive scheduling also produced
**423 non-empty detector calls rejected by the YOLO-owned face-anchor gate**, up
from 7 in the earlier mixed run. This is strong evidence that running face
detection from REACQUIRING/OCCLUDED/unobserved predicted person boxes was adding
ambiguous neighboring-face evidence rather than solving identity motion.

The new per-track diagnostics also confirmed that face size could still become
far too large: sticker width maxima were approximately **97.7 / 173.0 / 177.8 /
240.4 / 187.3 px** for FACE_ONLY IDs 1/2/3/5/6 respectively. The trusted face
cache was stored as ratios of the *current* person bbox, so an occlusion/group
bbox expansion could inflate a previously normal face. The right-side startup
artifact had a separate cause: with only two detector calls per frame, later IDs
could render a generic YOLO-head fallback while waiting for their first ROI turn.

The FACE_ONLY sidecar is therefore revised as follows:

- all never-attempted ACTIVE FACE_ONLY IDs receive a one-time acquisition burst
  on the first available frame (up to eight ROIs), while steady-state scheduling
  remains capped at two calls per output frame;
- face detection is now allowed only for **freshly observed ACTIVE** TrackManager
  identities. REACQUIRING, OCCLUDED, LOST, and temporarily unobserved identities
  never use a predicted bbox to make a new face association;
- a detector miss/anchor rejection no longer discards a still-fresh trusted face
  immediately. For at most 150 ms it uses short-lived `PREDICTED_FACE` geometry;
- trusted face cache geometry is stored in **absolute source pixels** together
  with the trusted person bbox. Short-term prediction may scale only within
  **0.88x..1.12x**, with at most 10% age expansion, rather than inheriting an
  arbitrarily enlarged current person bbox;
- temporal fallback after a trusted face also uses absolute trusted face size,
  bounded to 0.90x..1.12x person-scale change and only 1.24x conservative
  expansion;
- the no-history bootstrap YOLO-head fallback is independently tightened to a
  face/head-sized region so a right-edge first-frame detector rejection cannot
  create a head-and-shoulders sticker.

The same diagnostic showed that FULL_BODY ID 4 still had **30 association
ambiguities, 11 reacquire starts, and 9 reacquire timeouts**. Its privacy resolver
also accepted roughly **199 foreground-occluder carves**, while all 10 recorded
`PRIVACY_COVERAGE_DROP` events belonged to target 4. Two additional protections
therefore apply to all identity-protected privacy targets and specifically to the
mixed primary compositor:

- while a protected identity is unobserved/REACQUIRING/LOST, its predicted bbox
  is bounded around the latest reliable/occlusion-motion anchor: center travel is
  capped at 0.30 of the anchor reference dimension and bbox scale at
  **0.82x..1.18x**. Stale Kalman motion can no longer carry a full-body or face
  privacy mask across neighboring dancers;
- mixed FULL_BODY + FACE_ONLY primary composition enables a conservative
  unobserved-occluder policy. ACTIVE targets retain normal depth carving;
  OCCLUDED targets may be carved only by a TrackManager-explicit occluder;
  REACQUIRING/LOST targets are privacy-first and reject all external carving.
  Pure FULL_BODY behavior and the FACE_ONLY secondary sticker occluder path keep
  their historical policies.

Regression coverage now includes a five-target startup acquisition test, a
detector-miss trusted-prediction size cap, rejection of detector calls from an
unobserved REACQUIRING target, merged-bbox face-size bounding, protected
unobserved bbox travel/scale bounding, and mixed-mode explicit-vs-unconfirmed
occluder controls. On PLK110 / Android 16 this revision passes:

- `FaceOnlyPrivacyFrameProcessorInstrumentedTest`: **8/8**;
- Preview FACE_ONLY/mixed acceptance: **1/1**;
- Export static/dynamic/mixed acceptance: **3/3**;
- foreground production stress: **300/300 frames**;
- complete `dance_native` JVM unit suite: **all passed**;
- Flutter tests: **14/14**, `flutter analyze`: **0 issues**.

Export diagnostics now also aggregate `face_detector_rejected_calls_by_track_id`
and record whether the conservative mixed FULL_BODY occluder policy is enabled,
so the next real-video bundle can directly show whether the previous 423 rejected
calls and target-4 carve instability were reduced.

### Fourth real-video correction: offscreen dormancy and identity-local face refresh

The next real-video bundle came from commit
`e356c1779d695608d96e38936af095494ff43dea` on the same 751-frame / 60 fps
one-FULL_BODY plus five-FACE_ONLY clip. The prior identity tightening worked as
intended: detector rejections fell from **423 to 8**, and FACE_ONLY sticker width
maxima fell to approximately **65.5 / 100.9 / 125.6 / 76.5 / 67.7 px** for IDs
1/2/3/5/6. However the user still reported two distinct defects: the selected
FULL_BODY subject was a fast irrelevant passer who exited the frame but left a
residual mask that interfered with the remaining scene, while FACE_ONLY stickers
still lagged head motion during body actions.

The face telemetry explains the follow defect. The safer scheduler produced only
**387 detector calls**, **377 DETECTED**, **1,238 PREDICTED**, and **2,140
FALLBACK** track-frames. Per-track calls were only 58/46/27/126/130 for IDs
1/2/3/5/6. In other words, removing broad detector work from unobserved person
boxes eliminated false neighboring-face evidence but also left too much motion to
body-box prediction, which cannot follow a head moving inside a comparatively
stable torso/person bbox.

FACE_ONLY therefore now uses a two-stage detector geometry:

- first acquisition still uses the YOLO-owned source head ROI and may burst across
  all never-attempted FACE_ONLY IDs;
- after a trusted face exists, a **small identity-local ROI** is centered on the
  projected recent face, with side length at least 72 source pixels and otherwise
  about 2.8x the trusted face diameter;
- this local ROI may refresh a recently trusted face during short
  REACQUIRING/OCCLUDED intervals, because identity is constrained jointly by the
  YOLO track and the recent face location instead of by a broad predicted person
  head box;
- detector cadence is raised to about **30 Hz (33 ms)**, still capped at two
  steady-state ROI calls per output frame, so a five-person 60 fps export can
  refresh local head motion much more frequently;
- local detector results update the face **center** but do not directly redefine
  face size. The previous source-space trusted size, adjusted only by the bounded
  person-scale projection, is retained. This prevents a local-ROI feedback loop
  where detector box extent changes the next ROI size and then inflates the
  sticker again.

The first device Export run with local ROIs confirmed the position path but caught
exactly that size-feedback failure: the dynamic sticker-height ratio reached
**1.399** (heights 228/313/319/298/279/283/312) against the existing <=1.35
gate. After local detections were changed to center-only measurements for size
purposes, the same Export **3/3** regression passed again. A dedicated processor
test now deliberately doubles the local detector box extent while moving its
center and requires the sticker to move without growing more than 15%.

The FULL_BODY residue had a different cause. In the diagnostic trace selected
track 4 was still identity-protected after leaving view. Its final accepted boxes
moved rapidly downward with bottom edges approximately **975.9 -> 997.0 ->
1022.2 -> 1049.1 px** in a 1080 px frame. Once detections stopped, the normal
protected lifecycle could retain rendering through the REACQUIRING grace and LOST
window. Track 4 accumulated **31 association ambiguities, 11 reacquire starts, 9
reacquire timeouts, 6 protected-retained-LOST events**, and all 10 recorded
privacy coverage drops. Keeping that no-longer-visible protected slot in
occlusion-group and scene-motion calculations could also perturb nearby FACE_ONLY
identity geometry.

Mixed-mode FULL_BODY tracking therefore adds an opt-in **offscreen dormant** state:

- it is enabled only for mixed-mode FULL_BODY privacy targets; the historical
  FULL_BODY-only pipeline is unchanged;
- when the last reliable bbox is within 6% of a frame edge, reliable observed
  motion is clearly outward (at least 3% of the relevant bbox dimension per
  frame), and that protected target is then unmatched, the first missed frame
  immediately stops rendering its full-body mask;
- the identity record is retained as LOST/dormant rather than destroyed, so a
  strong later recovery can restore the same selected ID;
- dormant tracks are removed from occlusion groups and excluded from scene-motion
  estimation, preventing an offscreen stale selected person from continuing to
  steer visible FACE_ONLY tracks;
- a `PROTECTED_OFFSCREEN_DORMANT` diagnostic event records the ID, last bbox,
  predicted bbox, outward motion, and PTS so the next real-video bundle can show
  whether this path fired.

Regression coverage explicitly proves that an outward fast protected edge exit
clears the render mask on the first missed frame, that strong re-entry restores
the original identity, and that the same sequence retains the historical LOST
mask when the mixed-mode dormancy policy is disabled.

PLK110 / Android 16 verification for this correction:

- focused offscreen/crossing/recovery JVM tests: **all passed**;
- `FaceOnlyPrivacyFrameProcessorInstrumentedTest`: **9/9**;
- Preview FACE_ONLY/mixed acceptance: **1/1**;
- Export static/dynamic/mixed acceptance: **3/3** after the local-size feedback
  gate caught and corrected the initial 1.399 ratio;
- foreground production stress: **300/300 frames** at the new 33 ms cadence,
  with the stress contract requiring 300 detector calls, 300 DETECTED frames,
  0 PREDICTED, 0 FALLBACK, and 0 rejected calls;
- complete `dance_native` JVM unit suite: **all passed**;
- Flutter tests: **14/14**; `flutter analyze`: **0 issues**.

This revision remains a candidate until the same real clip confirms both that the
fast irrelevant FULL_BODY target disappears without residue and that FACE_ONLY
stickers remain attached during articulated head/body motion.

### Fifth real-video correction: keep long-LOST body tombstones inert and bridge face-local motion

The next 751-frame real-video export came from commit
`71c6e57a8f97b9fcd486d246907d183dfa80f897`. It confirmed that the previous
offscreen-edge path was too narrow: `PROTECTED_OFFSCREEN_DORMANT` fired only once,
near the end of the clip, while the user still saw an unrelated FULL_BODY mask
appear earlier. The trace exposed a separate long-absence resurrection path.

Selected FULL_BODY id 4 had no reliable assignment commit from approximately
**0.100 s to 10.725 s**. At 10.675 s the retained protected LOST slot was pulled
into REACQUIRING by overlap/group logic, and at 10.725 s it committed to a nearby
detection with only **bbox IoU 0.458 / mask IoU 0.20**. Those values are below the
strict protected LOST recovery rule (bbox >= 0.50 or mask >= 0.45), but changing
the slot from LOST to REACQUIRING first also changed which identity-evidence gate
was used. Worse, that transition could reconstruct `currentRenderMask` from the
old canonical segmentation before any identity commit succeeded. This directly
explains a stale selected full-body mask reappearing on an unrelated person.

Mixed mode now treats a protected FULL_BODY slot that is past the visible LOST
window and has no current render mask as an identity-only tombstone. Such a slot:

- remains LOST until the existing strict LOST recovery path proves identity;
- is excluded from occlusion groups and scene-motion estimation;
- cannot be converted to OCCLUDED/REACQUIRING merely because a fresh neighboring
  person overlaps its stale predicted/last-observed geometry;
- cannot revive its previous full-body mask before strict recovery succeeds.

The policy remains gated by mixed-mode offscreen dormancy, so historical
FULL_BODY-only LOST behavior is unchanged. A regression reproduces the retained
selected tombstone plus weak nearby entrant and requires the selected ID to remain
LOST with a null mask. The focused LOST/privacy suite passes **14/14**.

The same bundle also showed that FACE_ONLY follow was still detector-starved in
exactly the difficult identity periods. Across five FACE_ONLY IDs and 751 output
frames, the export produced **1,130 detector calls, 1,048 DETECTED, 887 PREDICTED,
and 1,820 FALLBACK track-frames**. Calls were highly uneven: IDs 1/2/3/5/6 received
123/289/62/358/298 calls respectively. ID 3 had only 104 reliable TrackManager
assignment commits while accumulating 137 association ambiguities and 23
reacquire starts. The current processor completely stopped local face detection
in LOST, then quickly fell back to body-box head geometry, so articulated head
motion could visibly outrun the sticker even though the face detector itself was
healthy.

FACE_ONLY therefore keeps the detector budget unchanged but uses it differently:

- a recently trusted face may continue using only its small identity-local ROI
  through short YOLO-unobserved/LOST intervals;
- this bridge is bounded to **30 unobserved output frames** and never opens the
  broad YOLO-head acquisition ROI while LOST; after the bound it stops and falls
  back conservatively, so face localization cannot become an independent identity
  tracker;
- accepted consecutive local detections estimate bounded face-relative velocity
  after subtracting current person-box translation;
- between ~30 Hz detector refreshes, that velocity is extrapolated for at most
  100 ms and at most 0.75 face diameters, letting 60 fps output frames continue
  recent head motion instead of freezing to the torso/person bbox;
- broad reacquisition after cache expiry resets relative velocity rather than
  deriving motion across a stale gap.

No extra steady-state detector calls per frame were added because the 71c6e57
bundle already measured face-detector CPU p95 near **47 ms** and FACE_ONLY privacy
p95 near **113 ms**. Local verification after this correction passes the complete
`dance_native` JVM unit suite and compiles all Android instrumentation tests. The
device is intentionally disconnected; real-video visual acceptance remains a
manual follow-up.

### Sixth real-video correction: use the validated person mask as face fallback geometry

The next user-reviewed real-video bundle came from commit
`20b0a8c301b68f89d02dba217c160a14c8755c91`. The stale FULL_BODY resurrection
fix remained separate, but FACE_ONLY localization was still visibly inaccurate.
The telemetry showed that the velocity bridge did not solve the dominant problem
and in fact made the identity-local ROI easier to drift away from the face:

- 751 output frames / five FACE_ONLY IDs = 3,755 target track-frames;
- **979 detector calls**, only **736 DETECTED** track-frames;
- **938 PREDICTED** and **2,081 FALLBACK** track-frames;
- **144 detector rejections**, concentrated on IDs 2/5/6 at 31/52/46;
- detector calls by ID 1/2/3/5/6 were 139/150/65/315/310, while detected frames
  were only 109/105/39/237/246;
- current sustained timings were face detector p95 about **43 ms** and total
  FACE_ONLY privacy p95 about **102 ms**.

The important architectural observation is that the already-stable YOLO person
segmentation remains available on every `TrackedPerson` before FACE_ONLY policy
substitutes the rendered face mask. Earlier real-video acceptance had already
shown that this full-person mask follows the selected person accurately. FACE_ONLY
can therefore reuse that existing per-ID segmentation as **local position
evidence** without ever rendering it as a full-body privacy effect.

The fallback order is now:

1. accepted MediaPipe face detection;
2. for non-detected frames, current same-ID YOLO person mask locally refines the
   recent face/head seed;
3. only when the person mask is unavailable/not freshly observed does geometry
   remain on the conservative bbox-derived head fallback.

`BodyMaskFaceHeadEstimator` searches only a small upper-body window around the
existing face seed. It computes a weighted centroid of current person-mask pixels,
with distance-to-seed weighting and bounded per-frame correction. This lets an
articulated head move inside an otherwise stable person bbox while preventing a
raised arm or distant body part from stealing the face center. The body mask is
used only as geometry evidence; FACE_ONLY still renders only the face ellipse /
sticker. YOLO/TrackManager remains the sole identity authority.

The previous face-relative velocity extrapolation is removed from ROI planning and
fallback. Without current pixel evidence, detector history no longer moves the ROI
by itself. When a fresh body mask exists, the same mask-guided center is also used
to recenter the next small identity-local detector ROI, directly addressing the
20b0a8c rise to 144 rejected detections.

MediaPipe BlazeFace's six detector keypoints are now retained as positional
evidence rather than discarded. The central eye/nose/mouth features drive
candidate anchor distance and contribute most of the detected privacy center;
the detector bbox remains the scale prior. Backends/test observations without
keypoints keep the previously validated bbox-only placement exactly.

Export diagnostics add `face_body_mask_guided_track_frames` and
`face_body_mask_guided_frames_by_track_id`, so the next manual real-video bundle
can directly show whether the 2,081 previous non-detected/fallback frames are now
being steered by the accurate same-person segmentation instead of bbox geometry.

Local verification for this correction includes the complete `dance_native` JVM
suite and successful Android instrumentation compilation. New pure tests verify
that a shifted head silhouette moves the fallback, a distant raised arm does not
steal it, missing mask mapping fails safe, profile-like detector boxes can be
localized by central face keypoints, and keypoints move privacy placement away
from a pose-skewed bbox center. Connected-device execution remains intentionally
manual.

### Seventh real-video correction: body mask is a motion constraint, not a face centroid

The next 751-frame real-video bundle came from
`3eee279ed0a3d47a18408c96c1857dc8c31a2f63`. Detector-side metrics improved, but
the user still saw both a stray FULL_BODY mask and visible FACE_ONLY drift:

- detector rejections fell from 144 to **54**;
- DETECTED track-frames rose from 736 to **847**;
- PREDICTED track-frames were **1,004** and FALLBACK remained high at **1,904**;
- body-mask guidance ran on **810** track-frames;
- IDs 1/2/3 still spent **502 / 456 / 637** frames in fallback despite the
  detector/keypoint improvements;
- FACE_ONLY p95 dropped to about **62 ms** and detector p95 to about **40 ms**, so
  the remaining visual error is not explained by a new processing stall.

This disproves the assumption that a local centroid of an accurate full-person
segmentation is itself a reliable face observation. The person mask is stable for
identity/pixel ownership but has no semantic distinction between head, shoulders,
hair, hands, or arms. When those pixels enter the local search window, centroid
guidance can visibly pull the FACE_ONLY sticker even though the full-body mask is
correct.

The body-mask path is therefore narrowed again:

- the person mask no longer recenters the identity-local **detector ROI** at all;
  detector ROI geometry stays anchored to the last accepted face plus bounded
  person-box translation, so an imperfect body estimate cannot recursively steer
  future detector observations;
- on non-detected output frames the body mask is still allowed to provide motion
  evidence, but `BodyMaskFaceHeadEstimator` now scans horizontal mask runs and
  accepts only narrow, head-like runs close to the trusted face seed;
- wide unions caused by shoulders or an arm crossing the face window are rejected
  instead of contributing to an absolute upper-body centroid;
- per-frame mask correction bounds are tightened. If the stable body silhouette
  cannot make a local head-like case, holding the trusted face seed is preferred
  over following another body feature.

The FULL_BODY interference in this bundle is also more specific than an offscreen
exit. Selected id 4 had reliable assignment commits only through about **0.100 s**,
then remained unobserved through OCCLUDED/REACQUIRING/LOST handling.
`PROTECTED_OFFSCREEN_DORMANT` fired **0 times**, so the edge-exit shortcut could not
help. The old canonical segmentation could still be warped and rendered during
the OCCLUDED/REACQUIRING grace even though no new id-4 observation had validated
those pixels.

Mixed mode now separates identity retention from mask retention more aggressively:

- a selected FULL_BODY identity may still remain OCCLUDED/REACQUIRING/LOST and
  later recover the same ID;
- but after **three consecutive real detection misses** its old full-body render
  mask is cleared, regardless of which unobserved state is carrying the identity;
- `MIXED_FULL_BODY_STALE_MASK_SUPPRESSED` records the first suppression frame;
- the policy is enabled only by the existing mixed-mode dormancy flag, so
  FULL_BODY-only tracking keeps the historical LOST/occlusion mask behavior.

Focused regressions prove both sides: mixed FULL_BODY clears the stale mask on the
third real miss without removing the protected identity, while the same three-miss
sequence in FULL_BODY-only mode still retains its legacy mask. Body-mask tests now
also cover an arm crossing through the local face window and require wide arm /
shoulder rows not to steal the face estimate.

Export diagnostics additionally record `face_sticker_max_center_step_by_track_id`
so the next real bundle can quantify any remaining abrupt face motion instead of
inferring drift from size ranges alone.

### Eighth real-video correction: clamp only non-physical face motion relative to the person

The next 751-frame manual bundle is from
`141d139bfc077859278657e52adf4fd6b0aa5229`. It validates the mixed FULL_BODY
stale-mask fix and isolates the remaining FACE_ONLY defect more sharply.

FULL_BODY id 4 had only four reliable commits, ending at **0.100066 s**. The new
mixed-mode render policy fired exactly as designed at **0.150100 s** with
`MIXED_FULL_BODY_STALE_MASK_SUPPRESSED` after the third real miss. No later id-4
assignment commit succeeded. Subsequent weak group candidates remained ambiguous
and failed protected identity evidence, so this bundle provides positive evidence
that the old id-4 full-body segmentation is no longer being resurrected after the
suppression point. The threshold is therefore not tightened again.

FACE_ONLY metrics are roughly stable rather than materially better or worse:

- detector calls: **997**;
- rejected calls: **65**;
- DETECTED / PREDICTED / FALLBACK track-frames: **853 / 1,008 / 1,894**;
- body-mask-guided track-frames: **447**, down from 810 after the narrow-run gate;
- detector CPU p95: about **46 ms** and total FACE_ONLY privacy p95: about
  **112 ms**.

The new center-step metric reveals the remaining visual failure directly. Maximum
one-output-frame sticker-center movement for IDs 1/2/3/5/6 was approximately
**197 / 100 / 90 / 106 / 112 px**. Reliable person detection-bbox center jumps
were substantially smaller, so the extra motion is introduced by face geometry
switching rather than by whole-person tracking. At 60 fps, a 90-197 px residual
head jump is not physically plausible for these roughly 29-119 px stickers.

`FacePrivacyTemporalStabilizer` now separates whole-person translation from
face-relative motion. Current person-box translation is applied immediately, so a
fast dancer moving across the frame is not smoothed or delayed. Only the residual
face displacement after subtracting that person motion is gated. If a DETECTED /
PREDICTED / FALLBACK switch asks for an implausible residual within 100 ms, the
center still moves toward the newest evidence but is limited to a face-scale step.
Normal residual movement passes through unchanged. This is a jump gate rather
than the earlier generic center low-pass that caused visible trailing.

The next export summary additionally reports
`face_position_clamped_track_frames` and
`face_position_clamped_frames_by_track_id`. Acceptance should therefore check
both that the 90-197 px maxima collapse materially and that the clamp count is
concentrated on the visually problematic source-transition periods rather than
ordinary motion.

### Ninth real-video correction: do not let a clamped bad detection poison the next ROI

The next manual bundle contains a controlled before/after export on the same
751-frame clip and inference sequence. Although the manifest still names
`141d139bfc077859278657e52adf4fd6b0aa5229`, the later export was built from the
working tree immediately before commit `860da63e0b97e8578f52847776182c1e4da3f1f1`;
the presence of `face_position_clamped_*` fields confirms that the position gate
is active. Detector/fallback counts are byte-for-byte comparable between the two
exports: 997 calls, 65 rejections, 853 DETECTED, 1,008 PREDICTED, 1,894 FALLBACK,
and 447 body-mask-guided track-frames.

The gate activated on **351** track-frames. It reduced the recorded maximum
placement-to-placement jump for IDs 2 and 3 from about **100/90 px** to
**68/60 px**, proving that the residual-motion gate is acting on real source
transitions. IDs 1/5/6 still retained old maxima around 197/106/112 px. Two
remaining implementation details explain why a render-only gate was incomplete:

- `CachedFaceGeometry` was committed from the raw accepted detector region before
  `FacePrivacyTemporalStabilizer` applied the position gate. A bad-but-owned face
  candidate could therefore be visually clamped on one frame while immediately
  moving the next identity-local detector ROI to the raw bad center. The
  stabilized DETECTED center is now the only center allowed to become the trusted
  face cache.
- whole-person translation was exempt from the residual gate even when the person
  bbox itself was only an unobserved TrackManager prediction. Reliable same-clip
  detection commits show consecutive person-center movement far below the largest
  sticker jumps. Full person translation therefore remains immediate only across
  consecutive observed frames; during unobserved/reacquiring transitions the
  person component also receives a generous face-scale step bound before the
  residual face gate is applied.

The old `face_sticker_max_center_step_by_track_id` statistic can also bridge a gap
where `FaceStickerPlacement.from()` returned null because a sticker was fully out
of frame, then count the next in-frame placement as though it were adjacent.
Exports now additionally report
`face_sticker_max_consecutive_center_step_by_track_id`, which only compares
placements from consecutive output frame numbers. Future visual acceptance should
prefer that metric when deciding whether a visible one-frame drift remains.

### Tenth real-video correction: person-box top is not a stable face-motion anchor

The next complete 751-frame export is from
`74b6a3e949136e983d226f0bb353070b315070b2`. The post-gate cache correction is
strongly validated by the real clip:

- detector rejections fell from **65 to 35**;
- DETECTED track-frames rose from **853 to 928**;
- FALLBACK track-frames fell from **1,894 to 1,814**;
- position-clamped track-frames fell from **351 to 65** instead of repeatedly
  fighting the same poisoned ROI state;
- maximum *consecutive* sticker-center steps for IDs 1/2/3/5/6 were about
  **78 / 68 / 74 / 67 / 92 px**, substantially below the earlier
  **197 / 100 / 90 / 106 / 112 px** maxima.

The remaining ID-6 peak exposes a separate geometry assumption. Consecutive
accepted YOLO observations at 60 fps changed that same person's bbox top from
about **380.5 px to 479.1 px** in one frame (roughly **98.6 px**), while the bbox
bottom changed only about **2.7 px** and the Kalman prediction stayed near the
same location. This is detector-box shape/upper-body coverage jitter, not a
physical 99 px vertical translation of the person. IDs 2/3/5 show the same
pattern at smaller magnitude: their largest top-edge jumps are much larger than
their corresponding bottom-edge motion.

Both short-term face projection and the temporal position gate therefore stop
using person-bbox `top` as the vertical whole-person motion anchor. Horizontal
translation still follows bbox center X immediately, while vertical body
translation follows the bbox **bottom/foot edge**. Actual head motion remains
owned by accepted face evidence and the bounded residual path. This prevents a
YOLO upper-silhouette height change from moving the face detector ROI or sticker
as though the entire dancer jumped vertically.

### Eleventh real-video correction: opposite box edges must agree on body motion

The next complete 751-frame export is from
`dfd91d6daed7b72087ed51dcec967e5f1591d8a5`. The foot-edge change improves the
remaining largest visible transitions, but also disproves the stronger assumption
that the lower edge is always stable:

- maximum consecutive sticker-center steps for IDs 1/2/3/5/6 are about
  **78.3 / 67.8 / 72.9 / 59.2 / 81.9 px**;
- ID 6 improves from about **92.4 to 81.9 px** and ID 5 from **67.4 to 59.2 px**;
- detector calls/rejections are **1000 / 47**, with **914 DETECTED**, **1019
  PREDICTED**, **1822 FALLBACK**, and **79** position-clamped track-frames;
- FACE_ONLY p95 is about **71 ms** and detector CPU p95 about **51 ms**, so the
  remaining motion is not explained by a processing stall.

The accepted YOLO boxes show both forms of one-sided shape jitter. ID 6 still has
the previously observed ~99 px top-edge jump with only ~3 px bottom motion, but it
also has consecutive observations where the **bottom edge jumps ~58 px while the
top moves almost 0 px**. A permanently selected top or bottom anchor therefore
cannot distinguish physical translation from changing segmentation-box extent.

`PersonBboxMotionEstimator` now estimates short-term body translation from both
opposite edges on each axis. When left/right or top/bottom move by approximately
the same amount, their average is accepted as whole-person translation. When the
edges disagree beyond a bounded fraction of the box dimension, the quieter edge
is used and the larger edge change is treated as box-shape jitter. The same rule
feeds both `CachedFaceGeometry.project()` and `FacePrivacyTemporalStabilizer`, so
ROI prediction and rendered sticker motion share one body-motion definition.

FULL_BODY id 4 remains unchanged: stale-mask suppression still fires at pts
150100 after the third real miss, and the latest bundle again contains only four
successful id-4 assignment commits ending at pts 100066.

### Twelfth real-video correction: a retained identity must not render a stale face for seconds

The next complete 751-frame export is from
`a9eb7906f2d54fde6fa340be5e0494a4d917eec8`. Four-edge box-motion consensus
continues to improve the actively observed people: ID 6's maximum consecutive
sticker-center step drops from about **81.9 px to 49.2 px**, and ID 2 drops to
about **63.1 px**. IDs 1/3/5 remain roughly **78.9 / 73.2 / 59.6 px**, however,
and the user still reports other face stickers drifting.

The remaining telemetry shows that those cases are dominated by a different
lifecycle problem rather than another choice of bbox edge. ID 1 has no successful
YOLO observation from roughly **1.20 s through 9.41 s** (an **8.21 s** gap), while
ID 3 has an approximately **8.09 s** gap. ID 2 contains multiple long gaps of
about **2.80 s, 3.99 s, and 1.77 s**. Despite those missing identity observations,
FACE_ONLY continued producing per-frame generic/predicted fallback geometry;
ID 1/2/3 therefore accumulated **478 / 448 / 615 fallback frames**. An opaque
sticker following a protected TrackManager tombstone for many seconds is the
visual "floating face" failure and no four-edge translation estimator can make
that stale geometry correct.

FACE_ONLY now separates identity retention from render retention, matching the
principle already validated for mixed FULL_BODY. A short **150 ms** YOLO-miss
window still bridges ordinary detector/tracker dropouts. Beyond that age the
protected identity remains available for same-ID reacquisition, but FACE_ONLY
stops detector ROI work, drops cached face/temporal state, and emits neither a
face mask nor a sticker until YOLO observes the same identity again. Dormant
FACE_ONLY tracks are also removed from the secondary privacy selection and their
stale body masks are nulled in that secondary pass, so intentionally suppressing
the stale sticker cannot trigger `PersonPrivacyPolicyAdapter`'s fail-closed
FULL_BODY escalation or carve another active face privacy mask.

Export summaries add `face_dormant_suppressed_track_frames` and
`face_dormant_suppressed_frames_by_track_id`. On the next real bundle these counts
should concentrate on the multi-second ID-1/2/3 observation gaps, while active
observed dancers continue using the four-edge `PersonBboxMotionEstimator`.

### Thirteenth real-video correction: bridge fast motion with the stable body mask before dormancy

The first real export from `509b5e891201837083c28a3be96d64d2b5966141`
validates the stale-face cutoff but shows that a hard 150 ms boundary is too
aggressive for dance motion. Drift is now controlled: the maximum *consecutive*
sticker-center steps for IDs 1/2/3/5/6 are only about **31 / 40 / 45 / 46 / 36
px**, and 851 detector calls produce only 11 rejected calls. The cost is visible
dropout: **2,051 FACE_ONLY track-frames** are dormant-suppressed (IDs 1/2/3/5/6 =
499/517/648/184/203).

The runtime therefore uses a three-stage FACE_ONLY lifecycle rather than simply
raising the stale timeout. Up to 150 ms after the last exact YOLO observation the
existing direct bridge is unchanged. From 150 ms through **800 ms**, a track may
enter `BODY_MASK_COMPENSATED` only when it still has both a trusted face cache and
a TrackManager full-body render mask. In that window the stable full-body mask is
allowed to nudge the cached face through the already-constrained narrow head-run
estimator, while four-edge body translation and the temporal residual gate remain
active. Sticker size expands gradually by at most 18% to preserve privacy under
motion blur. The face detector keeps attempting the identity-local ROI and
immediately reanchors on success.

If the body mask disappears, no trusted face exists, or the exact YOLO gap exceeds
800 ms, the identity becomes dormant exactly as in 509b5e8. Thus body segmentation
is a bounded motion compensator, never a new identity authority, and the former
multi-second floating-face failure remains impossible. New diagnostics report
`face_body_compensated_track_frames` and
`face_body_compensated_frames_by_track_id` separately from true dormancy.

### Fourteenth real-video correction: use uncommitted fresh YOLO geometry as motion-only evidence

The first real export from `c8b824aeaed97e2f29feafd4cf81f681c31e4d26`
shows that the bounded body-mask bridge is safe but still too dependent on the
TrackManager-owned render mask. Compared with the previous hard-dormancy export,
body compensation rescues **414** track-frames, but **1,637** track-frames remain
dormant. Per FACE_ONLY ID, body-compensated / dormant frames are roughly
**55/444, 116/401, 54/594, 129/55, 60/143** for IDs 1/2/3/5/6. The result matches
the user's report: ID 5 benefits strongly, while IDs 1 and 3 still disappear for
long stretches.

Those stretches are not empty YOLO frames. The same trace contains hundreds of
strict-association deferrals for FACE_ONLY identities: `ASSOCIATION_AMBIGUOUS`
counts for IDs 1/2/3/5/6 are about **65 / 81 / 178 / 72 / 5**, and their
occlusion groups reserve many current-frame detections rather than committing an
identity. TrackManager therefore often has fresh segmentation geometry but
correctly refuses to reset identity state because the second-best margin is too
small.

TrackManager now exposes a read-only `ProtectedTrackMotionEvidence` only when an
uncommitted protected FACE_ONLY candidate is still reciprocal-best in both
directions, passes the protected absolute bbox/mask gate, has a current mask, and
fails identity commit because ambiguity remains. Export/preview may use that
current detection's bbox/mask to move an already-trusted face anchor and refresh
the face ROI, but the evidence never calls Kalman update, never changes
`observedThisFrame`, never resets LOST/REACQUIRING counters, and never changes
privacy identity. FULL_BODY-selected tracks are explicitly excluded.

Unlike the stale 800 ms fallback, this path may remain active beyond 800 ms only
while qualifying current-frame body evidence continues to arrive. A frame that
lacks both trusted TrackManager body geometry and fresh motion-only evidence falls
back to the existing dormancy policy. Diagnostics add
`face_fresh_body_motion_track_frames` and
`face_fresh_body_motion_frames_by_track_id` so the next real-video bundle can
separate rescue from stale prediction.

### Fifteenth real-video correction: motion-only evidence must not reuse the identity gate

The next complete export is from
`43e31db85fdc35b20acb148929e6ea6373ea238f`. This run selects IDs 1-6 as
FACE_ONLY. The first uncommitted-body path remains visually controlled but is far
too sparse: only **36 track-frames** use `face_fresh_body_motion` (ID 2 = 11,
ID 5 = 25), while **2,213 track-frames** are dormant-suppressed. Excluding newly
FACE_ONLY ID 4 for a like-for-like comparison with the previous five-person run,
dormant frames are about **1,618 vs 1,637**, so coverage barely changes.
Consecutive sticker-center maxima remain approximately **47 / 60 / 56 / 47 / 47 /
41 px** for IDs 1-6, confirming that dropout, not renewed large drift, is now the
dominant failure.

The reason is visible directly in the association telemetry. ID 1 has 65 group
`ASSOCIATION_AMBIGUOUS` events and ID 3 has 135, but the protected identity
absolute-evidence gate is false on every event for both IDs. Median bbox IoU is
only about **0.21 / 0.24** and median mask IoU about **0.074 / 0.071**, while a
protected REACQUIRING identity commit deliberately requires bbox IoU >= 0.45 or
mask IoU >= 0.25. That gate is appropriate for transferring identity, but much
too strict for read-only motion that never updates identity state.

Motion-only evidence now uses a separate gate: bbox IoU >= **0.20** or mask IoU
>= **0.08**. The assigned detection must still be the column-best match, must meet
the ordinary minimum association score, and must lie within two configured
ambiguity margins of the row-best. The identity commit gate itself is unchanged.
Replaying the latest group ambiguity telemetry against this rule yields roughly
35/65 eligible ID-1 events and 84/135 eligible ID-3 events instead of zero.

Fresh motion evidence can also be intermittent. FACE_ONLY therefore retains only
the most recent body **bbox** for the same 150 ms short bridge already used for
direct misses. It deliberately does not retain the prior segmentation buffer.
Diagnostics add `face_recent_body_motion_bridge_track_frames` and
`face_recent_body_motion_bridge_frames_by_track_id` so fresh current-frame body
evidence and short temporal bridging can be evaluated separately on device.

### Sixteenth real-video correction: dormancy must hide, not erase, the trusted face anchor

The first complete export containing the relaxed motion-only gate still reports
**1,637 dormant track-frames**, exactly matching the earlier five-person body-mask
bridge run. Fresh ambiguous body motion rises to 48 frames and the new 150 ms
recent-body bridge contributes another 58, but total body-compensated frames remain
stuck at **414**. This proves the new evidence is arriving only while the face is
already renderable; it cannot recover a track after dormancy.

The processor was deleting `cachedFaceByTrackId` as soon as a FACE_ONLY track
entered dormant state. That cache contains the last post-gate *detected* face
anchor, not a continuously extrapolated stale sticker. Once deleted, a later fresh
ambiguous YOLO body detection has no trusted face anchor to move, so the policy's
"fresh body motion may bridge beyond 800 ms" path can never actually reactivate.

Dormancy now clears render/temporal state exactly as before but retains the hidden
detected-face anchor for as long as the protected FACE_ONLY identity remains
active. No sticker is drawn from this anchor during dormant frames. A current-frame
fresh body-motion detection may reactivate it; the 150 ms recent-body bridge can
then maintain continuity between fresh detections. The cache is removed when the
FACE_ONLY identity itself is removed or deselected. Diagnostics add
`face_dormant_reactivated_by_fresh_motion_events` and a per-track breakdown so the
next device run can verify that fresh ambiguity evidence is now rescuing previously
dormant IDs rather than merely replacing geometry inside the original 800 ms
window.

### Seventeenth real-video correction: confirm dormant reactivation before rendering

The first complete export from `2ae9d22255344121ebf668c1d3fdf247f15d6a58`
proves that preserving the hidden detected-face anchor fixed the dead reactivation
path. On the same five-FACE_ONLY clip, fresh motion rose from **48 to 241**
track-frames, recent-body bridging from **58 to 184**, body compensation from
**414 to 795**, and dormant suppression fell from **1,637 to 1,256**. The new
counter recorded **20 dormant reactivations**: IDs 1/2/3/5/6 = 3/6/7/3/1.

That coverage gain exposed the next boundary. Maximum consecutive sticker-center
steps increased to approximately **114.5 / 94.0 / 109.0 / 94.3 / 94.1 px** for
IDs 1/2/3/5/6, versus about **86.9 / 67.4 / 95.9 / 50.4 / 40.0 px** in the
previous build. Replaying the association diagnostics also shows that several
reactivations start from a single isolated motion-only hit: for multiple IDs the
next qualifying fresh sample does not arrive for hundreds of milliseconds or
more. The processor previously rendered immediately on that first hit.

Dormant reactivation now therefore uses a narrow confirmation state:

- the first current-frame `ProtectedTrackMotionEvidence` sample for a dormant
  FACE_ONLY ID is held as a non-rendering probe;
- a second independently-qualified fresh sample for the same protected ID must
  arrive within the existing **150 ms** recent-body bridge window before the
  hidden face anchor may return to `BODY_MASK_COMPENSATED`;
- recent bbox bridging by itself cannot confirm reactivation, and no old
  segmentation mask is retained;
- an exact YOLO observation cancels the pending probe immediately and returns to
  the normal direct path;
- TrackManager identity gates, the motion-only overlap gate, the 800 ms stale
  limit, and FULL_BODY behavior remain unchanged; no generic face-center smoothing
  is added.

Export diagnostics additionally record
`face_dormant_reactivation_pending_track_frames` and a per-track breakdown. The
next real-video bundle can therefore distinguish single-hit probes filtered by the
new gate from confirmed dormant recoveries without weakening identity semantics.

### Eighteenth real-video correction: motion opens a face probe, never a dormant sticker

The complete `6644760da2ffe02b369c65324c70f9a644b4954c` export disproves the
two-motion-sample confirmation as a sufficient safety boundary. It records 66
pending track-frames and reduces dormant reactivation from 20 to **15 events**, but
dormant suppression rises only modestly from 1,256 to **1,322** track-frames and
the worst consecutive center jumps remain severe: IDs 1/2/3/5/6 are about
**114.8 / 60.0 / 110.9 / 90.5 / 73.7 px**. In particular, ID1 and ID3 are
essentially unchanged from the one-sample build. Repeated motion-only evidence can
therefore be internally consistent while still describing the wrong ambiguous body
candidate; a second body sample is not independent face-localization evidence.

The same bundle exposes a second regression introduced when dormant face anchors
started surviving. Maximum sticker widths are approximately **156.5 / 139.2 /
187.2 / 256.4 / 213.2 px** for IDs 1/2/3/5/6. Before dormant anchors could
reactivate, the comparable five-person build was about **121.0 / 170.3 / 130.0 /
102.6 / 100.7 px**. ID5 and ID6 therefore reach roughly **2.50x / 2.12x** their
previous maxima. This is not a PNG/render-scale issue: the hidden detected-face
radius is being reintroduced through geometry that may apply bounded current-body
scale, cache-age expansion, and body-compensation expansion after the temporal
stabilizer state has deliberately been cleared for dormancy. Repeated dormant /
local-refresh cycles can then preserve an enlarged trusted size.

Dormant recovery is therefore changed from a body-motion confirmation gate to a
detector-confirmed probe:

- current-frame `ProtectedTrackMotionEvidence` for an already-dormant FACE_ONLY
  identity may open an **identity-local face-detector probe only**; it does not
  make the track renderable;
- the probe ROI translates the hidden detected-face anchor with the current body
  geometry but keeps the hidden source-space face radius unchanged. It does not
  apply stale person-bbox scale or age expansion to ROI size;
- detector miss, detector ambiguity, or detector failure keeps the track dormant.
  There is no predicted/head/body-mask fallback sticker on a dormant probe;
- only an accepted face detector candidate inside that identity-local ROI may
  reactivate FACE_ONLY. The accepted center is current pixel evidence, while its
  rendered/cached radius is forced to the hidden trusted source-space radius so a
  large local detector extent cannot inflate the sticker;
- if exact YOLO ownership returns while the identity is dormant, the old hidden
  anchor is discarded instead. Face acquisition restarts from the **current exact
  person bbox**, so an old local ROI or old body-scale reference cannot contaminate
  the new trusted face;
- recent bbox bridging cannot independently start a dormant probe. TrackManager
  identity commit thresholds, the motion-only overlap gate, the 800 ms ordinary
  compensation limit, FULL_BODY behavior, and the no-generic-center-smoothing
  rule remain unchanged.

New export diagnostics separate this boundary explicitly:
`face_dormant_reactivation_probe_track_frames`,
`face_dormant_reactivated_by_face_detection_events`,
`face_dormant_exact_reacquired_track_frames`, and per-track variants. The export
also records reactivation-frame sticker max width/height per track, making the next
real-video bundle able to prove whether any remaining size peak is created on the
reactivation frame or later in the ordinary local-refresh path.

### Nineteenth real-video correction: stop local size ratcheting and long-distance dormant probes

The complete `54380accb902eedbd55ae193a4900e66f6204573` export confirms that
detector-confirmed dormant reactivation materially improves the previous severe
size regression, but does not fully close it. Maximum sticker widths for IDs
1/2/3/5/6 fall from approximately **156.5 / 139.2 / 187.2 / 256.4 / 213.2 px**
to **104.5 / 108.6 / 162.5 / 199.3 / 107.9 px**. ID6 is essentially restored
and ID1/ID2 improve strongly, while ID3 and especially ID5 remain too large.

The new reactivation-only size telemetry isolates the remaining source. Dormant
reactivation frames themselves peak at about **70.1 / 88.0 / 155.4 / 171.8 px**
for IDs 1/2/3/5. Because the reactivation path now copies the hidden trusted
source-space radius exactly, those large ID3/ID5 values prove that the hidden
trusted radius was already inflated *before* dormancy. The overall ID5 maximum of
199.3 px is only about 16% above its 171.8 px hidden/reacquired size, matching the
bounded body-compensation expansion rather than a new reactivation-scale bug.

Reliable TrackManager assignment geometry explains how this can happen without a
real camera zoom. In the same export, accepted person bbox widths vary by roughly
**2.0x for ID3** and **3.2x for ID5** while their bbox heights vary only about
1.3x and 1.8x. Occlusion and segmentation-shape changes therefore dominate person
bbox width. The local face-refresh path was still calling
`cached.project(currentPersonBbox)` and writing the resulting bounded per-frame
person scale back into the trusted face cache. A ±12% per-refresh bound does not
prevent cumulative ratcheting when many local refreshes repeatedly see one-sided
bbox shape changes. The temporal stabilizer could also render an expanded
body-compensated size and the old cache write then stored that stabilized radius
as a new DETECTED trusted size.

Local refresh is therefore tightened without changing detector identity semantics:

- once a local face anchor exists, detector refresh updates **center only** and
  keeps the cached source-space radius exactly; current person bbox dimensions no
  longer redefine trusted face size;
- a DETECTED cache write keeps the **post-position-gate center** but stores the
  detector/local trusted radius from *before* temporal size smoothing. A temporary
  predicted/body-compensated render expansion can no longer become the next
  trusted detected size;
- exact YOLO reacquisition still discards a dormant anchor and performs a new
  broad current-person acquisition, which is the explicit path allowed to reset
  absolute face size.

Center continuity also remains incomplete in the `54380acc` bundle. Maximum
consecutive sticker-center steps for IDs 1/2/3/5/6 are approximately
**76.1 / 57.1 / 120.0 / 98.9 / 42.5 px**. Dormant face detection is especially
suspicious for ID3: all **7/7** dormant probes accepted a face, yet ID3 retains the
largest 120 px consecutive jump. Current ambiguous body geometry can therefore
move the hidden anchor too far before the local face detector is queried; a nearby
wrong face may then legitimately satisfy the ordinary local selector around that
already-wrong anchor.

Dormant probes now add two stricter safety boundaries:

- if fresh ambiguous body motion would translate the hidden face anchor by more
  than **half of its trusted face diameter** (with a small 24 px floor), the probe
  is rejected before face inference. Large-distance recovery must wait for exact
  YOLO identity instead of using motion-only evidence;
- an accepted dormant probe uses a dedicated face-anchor distance ratio of
  **0.10** instead of the ordinary local selector's 0.22. Normal active local
  refresh keeps its existing ownership rule.

Diagnostics add `face_dormant_probe_motion_rejected_track_frames` and the
per-track variant so the next real-video export can quantify how many ambiguous
long-distance probes were prevented. Mixed FULL_BODY stale-mask suppression is
unchanged and remains validated in this export at `pts_us=150100` after three real
misses for selected ID4.

### FULL_BODY-only performance audit: collapse redundant selected-mask dilation

The same `cc3aca9987ebe23591d684acf59cbd5320465000` diagnostic bundle contains a
separate QUALITY export with all six people selected as FULL_BODY and no FACE_ONLY
targets (`selected_ids=[1,2,3,4,5,6]`, `face_only_ids=[]`). This proves the user's
reported pure-FULL_BODY slowdown is not caused by MediaPipe or the FACE_ONLY
sidecar: `faceOnlyPrivacy` is absent entirely. The two dominant additional CPU
stages are `privacyClassTracking` at about **23.75 ms/frame** and `renderEffects`
at about **26.03 ms/frame**. The latter is much larger than the roughly 1.8 ms
mixed-mode render stage because the primary resolver currently dilates every
selected 160x160 FULL_BODY mask independently before unioning them.

The resolver now uses a mathematically equivalent fast path only when the current
effective privacy set has **no unselected mask at all**. In that case no foreground
carve is possible, each target's pre-carve and effective masks are identical, and
the render occluder must be empty. Since both grayscale dilation and `mergeMasks`
are max operations, the following are pixel-identical:

`union(dilate(A), dilate(B), ...) == dilate(union(A, B, ...))`

The fast path therefore unions raw selected masks first and performs one dilation
instead of N dilations. Per-target low-bbox-occupancy health telemetry is retained.
Any frame containing a fresh/tracked unselected or conservative-unknown mask still
runs the complete historical per-target carve resolver. A unit regression compares
the fast-path output against the old per-target-dilation order pixel-for-pixel.

`privacyClassTracking` remains enabled in FULL_BODY-only mode because it owns the
historical selection-class continuity used during ambiguous crossings. It should
not be disabled merely for speed. The next FULL_BODY-only real-video diagnostic
should first compare `renderEffects` against the 26.03 ms baseline; only if total
throughput remains materially below the user-validated baseline should that second
23.75 ms stage be optimized independently without changing its semantics.

### Twenty-first correction: stop using body geometry as the primary inter-frame face tracker

After the size-ratchet and long-distance dormant-probe fixes, the same production
clip became substantially safer numerically, but the user still judged FACE_ONLY
unusable. That is the important acceptance signal: further tuning of dormant/body
motion gates cannot solve the core fast-dance localization problem. YOLO person
bboxes are an identity/whole-body signal; they are not a sufficiently precise
per-frame head-motion signal when arms, torso coverage, crossings, and motion blur
change the segmentation shape.

QUALITY export already performs one 640x640 OpenGL RGBA readback per frame for
YOLO. FACE_ONLY now reuses that exact current-frame buffer for a short-lived
face-local appearance tracker, so this change adds **no new GPU readback and no
OpenCV/native runtime dependency**:

- only a real `DETECTED_FACE` can seed/refresh a per-track appearance template;
- a 9x9 luminance sample grid is matched by normalized correlation inside a small
  face-relative search window in the existing YOLO model coordinate space;
- the template remains fixed until the next real face detection, preventing
  match-to-match template drift;
- the winning correlation peak must pass an absolute correlation gate and be
  separated from other spatially distinct peaks; repeated/lookalike peaks fail
  closed instead of guessing identity;
- current YOLO person geometry is only a broad upper-body search boundary. It no
  longer supplies the accepted face displacement;
- a successful pixel match moves **center only**. Trusted face radius is preserved,
  so this path cannot reintroduce sticker-size pumping;
- pixel tracking expires **150 ms after the last real face detection**. It cannot
  become a long-lived independent tracker and is deleted on dormant/exact-reacquire
  state boundaries;
- detector miss/error now tries this current-pixel bridge before the existing
  body/head fallback. If pixel matching also fails, fail-closed behavior is
  unchanged;
- high-confidence/unique current-pixel motion bypasses only the temporal
  stabilizer's residual **position** clamp. Detector, body fallback, and all other
  geometry sources retain the existing position gate; size stabilization remains
  active for every source.

Candidate matching performs no per-candidate heap allocation. Correlation is
computed in two direct sampling passes to avoid adding GC pressure to the export
hot path. Unit coverage verifies known translation recovery with unchanged face
size, rejection of two equal lookalike peaks, 150 ms expiry, and immediate use of
trusted current-pixel centers by the temporal stabilizer.

New export diagnostics are `face_pixel_motion_track_frames`,
`face_pixel_motion_rejected_track_frames`, their per-track variants, and the
`facePixelMotionCpu` profiler stage. The next real-video acceptance should compare
these against DETECTED/PREDICTED/FALLBACK, dormant suppression, consecutive center
step, and total FACE_ONLY cost. The intended success condition is not merely more
`PREDICTED` frames: pixel-motion frames must replace body-derived fallback/dropout
while consecutive center steps stay bounded and CPU cost remains small relative to
face detection.

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
