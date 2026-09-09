# iOS Phase 7: Pre-Release Readiness

Phase 7 is a finite release-readiness phase. It does not add tracking or privacy
algorithms. Its purpose is to turn the already-accepted Phase 0-6 iOS
implementation into a release-shaped, auditable build that is ready for the
first physical-iPhone acceptance pass.

## Boundary

Phase 5 remains permanently closed at Phase 5H. Phase 6 remains permanently closed
at identity-independent privacy-class prototype tracking plus
FULL_BODY-only fresh-primary composition. Phase 7 must not reopen either phase.

Phase 7 explicitly does **not** include:

- new tracking algorithms;
- new FACE_ONLY algorithms;
- HEVC export;
- 4K60 export;
- CoreML/Metal delegate performance tuning;
- Android privacy/tracking behavior changes or Android gate relaxations;
- claims that Simulator, GitHub macOS, or no-codesign iPhoneOS builds substitute
  for physical-iPhone acceptance.

When the finite exit contract below is complete, the iOS status is exactly:

`implementation complete pending physical-device acceptance`

No later numbered phase is created merely because a physical iPhone is still
unavailable.

## Release build contract

The production release contract is:

- production entrypoint: `mobile/app/lib/main.dart`;
- configuration: Flutter `--release`;
- Bundle ID: `art.gaoge.dance`;
- app version/build source: `mobile/app/pubspec.yaml` (`0.1.0+1` at Phase 7
  start);
- iOS deployment target: `17.0`;
- output device build: `flutter build ios --release --no-codesign --target
  lib/main.dart`;
- production Simulator smoke build: `flutter build ios --simulator --release
  --target lib/main.dart`;
- release-regression Simulator build: `flutter build ios --simulator --release
  --target lib/ios_phase7_smoke_main.dart`;
- archive artifact: zipped release `Runner.app`, never a debug app relabeled as
  release.

The Release lane must preserve the tracked `WoahGitCommit` build setting and
must emit a machine-readable audit manifest containing the source commit,
pubspec version, dependency-lock SHA-256, app bundle metadata, app executable
SHA-256, model contract/hash evidence, bundled frameworks, and bundled privacy
manifests.

The canonical `yolo11n-seg-fp16.tflite` contract is pinned to
`ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340`.
That value was seeded from the existing repository-root model shared by the
Android/iOS asset flow after exact byte-size, TFL3 magic, and iOS staged-copy
equality checks. It is a candidate pin, not cloud acceptance: the dedicated
Release model job must independently reproduce the same SHA-256 before Phase 7
can satisfy its finite exit contract.

Debug-only diagnostic entrypoints may remain in source for CI, but the
production Release build must target `lib/main.dart`. Phase 7 does not add a
runtime switch that exposes smoke-only MethodChannel hooks through the normal
product UI.

## Apple privacy and permission contract

The app target and `dance_native` package both carry `PrivacyInfo.xcprivacy`.
The current contract declares no tracking, no tracking domains, no collected
data types, and no required-reason API categories. Phase 7 statically validates
those declarations and the Release bundle audit records every privacy manifest
that is actually packaged.

Photos access is add-only. `NSPhotoLibraryAddUsageDescription` is required, and
`IOSMediaLibraryBridge` must use `PHPhotoLibrary.authorizationStatus(for:
.addOnly)` / `requestAuthorization(for: .addOnly)`. A denied/restricted/missing
grant must fail safely with `PHOTO_LIBRARY_PERMISSION_DENIED`; the app must not
escalate to broad photo-library read permission merely to save or share an
export.

File import remains document-picker/security-scoped-URL based. Phase 7 does not
add broad filesystem or Photos-read entitlement. Share/open operations continue
to use the app-owned exported file URL.

Third-party privacy-manifest compliance is treated as a build audit, not a
source-tree guess. The Release audit records bundled frameworks and all
`PrivacyInfo.xcprivacy` files from the final `.app`; the dependency lock hash is
captured beside that inventory so a dependency update necessarily changes the
release evidence.

## Independent Phase 7 CI

`tools/release/verify_ios_phase7.py` is the host-independent repository contract
gate. It must remain separate from `verify_ios_phase5.py` and
`verify_ios_phase6.py`.

`tools/ios/run_phase7_macos_gate.py` is the Apple-only gate. It:

1. runs the Phase 0-7 static verifiers;
2. inherits the accepted Phase 6 macOS/Simulator gate;
3. builds the Phase 7 combined smoke entrypoint in **Release** mode, requires
   the unchanged Phase 3/4/5/6 markers again, then requires the Phase 7 media
   regression marker for no-audio, injected-failure cleanup,
   preferred-transform orientation, and VFR timestamp rebasing;
4. builds and launches the production `lib/main.dart` Simulator app in Release
   mode as a startup/crash smoke;
5. builds production iPhoneOS in Release mode with `--no-codesign`;
6. audits the built `Runner.app` and writes `phase7_release_audit.json`;
7. archives the exact audited app for artifact upload.

The dedicated GitHub workflow is `.github/workflows/ios-release.yml`. Its
tracked source template is `tools/ios/ios-release.phase7.workflow.yml`, because
the current local workspace security policy does not permit ordinary mutation
of `.github/workflows/**`; Phase 7 remains fail-closed until a trusted GitHub
write path commits the template at the protected workflow path. This lane is
Apple-only implementation evidence. It is not a physical-device acceptance
lane.

## Release regression matrix

The machine-readable matrix lives at
`tools/ios/phase7_regression_matrix.json`. It deliberately distinguishes
release-mode CI evidence from deferred physical-device evidence.

Release-mode Simulator/macOS must cover, either directly in the Phase 7 lane or
by rerunning the accepted deterministic/real-media smoke under Release:

- FULL_BODY export;
- FACE_ONLY export;
- mixed FULL_BODY/FACE_ONLY exclusion semantics;
- selected/unselected crossing and occlusion/reacquisition;
- trim;
- audio preservation;
- no-audio source export with no synthetic audio track;
- cancel and partial-file cleanup;
- injected terminal failure with no final/partial-file leak;
- landscape and preferred-transform portrait output sizing;
- common CFR input plus a real VFR presentation-timestamp fixture rebased to
  fixed 30fps output;
- H.264 / 1920x1080 / 30fps output contract.

All matrix cases remain on the physical-device checklist even when they also
have Release Simulator evidence. Phase 7 does not pretend deterministic
Simulator media checks are equivalent to visual playback on an iPhone.
iPhone.

## Physical-device acceptance package

`docs/ios_phase7_device_acceptance.md` is the fixed runbook to execute when an
iPhone becomes available. It includes:

- the exact release artifact/audit identity to install;
- FULL_BODY / FACE_ONLY / mixed privacy cases;
- crossing, occlusion, and reacquisition cases;
- trim, audio/no-audio, cancel, failure cleanup, portrait/landscape, CFR/VFR,
  and H.264 contract cases;
- automatic collection targets for FPS, memory, export throughput, errors, and
  build/model/dependency identity;
- thermal, background, interruption, and sustained-run observations;
- final visual privacy review.

The repository test video `testdata/videos/01_sample.mp4` is the fixed baseline
fixture. The runbook also defines additional capture categories that must be
materialized before final acceptance; absence of those real-device fixtures is
not filled in with Simulator claims.

## Finite exit contract

Phase 7 is implementation-complete only when all of the following are true:

1. `verify_ios_phase7.py` passes and locks the boundary, metadata, privacy,
   workflow, audit, regression-matrix, and device-runbook contracts.
2. Local host-independent Phase 0-7 static verification, Flutter tests/analyze,
   Python compilation, and `git diff --check` pass without weakening Android.
3. The dedicated GitHub macOS Phase 7 Release workflow succeeds, including
   Release regression Simulator smoke, production Release Simulator startup,
   Release no-codesign iPhoneOS build, bundle audit, archive, and artifact
   upload.
4. The produced audit manifest records commit/version/model/dependency identity
   and the final bundled privacy-manifest inventory.
5. The physical-device acceptance runbook is complete and directly executable
   without designing a new test process at device-acquisition time.

After item 1-5, the phase stops at
`implementation complete pending physical-device acceptance`. Physical-iPhone
performance, thermal/power, background/interruption, device-to-device
Vision/Metal behavior, and final visual privacy remain intentionally unclaimed
until the runbook is executed on real hardware.

