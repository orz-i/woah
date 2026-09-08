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

Implement first-frame analysis and cache semantics compatible with Android:

- trim-start frame extraction;
- canonical orientation/letterbox mapping;
- YOLO person segmentation;
- confidence filtering using the same selection semantics as Android;
- deterministic person IDs;
- person thumbnails in the app cache;
- `analysisCacheId` lifecycle and `releaseProject` cleanup.

The output DTO must remain unchanged unless a cross-platform protocol change is
strictly necessary.

## Phase 3: Metal preview

Port the visual/privacy compositor to Metal while retaining the current source
coordinate convention. Initial preview scope is solid, mosaic, blur, gradient,
outline, and face sticker. Occlusion subtraction is part of the privacy gate,
not a cosmetic follow-up.

## Phase 4: export

Target pipeline:

```text
AVAssetReader -> CVPixelBuffer -> inference/tracking -> Metal
             -> AVAssetWriter -> H.264 MP4 + source audio
```

The first production target is 1080p/30 H.264 with trim, audio preservation,
progress, cancellation, atomic output finalization, and explicit interruption
handling. HEVC, 4K60, and advanced background execution are later gates.

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
