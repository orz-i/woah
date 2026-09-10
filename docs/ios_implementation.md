# Woah iOS implementation roadmap

Status date: 2026-09-07

This document is the implementation handoff for the iOS port. The product UI,
domain model, and Pigeon API remain shared with Android. iOS work is scoped to a
native execution engine under `mobile/packages/dance_native/ios` plus the small
amount of Runner configuration required by Apple platforms.

## Current baseline

Phase 0 is being implemented on `feat/ios-phase0-bootstrap` from the Android
release baseline. The repository intentionally keeps the Android native engine
unchanged while iOS gains its own AVFoundation/Metal/LiteRT implementation.

Completed in Phase 0:

- Runner deployment target raised to iOS 17.0.
- Runner bundle identifier aligned to `art.gaoge.dance` and display name to
  `Woah`.
- iPhone/iPad AppIcon set regenerated from the current Woah sunglasses branding
  asset instead of the Flutter placeholder.
- App and plugin privacy manifests are packaged explicitly.
- Photos permission is add-only; the app does not request broad photo-library
  read access merely to save an export.
- iOS build metadata channel now returns native version/build configuration and
  can consume an optional `WOAH_GIT_COMMIT` Xcode build setting.
- Device capability reporting now probes Metal and VideoToolbox hardware
  encoders instead of hard-coding GPU/codec support.
- Video probing now derives transform/display geometry, frame rate, duration,
  video codec, and audio codec from AVFoundation metadata.
- Platform MethodChannel support exists for save-to-Photos, system sharing,
  native playback, and trim-timeline thumbnails.

Still intentionally unavailable to the product flow:

- `analyzeVideo`
- `getPreviewFrame`
- `startExport`
- public iOS processing profiles
- Metal privacy compositor
- iOS tracking/privacy implementation
- long-running/background export lifecycle

Those calls must continue to fail with `PLATFORM_NOT_SUPPORTED`. Phase 1 may
report isolated YOLO runtime candidates when both the runtime and staged model
are present, but it must not advertise a product processing profile yet.

## Architecture boundary

The target iOS path is:

```text
Flutter / Riverpod / dance_domain
             |
             v
       Pigeon DanceNativeApi
             |
             v
       Swift dance_native
             |
      +------+--------------------+
      |                           |
AVFoundation                  LiteRT
AVAssetReader              Core ML delegate
CVPixelBuffer               Metal/CPU fallback
      |                           |
      +------------+--------------+
                   v
          Tracking / Privacy
                   |
                   v
                Metal
                   |
                   v
           AVAssetWriter / MP4
```

Video decode/encode, Photos, app lifecycle, and Metal remain platform-native.
Tracking/privacy behavior is ported against shared golden fixtures first. A
Rust/C++ common core is deliberately deferred until both Android and iOS
behavior are stable.

## Phase 1: inference feasibility spike

Phase 1 remains a narrow experiment rather than a full export implementation.
The repository-side implementation is now present, but it is not yet accepted
on macOS/Xcode or an iPhone.

Implemented:

1. `dance_native` pins `TensorFlowLiteSwift/CoreML` and
   `TensorFlowLiteSwift/Metal` 2.17.0 for the iOS feasibility bridge. The
   first-party general Swift compatibility runtime is available through
   CocoaPods, while the newer LiteRT API does not currently give this Flutter
   plugin an equivalent drop-in SwiftPM integration. Flutter 3.44+ normally
   prefers SwiftPM, so the Phase 1 plugin intentionally omits its `Package.swift`
   and lets Flutter use its supported per-plugin CocoaPods fallback. This is a
   temporary distribution seam, not a long-term architecture commitment.
2. `yolo11n-seg-fp16.contract.json` locks the known graph/file contract against
   `reports/yolo11n_seg_fp16_graph_report.json`: NCHW float32 input
   `[1,3,640,640]`, detection output `[1,116,8400]`, proto output
   `[1,32,160,160]`, 11,799,725 bytes, and `TFL3` FlatBuffer magic.
3. `sync_ios_yolo_model.py` stages the ignored repository-local model into the
   CocoaPods resource bundle atomically and writes a SHA-256 sidecar. The
   source/staged hashes must match exactly. The isolated worktree does not have
   the ignored source bytes, so `expected_sha256` is deliberately still `null`;
   the first host that materializes the canonical model must pin the printed
   SHA-256 in the tracked contract before Phase 1 can pass its acceptance gate.
4. `IOSYoloPreprocessor` mirrors Android's 640x640 RGB-114 letterbox and NCHW
   float normalization.
5. `IOSYoloPostprocessor` mirrors the Android tensor layout, person confidence,
   mask coefficients, bbox mapping, mask-aware NMS thresholds, proto-mask
   decode, and deterministic left-to-right ordering.
6. `IOSYoloRunner` is serialized because the interpreter is not thread-safe. In
   automatic mode it attempts Core ML -> Metal -> XNNPACK and records every
   fallback reason plus initialization/inference latency. A probe can also force
   one backend at a time for real-device benchmarking.
7. `runIOSYoloPhase1Probe` is a MethodChannel-only diagnostic path. It extracts
   an oriented frame with AVFoundation and returns comparison-friendly bbox,
   confidence, mask coverage, tensor-shape, backend, and latency data.
8. Product `analyzeVideo` intentionally remains `PLATFORM_NOT_SUPPORTED`; the
   spike cannot affect user-visible person selection until device parity is
   accepted.

Model provisioning before an iOS inference build:

```text
python tools/release/sync_ios_yolo_model.py
python tools/release/verify_ios_phase1.py --require-model
```

The isolated Windows worktree does not materialize ignored `models/litert`
artifacts, so the real model copy/hash gate cannot be satisfied on this host.

Phase 1 acceptance gate:

- model loads on an iOS 17 device;
- no network access is required for inference;
- CPU fallback works when acceleration is unavailable;
- first-frame person ordering/IDs are deterministic;
- iOS output can be compared against the existing Android golden data;
- effective inference backend and latency are observable in debug diagnostics.

## Phase 2: analyze pipeline

Phase 2 is implemented at repository level and has passed the GitHub-hosted
macOS/Xcode 26 no-codesign compile lane. Real-iPhone runtime/delegate parity is
still pending. The production `analyzeVideo` path now performs first-frame
analysis and cache semantics compatible with Android:

- trim-start frame extraction;
- canonical orientation/letterbox mapping;
- YOLO person segmentation;
- confidence filtering using the same selection semantics as Android;
- deterministic person IDs;
- person thumbnails in the app cache;
- `analysisCacheId` lifecycle and `releaseProject` cleanup.

The output DTO must remain unchanged unless a cross-platform protocol change is
strictly necessary.

Implementation details:

- `IOSAnalyzePipeline` uses `AVAssetImageGenerator` with the preferred-track
  transform applied, so the YOLO source coordinate system is the same visual
  orientation returned to Flutter.
- The existing Phase 1 `IOSYoloRunner` remains the only inference seam. The
  product analyze path does not create a second TensorFlow Lite interpreter.
  Selection analysis explicitly forces the XNNPACK/CPU backend, matching
  Android's strict CPU first-frame path so device-specific Core ML/Metal
  differences cannot redefine the project's root person IDs.
- Detections retain the postprocessor's deterministic left-to-right order and
  receive IDs from that order. Flutter continues to enforce the shared 0.60
  first-frame selectable-person confidence threshold.
- `IOSAnalysisCache` mirrors Android's `cache/analysis/<cacheId>` layout with
  `source_uri.txt`, `analysis.json`, normalized bbox/confidence metadata, and
  fixed 160x240 person thumbnails. Partial caches are removed if analysis
  fails, and `releaseProject` removes the completed analysis cache.
- Phase 3 owns `getPreviewFrame`; Phase 4 now owns `startExport`, cancellation,
  status/progress, and the first AVFoundation writer pipeline described below.

Cloud compile evidence for the final CPU-selection revision (`62464b6`) is
GitHub Actions run `34190614591`: dependency resolution, CocoaPods integration,
Swift/Xcode iPhoneOS build, app archive, and artifact upload all completed
successfully. This is compile evidence only; it does not replace a real-iPhone
YOLO/runtime correctness run.

## Phase 3: Metal preview

Phase 3 is implemented at repository level and has passed the GitHub-hosted
macOS/Xcode 26 no-codesign iPhoneOS compile lane. Real-iPhone Metal runtime,
visual/privacy, and performance acceptance is still pending.

The production `getPreviewFrame` path now:

- resolves the source URI and normalized person metadata from the Phase 2
  analysis cache;
- decodes the requested frame with `AVAssetImageGenerator` and
  `appliesPreferredTrackTransform = true`;
- intentionally uses XNNPACK/CPU for preview YOLO while delegate parity is
  unverified, so a device-specific Core ML/Metal inference difference cannot
  silently change the selected identity mapping;
- assigns current detections back to cached IDs using the same 0.70 IoU-cost
  greedy matching contract currently called `HungarianSolver` on Android;
- fails closed with `PREVIEW_PRIVACY_UNRESOLVED` if any requested full-body or
  face-only target cannot be mapped on the current frame;
- caches the decoded/inferred frame analysis in memory so effect-style changes
  do not rerun YOLO for the same `<analysisCacheId,timestamp>` pair;
- renders at the source aspect ratio with a 1280-pixel maximum preview width;
- writes a nonce-suffixed JPEG at quality 0.85 and removes stale previews for
  the same analysis cache.

`IOSMetalPreviewRenderer` is an actual Metal compute compositor. The kernel is
compiled with `MTLDevice.makeLibrary(source:)`, runs over shared RGBA/mask
textures, and currently implements the product-visible privacy styles:

- solid;
- blur;
- gradient;
- mosaic;
- outward outline using the shared border configuration;
- privacy-safe built-in sunglasses sticker for FACE_ONLY mode.

Full-body masks are sampled from the original 160x160 YOLO proto using the
exact Phase 1 letterbox `scale/padLeft/padTop` mapping. Before compositing, each
selected target receives the same radius-1 grayscale max-filter dilation as
Android. The first iOS occlusion subset is also privacy-first: an unselected
person may carve only when current-frame bbox overlap is at least 10%, mask
overlap exceeds 2%, and its foot position is at least 10% of the shorter person
height lower in frame. Only that person's radius-1 eroded raw mask is
subtracted. Ambiguous depth never carves privacy.

FACE_ONLY does not yet claim parity with Android's temporal face detector and
`FaceOnlyPrivacyFrameProcessor`. Until that subsystem is ported, iOS uses a
conservative top-of-person head ROI, paints an underlying opaque privacy layer
in sticker mode, and then draws an opaque sunglasses sticker. Covering extra
hair/shoulder pixels is accepted in this interim path; exposing a selected face
is not. Real-device acceptance must replace/tune this fallback with the proper
face detector before release parity is claimed.

The legacy `FollowConfig` DTO is preserved for protocol compatibility, but the
hidden/removed subject-follow feature is not applied by this Phase 3 renderer.
Likewise the previously removed beauty/leg-stretch controls are not reintroduced
on iOS.

The Phase 3 Apple-only gate is now stronger than a normal Swift compile. The
runtime kernel remains embedded in `IOSMetalPreviewRenderer` so production does
not depend on locating a loose shader resource. `extract_phase3_metal.py`
extracts that exact embedded source, rather than maintaining a second shader
copy, and `compile_phase3_metal.py` compiles it with Apple's `metal` and
`metallib` tools for both `iphoneos` and `iphonesimulator` SDKs.

The same macOS gate also builds `lib/ios_phase3_smoke_main.dart` for an iPhone
Simulator, boots an available iPhone simulator with `simctl`, installs the
application, and invokes `runIOSMetalPhase3Smoke`. The native smoke path creates
an `MTLDevice`, constructs the production `IOSMetalPreviewRenderer`, dispatches
the real `woahPreviewKernel` over a synthetic full-person privacy mask, reads
the result back from the Metal output texture, and requires the expected opaque
red privacy pixel. A missing Metal device, shader/pipeline failure, command
buffer failure, or pixel mismatch fails the CI gate.

The protected cloud workflow already runs `verify_ios_phase1.py`, so the
Apple-only Phase 3 gate is invoked from that existing GitHub/macOS hook instead
of requiring a second workflow entry point. Local Windows verification does not
execute the Apple-only gate.

Final cloud evidence for this strengthened gate is GitHub Actions run
`34196697414` at commit `1f09d50`: the exact Phase 3 Metal source compiled for
both Apple SDK targets, the iOS Simulator application built and completed the
Metal dispatch/readback smoke test, and the normal Xcode 26 iPhoneOS production
build/archive lane also completed successfully. The workflow reported Success
with a total duration of 11m55s.

This closes the earlier "Metal code only compiled as Swift" gap, but it is not
equivalent to real-iPhone acceptance. Physical-device visual/privacy parity,
sustained GPU performance/thermal behavior, and the full temporal FACE_ONLY
pipeline remain explicit later validation gates.

## Phase 4: export

Phase 4 now implements the first production export closure:

```text
AVAssetReader -> oriented CVPixelBuffer -> XNNPACK YOLO -> temporal identity/privacy
             -> production Metal compositor -> AVAssetWriter -> H.264 MP4 + AAC audio
```

The accepted first target is 1080p/30 H.264. The writer fixes presentation
timestamps to 30fps, caps the long edge at 1920, preserves source audio through
PCM decode/AAC encode, rebases both media tracks to the requested trim start,
and emits the existing `JobStatusDto` lifecycle (`preparing`, `exporting`,
`completed`/`failed`/`cancelled`). A single in-process coordinator owns the
active reader/writer job so `cancelJob` can cooperatively terminate it.

Output publication is atomic: AVAssetWriter always writes a nonce-suffixed
`.partial.mp4` beside the requested destination. The final path is populated
only after `finishWriting` succeeds, using a same-directory move/replace. Any
failure or cancellation cancels the writer and removes the partial file.

Phase 4 does **not** replace Android's `TrackManager` or weaken its behavior.
Android remains untouched. The iOS temporal tracker mirrors the privacy-critical
boundaries needed by this first writer closure: identity-protected IDs are kept
separate from privacy-selected IDs, mixed FACE_ONLY mode protects every
credible (>=0.60) analysis identity from weaker neighbor association, protected
assignments use an ambiguity margin instead of guessing, and tracks distinguish
ACTIVE/OCCLUDED/REACQUIRING/LOST states. A selected identity with a short
observation gap receives a conservative predicted bbox-backed mask; that
synthetic fallback is explicitly marked so the preview-only foreground carve
cannot punch holes into it. If a selected identity cannot be resolved within
the bounded 90-frame occlusion window, export fails closed with
`EXPORT_PRIVACY_UNRESOLVED` instead of silently exposing frames.

The current iOS tracker is still a bounded Phase 4 subset, not a claim that the
full Android tracking stack has been ported. Android's scene-motion recovery,
all mature dormant/reactivation heuristics, and the complete temporal
`FaceOnlyPrivacyFrameProcessor` remain the reference. FACE_ONLY on iOS still
uses the conservative head ROI documented in Phase 3. SAM2, subject follow,
skin whitening, and leg stretch are explicitly rejected during iOS Phase 4
export rather than silently producing weaker or different semantics. HEVC,
4K60, and advanced background execution are later gates.

The GitHub-hosted macOS gate now keeps all Phase 3 Metal evidence and adds a
real-media Simulator end-to-end export. The smoke app creates an actual H.264
MP4 with an AAC tone track, exports the 200-800ms trim at 1920x1080/30fps,
exercises a deliberate one-frame selected-identity detection gap, and then
validates encoded codec/dimensions/frame count/duration/audio presence, an
opaque red privacy pixel, intermediate progress, absence of the final path
before writer finalization, and cancellation/partial-file cleanup. The smoke
uses a deterministic inference provider at the test seam so CI does not need to
regenerate the large YOLO model; the production branch on the other side of
that seam is statically locked to `IOSYoloRunner(... preferredBackend: .xnnpack)`.
Phase 1 separately guards the real LiteRT/TensorFlowLite runtime contract.

Final cloud evidence for this Phase 4 closure is GitHub Actions `iOS Cloud CI`
run `34203376538` (#15), triggered from pull-request head
`d169924a7575309c5217335d7550f9dab780c99b`. The complete job reported Success:
the Phase 0-4 repository contracts passed, the inherited Phase 3 Metal source
compiled for both `iphoneos` and `iphonesimulator`, the Simulator export gate
passed, dependency resolution completed, the production `lib/main.dart`
iPhoneOS no-codesign build completed, and the app archive/upload steps also
completed. The uploaded artifact is
`woah-ios-debug-c40cb4f3671c68a378e75c73321549c3a3b65a2d` (40,656,807 bytes).

The concurrent generic Production CI remains red at its Android native-unit-test
stage because the clean runner is missing the separately required
`models/litert/yolo11n-seg-fp16.tflite` asset. The same failure was already
present before Phase 4, and the Phase 4 change range does not modify Android
tracking/privacy source files. That infrastructure/model-provisioning baseline
must not be "fixed" by weakening Android model or privacy gates.

This Simulator gate is media-runtime evidence, not physical-device acceptance.
Real-iPhone visual/privacy parity, sustained encode/Metal performance and
thermal behavior, interruption/background behavior, and the full temporal
FACE_ONLY implementation remain required before release parity is claimed.

## Phase 5: tracking parity and Golden Trace

Phase 5 starts by converting the Android tracking/privacy behavior from an
informal reference into a replayable contract that can run on GitHub-hosted
macOS/iOS Simulator without local Apple hardware. The canonical first fixture is
`Resources/GoldenTraces/phase5_tracking_golden.json`. Each case records its
Android regression-test provenance, analysis identity roots, deterministic
detections/masks, selected privacy IDs, lifecycle expectations, emitted fallback
semantics, and expected fail-closed errors.

The initial Phase 5A suite intentionally targets privacy-critical behavior rather
than broad synthetic coverage:

- a selected FULL_BODY identity survives a short detection gap and returns to
  ACTIVE on the same frame that fresh evidence is observed;
- FACE_ONLY identity protection remains separate from privacy selection so an
  unselected credible neighbor cannot inherit the selected target's weaker
  association semantics;
- a protected near-tie defers identity commitment instead of guessing/swapping;
- selected privacy remains conservatively covered through the bounded 90-frame
  unresolved window and fails closed with `EXPORT_PRIVACY_UNRESOLVED` after the
  window is exceeded.

`IOSTemporalIdentityTracker.paritySnapshots()` is an internal observation
surface only; it does not alter the Flutter/Pigeon API. The first concrete parity
correction made under this gate changes a successfully re-observed iOS track to
ACTIVE immediately, matching Android `TrackManager` behavior instead of keeping
it REACQUIRING for one extra frame.

`IOSGoldenTracePhase5Smoke` loads the bundled JSON and replays every logical
frame through the production iOS temporal tracker. It compares exact track IDs,
state, missed-frame count, observed/not-observed state, identity protection,
privacy selection, output presence, and conservative fallback markers. The
combined Simulator app now runs Phase 3 Metal smoke, Phase 4 real-media export,
then Phase 5 Golden Trace replay. `run_phase5_macos_gate.py` preserves the
existing offline Metal compilation and Simulator export evidence and requires
the additional `WOAH_GOLDEN_TRACE_PHASE5_SMOKE=PASS` marker.

On non-Apple hosts, `verify_ios_phase5.py` validates the fixture schema, confirms
that every Golden Trace case still points at an existing Android reference test,
guards the Android constants/selection boundaries the fixture depends on, and
checks the iOS/CI wiring. At the time Phase 5A was introduced, Phase 0-5 static
verification passes on Windows.

Final cloud evidence for Phase 5A is GitHub Actions `iOS Cloud CI` run
`34211579109` (#17), triggered from pull-request head
`98b1b6640c80ad23f3a415210129280d1138c52f`. Job `102013584490`
(`Xcode 26 / no-codesign build`) completed successfully. In particular,
`Verify repository iOS contracts` passed the Phase 0-5 repository gates, the
inherited Phase 3 Metal compilation for both Apple SDK targets, the Phase 4
real-media Simulator export checks, and the Phase 5 Golden Trace replay. The
subsequent dependency resolution, production `lib/main.dart` iPhoneOS
no-codesign build, archive, and artifact-upload steps also all reported
Success. This makes the Phase 5A slice cloud-accepted for macOS/Simulator
evidence; it is not a real-iPhone performance/thermal acceptance claim.

The concurrent generic `Production CI` run `34211579145` (#76) remains red in
the same Android-native-unit-test stage that predates Phase 5; Flutter tests and
the Android model-asset setup step passed before that failure. Phase 5A changes
no Android production source and does not weaken or bypass Android tracking,
privacy, or model gates.

Phase 5A is infrastructure plus the first parity correction, not full tracking
parity. Android scene-motion recovery, mature occlusion-group/dormancy/reactivate
logic, mask-warp/sample-IoU behavior, and the complete temporal
`FaceOnlyPrivacyFrameProcessor` remain later Phase 5 work. Production iOS
inference remains XNNPACK until real-device delegate parity exists.

Phase 5B strengthens the first temporal tracker against two Android regressions
that matter directly to privacy identity ownership. Protected association now
uses the Android state-aware minimum evidence tiers: ACTIVE requires bbox IoU
>=0.35 or mask IoU >=0.20, OCCLUDED/REACQUIRING requires >=0.45 or >=0.25, and
LOST recovery requires >=0.50 or >=0.45. LOST recovery additionally follows the
Android current-prediction direction/proximity gate, so a different person at a
stale last-observed box cannot steal a selected LOST identity merely because an
old mask is similar.

Unobserved protected predictions are now bounded around the last reliable
observation using the Android 0.30 maximum center-travel ratio and 0.82...1.18
size bounds. A 10-frame post-occlusion grace window is also retained when a
long-running overlap separates, allowing the original ID to return ACTIVE even
after the ordinary 15-frame missed-detection window. A track that is already
LOST cannot be promoted back to OCCLUDED solely by overlap with an unrelated
fresh track.

The Golden Trace suite adds two matching Phase 5B cases: one establishes motion,
lets a protected target become LOST, then places a passer at the stale anchor
and requires a new ID; the other holds a selected target behind an observed
neighbor for 25 frames, requires OCCLUDED state plus conservative privacy,
requires REACQUIRING during the separation grace frame, then requires recovery
of the original ID. These cases also assert the 0.30 protected-prediction bound
and expose the remaining grace count through the internal parity snapshot.

Final cloud evidence for Phase 5B is GitHub Actions `iOS Cloud CI` run
`34214553738` (#19) at product head
`ace8b14b86744606dd11ae555f08eb2e743aa77a`. Job `102023156022`
(`Xcode 26 / no-codesign build`) completed successfully with no failed steps.
The Apple-only `Verify repository iOS contracts` step passed the expanded
six-case Golden Trace replay together with the inherited Phase 3 Metal and
Phase 4 real-media Simulator gates. Dependency resolution, the production
iPhoneOS no-codesign build, archive, and artifact-upload steps also all passed.
Phase 5B is therefore cloud-accepted for macOS/Simulator evidence.

The concurrent generic `Production CI` run `34214553783` (#78) at the same
product head remains red only at `Run Android Native Unit Tests`, matching the
pre-existing Android CI stage boundary seen before this iOS slice. Phase 5B
does not modify Android production source or relax Android tracking/privacy or
model gates.

### Phase 5C: temporal FACE_ONLY foundation

Phase 5C replaces the Phase 3/4 renderer-only head rectangle as the normal iOS
FACE_ONLY path with a privacy-first localization pipeline. YOLO plus
`IOSTemporalIdentityTracker` remains the only owner of person identity. Apple's
Vision `VNDetectFaceRectanglesRequest` supplies source-space face rectangles,
but a Vision observation may refine privacy geometry only after a conservative
one-to-one association to an **observed** YOLO person. A near-tie candidate, a
candidate that can plausibly belong to two people, a detector error, no face,
or an unobserved/predicted person never removes privacy: that track uses the
current YOLO-owned head fallback instead.

`IOSFacePrivacyGeometry` mirrors the Android `FacePrivacyRegionResolver`
geometry constants for the first cross-platform closure: detected face radii
use 0.66x width / 0.74x height with the -0.04 vertical center shift, while the
no-face fallback is centered 0.14 down the current person bbox and uses the
same width/height-derived radius floors. The iOS temporal resolver also ports
the Android trusted detected-size references and residual-motion limits,
including the 1.24 fallback reference expansion, 1.10 minimum trusted
expansion, 0.25 detected-reference update, 0.90 privacy target floor, and the
0.80/0.65 residual/person-motion radius-step bounds. Detector evidence refines
location; stale detector evidence does not become a second identity system.

Preview keeps one FACE_ONLY resolver per analysis cache so sequential preview
requests can reuse temporal geometry without leaking state between videos.
Export owns one resolver for the job and feeds it the same tracked persons and
presentation timestamps used by the production H.264 pipeline. Both paths pass
resolved face ellipses into `IOSMetalPreviewRenderer`. The Metal input builder
now paints the underlying FACE_ONLY privacy surface as an ellipse and uses the
same ellipse bounds for sticker placement; if a caller provides no resolved
region, the renderer still derives a YOLO head fallback locally so privacy does
not depend on the face subsystem being present.

The Phase 5 Simulator gate now includes `IOSFacePrivacyPhase5Smoke`. It executes
the real Vision request once on a synthetic image to prove the framework/runtime
path works (the detected-face count is intentionally **not** a correctness
expectation), then uses an injected deterministic locator to verify a clear face
is accepted, an initial/no-trust miss falls back, a near-tie is rejected, an
unselected observed neighbor participates in face ownership competition, and a
predicted body cannot consume fresh face evidence as a new identity root. The
same smoke renders an explicit ellipse through Metal and checks that its center
is opaque while a corner of the ellipse's bounding rectangle stays unchanged,
preventing a regression back to the oversized rectangular FACE_ONLY mask.

Final cloud evidence for the completed Phase 5C foundation is GitHub Actions
`iOS Cloud CI` run `34218031558` (#22) at head
`d98c8a1c89092507ec25c7264f86e52f352a798e`. Job `102035039064`
(`Xcode 26 / no-codesign build`) completed successfully. The Apple-only
repository-contract step passed the Vision runtime probe, deterministic
FACE_ONLY ownership/fallback smoke, Metal ellipse readback, the inherited
tracking Golden Trace suite, and the Phase 4 real-media export gate. Production
iPhoneOS no-codesign build, archive, and artifact upload also passed. This is
macOS/iOS-Simulator evidence only; no physical or formal cloud iPhone was used.

### Phase 5D: trusted-face short prediction lease

Because a formal cloud iPhone is not currently available, Phase 5 continues by
porting behavior that can be specified and replayed deterministically. After a
face has been accepted for a YOLO-owned identity, iOS now mirrors Android's
short trusted-face projection lease instead of immediately jumping back to the
generic head fallback on every detector miss. Trusted face geometry may be
projected for at most **150 ms**. It follows short-term body translation, clamps
person-box-derived face scale to **0.88...1.12**, and expands by at most **10%**
across the lease. Once the lease expires, stale face geometry is no longer
renderable and the resolver returns to the current YOLO head fallback.

The body translation used by that projection also ports Android's
`PersonBboxMotionEstimator`: coherent movement of opposite bbox edges is treated
as physical motion, while a one-sided segmentation/pose edge jump uses the
quieter edge. This prevents top/bottom/side coverage jitter from dragging the
face sticker even though the person did not translate. The deterministic
Simulator smoke now checks short detector-miss prediction, translated
prediction, 150 ms expiry, brief YOLO observation-gap prediction, coherent body
motion, and one-edge jitter rejection. These rules require no real-device
timing assumptions; real-iPhone visual quality and sustained Vision cost remain
separate release acceptance gates.

Final cloud evidence for Phase 5D is GitHub Actions `iOS Cloud CI` run
`34227432896` (#23) at product head
`bd843c622b3c9e16856e5a47b4bc7f62af96bde7`. Job `102064889713`
(`Xcode 26 / no-codesign build`) completed successfully with every step green.
The Apple-only repository-contract step passed the expanded FACE_ONLY smoke,
including the 150 ms trusted-face prediction lease, short translated/body-gap
projection, expiry to current YOLO fallback, and bbox-motion jitter checks,
together with the inherited Vision/Metal/Golden-Trace/real-media export gates.
Production iPhoneOS no-codesign build, archive, and artifact upload also passed.
This remains macOS/iOS-Simulator evidence only; no physical or formal cloud
iPhone was available or used.

### Phase 5E: current-mask-guided expired-face recovery

The next FACE_ONLY fallback tier is also deterministic and does not require a
physical or formal cloud iPhone. Between the 150 ms direct face lease and the
Android detector-seed hard cap at **800 ms**, an old trusted face may be used
only as a **local search seed**. The rendered center must come from the current
frame's YOLO person segmentation. iOS now ports Android's
`BodyMaskFaceHeadEstimator` scanline policy: it searches only the upper local
head window, accepts narrow head-like mask runs, rejects shoulder/arm-width
runs, prefers geometry near the translated trusted seed, and bounds the final
correction around that seed.

This does not turn the body mask into a face identity source. Exact person
identity still comes exclusively from `IOSTemporalIdentityTracker`. The
mask-guided path runs only for a currently observed person with current
segmentation. If current mask pixels cannot support a head-like local shape, the
stale trusted center is **not** rendered; the resolver immediately uses the
generic head ellipse derived from the current YOLO person bbox. Once the trusted
seed is older than 800 ms, it is ignored even if current body-mask pixels exist.
The deterministic Simulator smoke covers a shifted current head silhouette, the
800 ms seed expiry, and the no-current-mask-support fallback case.

The first Apple-only Phase 5E attempt, `iOS Cloud CI` run `34229591863` (#24)
at head `73923e548e392bd344b60b23c535f5d5d84e6026`, failed before the
mask-guided assertions because the smoke declared its deterministic
`IOSYoloPreprocessResult` after the first resolver call. Xcode correctly rejected
that test-only Swift ordering error; the production FACE_ONLY resolver had not
failed. Commit `927cc721d2b79ee746f0b1fd1966336371572b51` moves the fixture
declaration before use and adds a host-side declaration-order guard. The
replacement `iOS Cloud CI` run `34232354647` (#25) completed successfully in
18m04s, so Phase 5E is accepted on GitHub macOS/iOS Simulator. As with the other
Phase 5 lanes, this is not physical/formal-cloud iPhone evidence.

### Phase 5F: real-MP4 FACE_ONLY export closure

Resolver/Metal unit-style smokes are not enough to prove the privacy mode is
wired into the actual writer. Phase 5F therefore extends the existing real-media
Simulator export gate with a second, deterministic **FACE_ONLY** MP4. The test
still decodes a real H.264 source, runs the production temporal tracker and
production Metal compositor, writes H.264/AAC through `AVAssetWriter`, then
decodes the finished file for assertions.

The only new seam is an optional `FaceLocatorProvider` on the native export
pipeline/coordinator. Production construction leaves it `nil`, so release code
continues to instantiate the Apple Vision locator. The Simulator smoke injects
a stateful locator that emits one trusted face and then detector misses. Its
deterministic YOLO provider separately drops one early person observation and
then supplies a current head-like segmentation mask. That drives the real MP4
through detected-face privacy, the short predicted-face lease, and the later
mask-guided fallback without making CI depend on whether Vision happens to
recognize a synthetic test image.

The final encoded FACE_ONLY clip must preserve the Phase 4 H.264, 1920x1080,
30fps presentation-frame-count, and audio contracts. Pixel readback then checks
that the face location is opaque privacy red while the person's body center is
**not** red. This explicitly catches a regression where FACE_ONLY accidentally
falls back to FULL_BODY rendering. The smoke also asserts that both the YOLO
observation gap and the detected-to-missed face sequence actually occurred.
These are media/runtime/privacy semantics that the GitHub iOS Simulator can
validate without a physical or formal cloud iPhone.

Final cloud evidence for Phase 5F is GitHub Actions `iOS Cloud CI` run
`34234693464` (#26) at product head
`01b38d2c194a5cb4a73417bc98bba04bf8292aa3`. The public workflow result is
`completed successfully` (15m15s). This acceptance includes the inherited
Vision/Metal/Golden-Trace/full-body export gates plus the new second real MP4
FACE_ONLY export and encoded pixel readback. Production iPhoneOS no-codesign
build/archive/upload remain part of the same successful workflow. No physical
or formal cloud iPhone was used.

### Phase 5G: selected privacy-class residual fallback

Exact identity must still be allowed to remain unresolved during a protected
near-tie. However, Android also preserves privacy when a fresh detection's
**complete possible-owner set** is already known to belong to the selected
FACE_ONLY class. Phase 5G ports that sidecar without making privacy evidence an
identity assignment mechanism.

`IOSTemporalIdentityTracker` now records fresh anonymous class evidence only
for a **cardinality-balanced residual ambiguity group**: the protected ambiguity
gate must have explicitly deferred the fresh detections, the connected residual
group must contain exactly one detection per complete possible owner, and every
possible owner must be FACE_ONLY-selected. Raw near-margin competitors are
included in that possible-owner set even when they failed the normal identity
evidence threshold, so the privacy sidecar cannot silently drop the track that
caused the ambiguity. A 1-detection / 2-owner merge is therefore insufficient.
Accepted detections are reserved for the frame and do not create new real track
IDs. If the group is unbalanced or even one possible owner is not selected, no
class fallback is emitted.

`IOSFacePrivacyTemporalResolver` converts accepted class evidence into temporary
negative-ID regions (`-1_000_000 - detectionIndex`). Multiple possible selected
owners use the fresh detection's current YOLO head geometry. A unique possible
owner may borrow only its trusted face **size** (1.24x conservative expansion),
or use current-mask guidance when available; the fresh detection still owns the
rendered center. Existing normal selected coverage suppresses duplicate
synthetic regions. The Metal renderer accepts negative-ID regions directly but
never adds them to the person/track list.

The deterministic FACE_ONLY Simulator smoke covers: a balanced 2-detection /
2-selected-owner ambiguity group producing synthetic fallbacks without new real
IDs; a mixed selected/unselected owner set producing no fallback; unique-owner
trusted-size reuse with the fresh center preserved; and Metal rendering of the
negative-ID region. This stage remains pure privacy/identity logic and therefore
does not depend on a real or formal cloud device.

Final cloud evidence for Phase 5G is GitHub Actions `iOS Cloud CI` run
`34310518949` (#31) at head
`005726ca4a065b559016971696062924ad8cfd12`, completed successfully on
2026-09-09. The Apple-only contract gate passed the inherited Phase 3 Metal,
Phase 4 real-media export, Phase 5 Golden Trace/Vision/FACE_ONLY gates, and the
new balanced residual + synthetic negative-ID Metal smoke. The same job then
passed production iPhoneOS no-codesign build, archive, and artifact upload. The
uploaded artifact is `woah-ios-debug-e4a2914581cc726510361349ab1c040374e3481b`
(id `10088521354`, 40,819,677 bytes, not expired at acceptance time).

Runs #27-#29 were blocked by GitHub-hosted CoreSimulator migration/readiness
behavior before the Phase 5G app smoke could execute. The hardened Simulator
runner now uses the operation actually required by the test (`simctl install`)
as readiness rather than blocking on `simctl bootstatus -b`; app launch and all
Metal/export/FACE_ONLY markers remain mandatory. Run #30 reached the real smoke
and exposed only a CoreGraphics readback-Y convention in the new off-center
synthetic-pixel assertion. The smoke was aligned with the already accepted real
MP4 readback convention by checking the direct or vertically mirrored sample;
no renderer, tracker, privacy geometry, or production export behavior changed.

### Phase 5H: anonymous class-fallback temporal continuity

Android keeps a second render-only continuity layer for **unique-owner** privacy-
class fallbacks. iOS now mirrors that isolation with
`IOSFacePrivacyClassFallbackContinuity`: the selected owner ID is used only as a
private geometry-state key and is never written back to
`IOSTemporalIdentityTracker` or the normal per-ID face cache. Multi-owner class
fallbacks remain stateless because exact identity is still unresolved.

When current mask guidance is weak or unavailable, the continuity layer ignores
the raw body-proportion head-center jump and follows only robust whole-person
translation from `IOSPersonBboxMotionEstimator`. When mask-guided geometry
returns, the existing FACE_ONLY residual-size policy limits the correction step
rather than snapping immediately. State is retained only for unique owners that
are present in the current class-fallback evidence; a missing fallback clears the
private continuity state just as Android does.

The deterministic smoke mirrors Android's two continuity regressions: a
guided/raw availability toggle must keep each center step below 35 px, and an
unguided frame with a translated body bbox must land near the previous anonymous
center plus body translation instead of following a far-away raw head center.
This remains pure render/privacy continuity and needs no physical or formal
cloud iPhone.

Final cloud evidence for Phase 5H is GitHub Actions `iOS Cloud CI` run
`34311848716` (#32) at head
`296a4d0c95768c5fed6e19cd6db96ce6dd42010c`. The public Actions result is
`completed successfully`. This run exercises the Phase 5H continuity smoke on
top of the already-required Phase 3 Metal, Phase 4 real-media export, Phase 5G
synthetic privacy-class fallback, and real FACE_ONLY MP4 gates, followed by the
workflow's production iPhoneOS no-codesign build/archive path. This remains
Simulator/cloud-build evidence, not physical-iPhone visual or performance
acceptance.

### Phase 5 boundary (closed)

Phase 5 ends at Phase 5H. Its engineering objective was temporal identity and
FACE_ONLY privacy parity that can be validated without a physical iPhone:
Golden Trace replay, protected identity recovery, temporal FACE_ONLY geometry,
real-MP4 FACE_ONLY export, anonymous residual class fallback, and render-only
fallback continuity. `296a4d0c` is the final Phase 5 product-code checkpoint;
`37b0a688` records its cloud acceptance. New tracking/privacy subsystems are not
added as further Phase 5 lettered slices.

The privacy-class prototype work originally entered development under the
temporary label "Phase 5I". That label was scope creep, not a meaningful phase
boundary. The code is retained, but from this point it is classified and gated
as Phase 6.

### Phase 6: identity-independent privacy-class prototypes

Android also maintains a selected/unselected temporal classifier whose state is
deliberately independent of person IDs. iOS now mirrors that design with
`IOSPrivacyClassTemporalTracker`. The first non-empty hard class map is the only
immutable privacy root; later runtime identity labels are ignored completely.
Class similarity uses predicted bbox IoU, bbox-relative distance, and a warped
segmentation-mask IoU with the Android reference weights (0.40 / 0.20 / 0.40).
The selected/unselected inference thresholds remain 0.42, 0.65 for a single
known class, and a 0.12 class margin. Prototypes decay on misses and are removed
after four missed frames or insufficient reliability.

If a current detection cannot be classified confidently, it is emitted as
SELECTED **only for fail-closed rendering** with `conservativeUnknown=true`; it
does not update either class prototype. This prevents a merged crossing or new
far entrant from poisoning the immutable selection history. The deterministic
smoke covers poisoned runtime hard labels, selected/unselected crossings, a
merged unknown, a far new entrant, and a one-frame unselected occlusion/return.

Production use is intentionally narrower than the tracker itself. Fresh
identity-independent class evidence may become the primary full-body compositor
input only when at least one FULL_BODY target is selected and **no FACE_ONLY
target exists**. The renderer treats selected/unknown-selected fresh detections
as anonymous privacy targets and unselected detections only as occluders; if the
fresh selected count is below the expected selected count, existing selected
tracks fill the deficit. Mixed/FACE_ONLY rendering ignores this path even if a
caller supplies evidence, preserving exact-ID semantics for face privacy.

The initial Phase 6 implementation is commit
`f42c5a1ae512ac302eb35534b5bccabe7a776bef`. GitHub Actions `iOS Cloud CI`
run `34314346309` (#34) completed successfully for that implementation before
the phase-boundary naming cleanup. Commit
`62a4ab1680f10005b6731e3e7b6d055d9c667eef` then separated Phase 5 and Phase 6
into independent smoke entrypoints, static verifiers, Simulator markers, and
macOS gates. GitHub Actions `iOS Cloud CI` run `34318126080` (#35) at that head
completed successfully: the independent Phase 6 contract/Simulator gate,
production iPhoneOS no-codesign build, archive, and artifact upload all passed.
The uploaded artifact was
`woah-ios-debug-eec3ab297afc5e9367b39dd3b42f162d99ad3325` (id
`10091079810`, 40,874,554 bytes, not expired at acceptance time). This closes
Phase 6's structural contract without changing the already-closed Phase 5 gate.

Phase 6 has a finite exit contract:

- immutable selected/unselected privacy-class roots and bounded prototype decay;
- conservative UNKNOWN -> SELECTED rendering without prototype poisoning;
- FULL_BODY-only fresh-primary composition with mixed/FACE_ONLY exclusion;
- independent `verify_ios_phase6.py` and `IOS_PRIVACY_CLASS_PHASE6` Simulator
  evidence while the Phase 5 verifier remains unchanged by future Phase 6 work;
- successful GitHub macOS/iOS Simulator gate and production iPhoneOS no-codesign
  build/archive path.

Anything beyond this list is a later Phase, not another Phase 6 lettered slice.

### Phase 6 boundary (closed)

Phase 6 ends at `62a4ab1` plus the successful #35 cloud acceptance above. Its
scope is limited to identity-independent selected/unselected privacy-class
prototype tracking and FULL_BODY-only fresh-primary composition. Stale-mask
replacement refinements, detector/face improvements, dormancy/reactivation,
performance tuning, and real-device acceptance are explicitly outside Phase 6.

Validation entrypoints are phase-scoped rather than cumulative by accident:
`ios_phase3_smoke_main.dart`, `ios_phase4_smoke_main.dart`,
`ios_phase5_smoke_main.dart`, and `ios_phase6_smoke_main.dart` each stop at their
own phase boundary. `run_phase6_macos_gate.py` is the strongest GitHub hook and
inherits the accepted earlier gates, while `run_phase5_macos_gate.py` remains
independently runnable without executing Phase 6.

This is a substantial FACE_ONLY closure, but it is still not a claim of complete
Android `FaceOnlyPrivacyFrameProcessor` parity. Android's local ROI detector
budgeting, landmark/keypoint center refinement, pixel-motion prediction,
dormancy/reactivation probes, deeper fresh-primary stale-mask replacement, and
mature diagnostics remain reference work for later phases.
Vision-vs-MediaPipe visual quality and sustained face
inference cost also require real-iPhone evidence before release parity can be
claimed.

### Phase 7: iOS Pre-Release Readiness

Phase 7 is deliberately finite and release-shaped rather than another tracking
phase. Its contract is maintained in `docs/ios_phase7_release_readiness.md`.
Phase 5 remains closed at 5H and Phase 6 remains closed at the privacy-class
prototype boundary above; Phase 7 does not add new tracking/FACE_ONLY
algorithms, HEVC/4K60, delegate performance tuning, or any physical-device
parity claim.

The Phase 7 implementation adds an independent host verifier
(`tools/release/verify_ios_phase7.py`), a Release-mode macOS gate
(`tools/ios/run_phase7_macos_gate.py`), and a dedicated GitHub workflow
(`.github/workflows/ios-release.yml`). The Apple-only lane reruns the accepted
Phase 3-6 Simulator regressions under Release optimization and then executes a
Phase 7-owned real-media smoke for video-only input, injected-failure cleanup,
preferred-transform portrait/landscape output, and VFR timestamp rebasing into
the fixed H.264/30fps contract. It separately launches
the production `lib/main.dart` Release Simulator app, builds the production
iPhoneOS target with `--release --no-codesign`, audits the exact resulting
`Runner.app`, and archives that audited bundle. The audit records the Git
commit, Flutter app version/build, app dependency-lock SHA-256, executable
SHA-256, packaged model/contract SHA-256, bundled frameworks, and packaged
privacy manifests. These remain macOS/Simulator/build facts, not physical-iPhone
acceptance.

Release reproducibility now includes a tracked `mobile/app/pubspec.lock` while
package-level Flutter lockfiles remain ignored. The production Release build
also receives the exact Git commit through the ignored/generated
`Flutter/Phase7Release.xcconfig`; the built `WoahGitCommit` must match the source
HEAD during bundle audit.

Apple privacy behavior remains intentionally narrow: both app and native
privacy manifests declare no tracking/collected-data/required-reason API
categories, and saving an export continues to request Photos `.addOnly` access.
Denied or unavailable add-only permission fails closed with
`PHOTO_LIBRARY_PERMISSION_DENIED`; Phase 7 does not add broad Photos read
permission. Final third-party manifest evidence is taken from the built Release
`.app` inventory and tied to the tracked dependency lock rather than inferred
only from source package names.

The finite release-regression matrix is
`tools/ios/phase7_regression_matrix.json`, and the predesigned real-device
runbook is `docs/ios_phase7_device_acceptance.md`. Once the independent Phase 7
Release CI lane succeeds and the fixed handoff package is complete, the iOS
implementation status stops at:

`implementation complete pending physical-device acceptance`

Physical-iPhone visual privacy, sustained throughput, memory, thermal/power,
background/interruption behavior, and cross-device Vision/Metal behavior remain
unclaimed until that runbook is executed on real hardware. No Phase 8/9 is
created merely to avoid this device gate.

Phase 7 now pins the canonical shared Android/iOS FP16 TFLite source at
`ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340`.
The candidate pin was taken from the repository-root
`models/litert/yolo11n-seg-fp16.tflite` after verifying the existing
11,799,725-byte / TFL3 contract and byte-identical iOS staging. This pin is not
itself clean-cloud acceptance: the dedicated Phase 7 `release-model` job must
reproduce exactly the same hash in the locked model-export environment or the
Release lane fails closed.

The first independent Phase 7 Release workflow run (`34339971155`) exposed a
model-production provenance bug before any Xcode Release job started. The
historical file's embedded `metadata.json` records Ultralytics `8.4.130`, while
the repository application lock still pins `8.3.82`. The same metadata argument
set matches the `8.4.130` direct LiteRT exporter (`format=litert`,
`quantize=null`), not the old TensorFlow/TFLite recipe. The canonical file is
therefore now contracted as a deterministic 11,798,720-byte FlatBuffer core
(`881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c`)
plus a 1,005-byte historical ZIP metadata tail. Clean-cloud CI must reproduce
the core from the pinned `yolo11n-seg.pt` checkpoint before restoring that tail
and re-establishing the original whole-file hash. This fixes reproducibility
without changing the inference graph or Android runtime behavior.

Phase 7 Release Run #2 (`34344338654`) still failed inside the clean Ubuntu
`Generate canonical model` step before the core comparison produced an
annotation. The provisioner therefore now pins the cutoff-era
`litert-lm-builder==0.16.1` transitive dependency and converts exporter-package
installation, LiteRT export exceptions, and staging failures into explicit
GitHub annotations. Hash gates remain unchanged; this is diagnostic hardening,
not an acceptance relaxation.

Phase 7 Release Run #3 (`34344945710`) confirmed the pinned exporter stack was
installed and surfaced the intended annotation path. Its only reported drift
was `litert-lm-builder actual=null`; inspection found a local diagnostic bug:
the dependency was present in both the expected and install lists but omitted
from `exporter_versions()`. The package's published wheel metadata confirms the
distribution name `litert-lm-builder`. Run #4 therefore changes only that
version-observation omission; all model/checkpoint/core/full-file hash gates
remain unchanged.

Phase 7 Release Run #4 (`34345293459`) then crossed the version gate and reached
the real `format=litert` exporter. Its exception arose while `litert-torch`
imported TorchAO's PT2E stack. This disproved the remaining assumption that the
canonical exporter inherited the application's root Torch `2.6.0` lock:
TorchAO `0.18.0` targets newer PyTorch APIs, while the 2026-08-27 provenance
cutoff already had PyTorch `2.13.0`, torchvision `0.28.0`, and NumPy `2.4.6`
available. Phase 7 model production is therefore now explicitly isolated in a
temporary Python 3.11 venv with the CPU PyTorch pair and cutoff-pinned LiteRT
stack. The Android/application root environment is not mutated or treated as
model-export provenance; the generated FlatBuffer core hash remains the final
acceptance authority.

Phase 7 Release Run #5 (`34346445889`) validated the isolation boundary but
failed before package installation because `uv` correctly refused to search a
second index for `torch==2.13.0+cpu` under its dependency-confusion protection.
Rather than enable `unsafe-best-match`, the contract now pins the official
PyTorch and torchvision cp311/Linux x86_64 CPU wheel URLs and their SHA-256
digests directly. PyPI is used only for their ordinary dependencies and the
cutoff-pinned LiteRT exporter stack.

Phase 7 Release Run #6 (`34346810849`) then exposed an isolated-environment
resolver error rather than a model error: the provisional NumPy `2.5.2` pin
requires Python 3.12+, while the release workflow intentionally uses Python
3.11. The pin was corrected to cutoff-era NumPy `2.4.6`, for which a cp311 Linux
x86_64 wheel exists and which is also present in the surviving historical
LiteRT environment residue.

Phase 7 Release Run #7 (`34357948244`) was the first clean-cloud attempt to
complete dependency installation and a real LiteRT export. The generated core
had the exact historical size (11,798,720 bytes) but a different raw SHA-256
(`3d25c2be9f1d32bd843fd1ed502d960831e8304bc0d06b0d70fa0bed1f17937d`
instead of `881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c`).
The raw core hash remains blocking. Before any acceptance rule can be changed,
Phase 7 now records a serialization-order-resistant semantic fingerprint over
643 tensors, 394 operators, operator options/wiring, and 248 constant tensors,
and exports the model twice in the same isolated environment. This diagnostic
will distinguish a serializer/layout difference from a real graph or weight
drift without weakening the historical whole-file or core identities.

Phase 7 Release Run #8 (`34360135588`) showed the new exporter is fully
deterministic: two clean-cloud exports produced the same raw core SHA
`3d25c2be9f1d32bd843fd1ed502d960831e8304bc0d06b0d70fa0bed1f17937d`
and the same semantic fingerprint. The historical and clean-cloud structures
match exactly (643 tensors, 394 operators, identical shapes/wiring/options),
but the aggregate constant-tensor fingerprint differs. Phase 7 therefore now
tracks the historical 248 constant tensors individually and reports the count,
total bytes, and largest named tensors that differ. The raw historical core
hash remains blocking until those constant differences are explained; no model
identity or acceptance threshold is relaxed by this diagnostic.

Phase 7 Release Run #10 (`34365257098`) then refined the constant drift to
FLOAT32 low-bit reproducibility rather than model-weight or tensor-layout
drift. The clean-cloud export kept the exact historical graph structure and
constant shapes. Of the 46 constants whose raw bytes differed, 43 became
identical after clearing the lowest 12 mantissa bits and 32 already matched
after clearing only the lowest 8 bits. The largest convolution constants kept
identical min/max values, while aggregate L2 and absolute-sum relative deltas
were on the order of 1e-9. The same runner reproduced its own raw core exactly,
but different GitHub runners produced different raw core hashes. Ultralytics
8.4.130 performs `model.float()` followed by CPU `model.fuse(...)` before
serialization, so Phase 7 treats host CPU dispatch during Conv/BN fusion as the
remaining bounded hypothesis instead of relaxing the historical core identity.

Run #11 therefore tests exactly two PyTorch CPU dispatch candidates in one
clean-cloud job: `ATEN_CPU_CAPABILITY=default` and `ATEN_CPU_CAPABILITY=avx2`.
Both paths are single-threaded (`torch_num_threads=1`, inter-op threads 1, and
the common OMP/MKL/OpenBLAS/NumExpr/vecLib thread variables set to 1). A
candidate is accepted only if it reproduces the historical 11,798,720-byte core
and SHA-256 exactly; the selected capability must then reproduce that core a
second time byte-for-byte before the canonical metadata tail is restored. If
neither candidate matches, the Release lane remains fail-closed and reports
both candidate hashes and constant diagnostics in the same run. This is a
finite reproducibility experiment, not a new product phase or an acceptance
tolerance.

The first Run #11 attempt (`34425316282`) did not reach either candidate. A
parent-side checkpoint verification had been moved ahead of the isolated
worker even though the worker is responsible for materializing the ignored
`yolo11n-seg.pt` on a clean runner. The worker still verifies the checkpoint
size/SHA before every export; the parent now performs its redundant verification
after the candidate workers have had the documented materialization
opportunity. This is an ordering correction only and does not change the
checkpoint, core, or Release acceptance identities.

Two subsequent legacy `iOS Cloud CI` runs provide intermediate Apple-only
evidence while the independent Phase 7 Release workflow remains intentionally
blocked on protected-path installation. Run `34334830050` (#40), job
`102411798459`, completed successfully at
`af7df52fa153ba252d630b6daed663c2e6c8322c`; repository iOS contracts,
production iPhoneOS build, no-codesign archive, and artifact upload all passed
after the candidate model pin and Phase 7 Release-regression Swift sources were
added. Run `34336876495` (#41), job `102418230396`, completed successfully at
`db384382e8061894f4754168fdf1434d2cb8b010` after the Release smoke-hook
fail-closed guard and Phase 7 macOS-gate deduplication were added. These runs
prove compatibility with the existing Apple-only Debug/Phase-6 lane; they are
not the dedicated Phase 7 Release Simulator/no-codesign acceptance lane and do
not substitute for physical-iPhone evidence.

## Cross-platform privacy gate

iOS is not accepted merely because YOLO runs. The current Android behavior is
the reference contract for:

- identity continuity;
- LOST/dormant/reactivation states;
- face-only continuity;
- occlusion handling;
- conservative fallback coverage;
- false-mask suppression.

Phase 5A established the first Golden Trace schema for person ID, lifecycle
state, privacy-selection class, protection class, observation age/fallback
behavior, and fail-closed outcomes. Future phases may extend the fixture family
with explicit face ROI, occlusion-group state, scene motion, predicted bbox, and
mask coverage/hash when those subsystems receive their own bounded phase.

## Verification lanes

Cross-platform static gate, runnable on Windows/Linux/macOS:

```text
python tools/release/verify_ios_phase0.py
python tools/release/verify_ios_phase1.py
python tools/release/verify_ios_phase2.py
python tools/release/verify_ios_phase3.py
python tools/release/verify_ios_phase4.py
python tools/release/verify_ios_phase5.py
python tools/release/verify_ios_phase6.py
python tools/release/verify_ios_phase7.py
```

Flutter regression gate:

```text
cd mobile/packages/dance_native
flutter test

cd ../../app
flutter test
flutter analyze
```

Required macOS gate before Phase 1 can be called runtime-validated:

```text
python tools/release/sync_ios_yolo_model.py
python tools/release/verify_ios_phase1.py --require-model

cd mobile/app
flutter pub get
flutter build ios --debug --no-codesign
```

Then open the Runner workspace/project with the current Xcode toolchain and run
on at least one real iOS 17+ device. The macOS lane must specifically verify:

- mixed Flutter SwiftPM + `dance_native` CocoaPods fallback resolution;
- `TensorFlowLiteSwift` CoreML/Metal pod resolution and model-resource packaging;
- `art.gaoge.dance` signing configuration;
- Photos add-only permission and successful save;
- share sheet and native player presentation;
- portrait/landscape/HEVC video metadata probing;
- trim thumbnails across several timestamps;
- hardware capability values on device versus simulator.
- forced `tflite_coreml`, `tflite_metal`, and `tflite_xnnpack` Phase 1 probes;
- automatic fallback backend and fallback-reason reporting;
- first-frame person count/order, confidence, bbox, and raw-mask coverage versus
  the Android reference fixture;
- repeated inference latency and memory/thermal behavior on at least one A12
  baseline device and one newer device before choosing the production default.

The Windows development host cannot satisfy this gate and must never be treated
as evidence that the iOS target compiled or ran on device.

## No-local-Mac / no-local-iPhone validation path

Woah can continue the iOS port without owning Apple hardware. The repository
now separates the remaining Apple-only evidence into two cloud lanes.

### 1. GitHub-hosted macOS compile lane

`.github/workflows/ios-cloud.yml` runs automatically for relevant changes on
`main`/`master` and pull requests, and can also be started manually. It uses the
GitHub-hosted `macos-26` runner, pins Flutter 3.44.2, enables Flutter's SwiftPM
integration, and lets Flutter fall back to CocoaPods for `dance_native` while
the Phase 1 TensorFlowLiteSwift bridge is CocoaPods-only.

The job executes the Phase 0/1 static gates and then performs:

```text
flutter build ios --debug --no-codesign --target lib/main.dart
```

The produced `Runner.app` is archived as a workflow artifact. This lane needs
no BrowserStack or Apple signing credentials and is the first authority for
Swift/CocoaPods/Xcode compile failures when development is being done from
Windows.

### 2. BrowserStack real-iPhone Phase 1 lane

`.github/workflows/ios-browserstack.yml` is deliberately `workflow_dispatch`
only. Normal pushes and pull requests never spend BrowserStack minutes. Add the
following repository Actions secrets when a BrowserStack account is available:

```text
BROWSERSTACK_USERNAME
BROWSERSTACK_ACCESS_KEY
```

If either secret is absent, the credential guard succeeds and the real-device
job is skipped instead of making CI red.

The workflow accepts a BrowserStack device name, iOS version, and the list of
backends that are mandatory for that run. The default mandatory set is:

```text
auto,tflite_xnnpack
```

Core ML and Metal are always attempted and reported, but they can initially be
treated as feasibility evidence rather than hard requirements. Once a device
class is accepted, set `required_backends` to include `tflite_coreml` and/or
`tflite_metal` to promote those paths into hard gates.

The workflow first reproduces the ignored YOLO model on an Ubuntu runner. This
is deliberate: the repository's locked PyTorch source is CUDA/Linux-oriented,
so model export does not consume expensive macOS minutes or depend on macOS
PyTorch wheel availability. The verified TFLite file is handed to the macOS job
as a short-lived Actions artifact. The macOS job then builds the dedicated
`lib/cloud_probe_main.dart` entrypoint, ad-hoc signs the device `.app`, packages
it as an `.ipa`, uploads it through BrowserStack App Automate, and runs Appium
against a real iPhone. BrowserStack re-provisions the uploaded iOS application
for its device fleet.

The probe does not use the user's Photos/files. A tracked copy of
`tools/litert/test_frame.jpg` is bundled inside the plugin resource bundle. The
cloud-only Flutter entrypoint automatically runs:

```text
auto
tflite_coreml
tflite_metal
tflite_xnnpack
```

and publishes one accessibility value named
`woah-ios-phase1-cloud-report`. The Appium harness collects that JSON and writes
`browserstack-phase1-report.json`, including BrowserStack session metadata,
effective backend, fallback reasons, inference latency, detections, bbox,
confidence, mask coverage, and cross-backend comparison results.

The current tracked fixture is expected to produce one YOLO person detection,
matching `tools/litert/yolo_parity_report.json`. Required backends fail the
cloud gate if that count drifts or their bbox/confidence/mask coverage diverges
from the XNNPACK reference beyond the initial tolerances encoded in
`tools/ios/browserstack/phase1_report.py`.

### Model hash bootstrap

The repository intentionally still does not commit the 11+ MB model binary.
On a clean cloud runner, `tools/release/provision_ios_yolo_ci.py` can download
the pinned YOLO11n segmentation checkpoint through the locked Python
environment and reproduce the FP16 TFLite export recipe. The generated file
must still match the tracked byte size and TFL3 contract.

The Phase 7 candidate contract now pins:

```text
ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340
```

It was seeded from the existing repository-root Android/iOS source after
byte-size, TFL3 magic, and iOS staged-copy equality checks. The clean-cloud
Phase 7 model job remains the independent reproduction authority: it must
generate the same SHA-256 before the Release lane can pass. Historical
`--allow-unpinned-hash` support remains only for older bootstrap workflows; the
Phase 7 Release workflow does not use it.

### What cloud devices still do not replace

BrowserStack is sufficient for delegate compatibility, correctness, and broad
device coverage. It is not the final authority for sustained thermal behavior,
battery draw, long-video background transitions, or performance tuning because
shared remote-device conditions introduce noise. Those items can remain a
later release-candidate gate when physical Apple hardware becomes available;
they do not block architecture/feature implementation now.
