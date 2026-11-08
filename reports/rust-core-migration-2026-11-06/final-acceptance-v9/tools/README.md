# Full native regression evidence

Use the eight materials in the frozen matrix. The 22 legacy, 27 original 4K and
13 focused cases run with previews and native core smokes. Run the entire
original 4K cohort in one process so that cancellation, immediate retry, and
consecutive exports exercise the same native session. An interrupted run or a
filtered diagnostic cannot supply missing cases for that cohort.

Run `tools/privacy/run_regression.py --suite all`, followed by
`audit-final-sequence.py`. The auditor waits for a completed native cohort
before creating independent media, face, preview, gallery and lifecycle
evidence. It does not accept unresolved alerts. Review the actual boards,
including topology, export and nested preview candidates. Keep raw audit
statuses and record separate decisions tied to the reviewed image hashes.

The case-board tool can seek to the three already decoded movie frames after
matching the independent media row, movie hash, frame count, dimensions and exact
encoded thumbnail samples. If any binding or sample differs, it decodes in
order. The independent full-media audit remains mandatory.

Collect each completed cohort on its originating host:

```text
python collect-final-evidence.py REPOSITORY RAW_REPORT PRIVATE_CURATED_PLATFORM --platform android --suite legacy22
```

Repeat for `original4k` and `focused13`, on Android and on iOS. Archive the
local detector ledger, control, producer approvals and contract. Use
`tools/privacy/audit_observation_replay.py` to place the aggregate
`observation-replay-audit.json` in each curated cohort. A cache hit only
substitutes compatible detector observations. Tracking, ownership, temporal
policy, masks and native media output run again.

For supplemental review:

- `review-known-effects.py` creates 88 source/output comparisons for the
  complete non-solid solo 4K exports.
- `create-risk-targets.py` creates local targets from independent source
  detections, without consulting protected output.
- `review-continuous-risk-spans.py` compares six trajectories covering
  overlap, image clipping, the profile transition and chin margins. It creates
  91 face-frame pairs per closed, immutable group output, across 12 modes.
- `review-native-edge-previews.py` enlarges actual source/protected preview
  pairs at 7400 and 8120 ms.

Inspect the actual images and record SHA-bound decisions. Reuse an earlier
visual decision only for exact matching displayed image bytes with the same
source/case/frame/crop context. A change in displayed pixels needs inspection.
Place the reviewed supplemental folders in the curated platform directory.

```text
python aggregate-final-evidence.py REPOSITORY PRIVATE_CURATED_BASE android
python combine-safe-summaries.py ANDROID_SUMMARY IOS_SUMMARY SAFE_COMBINED_SUMMARY
```

Aggregate each host independently. All 62 cases and their independent checks,
replay receipts, exact installed model bytes and supplemental reviews must
validate before the platform summary can be emitted. Transfer only counts and
hashes summaries. Keep raw detector observations, masks, coordinates, crops,
review images and approval files on their originating host. iOS simulator
results do not measure iPhone hardware performance.
