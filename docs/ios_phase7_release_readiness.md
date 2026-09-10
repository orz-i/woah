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
- production Simulator smoke build: `flutter build ios --simulator --debug
  --target lib/main.dart`;
- release-regression Simulator build: `flutter build ios --simulator --debug
  --target lib/ios_phase7_smoke_main.dart`;
- archive artifact: zipped release `Runner.app`, never a debug app relabeled as
  release.

The Release lane must preserve the tracked `WoahGitCommit` build setting and
must emit a machine-readable audit manifest containing the source commit,
pubspec version, dependency-lock SHA-256, app bundle metadata, app executable
SHA-256, model contract/hash evidence, bundled frameworks, and bundled privacy
manifests.

The YOLO release artifact has two byte identities. The complete packaged file
is pinned at SHA-256
`ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340`,
and its 11,798,720-byte inference FlatBuffer core is pinned at
`881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c`.
Phase 7 tracks that single 11.8 MB canonical binary at
`models/litert/yolo11n-seg-fp16.tflite`; Android and iOS continue to consume the
same repository-root source. A clean Release checkout therefore starts from
the exact audited production bytes rather than trying to recreate them with a
host-dependent floating-point conversion step. The 1,005-byte metadata-tail
evidence remains tracked separately so the historical file structure can still
be audited.

The canonical model metadata identifies Ultralytics `8.4.130` and its LiteRT
argument set (`format=litert`, unquantized, 640 input). The historical `fp16`
filename is retained for compatibility, but the graph tensors are float32;
LiteRT GPU delegates may execute that graph with FP16 arithmetic at runtime.
The retained re-export diagnostic environment is isolated from the
Android/application Python lock and uses a throw-away Python 3.11 virtual
environment. At the
canonical 2026-08-27 cutoff, that exporter environment resolves CPU
PyTorch `2.13.0`, torchvision `0.28.0`, NumPy `2.4.6`, Ultralytics `8.4.130`,
and the pinned LiteRT converter stack. The application/root `uv.lock` remains
unchanged and is not used as evidence for model-production package identity.

The canonical `yolo11n-seg-fp16.tflite` contract remains pinned to the same
full/core hashes. Runs #7-#12 showed why model *conversion* is not a valid
bit-for-bit release-artifact source: with the same checkpoint and graph
structure, clean hosts produced only low-order FLOAT32 fusion differences, and
even explicit `ATEN_CPU_CAPABILITY=default` versus `avx2` produced the same
non-historical core. Re-export remains a diagnostic for graph/weight semantics,
not the mechanism that creates Release bytes. The dedicated Release model job
must instead verify that the clean checkout contains the Git-tracked canonical
file, stage it byte-identically for iOS, and report the pinned SHA-256 before
the macOS Release job may start.

The tracked constant manifest is diagnostic JSON, not a release binary. Phase 7
validates its schema, 248-entry/byte totals, required diagnostic fields, and the
canonical `{tensor, bytes, sha256}` aggregate identity. It intentionally does
not hash raw text bytes, because CRLF/LF checkout conversion has no model
semantics and must not make a macOS Release checkout disagree with Windows.

The embedded canonical metadata identifies the model as Ultralytics AGPL-3.0,
while this repository's source-code license is MIT. `models/litert/README.md`
therefore scopes the third-party model separately and records its hashes and
upstream license identity; the model is not represented as MIT-licensed code.

Debug-only diagnostic entrypoints may remain in source for CI, but the
production Release build must target `lib/main.dart`. Phase 7 does not add a
runtime switch that exposes smoke-only MethodChannel hooks through the normal
product UI.

All native `runIOS*` diagnostic/smoke MethodChannel hooks are fail-closed in
Release builds. `Release.xcconfig` defaults `WOAH_ENABLE_SMOKE_HOOKS` to `NO`;
Debug builds remain enabled for the already-accepted Phase 1-6 diagnostics.
The Phase 7 smoke and production-startup Simulator builds are Debug builds;
their native diagnostic hooks are enabled by the existing Debug compile guard.
Before building the production iPhoneOS Release artifact, the macOS gate writes
`WOAH_ENABLE_SMOKE_HOOKS = NO`. The final iPhoneOS bundle auditor checks that
fail-closed value and records `smoke_hooks_enabled` in the audit manifest.
Debug Simulator startup is not evidence that Release hooks are disabled.

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
3. builds the Phase 7 combined smoke entrypoint for a **Debug iOS Simulator**,
   first invokes the actual bundled YOLO model twice through the existing
   native probe with an explicitly selected CPU/XNNPack backend, checking the
   model/fixture SHA-256, tensor shapes, finite positive timings, and nonempty
   detections/masks; requires the unchanged Phase 3/4/5/6 markers again, then requires the Phase 7 media
   regression marker for no-audio, injected-failure cleanup,
   preferred-transform orientation, and VFR timestamp rebasing;
4. builds and launches the production `lib/main.dart` Debug Simulator app as a
   startup/crash smoke;
5. builds production iPhoneOS in Release mode with `--no-codesign`;
6. audits the built `Runner.app` and writes `phase7_release_audit.json`;
7. archives the exact audited app for artifact upload.

Flutter 3.44.2 does not support Release-mode iOS Simulator builds. Simulator
steps are therefore Apple-runtime evidence only. The iPhoneOS no-codesign build
is the actual Release-configuration compilation gate, and its bundle audit must
prove `WoahEnableSmokeHooks` is disabled. Release runtime behavior remains on
the physical-device acceptance checklist rather than being inferred from a
Debug Simulator.

The Phase 1 verifier historically auto-launches the Phase 6 Apple gate whenever
it detects GitHub Actions on macOS. Phase 7 suppresses that inherited auto-hook
for both Phase 1 verifier invocations, then explicitly executes the Phase 6
Apple gate once. This avoids duplicate expensive Simulator/Metal/media
execution without weakening or bypassing any accepted gate.

The dedicated GitHub workflow is `.github/workflows/ios-release.yml`, installed
and accepted before the pre-merge audit. Its tracked source template is
`tools/ios/ios-release.phase7.workflow.yml`; both copies must stay identical.
When the workspace policy protects `.github/workflows/**`, update that path
only through an authorized write mechanism. This lane is
Apple-only implementation evidence. It is not a physical-device acceptance
lane.

## Release regression matrix

The machine-readable matrix lives at
`tools/ios/phase7_regression_matrix.json`. It deliberately distinguishes
inherited Phase 6 Simulator runtime evidence, Phase 7 Simulator runtime
evidence, iPhoneOS Release-build evidence, and deferred physical-device
evidence.

The Phase 7 macOS lane must cover the following either through the Debug
Simulator runtime smoke or the audited iPhoneOS Release build:

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
have Simulator runtime evidence. Phase 7 does not pretend deterministic Debug
Simulator media checks are equivalent to Release playback on an iPhone.

The Phase 4/7 MP4 media smokes inject deterministic detection/face results.
They exercise real decoding, Metal composition and encoding, not an entire
production-model video pipeline. The added `WOAH_YOLO_CPU_SMOKE=PASS` gate is
separate real-model, fixed-image inference evidence; it does not establish
real-video tracking quality, GPU/CoreML throughput or visual privacy acceptance.
Neither a contract-parser unit test nor a source-token static verifier counts
as a successful native inference run. Changes made during pre-merge cleanup
must receive fresh Apple CI evidence before the updated head is merge-ready.

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
   Debug-Simulator regression smoke, production-entrypoint Simulator startup,
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
