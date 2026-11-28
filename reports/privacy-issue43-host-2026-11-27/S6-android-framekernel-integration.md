# S6 — Android shared head ellipse and two-axis integration

Baseline 9482e363 (iOS connected head ellipse and axis guard, prior CI green). Android adapter and instrumentation APK build PASS; device acceptance NOT_RUN_NO_ADB_DEVICE.

## Scope
- Add JNI transport for the existing Rust head ellipse and per-axis state. Both preview and export use these only for an explicit single FACE_ONLY sticker; whole-scene/other-face selection, body supplements, and ordinary face masks retain prior logic. Current mask refinement requires the actual observed selected instance, not unresolved motion evidence. The required render ellipse drives both raster and sticker placement after the temporal/identity cache update. Missing/invalid optional geometry follows the existing coverage route; no new identity or observation lease.
- Android already has local face ROI and pixel motion. No iOS primary/local detector arbitration or multi-person research was imported. Core Rust algorithms unchanged in this step. Mode changes clear size history; existing target/dormancy lifecycle and original 150ms direct lease retained.

## Verified
- 33 JVM tests passed with the actual linked Rust JNI dylib: region/size transport 6, current coverage 5, privacy mask raster 10, temporal stabilizer 12. New cases verify both axes, expiry/seek/mode reset, invalid input, connected-head source-cell coverage and production mask raster, and shoulder exclusion.
- Kotlin/Java production compilation, JNI syntax, ABI v1 smoke, and Android instrumentation compilation/package all pass. Built ARM64, ARMv7, x86_64 and verified exported new JNI symbols. APK package and embedded model/manifest and all 18 input-frame hashes verified. Local artifact archived under issue43/android-s6; hashes in s6-android-transport-summary.json.
- No ADB device was attached. Actual Android decoder/inference/renderer/encoder and original dynamic sticker test remain to be executed on device; no size or motion result inferred from host tests.

## Device command
With the intended device authorized and connected, from the repository root (Mac development environment):
```sh
source "$HOME/Coding/development-tools/env.sh"
adb devices -l
adb install -r "$HOME/Coding/woah-rust-tools/issue43/android-s6/dance_native-debug-androidTest.apk"
adb shell am instrument -w -r -e class 'art.gaoge.dance.engine.pipeline.FaceOnlyExportPipelineInstrumentedTest#faceOnlyPrivacyFollowsDeterministicAffineDanceMotion' art.gaoge.dance.engine.test/androidx.test.runner.AndroidJUnitRunner
```
If an installed test package is signed differently, use a fresh QA package or explicitly authorized replacement rather than uninstalling unrelated apps. Then run the entire FaceOnlyExportPipelineInstrumentedTest class. Original 1.35 width/height, 75px motion and 55px horizontal span thresholds remain unchanged. No merge/closure before device verification. iOS full-frame left width ratio 1.3556 and approximate envelope corners remain recorded in the previous report; #47 stays separate.
