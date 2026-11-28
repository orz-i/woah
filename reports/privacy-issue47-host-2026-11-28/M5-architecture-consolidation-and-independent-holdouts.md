# M5 — ablation, retirement and independent crowded holdouts

Base `7417d9f1` (M4.8). Research-only; no native activation or issue closure. Active host candidate is M4.2 canonical association with M4.6 offline identity consensus and M4.7 current shoulder/ear/flip/detail geometry. Shared dense evidence is retained. This is a smaller research configuration, not a release-ready policy.

## New independent footage

Use existing pinned source-only body landmarks and source video hashes, never later labels as matching input. New to this PR's identity/coverage experiments: C5 Danzón and Baganda and C8.3 Ensemble. Decode at 25 fps, 960x540; choose the first existing landmark with a unique selected raw mask, confidence >=0.60 and no negative landmark support. Danzón and Ensemble start at source frame 0, target ID4; Baganda starts at source frame 25 (initial selected raw confidence only 0.35 at frame 0), target ID10. Time-shift labels only; the distractor is a current negative, not a persistent partner. All three MP4 hashes match the frozen repository source descriptions.

| Source | M3.3 regions | M4 regions | M4.1 regions | M4.2 regions | Selected correct landmarks M3.3 -> M4.2 |
|---|---:|---:|---:|---:|---|
| Danzón | 259/300 | 300/300 | 300/300 | 300/300 | 10/12 -> 12/12 |
| Ensemble | 277/300 | 294/300 | 294/300 | 294/300 | 11/12 -> 12/12 |
| Baganda | 92/275 | 37/275 | 37/275 | 37/275 | 4/11 -> 1/11 |

No wrong selected-body landmarks in these sparse audits. Missing/unconfirmed checks are not proof of safe occlusion. The gains and regression are already present in fixed M4 clothing association; subsequent adaptive and motion stages do not change these counts or sparse audits. M4.2 must not be globally activated on this evidence. Baganda is a new independent regression.

Targeted source-only head rectangle audit of the three gained body checkpoints (sample times selected after body audit, not a blind dense coverage audit): Danzón 1/2 full head envelopes, the other 62.51% covered, max checked neighbor rectangle overlap 7.05%; Ensemble 1/1 full head with 43.75% checked nearby head overlap. Checked torso probes clear. The M3.3 baseline covers none of these three sampled head labels. This separates correct body association from actual head protection quality; even the two identity-successful holdouts do not pass privacy acceptance. Labels committed alongside this report.

## Actual head path ablation

Re-run five cases (Basque original/mirror plus all three new holdouts) using the identical M4.8 Release library and dense input. Nested profiles are shoulder only, +ear and mapped flip, +two-view detail, +RGB pixel. Counts below are generated regions, not dense pixel acceptance.

| Profile | Basque original | Basque mirror | Additional over preceding stage | Extra model cost in two Basque cases |
|---|---:|---:|---:|---|
| M4.2 direct reference | 75 | 62 | — | — |
| Shoulder cap | 88 | 74 | 25 | 39 current recovery attempts |
| Ear + mapped flip | 91 | 76 | 5 | 10 extra current pose calls |
| Two-view detail | 92 | 78 | 3 | 12 additional local person calls, plus local face/pose |
| RGB pixel | 93 | 78 | 1 | 6 bounded current RGB geometry attempts, no new model |

The three new independent sources add no coverage in any offline profile: Danzón already renders every frame; Baganda and Ensemble have no accepted offline target spans. No positive independent head-recovery generalization yet.

## Code consolidation

Retire M4.3 three-scale offline face coverage (Swing frame 146 had up to 51.61% checked non-target head overlap), while preserving the shared raw-mask reciprocal two-anchor path used for seed and triplet evidence. Remove the M4.8 RGB component lane from active code: one source-specific verified region and no mirrored or independent improvement for a large additional path. Its valid Basque sample is deliberately no longer rendered, not relabeled as occluded. Both can be reproduced by checking out historical commit `7417d9f1` and using the historical flags/reports; do not use those flags on this pruned head. The recovery adapter rejects traces carrying retired kind 6 rather than silently preserving the known bad old region. Keep M4.7 two-view detail as a conditional extended stage; it recovered three source-audited regions at the observed cost. Shared dense reuse stays in place.

Remove 714 code lines and three unused research C entry points (no native consumer); ABI v1 and geometry hash unchanged. No production feature changes. All 13 complete final-library canonical and dense/recovery comparisons reproduce the pre-prune tracking state and selected spans. Exactly two placement rows differ: Basque 93 -> 92/275 (retired RGB sample), Swing selected 266 -> 265/300 (retired high-overlap sample). The other 11 cases reproduce all frame rows exactly. The canonical replay reproduces prior M4.2 output on all 13 cases. Final Basque late spans are 17/20 and 16/19, six unresolved current head placements. The much larger long-term selected-body deficit on Basque and the older mirror regression remain open.

Rust all-targets 225 passed, 1 ignored (two tests removed with the two retired research branches), strict Clippy, fmt, Python syntax, ABI v1 pass. All final evidence uses the same rebuilt Release library. Detailed source/replay/audit hashes in `m5-summary.json`. Raw media, model weights and replay traces remain local.

Next: fix Baganda's fixed-appearance association failure without sacrificing Danzón and Ensemble, and replace incorrect oversized or under-covering head placement on correctly identified bodies. Use these independent failures, not the five/six Basque-specific misses, as priority. No new threshold expansion or mobile activation. The separate iOS CI dependency issue remains unresolved by this host task.

## Reproduction

Use the current library with the existing M4.2 replay flags through `--motion-clothing` (no retired `--offline-tracklet`). Then `evaluate_dense_tracklets.py` and `evaluate_tracklet_recovery.py --ear-cap --detail-cap`. For C5 Danzón/Baganda and C8.3 Ensemble verify the exact tested MP4 hashes from repository source descriptions; decode JPEGs in order at their native 25fps, slice Baganda at frame 25, make the usual hashed manifest and shift source annotation time accordingly. Use `audit_pair_identity_landmarks.py --selected-only`. For the M4.8 nested ablations check out `7417d9f1`, use the original M4.8 binary and historical `--pixel-cap` option.
