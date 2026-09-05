# Face / Full Body Dual-Line Performance Workflow

This workflow extends the existing Face-only iteration process without merging
the Face and Full Body runtime implementations. The goal is to validate shared
changes on both privacy paths while preserving cheap path-specific iteration.

## 1. Two independent axes

Every candidate is classified on two axes before real-device acceptance.

### Algorithm path

- **Shared**: canonical YUV, LiteRT/YOLO preprocess-run-decode, common
  TrackManager primitives, common resolver/mask primitives, diagnostics,
  renderer/encoder infrastructure.
- **Face-only**: deterministic Face CPU-primary, Face ROI / MediaPipe,
  Face pixel motion, Face temporal/fallback evidence, sticker placement.
- **Full-Body-only**: Full Body fresh-class primary, historical production
  tracking/compositor policy, and Full Body-specific mask composition.

A change is path-specific only when its runtime guard and call graph prove that
the other path cannot execute the changed logic. Changes to shared primitives
default to **Shared** even if they were discovered while profiling one path.

### LiteRT hardware lane

Performance evidence is grouped by the actual runtime lane, not by requested
configuration alone. At minimum the lane includes:

- `yolo_requested_accelerator`
- `yolo_effective_accelerator`
- `yolo_gpu_fallback_reason`
- `yolo_inference_input_path`
- `cpu_mt4_probe_threads`
- `cpu_mt4_signature_scope`
- `cpu_mt4_probe_fallback_reason`

`requested=GPU` is not evidence that GPU acceleration was active. Acceptance
uses `effective_accelerator` and fallback telemetry from the completed export.

## 2. Normal same-device loop

The ordinary optimization loop remains one target device rather than returning
to KB x2 + PLK + Xiaomi for every candidate.

### Shared candidate

Use the same build and the same KB device:

1. Run the standard Face-only fixture once.
2. Run the standard Full Body fixture once.
3. Gate Face against its own exact + accumulation baseline.
4. Gate Full Body against its own device/runtime-lane exact + accumulation
   baseline.
5. Accept only when both paths preserve their required exact invariants and the
   target stage reaches the frozen promotion threshold.

### Face-only candidate

Run only the Face fixture when isolation is proven. A shared TrackManager,
decoder, YOLO, resolver, renderer, or scheduling change is not eligible for this
exemption.

### Full-Body-only candidate

Run only the Full Body fixture under the same isolation rule.

## 3. Full Body quality contract

Full Body production inference may legitimately execute on device GPU hardware,
so different devices are not required to produce byte-identical GPU output.
Instead, each device keeps its own exact golden for its effective hardware lane.

The Full Body gate fingerprints:

- production TrackManager geometry/state topology for every frame;
- production identity topology for every frame;
- inputs sent to the Full Body fresh-class resolver;
- the available production LiteRT detection/mask diagnostic probe;
- full-export CPU4T detection signatures;
- full-export CPU4T reference tracking.

The CPU4T lane remains the deterministic reference anchor. Raw CPU4T detector
geometry/mask signatures may retain small device-level numeric differences, but
the CPU4T TrackManager identity topology must remain frame-exact across milestone
devices. A Full Body golden snapshot is rejected if that identity invariant
diverges. Production GPU results are evaluated against the same device's accepted
GPU golden.

The debug-only events used for this contract are:

- `FULL_BODY_PRODUCTION_TRACK_SIGNATURE`
- `FULL_BODY_PRIVACY_INPUT_SIGNATURE`

They must never change TrackManager, resolver, privacy selection, or rendering
state.

## 4. Runtime compatibility gate

Both Face and Full Body gates default to:

`--runtime-compatibility same`

When the baseline and candidate differ in effective accelerator, fallback state,
input path, or CPU4T probe configuration, the normal performance comparison is
invalidated rather than silently comparing unlike workloads.

Hardware-acceleration experiments must explicitly use:

`--runtime-compatibility report-only`

If the runtime lane changes, the gate reports the timing difference but does not
accept the candidate into normal accumulation from that timing comparison alone.

## 5. Hardware-acceleration experiments

GPU / delegate / GL / LiteRT-GPU candidates are cross-device-sensitive by
definition. They should be tested on genuine hardware with verified effective
GPU execution; emulator CPU microbench results are not substitutes.

Current device roles remain:

- `emulator-5560`: CPU-only microbench and deterministic local profiling.
- KB device: ordinary same-device Face / Full Body accumulation canary.
- BK (`334da6a3`): genuine GPU / GL / LiteRT-GPU candidate validation when a
  hardware-acceleration candidate exists.

Do not compare a GPU result to a CPU-fallback baseline as if it were an ordinary
algorithm improvement. First establish the hardware lane, then compare repeated
results within that lane.

## 6. Milestone validation

Three-device validation remains a promotion/milestone gate, not a default inner
loop. At a milestone, run Face and Full Body on the selected hardware set and
check each device against its own accepted device/runtime-lane golden.

A hardware backend or precision change may trigger an earlier milestone because
its correctness/performance properties are device-dependent even when the source
code change is shared.

## 7. Promotion rule

For a shared candidate, acceptance requires all of the following:

1. Face quality contract passes.
2. Full Body device-specific quality contract passes.
3. Runtime lane is unchanged for an ordinary algorithm comparison.
4. Structural work invariants pass for both paths.
5. The intended stage gain passes the frozen threshold.

If only one path benefits but the other remains exact and does not regress, the
candidate may still be accepted as a shared optimization. A quality drift on
either path rejects the shared candidate.
