# S6 Android current-head ROI retry

**Original Issue #43 real-device gate PASS; left extension now meets the comparison size reference; right extension has residual size variation.** OnePlus 8T, Android 14, same isolated QA package. No merge or release-level privacy claim.

## Change

A narrow cached/pixel-local ROI sometimes repeatedly fails despite an exact currently observed selected person. For one explicitly selected FACE_ONLY sticker, add at most one different current-body head ROI retry after a due local detector fails to supply a direct face. Requires active observed target, current instance mask, normal DIRECT mode, and no dormant probe. Uses the existing FrameKernel head plan and current-face mask ownership, anchor ambiguity rules, and an additional veto if another current observed person owns the candidate. The new broad observation retains its measured size and goes through the existing temporal center and coverage gates. Reuse existing renderer/sampler and detector, track no new identity, and do not extend historical leases. No changes to Rust thresholds, masks, sizing policy, or #47. Other modes unchanged.

The existing real-media instrumentation test now accepts an explicit selectedId and reports analyzed boxes, enabling exact comparison to iOS without changing follow or whole-scene scope.

## Device exports

| Case | Frames | Direct / predicted / estimated | Sampled W / H |
|---|---:|---:|---:|
| Affine baseline | 18/18 | 18 / 0 / 0 | 1.070 / 1.079 |
| Affine candidate | 18/18 | 18 / 0 / 0 | 1.070 / 1.079 |
| Left baseline | 90/90 | 17 / 61 / 12 | 1.548 / 1.524 |
| Left candidate | 90/90 | 69 / 18 / 3 | 1.347 / 1.347 |
| Right candidate | 90/90 | 89 / 1 / 0 | 1.400 / 1.416 |

Original all-frame ratio 1.085/1.079, relative motion 8.757/53.838px, horizontal span 121px; sampled metrics equal to baseline and original assertions pass. All three actual exports have the correct counts. Left and right all-frame ratios equal sampled ratios. Initial analysis boxes match the reviewed left/right targets. Right is a first Android measurement, no baseline claim. Left additional detector calls 78 (168 vs 90), one test run's pipeline duration 35.67s vs 32.50s (~9.8% increase, not a controlled performance benchmark). Right uses 97 calls, affine 18 (unchanged). Asynchronous diagnostics may omit tail events; complete logged placement records contain only selected IDs. No general throughput claim.

Minimum approximate changed>=90 head-rectangle fraction: affine 88.615% (unchanged), left 77.136% (baseline 76.667%), right 79.725%. Original checked neighbor change fraction 0%. Sparse real-head rectangles and color change are not semantic opacity/coverage; dark sticker artwork matches some dark source pixels. Visual inspection of five source/output pairs per real clip shows sticker placement over the target head; right placement width ranges from ~126px at early frames to ~90px later as the dancer turns/lowers, then increases. Exact real head scale versus excess size variation is not independently resolved. Do not assert that all right variation is normal or simply extend size smoothing to force the ratio below 1.35.

## Verification
13 focused JVM/JNI tests pass (8 candidate selector, 5 current face coverage), including missing-mask, rival-owner, and near-tie rejection. Instrumentation APK compiled, installed APK hash matches local candidate. Full FACE_ONLY export regression 3/3 passes on the candidate, original affine test independently passes. Tested patch hash, APK hash, actual decoded pixel traces, selected IDs, telemetry and placement data in s6-android-current-head-retry-summary.json. Local videos and logs in issue43/android-s6/roi-v1; no video or raw masks committed. Candidate APK is bound to the exact diff from eac3e8b5, before the source/evidence commit. iOS not rebuilt because no iOS/Rust source changed.

Reproduction: build the isolated QA APK with -PWOAH_ISSUE43_QA=true, run the original affine and full-class tests from S6-android-device-acceptance.md, and the existing NetworkPrivacyWorkflowInstrumentedTest with -e selectedId 0/1, -e faceOnly true -e fill sticker -e width 592 -e height 1344 -e startMs 0 -e endMs 3000 on issue43-reference/target.mp4. Audit with compare from tools/privacy/audit_ios_issue43_export.py using the existing source-reviewed rectangles.

Retain the device-verified ROI repair. The original #43 numeric issue passes on both platforms; right expanded comparison remains above the original reference and semantic edge coverage is not fully proven. Keep Draft. Remaining issue is scoped Android geometry/actual head scale, not multi-person recovery or repeated ROI threshold relaxation.
