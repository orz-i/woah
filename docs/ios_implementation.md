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
