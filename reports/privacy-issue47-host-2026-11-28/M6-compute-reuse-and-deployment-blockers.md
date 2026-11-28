# M6 — Compute reuse and deployment blockers

Base `20b8a05f`; host research only, same candidate and Rust library. No new algorithm branch, model or mobile resource.

## Retained changes

- Same-video dual-view head observation reuse via `--shared-head-stream`: verify source, model and library hashes, per-frame timestamps and raw geometry, then recompute fusion, ownership and links against current masks. The first target still requires inference.
- Compute the exclusive prototype grid once per dense scene, and reuse the source RGBA conversion for its face crops. Model inputs, weights and identity decisions remain unchanged.

Thirteen complete target replays preserve all 2,665 candidate rows and 2,093 proposed regions, canonical state, placement output, head observation rows and scheduled dense evidence. Head model calls decrease from 5,330 to 4,090 (1,240 avoided on four subsequent same-video targets); other model counts are identical. Wrong-fixture and modified-owner cache cases are rejected.

Pose preprocessing: 538 input comparisons and 269 face input comparisons are byte-exact on 18 source scenes. Two alternating 1,076-pose preprocessing runs per implementation average 1.793 s versus 0.978 s, without model execution. A separate sequential actual-model dense extraction benchmark on 33 scenes / 449 raw people gives old 5.283 and 4.129 s, new 4.030 and 3.867 s; all appearance vectors, face measurements and support arrays are exact. The small run-to-run variation and host environment limit timing interpretation. These are not mobile performance measurements.

## Deployment decision

Nano ONNX [model card](https://huggingface.co/trysem/YOLOv8_head_detector) lists MIT; [original training project](https://github.com/Abcfsa/YOLOv8_head_detector) has no separately visible license file, identifies YOLOv8 and SCUT-HEAD A/B. [SCUT-HEAD's official release](https://github.com/HCIILAB/SCUT-HEAD-Dataset-Release) specifies academic research use only. [Ultralytics licensing](https://www.ultralytics.com/license) describes AGPL-3.0 and Enterprise terms for its code and models. The Woah MIT license does not itself establish downstream model rights. Do not package or redistribute this weight without resolving applicable rights. Verify separately any existing entitlement for packaged YOLO11n-seg; do not infer it from the model export format. No change to the project's license or commercial agreement. No new weights downloaded.

## Residual quality and compute

Existing source labels and traces show distinct remaining mechanisms. Baganda fixed-grid coverage stays 7/11; on three of four labeled gaps the matching head detection has IoU >= 0.5, but current body/path evidence is insufficient or conflicting. In Basque mirror three audited missing frames have head IoU below 0.5, including two with zero overlap; other missing frames have a detected head but inadequate identity proof. For a Basque audited sample the required target rectangle overlaps 91.5% of the labeled neighbor and the best detected rival IoU is 0.052; final square covers 100%. Several Ensemble high-overlap samples similarly lack a distinct measured neighbor or have conflicting necessary bounds. Shifting the same square cannot resolve these mechanisms. Existing audited labels are sparse, not exhaustive.

A conservative possible-path probe using cached raw triplet graphs and head paths found at least one potentially relevant identity proof in each of the nine dense-scheduled target cases; therefore do not simply skip the full dense stage. The remaining first-target model inference cost, replacement/authorization of the head model, and residual coverage/competition errors are unresolved. Native Android/iOS integration and device export have not started.

## Reproduction

Use the M6 unified runner unchanged for the first target. For additional targets of exactly the same fixture, add `--shared-head-stream "$FIRST/head_stream.json"`, and optionally the established paired dense-evidence reuse arguments. The local trace hashes, timing observations, input checks and source diagnostics are summarized in `m6-compute-summary.json`; source media and weights remain local.
