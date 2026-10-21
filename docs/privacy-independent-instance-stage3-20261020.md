# Woah Stage 3 — MagicTouch Android synthetic-only ABI, CPU and geometry smoke

**Date:** 2026-10-20. **Branch:** `feat/crop-clarity-phase2-quality-performance`. Starting HEAD: `af0a28ac9c67c6b1541c7229bd0b73887f5f264f`.

**Current disposition after real Android CPU report:** Stage 3 **CPU synthetic runtime smoke completed** on PLK110 / API 37, with three uniquely verified runs from `c9a3edfe`. Median CPU `segment` = **508.078 ms**, highest per-run P95 = **657.995 ms**; end-of-run whole-process PSS ~480–491 MiB (not model-only/peak). **CPU per-frame production integration is not recommended**, and **privacy output / protected-person ownership remain NOT PROVEN**. The earlier initialization and logcat failures below are historical. FULL_BODY 39.733–39.900s STILL NOT FIXED; PRIVACY NOT SEALED; NO master MERGE.** Stage 3 touches only the Android **androidTest** source tree, a host-only test harness, numerical verifier tests and CI compile invocation. No production class, UI, renderer, source-video processing, proprietary media or model asset is modified or bundled.

## What has been independently confirmed

- Stage 2 officially pinned the public MagicTouch bundle (30,525,312 bytes; SHA-256 `38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03`), with two internal TFLite files plus manifest/metadata. The model weights remain outside the Git repository and both application and test APK assets.
- Google's stateful Android API uses `InteractiveSegmenter.createFromOptions`, `setImage(MPImage)`, `segment(List<Stroke>)` and `ByteBufferExtractor`. Android `BaseOptions.Builder.setModelAssetBuffer` accepts a **direct or memory-mapped ByteBuffer**, enabling a file provisioned separately into app-private storage. The present Gradle dependency is fixed at `com.google.mediapipe:tasks-vision:1.0.0`, not `latest.release`.
- A separate historical open-source Java 1.0.0 API reference contains stateful `setImage` and `segment`. The Windows Android test build and completed CPU instrumentations now establish the specific test-only runtime path on PLK110/API 37. This does **not** establish production export, GPU compatibility, or independent instance-identity correctness.

Official API sources:
- https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android
- https://developers.google.com/edge/api/mediapipe/java/com/google/mediapipe/tasks/core/BaseOptions.Builder
- https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/interactive_segmentation/android/app/src/main/java/com/mediapipe/example/interactivesegmentation/InteractiveSegmentationHelper.kt

## Test-only implementation (no private media)

`mobile/packages/dance_native/android/src/androidTest/kotlin/art/gaoge/dance/engine/inference/MagicTouchSyntheticSmokeInstrumentedTest.kt`

- Runs **only** when `woahMagicTouchStage3Enabled=1` AND given a fresh random 16-hex `woahMagicTouchStage3RunToken` through AndroidX `InstrumentationRegistry.getArguments()`. Reading arguments from a framework `Instrumentation` instance was corrected during pre-push API review. Unrelated ordinary `androidTest` runs skip the optional experiment.
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
   Verify the corresponding **instrumentation APK and its target debug application** are installed. Installation mechanics differ for a library AndroidTest variant. Inspect installed runners with `adb shell pm list instrumentation` if setup fails. The host runner now auto-discovers exactly **one** AndroidJUnitRunner whose target is among `art.gaoge.dance`, `art.gaoge.dance.engine` or `art.gaoge.dance.engine.test`. If more than one matching runner is present it refuses to stage the model, and you can explicitly choose the observed `(target=...)` using `--package`. **Do not assume the target app package from the test class namespace.**

2. Download the **public** fixed-version model to a location outside the source checkout using your normal network/tooling (only the model file, not personal source video):
   ```text
   https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task
   ```
   From the repo root, verify it first:
   ```powershell
   py -3 tools\diagnostics\verify_interactive_segmenter_asset.py --artifact "D:\WoahStage3\interactive_segmentation.task"
   ```

3. **One command** stages the SHA-pinned model into app-private storage, runs exactly the chosen instrumented class **three times**, assigns a **different random 16-hex run token to each invocation**, and verifies that invocation's tagged `WoahMagicTouchStage3` numeric event immediately after the test completes. This prevents a last-only logcat snapshot from losing earlier rounds, and prevents old or duplicated records from being counted as new runs. It writes one numeric JSON report with `run_tokens` **outside Git** and removes the staged task file in `finally`. It never clears the device's other logcat events:
   ```powershell
   py -3 tools\diagnostics\stage_magic_touch_android_test.py run `
     --artifact "D:\WoahStage3\interactive_segmentation.task" `
     --output "D:\WoahStage3\stage3-cpu-smoke.json" `
     --repeats 3
   ```
   The default `--package auto` finds the sole eligible installed instrumentation target. If several Woah runners exist, specify `--package` with the **real** `target=` value from `adb shell pm list instrumentation`. You can pass `--serial <adb-device-id>` when more than one Android device is connected.

4. The result is either an explicit ABI/model/test failure or a CPU synthetic-only timing report that still says `privacy_visual_acceptance=NOT_PROVEN`. A previous genuine Android execution passed all three instrumentations but failed the **final single logcat dump** check with `tagged synthetic smoke records incomplete/duplicated`. The available error gives no exact event count, so ring-buffer loss versus duplication cannot be proven retroactively. Stage 3 now validates **exactly one valid event for each distinct attempt token** as it runs. Failure writes `D:\WoahStage3\stage3-cpu-smoke.failure.txt` with integer-only counts (`tagged_json_lines`, `matching_attempt_token`, `valid_attempt_records`, `malformed_json`, etc.) and does not copy raw logs or masks. **Only the host Python runner and numeric analyzer changed; no APK reinstallation or model re-download is required.**

   **Pre-build follow-up:** on some AndroidJUnitRunner versions, `am instrument -w -r` reports a successful single test through `INSTRUMENTATION_STATUS_CODE: 1` (start), `0` (pass), and final `INSTRUMENTATION_CODE: -1`, but omits the formatted `OK (1 test)` footer. The Python runner now accepts only that exact single-test status sequence or a proven formatted single-test success, while still rejecting negative/skipped, missing-final, duplicate and unrelated test statuses. If the test genuinely fails, it saves a bounded, path-redacted synthetic-instrumentation excerpt as **`D:\WoahStage3\stage3-cpu-smoke.failure.txt`**, prints the reason, and removes the staged model. It does not clear logcat or capture user video. **This repair only changes the host Python helper; with the existing test APK installed, rerun after `git pull` without rebuilding/reinstalling APK or re-downloading the model.**

   The same report can be independently generated from already collected *scalar-only* logcat text with:
   ```powershell
   $tokens = (Get-Content "D:\WoahStage3\stage3-cpu-smoke.json" |
     ConvertFrom-Json).run_tokens
   py -3 tools\diagnostics\analyze_magic_touch_stage3_smoke.py `
     --log "D:\WoahStage3\stage3-tag-only.log" `
     --output "D:\WoahStage3\independent-report.json" `
     --min-runs 3 --run-tokens $tokens
   ```

The optional independent `--run-tokens` check requires a previously retained tag-filtered logcat text containing all three unique attempts. On a small Android log buffer those older messages may already have rotated away; **the automatic per-attempt verification is the authoritative host-side evidence**. There is no need to collect old logcat manually. The completed `stage3-cpu-smoke.json` report already contains the three verified per-attempt tokens.

Optional cleanup (should already be automatic after `run`):
```powershell
py -3 tools\diagnostics\stage_magic_touch_android_test.py cleanup --package art.gaoge.dance.engine
```

## CI / verification

Production CI now **compiles** the optional `androidTest` source via `:dance_native:compileDebugAndroidTestKotlin` against the pinned library; it does **not** stage or download a model, run an emulator, request permission changes or call the test. This is a **native ABI compilation gate**, and can be marked passed **only after the GitHub run actually succeeds**.

The current Mac lacks JDK/Android SDK and cannot compile or invoke Android natively. The user's separate Android workstation compiled and ran the installed test APK. After fixing the descriptor source and per-attempt logcat collection, **three independently tagged CPU runs were verified** with numeric latency/PSS/thermal aggregates. The result closes the CPU synthetic smoke *only*, while GPU, model-specific incremental/peak RAM, thermal endurance and real-video privacy remain unverified. Detailed current evidence is recorded in `docs/privacy-independent-instance-stage3-plk110-device-result-20261020.md`. Host modules guard the test-only boundary, SHA/private transfer, cleanup, run-token isolation and metric integrity:

```bash
python3 -m unittest tools.diagnostics.test_magic_touch_stage3_native_contract -v
python3 -m unittest tools.diagnostics.test_stage_magic_touch_android_test -v
python3 -m unittest tools.diagnostics.test_analyze_magic_touch_stage3_smoke -v
python3 -m unittest discover -s tools/diagnostics -p 'test_*.py' -q
```

## Android device follow-up: ExternalFile missing model source (2026-10-20)

A real opt-in Android 17 instrumented run from `23ada361b8904919e238796432ded41987aba47b` successfully reached the expected Stage 3 test class (target package `art.gaoge.dance.engine.test`, run 1/3). The host Stage 2 model SHA and the device-private 30,525,312-byte model SHA had already been validated by the test runner. The Java/Kotlin instrumentation setup was therefore functional, but the MagicTouch native model initialization **FAILED** before `setImage` or `segment` could be exercised:

```text
MediaPipeException: invalid argument: ExternalFile must specify at least one of
'file_content', 'file_name', 'file_pointer_meta' or 'file_descriptor_meta'.
    at InteractiveSegmenter.nativeCreate(...)
    at MagicTouchSyntheticSmokeInstrumentedTest.kt:82
INSTRUMENTATION_STATUS_CODE: -2
```

The previous test passed a read-only memory-mapped `ByteBuffer` to `BaseOptions.setModelAssetBuffer`. This did not produce a usable native `ExternalFile` source in the observed runtime, but the precise failure within the JNI buffer conversion is **not proven** by this stack trace. The narrow fix replaces **only the androidTest model binding**, using `ParcelFileDescriptor.open` with `MODE_READ_ONLY` and `BaseOptions.setModelAssetFileDescriptor(modelDescriptor.fd)`. The descriptor is kept open through the entire segmenter lifetime, then closed after `segmenter.close()`. This is an officially supported MediaPipe Tasks model source, maps to `file_descriptor_meta` rather than `file_pointer_meta`, and preserves the existing verified private file and CPU-only synthetic image tests. It never changes the production MediaPipe face locator, APK model assets, privacy resolver, renderer or app.

**Acceptance after code change: PENDING REBUILD/REINSTALL/DEVICE RECHECK.** The source and host contract tests pass; actual native fd-based `InteractiveSegmenter.createFromOptions` has not yet been run. A subsequent device error (for example an unsupported model operation) would be a separate cause, not proof the current issue was solved. No model download or original source-video retest is needed.

Windows procedure from the repository root, with the already-installed original Woah test target and model file:

```powershell
git pull --ff-only origin feat/crop-clarity-phase2-quality-performance
cd mobile\app\android
.\gradlew.bat :dance_native:assembleDebugAndroidTest
.\gradlew.bat :dance_native:installDebugAndroidTest
cd ..\..\..
uv run python .\tools\diagnostics\stage_magic_touch_android_test.py run `
  --artifact "D:\WoahStage3\interactive_segmentation.task" `
  --output "D:\WoahStage3\stage3-cpu-smoke.json" `
  --repeats 3
```

If the Android Gradle plugin does not expose `installDebugAndroidTest` for this library variant, do not assume the APK was updated. Collect the Gradle task error and installed `adb shell pm list instrumentation` result to resolve the actual APK path. The test host will save bounded failure context to `D:\WoahStage3\stage3-cpu-smoke.failure.txt` without requiring original media or a broad logcat dump.

## No-go / exit conditions

A passing synthetic ABI+CPU smoke can only qualify MagicTouch for **further research**, not for production deployment. Explicit blockers remain:

1. The pinned v2 model's license and intended-use scope for nonconsensual human segmentation must be reviewed. The 2023 MagicTouch model card concerns a different 512×512×4 model and **does not authorize shipping the 2026 binary**.
2. Stage 1 produced **96 of 96 intentionally adversarial incomplete-protected-census layouts with hypothetical protected-person false-clears**; prompting a mask does not establish root-ID ownership or ensure all selected people are currently represented.
3. Zero protected-person false-clears must be demonstrated on independently reviewed overlapping real final frames (39.733–39.900s), while preserving all other people and the 46s stale-mask regression.
4. Android native synthetic CPU test **ran successfully**, but its >500 ms per-inference cost makes full-frame CPU integration a poor fit. Acceptable Release throughput, GPU/memory/thermal performance (if GPU is ever justified), Crop Clarity Release OFF/ON and actual exported protected video review remain outstanding before full sealing and merging master.

**Stage 3 CPU smoke is bounded and closed:** do not ask for another routine CPU synthetic benchmark or use its PASS to weaken masks. Do not resurrect obsolete SAM2/ONNX, widen YOLO boxes, or add trial-and-error privacy carve paths. Do not push/merge without explicit user instruction.
