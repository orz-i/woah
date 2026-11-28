# M6 — Single candidate convergence and code pruning

Base: `d292c860`. Local research configuration; PR #48 / Issue #47.

## Configuration

Retain the canonical tracker, anatomical observation clock, two-anchor fragment recovery, certified offline body tracklets and nano head-instance arbitration with M5.8 identity reconnect. One unified runner requires the pinned nano model. Dense trace reuse and exact inference memoization remain performance options, not algorithm selectors.

The pre-pruning control uses this exact configuration for all thirteen targets. It reproduces all M5.8 candidate geometry and tracker states from the historical mixed references. One Ensemble row differs only in the removed compact-rival diagnostic field.

## Actual pruning

Remove profile-axis projected-head repair, the separate M5.5 post-placement ownership pass, M5.6 compact local negative-head retry, medium head-model selection, and the head-only/direct-replacement control. Retain the shared anatomical cap, observation veto, head competition minimax geometry, fragment proof and certified head/body reconnect. Historical experiments can be reproduced at the earlier Git commits; their code is not duplicated in the current tree.

11 implementation files changed, 276 lines added and 1,733 removed (net reduction 1,457); eight research C entry points removed. No official C API from the Issue #47 starting baseline is missing. No mobile code or model weights were changed.

## Results

| Target | Frames | Proposed regions | Longest empty run |
|---|---:|---:|---:|
| Baganda | 275 | 189 | 50 |
| Basque | 275 | 102 | 103 |
| Basque mirror | 275 | 77 | 132 |
| Ensemble | 300 | 294 | 5 |
| Danzon | 300 | 300 | 0 |
| Swing ID6 | 300 | 265 | 13 |
| Swing ID7 | 300 | 269 | 24 |
| Group ID2 | 120 | 106 | 14 |
| Group ID3 | 120 | 106 | 14 |
| Group ID4 | 120 | 120 | 0 |
| Group mirror ID5 | 120 | 105 | 13 |
| Salsa ID0 | 80 | 80 | 0 |
| Salsa ID1 | 80 | 80 | 0 |
| Total | 2665 | 2093 | — |

The retained configuration removes 214 person-segmentation calls in Ensemble (514 in M5.6 with compact-rival retry to 300), preserving the M5.8 final placement geometry. The code-pruning comparison is separately evaluated against the identical configuration before source deletion.

## Reproduction

```sh
"$PY" tools/privacy/evaluate_unified_candidate.py --library "$LIB" \
  --fixture "$FIXTURE" --target-id "$TARGET" --model-dir "$MODEL_DIR" \
  --head-instance-model "$HEAD_MODEL" --output-dir "$OUTPUT"
```

No experimental algorithm flags are required. Use `--shared-dense` and `--shared-reference` together when reusing same-video dense evidence. Head model weights and all per-frame source/trace outputs remain outside Git.

## Acceptance boundary

Convergence and behavior preservation are distinct from release acceptance. The Basque and mirror long gaps, severe neighbor overlap in some source audits, uncertain third-party model redistribution rights and device compute budget remain unresolved. This is not a mobile production configuration. Android device and iOS simulator exports are outstanding. Further work should target these actual blockers, not reintroduce a series of experimental algorithm branches.

## Final verification

Both complete runs (before and after pruning) passed all thirteen targets. All 2,665 candidate rows, canonical states, final placements, head-stream observations and scheduled dense evidence rows are exactly equal; per-model invocation counts are also identical. All 27 existing source-audit cases (183 labeled samples, including repeated samples between audits) yield exactly equal results. Rust: 236 passed, zero failed, one ignored; Release build, strict Clippy, formatting, ABI v1 smoke, C-header syntax and Python compilation passed. The three removed Rust tests covered retired experiments. The retained shared geometry tests remain.

See `m6-summary.json` for per-case trace hashes, counts, source-audit results and aggregate verification. GitHub CI is not the local acceptance basis for this research phase.
