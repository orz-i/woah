# Woah Stage 3 — MagicTouch Android synthetic-only ABI, CPU and geometry smoke

**Date:** 2026-10-20. **Branch:** `feat/crop-clarity-phase2-quality-performance`. Starting HEAD: `af0a28ac9c67c6b1541c7229bd0b73887f5f264f`.

**Disposition: ANDROID SYNTHETIC SMOKE HARNESS IMPLEMENTED; ACTUAL NATIVE COMPILE / ANDROID DEVICE RUNTIME NOT YET EXECUTED. FULL_BODY 39.733–39.900s STILL NOT FIXED; PRIVACY NOT SEALED; NO master MERGE.** Stage 3 touches only the Android **androidTest** source tree, a host-only test harness, numerical verifier tests and CI compile invocation. No production class, UI, renderer, source-video processing, proprietary media or model asset is modified or bundled.

## What has been independently confirmed

- Stage 2 officially pinned the public MagicTouch bundle (30,525,312 bytes; SHA-256 `38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03`), with two internal TFLite files plus manifest/metadata. The model weights remain outside the Git repository and both application and test APK assets.
- Google's stateful Android API uses `InteractiveSegmenter.createFromOptions`, `setImage(MPImage)`, `segment(List<Stroke>)` and `ByteBufferExtractor`. Android `BaseOptions.Builder.setModelAssetBuffer` accepts a **direct or memory-mapped ByteBuffer**, enabling a file provisioned separately into app-private storage. The present Gradle dependency is fixed at `com.google.mediapipe:tasks-vision:1.0.0`, not `latest.release`.
- A separate historical open-source Java 1.0.0 API reference contains stateful `setImage` and `segment`, but **the resolved AAR has not yet been linked against this Kotlin test in a real Android build**. The CI compile lane below is specifically intended to settle that ambiguity.

Official API sources:
- https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android
- https://developers.google.com/edge/api/mediapipe/java/com/google/mediapipe/tasks/core/BaseOptions.Builder
- https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/interactive_segmentation/android/app/src/main/java/com/mediapipe/example/interactivesegmentation/InteractiveSegmentationHelper.kt

## Test-only implementation (no private media)

`mobile/packages/dance_native/android/src/androidTest/kotlin/art/gaoge/dance/engine/inference/MagicTouchSyntheticSmokeInstrumentedTest.kt`

- Runs **only** when `woahMagicTouchStage3Enabled=1` AND given a fresh random 16-hex `woahMagicTouchStage3RunToken` through Android instrumentation. Unrelated ordinary `androidTest` runs skip the optional experiment.
- Loads the fully SHA-checked public .task from the debug application's **private `files/woah_magic_touch_stage3.task`**, memory maps it, and enables **CPU**. A genuine mismatch or missing file fails the opted-in test; it does not silently fall back.
- Generates synthetic colored rectangles and a circle directly inside RAM with `Bitmap.createBitmap` and `Canvas`. No video, camera, person photo, source path, consent-sensitive frame, portrait ROI, video identity or actual Woah privacy Mask is read.
- Measures initialization time and, after one warmup, five independent `setImage` and positive stroke `segment` pairs; reuses embeddings for additional positive+negative strokes. Rebinds a second synthetic 512×288 landscape image to check source-dependent output width/height (first input 384×384).
- Checks confidence buffer length, finiteness, 0..1 value bounds and matching pixel-space geometry; logs only small aggregate counts, initialization/set/segment median/p95 wall times, start/end sampled PSS and Android thermal state, **never confidence arrays or pictures**.
- The numeric log event is `WoahMagicTouchStage3` and is irrevocably labeled `pixel_carve_authorization=NEVER` and `privacy_visual_acceptance=NOT_PROVEN`. End-state PSS is not peak RAM; CPU wall clock is not GPU kernel time. Even a successful smoke does not prove any person identity, pixel ownership, quality or supported license terms.

The analyzer `tools/diagnostics/analyze_magic_touch_stage3_smoke.py` validates a strict whitelist of scalar telemetry and refuses mismatched APKs, missing/incorrect synthetic run tokens, GPU or privacy-PASS claims. At least **three** complete smoke runs must be collected for a repeat-status result; performance acceptance remains **NOT_PROVEN**.

## Automated Windows/Android device workflow (after Android test APK installation)

This flow is optional, intentionally external to the production Flutter editor, and must only use the public pinned model. It is not necessary to import user video or upload any visual media.

1. On the Android build workstation, compile Android native tests against the existing pinned AAR:
   ```powershell
   cd mobile\app\android
   .\gradlew.bat :dance_native:compileDebugAndroidTestKotlin
   .\gradlew.bat :dance_native:assembleDebugAndroidTest
   ```
   Verify the corresponding **instrumentation APK and its target debug application** are installed. Installation mechanics differ for a library AndroidTest variant. Confirm the instrumented runner's actual package with `adb shell pm list instrumentation` and the `(target=...)` value. **Do not assume the target app package from the test class namespace.** The host tool accepts only `art.gaoge.dance`, `art.gaoge.dance.engine` or `art.gaoge.dance.engine.test`; anything else fails closed for review.

2. Download the **public** fixed-version model to a location outside the source checkout using your normal network/tooling (only the model file, not personal source video):
   ```text
   https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task
   ```
   From the repo root, verify it first:
   ```powershell
   py -3 tools\diagnostics\verify_interactive_segmenter_asset.py --artifact "D:\WoahStage3\interactive_segmentation.task"
   ```

3. **One command** stages the SHA-pinned model into app-private storage, runs exactly the chosen instrumented class **three times**, reads only `WoahMagicTouchStage3` scalar logcat events with a unique random run token, writes one numeric JSON report **outside Git**, and removes the staged task file in `finally`. It never clears the device's other logcat events:
   ```powershell
   py -3 tools\diagnostics\stage_magic_touch_android_test.py run `
     --artifact "D:\WoahStage3\interactive_segmentation.task" `
     --package art.gaoge.dance.engine `
     --output "D:\WoahStage3\stage3-cpu-smoke.json" `
     --repeats 3
   ```
   Replace the `--package` argument with the **real** `target=` reported by the installed instrumentation. You can pass `--serial <adb-device-id>` when there is more than one connected Android target.

4. The result is either an explicit ABI/model/test failure or a CPU synthetic-only timing report that still says `privacy_visual_acceptance=NOT_PROVEN`. The same report can be independently generated from already collected *scalar-only* logcat text with:
   ```powershell
   py -3 tools\diagnostics\analyze_magic_touch_stage3_smoke.py `
     --log "D:\WoahStage3\stage3-tag-only.log" `
     --output "D:\WoahStage3\independent-report.json" `
     --min-runs 3 --run-token <run_token-from-stage3-cpu-smoke.json>
   ```

Optional cleanup (should already be automatic after `run`):
```powershell
py -3 tools\diagnostics\stage_magic_touch_android_test.py cleanup --package art.gaoge.dance.engine
```

## CI / verification

Production CI now **compiles** the optional `androidTest` source via `:dance_native:compileDebugAndroidTestKotlin` against the pinned library; it does **not** stage or download a model, run an emulator, request permission changes or call the test. This is a **native ABI compilation gate**, and can be marked passed **only after the GitHub run actually succeeds**.

On the current Mac, Java Runtime and Android SDK are unavailable; thus no Gradle build, actual MagicTouch invocation, model GPU path, Android 17 PSS or thermal measurements were performed in this stage. Host test modules verify exact test-only boundary, SHA/private transfer, failure cleanup, run-token isolation and aggregated metric integrity:

```bash
python3 -m unittest tools.diagnostics.test_magic_touch_stage3_native_contract -v
python3 -m unittest tools.diagnostics.test_stage_magic_touch_android_test -v
python3 -m unittest tools.diagnostics.test_analyze_magic_touch_stage3_smoke -v
python3 -m unittest discover -s tools/diagnostics -p 'test_*.py' -q
```

## No-go / exit conditions

A passing synthetic ABI+CPU smoke can only qualify MagicTouch for **further research**, not for production deployment. Explicit blockers remain:

1. The pinned v2 model's license and intended-use scope for nonconsensual human segmentation must be reviewed. The 2023 MagicTouch model card concerns a different 512×512×4 model and **does not authorize shipping the 2026 binary**.
2. Stage 1 produced **96 of 96 intentionally adversarial incomplete-protected-census layouts with hypothetical protected-person false-clears**; prompting a mask does not establish root-ID ownership or ensure all selected people are currently represented.
3. Zero protected-person false-clears must be demonstrated on independently reviewed overlapping real final frames (39.733–39.900s), while preserving all other people and the 46s stale-mask regression.
4. Native APK compilation/device measurements, acceptable benchmark budget, Crop Clarity Release OFF/ON and actual exported protected video review must pass before full sealing and merging master.

**Stage 3 is bounded:** if native ABI or synthetic model initialization fails, stop and resolve that specific incompatibility; do not weaken masks, resurrect obsolete SAM2/ONNX, widen YOLO boxes, or add extra trial-and-error privacy carve paths. Do not push/merge without the usual explicit user instruction.
