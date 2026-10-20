# FULL_BODY 39.7s — YOLO NMS visible-mask counterfactual (closed)

**Decision (2026-10-20): RETIRED AS CAUSE OF THIS FIXTURE.**
The experiment shipped only as a Debug shadow in `3b352908eb15b30fa95fd9c4caafdb12f009cbff`. Production detections and privacy rendering were unchanged. The follow-up on real Android exports proves that changing NMS's active-mask threshold from 1 to 128 **does not recover any additional detection candidate** in the entire focus window. It does not prove that the original final-video privacy is correct. **FULL_BODY visual acceptance remains NOT_PROVEN; sealing and master merge remain BLOCKED.**

## Paired device evidence

Both ordinary diagnostic bundles declare `git_commit_sha=3b352908eb15b30fa95fd9c4caafdb12f009cbff`, Android API 37, an identical 1280×720 / 30 fps source, and independent completed jobs:

| Mode | Source ZIP | Job ID | Output | Frames decoded/rendered/encoded |
| --- | --- | --- | --- | --- |
| Landscape | `woah_diag_20261020_091700_20261020_090956_294440ff.zip` | `job_1792487406903` | 1280×720 | 1391/1391/1391 |
| Portrait | `woah_diag_20261020_092457_20261020_090956_294440ff.zip` | `job_1792487842110` | 810×1440 | 1391/1391/1391 |

Only job-scoped JSONL aggregate events, manifest, and pipeline summary were read. The ZIPs also contain raw model `.f32s`, `.rgba`, and YUV sample files; **none of those media/tensor files were opened, rendered, copied to the Git repository, or used for pixel-ownership conclusions**. The second archive also contains historical events; the comparison explicitly filters by the actual `job_id`.

### Actual per-frame NMS experiment (identical across both modes)

| Source PTS (seconds) | YOLO raw candidates | Legacy NMS kept, byte >=1 | Counterfactual NMS kept, byte >=128 | Counterfactual-only candidates |
| ---: | ---: | ---: | ---: | ---: |
| 39.600 | 58 | 6 | 6 | 0 |
| 39.633 | 55 | 6 | 6 | 0 |
| 39.667 | 56 | 6 | 6 | 0 |
| 39.700 | 50 | 6 | 6 | 0 |
| 39.733 | 56 | 7 | 7 | 0 |
| 39.767 | 63 | 7 | 7 | 0 |
| 39.800 | 70 | 8 | 8 | 0 |
| 39.833 | 61 | 8 | 8 | 0 |
| 39.867 | 60 | 8 | 8 | 0 |
| 39.900 | 62 | 7 | 7 | 0 |

Each of the **20** shadow comparison events (`10 frames × 2 outputs`) also reports `legacy_only_count=0`, `strong_only_geometry=[]`, and `does_not_change_rendering=true`. Thus both policies kept the **same candidate instances**, not merely equal candidate counts. The 10 events' scalar records match exactly across modes when excluding job ID.

Source-space paired equivalence is also intact: **1391/1391** identical production Track signatures, **1391/1391** privacy input signatures, **41/41** unknown foreground overlap audits, and **11/11** final-tail GL-input audits (ignoring intentional crop clarity scale). The one `PRIVACY_COVERAGE_DROP` remains at 39.733333s: selected raw area 82, effective 65, ratio **0.79268295** (79.27%). No severe undercoverage/fail-closed event occurred in these scoped export logs, but this cannot establish that a protected person's pixels were never exposed.

At **46.333333s**, selected root ID1 was unobserved for **178 frames** and its fallback Mask was expired. Actual GL-upload source-visible privacy-mask sample count was **23** in both modes, as previously observed. This is **log-level non-regression**, not final-video visual acceptance.

## Engineering disposition

The soft-mask-tail hypothesis was scientifically plausible: counting all nonzero sigmoid mask bytes in IoU can merge two nearby candidates whose >=128 foreground cores differ. Kotlin synthetic tests at the experiment checkpoint demonstrated that mechanism. It is **not an effective explanation for the current real clip**, as directly falsified by the zero-difference device comparison.

- **Retire all Android runtime shadow machinery**, including the second per-frame NMS run, its threshold parameter/hot-loop branches, Debug event emission, and temporary native synthetic comparison tests. Restore `YoloMaskDecoder`, `YoloLiteRtTensorAdapter`, `YoloLiteRtSegmenter` and `YoloMaskAwareNmsTest` exactly to their contents preceding `3b35290`. The observed production NMS threshold remains its historical nonzero-byte rule.
- Retain a **read-only, numeric-only historical parser and tests** for `YOLO_NMS_VISIBLE_THRESHOLD_SHADOW` in `tools/diagnostics/analyze_privacy_overlap_evidence.py`, so archived device ZIPs can still be independently verified. This parser does not modify production code and never reports a visual privacy PASS.
- Preserve earlier raw YOLO top-support diagnostics, conservative unknown SELECTED masking, GL fail-closed, selection isolation, and 46-second observation-age mask expiry. Do not widen YOLO boxes, lower mask thresholds, clear selected roots, or infer ownership from motion/appearance.
- No repeat export for NMS is necessary. Future work must obtain genuinely **independent pixel/instance evidence** in the overlap (including protection of the real selected person) before any proposed unmasking. A model- or correspondence-based route must demonstrate zero protected-person false clearing under ambiguous similar-attire crossings, and pass final output video review.

## Sealing / merge gate

**NOT READY.** 39.733–39.900s protagonist overmask remains unresolved; model-level raw upper-body support was weak and the NMS alternative has been falsified. Real protected final-output review of that region and 46s, Crop Clarity Release OFF/ON benchmark and image quality review, and complete Android native/CI verification remain outstanding. No inference, GL output, follow-camera, or privacy-class change is authorized by this diagnostic experiment. No push/master merge is part of this retirement.
