# iOS pre-merge cleanup

## Scope and status

This is a finite response to the 2026-09-10 audit, not a new numbered phase.
No tracking/privacy algorithm changes, merge, branch deletion, or release are
authorized by this cleanup. Physical-iPhone acceptance remains pending.

The audit started from iOS head `4268798ef741a38f782663a95ee1748db8a6869d`.
At that snapshot, local master was `e63f070fb12edc5248e6c327143ffb722f461b57`
and remote master was `fa8ec495d2affa9e623b6f17657338d556a7982c`.
Local master was an ancestor of the iOS head, with 68 additional iOS commits.
Remote master lacked 84 already-local mainline commits, so PR #1 contained
152 commits/228 files, not just the 105-file iOS increment. Recompute these
counts at the eventual merge; do not squash that inherited mainline history
under an iOS-only description or push master as an incidental cleanup action.

## Android assets: supported YOLO/LiteRT contract

The user clarified on 2026-09-10 that SAM2 is currently unavailable and ONNX
is obsolete. The initial cleanup at `10e5629bce5198fdc687d6c79db6a3504ea54f08`
incorrectly carried forward a four-model prerequisite. That requirement and
the instructions to locate/copy/publish SAM2 files are superseded. Missing
SAM2 or ONNX files must not block the supported build or merge readiness.

`tools/setup_models.py --android` validates and stages only
`models/litert/yolo11n-seg-fp16.tflite`, already tracked in Git and pinned by
the shared YOLO contract. It does not inspect, copy, download, export or revive
SAM2/ONNX models. Existing historical runtime/exporter code is left untouched;
this cleanup does not claim that SAM2 works or remove its runtime safety gate.

The Gradle sync path stays anchored to the plugin project. Its required LiteRT
list is YOLO-only, and direct Gradle builds also verify the canonical YOLO
SHA-256 rather than just file presence. The separately pinned face-detector
model check, prohibition on ONNX Runtime/legacy TFLite dependencies, native
tests and APK build steps all remain mandatory.

A clean checkout now has the supported provisioning inputs. No private SAM2
artifact host or manual model copy is needed for this scope:

```text
python tools/setup_models.py --android
```

The optional `--source-dir` accepts a directory containing that same pinned
YOLO file; other files in the directory are not processed. A successful
staging result still does not substitute for native tests, an APK build, or
the new Apple CI run.

## Real CPU inference versus media regression

The Phase 7 Debug Simulator app now calls `runIOSYoloPhase1BundledProbe` twice
with `backend=tflite_xnnpack`. The existing native runner performs allocation,
`Interpreter.invoke()` and postprocessing. The probe hashes the actual bundled
model and JPEG bytes. The diagnostic contract rejects changed identities,
backend fallback, wrong tensor shapes, invalid/empty detections, empty masks,
invalid geometry, and nonfinite/nonpositive timing fields.

The macOS runner requires `WOAH_YOLO_CPU_SMOKE=PASS` as well as all existing
Phase 3-7 media/privacy markers and a zero launch exit code. The JSON
`WOAH_YOLO_CPU_REPORT` log contains both invocations. This gate is not evidence
of physical-device performance, GPU/CoreML correctness, or full-video privacy.
The existing MP4 smokes still use deterministic injected detection/face results.

Native execution of this added probe must be observed in a NEW Apple CI run.
Dart negative tests validate its report parser only. The preceding Run #16
cannot be reused as evidence for a probe that was not yet part of that run.

## Bounded verification and merge decision

Initial cleanup verification on 2026-09-10: model-staging unit tests 8/8;
Flutter app tests 45/45 (including six CPU-report contract tests), native Dart
tests 3/3, domain tests 5/5 and UI tests 1/1; app analysis and the Phase 0-7 /
cloud static verifiers passed. Domain/UI dependency caches were initialized
offline; no tracked dependency lock was changed. That earlier Android
provisioning run failed only because the then-current contract required SAM2.
It is historical evidence of the incorrect scope, not an outstanding model
provisioning request. Re-run the corrected YOLO-only gate and native/build
checks; do not reuse the earlier tests as evidence for subsequent changes.

Supported-scope correction verified locally on 2026-09-10:

- staging tests: 12/12, including missing/ignored SAM2 and obsolete ONNX cases;
- actual `python tools/setup_models.py --android`: PASS with the existing
  canonical YOLO hash, without supplying any SAM2 or ONNX model;
- `:dance_native:testDebugUnitTest`: 351 tests across 89 suites, zero failures,
  errors or skips (generated JUnit XML totals);
- Flutter app tests: 45/45; Phase 7 static verifier and `git diff --check master`
  passed;
- `flutter build apk --debug --no-pub`: PASS;
- built APK inspection: the YOLO and face-model SHA-256 values match their
  pins; no SAM2 `.tflite`, ONNX model, or ONNX Runtime file entry was found.

The APK build first failed because the optional toolchain selector injected a
standalone platform-tools directory as ANDROID_HOME. Retrying without that
selector let Flutter use its existing full Android SDK and succeeded. No SDK
license acceptance, SDK installation command, model re-export or gate bypass
was used. These are local candidate-working-tree results, not new GitHub Apple
CI or physical-device evidence. The artifact is
`mobile/app/build/app/outputs/flutter-apk/app-debug.apk`.

```text
python -m unittest tools.test_setup_models -v
python tools/release/verify_ios_phase7.py
python tools/setup_models.py --android
```

Also run the unchanged Phase 0-6 static verifiers, all Flutter tests/analyze,
Android `:dance_native:testDebugUnitTest`, the Debug APK build, and the Apple
Phase 7 lane on the exact candidate. No SAM2 or ONNX provisioning is required.

After the updated head has successful Production CI and Apple CI, the code
may be merged as an implementation baseline with real-iPhone acceptance
pending. No merge-readiness or release-readiness claim follows from parser
tests alone. Use `docs/ios_phase7_device_acceptance.md` for the separate
physical-device gate.

Final cleanup follow-up: Phase 7 Release Run #18 (`34458560785`) reached the
real CPU/XNNPack Simulator probe but failed before the inherited Phase 3-7
markers because the synthetic Phase 1 parity fixture produced no detections at
the production `0.25` confidence threshold. The tracked parity harness already
uses a low diagnostic threshold for this synthetic fixture. The bounded fix
therefore makes `0.001` explicit only for `runBundledFixture`, records and
validates that threshold in the smoke report, and keeps the production runner
default pinned at `0.25`. A fresh Phase 7 Release run must pass before merge;
Run #18 is not accepted evidence.

## PR #1 description for synchronization

Suggested title: `feat(ios): implement Phase 0-7 baseline; device acceptance pending`

Suggested body:

> Implements the bounded iOS 17 / art.gaoge.dance baseline: native media bridge,
> YOLO analyze, Metal preview, H.264/1080p30 export, temporal privacy semantics,
> independent Release build/audit CI and a fixed physical-device acceptance
> package. Pre-merge cleanup adds real CPU inference smoke and repairs Android
> YOLO/LiteRT staging with canonical hashes and existing face/native/build
> gates. SAM2 is unavailable and ONNX obsolete; neither is a model prerequisite.
> New Production/Apple CI results must be linked before merge.
>
> Simulator runtime checks are Debug evidence; the no-codesign iPhoneOS app
> supplies Release compilation/audit evidence only. Real-iPhone acceptance is
> pending. Phase 5 remains closed at 5H; no new numbered phase is introduced.
>
> The current remote base predates local master. This PR also includes existing
> Android/UX mainline history; do not describe the entire PR as iOS-only.

This text is a tracked handoff, not a claim that GitHub PR metadata was updated.
