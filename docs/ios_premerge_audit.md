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

## Android assets: no gate bypass

`tools/setup_models.py --android` now validates and stages these four existing
LiteRT exports from the repository-root `models/litert` directory:

- `yolo11n-seg-fp16.tflite` (canonical SHA-256 from the shared YOLO contract);
- `sam2_image_features.tflite`;
- `sam2_init_step.tflite`;
- `sam2_temporal_step.tflite`.

The Gradle sync path is anchored to the plugin project, fixing the old
app-root-relative path. All four required-model checks, native tests and APK
build steps remain mandatory. The legacy CI entrypoint stays compatible;
it no longer downloads/re-exports an unrelated ONNX model.

This worktree contains the Git-tracked canonical YOLO model but no accepted
SAM2 binaries. The bootstrap deliberately lists every missing SAM2 file and
fails before staging any subset. A correct staging implementation is NOT a
successful production CI run. SAM2 hashes printed by the tool identify the
supplied bytes; they do not establish a newly accepted algorithm/model baseline.

To complete provisioning, place the three previously accepted SAM2 exports in
`models/litert` alongside the canonical YOLO file. Alternatively, an explicitly
supplied local directory containing all four accepted exports can be staged:

```text
python tools/setup_models.py --android --source-dir <accepted-four-model-directory>
```

A clean GitHub checkout also needs these accepted SAM2 bytes from an approved
artifact source, with its immutable identity recorded and verified. Local
ignored files do not automatically reach GitHub. No source URL or SAM2 hash
was invented, and no placeholder, random model, or implicit model re-export
was added to make the job green. Publishing or provisioning that accepted
artifact is still required before claiming Android CI is fixed end-to-end.

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

Local cleanup verification on 2026-09-10: model-staging unit tests 8/8;
Flutter app tests 45/45 (including six CPU-report contract tests), native Dart
tests 3/3, domain tests 5/5 and UI tests 1/1; app analysis and the Phase 0-7 /
cloud static verifiers passed. Domain/UI dependency caches were initialized
offline; no tracked dependency lock was changed. Actual Android provisioning
was executed and returned exit 1 for the three missing SAM2 files. No native
Android test/APK build or new Apple CI success is claimed from these results.

```text
python -m unittest tools.test_setup_models -v
python tools/release/verify_ios_phase7.py
python tools/setup_models.py --android
```

Also run the unchanged Phase 0-6 static verifiers, all Flutter tests/analyze,
Android `:dance_native:testDebugUnitTest`, the Debug APK build, and the Apple
Phase 7 lane on the exact candidate. The Android commands cannot pass on this
checkout until the accepted SAM2 models are supplied. Retain that blocker.

After the updated head has successful Production CI and Apple CI, the code
may be merged as an implementation baseline with real-iPhone acceptance
pending. No merge-readiness or release-readiness claim follows from parser
tests alone. Use `docs/ios_phase7_device_acceptance.md` for the separate
physical-device gate.

## PR #1 description for synchronization

Suggested title: `feat(ios): implement Phase 0-7 baseline; device acceptance pending`

Suggested body:

> Implements the bounded iOS 17 / art.gaoge.dance baseline: native media bridge,
> YOLO analyze, Metal preview, H.264/1080p30 export, temporal privacy semantics,
> independent Release build/audit CI and a fixed physical-device acceptance
> package. Pre-merge cleanup adds real CPU inference smoke and repairs Android
> LiteRT staging without weakening Android gates. Actual model provisioning
> and new Production/Apple CI results must be linked before merge.
>
> Simulator runtime checks are Debug evidence; the no-codesign iPhoneOS app
> supplies Release compilation/audit evidence only. Real-iPhone acceptance is
> pending. Phase 5 remains closed at 5H; no new numbered phase is introduced.
>
> The current remote base predates local master. This PR also includes existing
> Android/UX mainline history; do not describe the entire PR as iOS-only.

This text is a tracked handoff, not a claim that GitHub PR metadata was updated.
