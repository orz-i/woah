# Android cutout A4: isolated Release GPU candidate

Base: `b8408c39` product-only Release preview. No C9.5, model replacement,
tracker change, iOS change, or privacy-pipeline change.

## Method

Physical OnePlus 8T (KB2000), Android 14; pinned LiteRT 2.1.5 and YOLO11n.
Current native instrumentation uses the two C8.3 960x540 25fps 12s clips,
600 frames, anchor 6s, reference touch (.46,.60), target (.422,.54),
existing detail policy and no observation dump. CPU then GPU, one fresh
process per arm. Both effective accelerators match requested. Temperature
26.9 -> 29.9 C for CPU and 29.9 -> 29.7 C for GPU, thermal status 0.
These single runs are not a thermal-matched repeated benchmark.

| Metric | CPU4 | GPU |
|---|---:|---:|
| Initialization (ms) | 214.165 | 2199.847 |
| Inspection (ms) | 1557.705 | 1492.362 |
| Initial preview analysis (ms) | 11185.553 | 8896.487 |
| Full analysis (ms) | 110649.001 | 69975.187 |
| Total including alpha evidence readback (ms) | 112702.219 | 73954.038 |
| Reference visible | 300/300 | 300/300 |
| Target visible | 245/300 | 245/300 |
| Longest target missing (ms) | 1200 | 1200 |
| Independent sparse selected-point hits | 20/24 | 20/24 |
| Marked distractor hits | 0/24 | 0/24 |

Full analysis saves 40.674s (36.76%). The GPU still exceeds the proposed
60s full and 8s preview targets in this native run. Mean paired mask IoU
reference 0.992649, target 0.993245; two paired frames below 0.90; one
CPU-visible frame absent on GPU and one GPU-visible frame absent on CPU.
Manual review of all four outlier overlays found the same intended person
in the visible masks, with limb/outline differences. This is sparse
identity evidence, not dense identity ground truth.

The cumulative full-frame stage timings across both lanes (ms):

| Stage | CPU4 | GPU |
|---|---:|---:|
| Decode + convert | 17066 | 24633 |
| GPU prefetch waiting | 0 | 2940 |
| Model run | 57696 | 2235 |
| Output read including synchronization | 4852 | 22320 |
| Input preprocessing | 940 | 6803 |
| FrameKernel postprocessing | 4819 | 9133 |
| Tracking | 546 | 833 |
| Alpha storage | 334 | 530 |
| Detail decode | 7976 | 9899 |
| Detail inference | 8552 | 5141 |

The decode and inference stages can overlap: do not sum as wall time.
Both target lanes make 71 detail attempts, 21 accepted. GPU uses an
auxiliary decoder rather than borrowed current source; CPU had 39 inline
detail decodes, GPU 0. LiteRT Java TensorBuffer only provides allocating
readFloat(), not a caller-owned reusable float-array overload. The read
timing includes waiting for device work and is not all copying. Next
optimize GPU decode/ROI scheduling and CPU contention with fixed evidence
rather than assuming a zero-copy JNI change will help.

## Actual Release AOT application trial

Enable only in an explicitly isolated local package with suffix
`.cutoutreleaseqa`, normal Flutter entry, Release signing for local
testing only (do not distribute). In addition to the normal preview
opt-in, use `--dart-define=WOAH_CUTOUT_RELEASE_GPU_QA=true`. Standard
production package remains CPU4 even if the QA flag is present. A
separate no-define Gradle run generated both boolean flags false.
The existing backend lock rejects effective accelerator mismatch
rather than mixing observations in one identity session; cache keys
already include accelerator. GPU runner is reused between selections.

- Full pair with the same UI gestures as R2 at 6s: GPU effective in
  logs, compile 2091ms, first playable observed by 16s, still processing
  at 48s, completed by 53s, 300/300 and 251/300. R2 CPU UI observed
  completed by 103s, 300/300 and 253/300. These are manually sampled
  upper bounds on different runs, not the precise native A/B measurements.
- Analysis memory snapshots: CPU R2 PSS 373388 KiB; GPU PSS 308137 KiB,
  completed GPU 228102 KiB. Not a peak or leak test. Missing frames kept
  export disabled on the full range.
- Short 1.2s pair at zero anchor, both 30/30, cached canvas observed
  by 6s after the second drop. MediaStore export pulled and decoded:
  H.264/AAC, 1080x1550, 25 fps, 30 video frames, 1.200s actual duration.
  Export SHA256 `2cd5af2c7ed7713c0bdf75971b6a6ef7344b71b2112e1b5442474668ee29501b`.
  Release APK SHA256 `fe52ee018f381388131325622ca17bcec7eebfb878807ae650952ebeebf0d105`.
- Native instrumentation APK built successfully, both instrumented
  tests passed, real Release APK built and installed successfully,
  diff check clean. Production package remains installed. No temporary
  signing/package-ID override committed.
- Evidence: `/tmp/woah-cutout-a4`, includes both reports/alphas, review
  sheets, screenshots, runtime logs and exported MP4. Initial logcat
  time filter failed due shell quoting; recovered both complete
  per-lane performance logs by recorded process IDs.

Decision: GPU is a promising isolated Release acceleration candidate,
not default-ready. Need repeat cold/warm timing on different clips and
devices, test unsupported GPU/CPU fallback and cancel/retry/cache
lifecycle, preserve identity evidence and measure GPU scheduling.
Do not claim universal 60s/8s or broad Release acceptance.

## A4.2: GPU decode/source-reuse study

Experiment commit `b4f79874`, branch
`research/comparison-cutout-gpu-source-reuse-20261127`, based on
`43d68584`. The product performance branch does not include its
shared decoder refactor or experimental scheduling flags.

All arms used actual GPU, same physical KB2000, pinned YOLO11n, same
two 12s C8.3 sources (600 total source PTS), 6s anchor and touch points
as A4.1. Each instrumented arm ran in a fresh process. Sequence:
hybrid, baseline (same instrumentation build), then snapshot (new
instrumentation build including the shared converter refactor). No
replay or observation dump; same alpha output checks.

| Metric | Previous GPU | Fresh prefetch | Serial forward hybrid | GPU snapshot |
| --- | ---: | ---: | ---: | ---: |
| First preview analysis s | 8.896 | 9.222 | 7.829 | 8.658 |
| Full analysis s | 69.975 | 71.276 | 77.064 | 68.773 |
| Total wall s | 73.954 | 75.228 | 80.850 | 72.788 |
| Reference visible | 300 | 300 | 300 | 300 |
| Target visible | 245 | 245 | 245 | 245 |
| Detail attempts / accepted | 71/21 | 71/21 | 71/21 | 71/21 |
| Direct source crops | 0 | 0 | 39 | 39 |
| Auxiliary decoder opens | 4 | 4 | 2 | 2 |
| Auxiliary seeks | 31 | 31 | 30 | 30 |
| Detail decode s | 9.899 | 9.877 | 8.787 | 8.631 |
| Full-frame decode/convert s | 24.633 | 25.169 | 23.346 | 25.581 |
| Output read/sync s | 22.320 | 22.787 | 23.778 | 22.467 |

Timing phases overlap; do not sum. Thermal status 0 throughout;
battery temperature hybrid 27.7->28.1 C, fresh baseline 28.1->28.3 C,
snapshot 28.3->28.6 C. Each arm's two alpha evidence files matched
the previous GPU run byte for byte, including all 55 missing records
and 545 valid masks. Sparse checks unchanged (20/24 selected-point hits,
0/24 marked distractor hits across lanes; max gap 1.2s).
No claim of dense identity correctness.

Hybrid retains reverse prefetch but serializes forward decoding to
borrow the source Image. Reject: complete analysis 8.12% slower
than fresh baseline despite 39 inline crops and faster first preview.
Snapshot retains parallel decode and uses two exclusive raw YUV
copy slots per lane, capped at 8 MiB each; copies original plane
bytes/strides, crop and color metadata, then uses the existing Rust
converter on the consumer. Oversized slots fall back to the original
auxiliary path. Observed capacity 1113918 bytes per slot, 149 source
snapshots per lane, no fallback; only 39 crops on target and none
on reference. Raw source is not persisted. Two lane work objects
may retain allocations concurrently, so 16 MiB is a per-lane source
slot cap, not a whole-pair peak memory claim. Sampled snapshot
instrumentation PSS 156628 and 160334 KiB, not peak or matched A/B.

The physical decoder parity test passed across 0/90/180/270 degrees
(8 exact snapshot crops, after codec closure, plus existing fresh,
inline, sequential and reverse-seek parity, expired crop callback
rejection and budget fallback). Existing borrowed-image cancellation
check passed; snapshot prefetch cancellation itself is not separately
verified. The actual benchmark APK is instrumentation Debug using
current production algorithms, not a Release Flutter UI run.

Snapshot gains 2.502s / 3.51% vs fresh baseline and only 1.202s /
1.72% vs previous GPU. Small single-run gains across builds do not
justify promotion. Preserve experiment in its own research branch,
no product code migration; do not iterate the same forward policy.
Next higher-value performance study: reverse ROI seek amortization
(30 seeks remain), followed by broader GPU candidate reliability,
fallback and repeatable release measurements. No model/identity
algorithm changes and no claim of native 60s completion.

Evidence: `/tmp/woah-cutout-a4-source` (reports, alpha files, logs,
comparison.json, parity.json and scripts). Local only; no media
or signing artifacts committed.
