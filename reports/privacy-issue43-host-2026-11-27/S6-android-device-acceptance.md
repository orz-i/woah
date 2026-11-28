# S6 Android actual device verification

**Original Issue #43 PASS on the original OnePlus 8T / Android 14 device. Full FACE_ONLY export regression PASS. Extended real-motion comparison is not yet closed.**

Tested source 51551667 (production Android adapter 16aaec40). The default test package had an unrelated signing key. A separate opt-in `-PWOAH_ISSUE43_QA=true` QA package was built and installed after device-owner confirmation; existing apps and system installation policy were preserved. Installed base APK SHA-256 matches the locally built APK.

## Original reproduction

The unchanged `FaceOnlyExportPipelineInstrumentedTest#faceOnlyPrivacyFollowsDeterministicAffineDanceMotion` passed twice, including the full three-test class. On-device pipeline telemetry reports decoded/latched/rendered/encoded 18/18; 18 direct face observations. Original sampled values:

| Metric | Requirement | Android actual | iOS simulator previous |
|---|---:|---:|---:|
| Width ratio | <=1.35 | 1.0697 | 1.1329 |
| Height ratio | <=1.35 | 1.0788 | 1.1315 |
| Relative X error | <=75 px | 8.757 | 15.243 |
| Relative Y error | <=75 px | 53.838 | 22.338 |
| Horizontal span | >=55 px | 121 | 128 |

Width samples [339,353,346,336,332,331,330], height samples [340,356,346,335,332,332,330]. The standalone test and full-class repetition produce identical metrics. Full regression 3/3 passes (head-only, affine motion, independent mixed FULL_BODY / FACE_ONLY).

Pulled the actual AVC input and output to the local research directory. Independent decoded pixel audit passes the same original numeric assertions: all-frame ratios 1.0851 / 1.0788, 18/18 annotated head points exhibit changed>=90 pixels, maximum changed-pixel fraction of the checked neighbor regions zero. Minimum changed-pixel fraction of the approximate target head rectangle is 88.6148% (frame 15); visual review of four source/output pairs shows the sticker over the target head. Pixel change is not semantic privacy ground truth or an opaque mask coverage guarantee. Source and output hashes, per-frame observations and instrumentation log hashes are in s6-android-device-summary.json.

## Real reference extension (one explicitly selected target)

Existing opt-in NetworkPrivacyWorkflowInstrumentedTest, quality/CPU, sticker fill, first selected ID 0; initial analyzed source bbox [134.25,464.5,241.5625,849.0625] matches the left target of the iOS reference run. Same input SHA-256 as iOS, 592x1344 at 30fps, 90/90 output frames, increasing PTS. Device inference telemetry: 17 detected, 61 predicted, 12 estimated; zero-observation calls 69/90, 4 rejected; all 90 frames rendered. 89 complete placement-signature events persisted in asynchronous diagnostics, all recorded placements owned by ID 0. Do not infer the missing final event's placement from this log.

Sampled and all-frame decoded width/height ratios 1.5476 / 1.5238 (iOS corresponding sampled 1.2967 / 1.2527). The original synthetic gate has passed; this real clip exceeds the 1.35 comparison reference and remains unresolved. The Android test here asserts successful export and PTS, not the extended stability metric. Reviewed sparse head-envelope changed-pixel fraction minimum 76.667%; no independent neighbor annotations for this clip. Android right-target clip not executed in this run.

Root-cause diagnostic: using the actual device-reported ROI rects and person bboxes, host CPU inference with the same model, current host segmentation masks matched by >=0.60 IoU, and FrameKernel ownership/selection compared the recorded local crop (center anchor) to the current body-head plan. 89 frames matched. Local ROI selects 16, head ROI selects 66, 55 head selections where local fails, 56 head selections where actual device detection failed. PIL crop/host CPU masks are not exact native inputs; this is evidence for testing a bounded current-head ROI fallback, not an adopted device fix or new ownership evidence. No new algorithm or lease change in this verification turn.

## Decision

Original Issue #43 regression is fixed on both iOS simulator and Android original device. The expanded single-target real-motion consistency still differs across platforms. Keep PR #46 Draft pending scope decision or a focused native ROI follow-up; multi-person research stays #47. Do not interpret the original gate pass as full privacy acceptance. Latest CI for the QA change was running at the time of device verification; prior production adapter CI was green.

Device outputs and logs retained locally in `issue43/android-s6/device-affine` and `device-reference`. Reproduction:
```sh
./gradlew --offline -PWOAH_ISSUE43_QA=true :dance_native:assembleDebugAndroidTest
adb install -r issue43qa-debug-androidTest.apk
adb shell am instrument -w -r -e woahKeepIssue43Output true -e class 'art.gaoge.dance.engine.pipeline.FaceOnlyExportPipelineInstrumentedTest#faceOnlyPrivacyFollowsDeterministicAffineDanceMotion' art.gaoge.dance.engine.issue43qa.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class 'art.gaoge.dance.engine.pipeline.FaceOnlyExportPipelineInstrumentedTest' art.gaoge.dance.engine.issue43qa.test/androidx.test.runner.AndroidJUnitRunner
```
The 90-frame reference uses the pre-existing network test with locally supplied source, `-e case issue43_reference_left -e input issue43-reference.mp4 -e select first -e faceOnly true -e fill sticker -e width 592 -e height 1344 -e startMs 0 -e endMs 3000`. No source media or raw diagnostic files committed.
