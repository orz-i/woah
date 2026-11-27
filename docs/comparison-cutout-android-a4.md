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
