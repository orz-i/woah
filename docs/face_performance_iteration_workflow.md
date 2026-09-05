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

Pick the device that exposes the target bottleneck most strongly. For the current
MediaPipe Face detector stage this is **KB2000**. Do one normal fixture export and
run:

```text
python tools/diagnostics/face_iteration_gate.py check <KB-bundle.zip> \
  --contract tools/diagnostics/baselines/face_only_78a1beca_contract.json \
  --target-stage face_privacy --min-improvement-pct 5
```

The gate has two independent requirements:

1. the accepted privacy/identity quality contract must remain unchanged;
2. the target stage must improve enough to justify another hardware cycle.

`NO_GO_TRI_DEVICE_QUALITY_DRIFT` means the optimization changed visible/privacy
behavior and must be diagnosed on the canary before any other phone is used.
`NO_GO_TRI_DEVICE_INSUFFICIENT_GAIN` means the change is too small/noisy to promote
yet; keep iterating locally and on the same canary. Only
`READY_FOR_MILESTONE_TRI_DEVICE` is eligible for the full matrix.

## 3. Batch gains before the three-device milestone

Behavior-neutral optimizations may accumulate behind the canary gate. Do not run
three phones after each 2–5% micro-gain. Promote when one of these is true:

- cumulative target-stage improvement is materially useful (normally >=10%);
- the stage is considered finished and the candidate is ready to become the new
  baseline;
- the change touches a cross-device-sensitive boundary such as decoder pixel
  semantics, model/precision/backend, deterministic identity authority, or a
  platform-specific implementation.

Changes that intentionally alter detector cadence, fallback behavior, or rendered
geometry are **quality changes**, even if motivated by performance. They require a
visual/privacy acceptance decision before becoming the new golden contract.

## 4. Three-device promotion gate

At a milestone, run PLK110 + 2206123SC + KB2000 once. Require the established
cross-device checks (Analyze selection, CPU identity, Face ROI where applicable,
sticker placement, class fallback, temporal evidence) to converge. If accepted,
archive one accepted bundle as the exact golden for the next iteration series:

```text
python tools/diagnostics/face_iteration_gate.py snapshot <accepted-bundle.zip> \
  --output tools/diagnostics/baselines/face_only_next.json
```

Snapshot contracts include exact SHA-256 fingerprints for sticker placements,
class fallback, temporal evidence, and deterministic CPU identity, so subsequent
single-device iterations can detect subtle frame-level drift without another
three-device run.

## Current baseline status

`face_only_78a1beca_contract.json` represents the last accepted behavior before
the detector-cadence experiment. It is a summary contract because the old bundle
was no longer present when this workflow was introduced. The next accepted
milestone should replace it with a `snapshot` contract containing exact frame-map
fingerprints.
