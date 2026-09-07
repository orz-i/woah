# Woah iOS implementation roadmap

Status date: 2026-09-06

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

Still intentionally unavailable after Phase 0:

- `analyzeVideo`
- `getPreviewFrame`
- `startExport`
- LiteRT/Core ML inference profiles
- Metal privacy compositor
- iOS tracking/privacy implementation
- long-running/background export lifecycle

Those calls must continue to fail with `PLATFORM_NOT_SUPPORTED`; capability
reporting must not advertise inference backends until the real runtime is wired.

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

Phase 1 should be a narrow experiment rather than a full export implementation.

1. Add the supported LiteRT iOS dependency to `dance_native`.
2. Bundle `yolo11n-seg-fp16.tflite` from the repository model source with a
   deterministic hash check.
3. Implement a small `IOSYoloRunner` accepting a canonical RGBA frame.
4. Exercise delegates in priority order and record the effective backend rather
   than the requested backend.
5. Run the same fixed first-frame fixture through Android and iOS and compare
   person count, confidence, bounding boxes, and mask coverage.
6. Only after parity is acceptable should `analyzeVideo` be connected to the UI.

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
```

Flutter regression gate:

```text
cd mobile/packages/dance_native
flutter test

cd ../../app
flutter test
flutter analyze
```

Required macOS gate before Phase 0 can be called device-validated:

```text
cd mobile/app
flutter pub get
flutter build ios --debug --no-codesign
```

Then open the Runner workspace/project with the current Xcode toolchain and run
on at least one real iOS 17+ device. The macOS lane must specifically verify:

- CocoaPods/SwiftPM privacy-resource packaging;
- `art.gaoge.dance` signing configuration;
- Photos add-only permission and successful save;
- share sheet and native player presentation;
- portrait/landscape/HEVC video metadata probing;
- trim thumbnails across several timestamps;
- hardware capability values on device versus simulator.

The Windows development host cannot satisfy this gate and must never be treated
as evidence that the iOS target compiled or ran on device.
