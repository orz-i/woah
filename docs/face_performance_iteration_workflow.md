# FACE_ONLY performance iteration workflow

The three-device loop is a **promotion gate**, not the default development loop.
Cross-device determinism is already established on the accepted fixture, so small
performance changes should be rejected as cheaply as possible before requiring
PLK110, 2206123SC, and KB2000 again.

## 1. Local gate for every edit

Run focused JVM tests for the touched component, then the complete
`:dance_native:testDebugUnitTest` suite and `git diff --check`. A change that
cannot prove its policy/math equivalence locally does not go to a phone.

## 2. One-device canary for micro-iterations

Pick the device that exposes the target bottleneck most strongly. **KB2000** is
the default Face-only CPU canary unless a different device is demonstrably more
sensitive to the stage under test. For the current pixel-motion work, do one
normal fixture export and run:

```text
python tools/diagnostics/face_iteration_gate.py check <KB-bundle.zip> \
  --contract tools/diagnostics/baselines/face_only_febd1b4_exact.json \
  --performance-baseline-contract tools/diagnostics/baselines/face_only_accumulation_kb.json \
  --target-stage face_pixel_motion --target-stat p50_ms \
  --min-improvement-pct 10 \
  --target-work pixel_motion_frames_total --target-work-mode equal
```

Current local device roles: use `emulator-5560` for CPU-only micro-benchmarks.
Reserve `334da6a3` (BK) for candidates that actually touch a GPU/GL/LiteRT GPU
boundary. GPU acceleration remains performance-only and must never become
deterministic Face identity authority.

The gate has independent quality, target-stage, and optional structural-work
requirements. Use the nearest stage to the optimization rather than a noisy
full-pipeline average. For detector scheduling, `detector_calls_total` is a
deterministic work-count signal while detector `p50_ms` is much less sensitive to
thermal/scheduler outliers than the full `face_privacy` average.

For temporal privacy-class optimizations, target `face_temporal_class` directly.
Its baseline is captured from the `FACE_PRIVACY_TEMPORAL_CLASS_EVIDENCE`
`elapsed_ms` field, while the exact golden fingerprints still require temporal
evidence, sticker placement, class fallback, and deterministic CPU identity to
remain frame-exact.

During an accumulation batch, quality remains anchored to the last promoted
three-device exact golden, while performance should compare against the latest
accepted same-device accumulation state. Pass that bundle with
`--performance-baseline-bundle`, or preferably persist it once with `snapshot`
and use `--performance-baseline-contract` when `/logs` is routinely replaced.
This prevents later optimizations from claiming credit for gains that were
already accepted earlier in the batch.

For pixel-motion execution-only work, also require
`--target-work pixel_motion_frames_total --target-work-mode equal` so a candidate
cannot win by silently running the motion tracker on fewer frames.

For parallel detector execution, target `face_detector_wall` p50 instead of the
sum-of-call `face_detector` metric. The accepted pre-parallel KB baseline aliases
`face_detector_wall` p50 to the historical sequential detector p50 (85 ms), since
those calls were executed serially and this is the correct transition baseline
for an execution-overlap-only optimization. Pair it with
`--target-work detector_calls_total --target-work-mode equal` so a faster result
cannot be promoted by silently reducing detector cadence.

The default gate mode is **accumulation**, not promotion. A normal micro-iteration
therefore has three possible outcomes:

- `NO_GO_TRI_DEVICE_QUALITY_DRIFT`: stop and diagnose on the canary;
- `CONTINUE_SINGLE_DEVICE_OPTIMIZATION`: quality is intact but the cumulative gain
  is not yet worth another hardware run;
- `ACCEPT_FOR_ACCUMULATION`: keep the change in the current performance batch and
  continue optimizing without another phone run.

A second KB2000 run is **optional diagnostic evidence**, not a standard gate. Use
it only when the first run is suspiciously close to the threshold, shows unusual
thermal/scheduler behavior, or the changed boundary is known to be runtime-
sensitive. Multiple same-device bundles can still be passed to `check`, which uses
their median target-stage statistic.

## 3. Accumulate gains before the three-device milestone

Behavior-neutral optimizations accumulate behind the same exact golden. Do not run
three phones after each accepted canary. The normal hardware budget is therefore
**one KB2000 run per optimization candidate**, not `KB x2 + three-device`.

Continue stacking accepted-on-canary changes until one of these explicit milestone
triggers is true:

- cumulative end-to-end or bottleneck-stage improvement is large enough to close
  the current performance batch (normally >=15-20% over the last three-device
  golden, judged on a stable stage rather than one noisy full-pipeline run);
- the current performance phase is considered finished or a release checkpoint is
  approaching;
- the change touches a cross-device-sensitive boundary such as decoder pixel
  semantics, model/precision/backend, deterministic identity authority, or a
  platform-specific implementation.

Do **not** trigger a milestone merely because one micro-optimization passed its
single-device target. The purpose of accumulation is to amortize one PLK110 +
2206123SC + KB2000 matrix across several optimizations.

Changes that intentionally alter detector cadence, fallback behavior, or rendered
geometry are **quality changes**, even if motivated by performance. They require a
visual/privacy acceptance decision before becoming the new golden contract.

## 4. Three-device promotion gate

At an explicit milestone, first run the current KB bundle through the gate with
`--promotion-mode milestone`. One KB run is sufficient by default; an extra
same-device confirmation is opt-in through `--min-canary-runs-for-milestone 2`.
Only then run PLK110 + 2206123SC + KB2000 once. Require the established
cross-device checks (Analyze selection, CPU identity, Face ROI where applicable,
sticker placement, class fallback, temporal evidence) to converge. If accepted,
archive the accepted cross-device bundles as one exact golden for the next
iteration series:

```text
python tools/diagnostics/face_iteration_gate.py snapshot \
  <PLK-bundle.zip> <Xiaomi-bundle.zip> <KB-bundle.zip> \
  --output tools/diagnostics/baselines/face_only_next.json
```

Snapshot contracts include exact SHA-256 fingerprints for sticker placements,
class fallback, temporal evidence, and deterministic CPU identity, plus per-device
performance/observability baselines. The snapshot command rejects a milestone if
the accepted bundles disagree on quality or any exact fingerprint, so subsequent
single-device iterations can detect subtle frame-level drift without another
three-device run.

## Current baseline status

`face_only_febd1b4_exact.json` is the current accepted Face-only baseline. It was
created from the PLK110, 2206123SC, and KB2000 milestone bundles after the
scheduled Face detector parallelization passed with pairwise-exact Analyze, Face
ROI, sticker placement, class fallback, temporal evidence, and deterministic CPU
identity output. `face_only_78a1beca_contract.json` is retained only as the
historical pre-parallel summary baseline.
