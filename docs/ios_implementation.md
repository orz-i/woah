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

The same macOS gate also builds `lib/ios_metal_smoke_main.dart` for an iPhone
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

This Simulator gate is media-runtime evidence, not physical-device acceptance.
Real-iPhone visual/privacy parity, sustained encode/Metal performance and
thermal behavior, interruption/background behavior, and the full temporal
FACE_ONLY implementation remain required before release parity is claimed.

## Cross-platform privacy gate

iOS is not accepted merely because YOLO runs. The current Android behavior is
the reference contract for:

- identity continuity;
- LOST/dormant/reactivation states;
- face-only continuity;
- occlusion handling;
- conservative fallback coverage;
- false-mask suppression.

The golden trace format should eventually include frame index, person ID, bbox,
track state, privacy class, face ROI, occlusion state, and mask coverage/hash so
both platforms can be evaluated with the same tooling.

## Verification lanes

Cross-platform static gate, runnable on Windows/Linux/macOS:

```text
python tools/release/verify_ios_phase0.py
python tools/release/verify_ios_phase1.py
python tools/release/verify_ios_phase2.py
python tools/release/verify_ios_phase3.py
python tools/release/verify_ios_phase4.py
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

Until the first successful clean-cloud export is observed, the contract keeps
`expected_sha256: null`. BrowserStack bootstrap runs use:

```text
python tools/release/verify_ios_phase1.py --require-model --allow-unpinned-hash
```

which prints `BOOTSTRAP_SHA256=<hash>`. After one clean-cloud export is checked
against the existing Android model provenance, commit that hash into
`yolo11n-seg-fp16.contract.json` and remove the bootstrap allowance from the
workflow. A release/accepted Phase 1 state must use the pinned hash; the
bootstrap flag is not release evidence.

### What cloud devices still do not replace

BrowserStack is sufficient for delegate compatibility, correctness, and broad
device coverage. It is not the final authority for sustained thermal behavior,
battery draw, long-video background transitions, or performance tuning because
shared remote-device conditions introduce noise. Those items can remain a
later release-candidate gate when physical Apple hardware becomes available;
they do not block architecture/feature implementation now.
