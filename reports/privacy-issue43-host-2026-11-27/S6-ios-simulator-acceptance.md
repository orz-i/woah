# S6 — iOS simulator export verification

**Original Issue #43 numeric gate PASS; expanded real-dance iOS acceptance NOT_PROVEN.** No merge. Production source c2d2d9e9, QA harness 3b04505e. Mac Xcode 27.2, iPhone 15 Pro simulator iOS 17.0.1, pinned Flutter 3.44.2/CocoaPods 1.17.0. No physical iPhone or Android run.

## Execution
Built the QA entry point with source fingerprint, checked compiled model assets, installed and ran the actual native media harness. Selected explicit ID 1 in the 18-frame AVC affine source (720x1280, 30fps), ID 0 in the first 3s of the reference clip, ID 1 in the first 3s of the target clip (both 592x1344, 30fps). Native analysis bounds match the reviewed initial subjects. Solid fill with face sticker enabled, quality profile, no follow, 4 Mbit/s. Six native smoke methods pass (Metal, export, Phase 5 shared golden traces including final size continuity, Phase 6, cache lifecycle, diagnostic privacy). All three actual exports complete, 198 decoded frames total; three protected preview/control pairs per case produced. The repeated experimental ROI run also passed all six smokes and completed the same three exports.

The numeric audit reproduces the original Android changed-yellow-pixel rule, checks decoded frame count, frame rate, dimensions, sampled width/height, count, height limit, displacement and horizontal span. Native crop traces independently confirm source timing and selected-ID coverage. The approximate source head labels and pixel differences are not semantic face pixel ground truth.

| Case | Frames | Initial sampled W/H ratio | ROI experiment W/H ratio |
|---|---:|---:|---:|
| Original affine | 18 | 1.214 / 1.169 | 1.163 / 1.167 |
| Reference left | 90 | 2.215 / 2.000 | 2.000 / 1.990 |
| Target right | 90 | 1.803 / 1.744 | 1.766 / 1.774 |

Original initial 7 sampled widths [345,403,389,380,332,362,354], motion errors 51.243/47.174 px and X span 149.5px; all original numeric assertions pass with the unchanged 1.35/75px/55px thresholds. Native selected region present 198/198 with zero additional region IDs. Affine approximate ellipse coverage minimum 99.970%, neighbor ellipse overlap zero; minimum changed>=90 pixel fraction in target rectangle 97.925%, neighbor fraction zero. These are limited audit probes, not a guarantee of complete privacy.

The expanded clips show actual disproportionate sticker expansion toward shoulders during arm motion, shrink on fallback and rebound on direct reacquisition. Initial left approximate ellipse minimum 99.952% (7/8 fully covered), right 55.455% (5/8). Pixel changes are not equivalent to face coverage, e.g. dark sunglasses can preserve dark pixel values. The right clip's visible upper head at a short miss motivates further native boundary work.

## Rejected experiment
A scoped explicit-selected-face ROI used existing FrameKernel head planning and existing current-mask/unique-owner association, with duplicate global-face checking; no tracker mutation. It improved original width ratio and the right minimum approximate ellipse coverage to 99.559%, but real clips retained substantial size swings, and right sampled height ratio worsened. Do not adopt as final production repair. Native Swift experiment reverted; local patch and both original outputs/metadata retained under the issue43/ios-s6 research directory. Source fingerprints, binary hashes, traces, native source hashes and complete decoded pixel measurements are in s6-ios-simulator-summary.json.

## Reproduction
Build ios_privacy_regression_20261104_main.dart with pinned models and QA source fingerprint; use configured harness with selectedIds and faceStickerEnabled (added for exact test selection, no production UI changes). Source affine is the previously archived AVC input, independent inputs are the first 3s of the local corpus normalized to 30fps, reencoded libx264 yuv420p; hashes in summary. Use tools/ios/run_privacy_regression.py with the three-case config recorded in the summary. Then run tools/privacy/audit_ios_issue43_export.py --sources SOURCES --run RUN_DIR --output AUDIT_JSON using OpenCV/numpy.

## Acceptance decision
S6 iOS original numeric reproduction passed. Extended actual-motion sizing and approximate coverage did not meet the desired generality. Next focus: current face support geometry and renderer boundary rather than extending temporal hold or adding multi-person recovery. Retain the previously scoped production integration, keep PR #46 draft and Issue #43 open. #47 remains separate.
