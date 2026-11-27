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

## A4.3: bounded reverse ROI source blocks

Code commit `5f234707` on
`research/comparison-cutout-gpu-reverse-roi-20261127`, based on
`b4f79874` only for the already-tested exact YUV source-copy primitive.
No forward snapshot or forward serial option enabled in these trials.
No experimental shared-decoder or tracker changes migrated to the
product branch. The reverse-block flag defaults to false.

Cleaned reproducible Flutter, Android and Rust build/intermediate
directories in both worktrees and the scratch Cargo targets. 68
directories, 7.811 GiB allocated size, available space 14.58 -> 21.28
GiB (6.70 GiB actual free-space increase). Retained all branch refs,
worktrees, canonical model assets, Pods, global dependency caches,
research dynamic libraries, Python environments, and raw evidence.
Cleanup inventory and before/after df in `/tmp/woah-a4-reverse`.

### Implementation

The reverse tracker still consumes its original descending exact PTS
and uses the same full observations, selection, ROI geometry, detail
budget and inference. On a reverse crop request, decode up to four
ascending source samples ending at that request and copy only the
original decoder YUV planes/strides and color/crop metadata. Do not
resize full RGBA in this auxiliary path. Crop on demand from the owned
block with the unchanged Rust converter. Drop expired slots, close
at span end, and fall back to the ordinary exact-source decoder on
oversize or a recoverable decode error. No extra inference and no
raw source pixels persisted. Four slots of at most 6 MiB each bound
source payload per active reverse block to 24 MiB (not whole-process
peak). Actual maximum allocated slot capacity sum was 4,455,672 bytes
in both clips. Direct buffers are managed by the JVM; memory sampling
is not a leak or peak test. The dedicated candidate remains isolated.

### Same-APK physical Android A/B

OnePlus 8T, Android 14, effective GPU, current YOLO11n/LiteRT and
unmodified identity thresholds. APK SHA-256
`54cab525d3ad5f14c68faef11dd5bf583498b37d127083e1f1fa93516fdcf9b6`.
Fresh process per arm, no observation dump/replay. C8.3 6s anchor
(.46,.60)/(.422,.54), candidate then baseline. C5 independent source
pair, 11s anchor (.55,.60)/(.37,.52) from existing source annotations,
baseline then candidate. Both sets are two 12s 25fps sources with
600 source records; C5 uses different source recordings, not newly
acquired footage. Fixed four-frame block, no parameter search.

| Metric | C8.3 original | C8.3 reverse | C5 original | C5 reverse |
| --- | ---: | ---: | ---: | ---: |
| Initial preview analysis s | 9.186 | 9.032 | 8.833 | 8.467 |
| Full analysis s | 70.464 | 65.779 | 86.977 | 74.634 |
| Total wall s | 74.487 | 69.796 | 90.245 | 78.053 |
| ROI decode s | 10.016 | 6.374 | 20.403 | 8.692 |
| Total auxiliary seeks | 31 | 12 | 57 | 19 |
| Total auxiliary opens | 4 | 4 | 4 | 4 |
| Detail attempts/accepted | 71/21 | 71/21 | 63/35 | 63/35 |
| Reference visible | 300 | 300 | 267 | 267 |
| Target visible | 245 | 245 | 300 | 300 |
| Longest missing s | 1.20 | 1.20 | 0.96 | 0.96 |
| Reverse block loads | 0 | 13 | 0 | 21 |
| Reverse block hits | 0 | 19 | 0 | 38 |
| Source samples copied | 0 | 48 | 0 | 84 |
| Reverse fallback | 0 | 0 | 0 | 0 |

Total seeks include the ordinary reader and the block reader; 1+11
for C8.3 and 0+19 for C5. All four per-role output alpha files are
byte-identical to the matching baseline (1200 records, 1112 visible,
88 missing), not merely equivalent counts. Sparse selected-point
checks unchanged: C8.3 20/24, C5 22/24, zero marked distractor hits.
This does not prove dense identity correctness. Full analysis saves
4.685s (6.65%) and 12.343s (14.19%); ROI decode saves 3.642s and
11.711s. Temperature baseline/candidate order reversed between pairs:
C8.3 27.5->28.0 C candidate, 28.0->28.3 C baseline;
C5 28.2->28.4 C baseline, 28.4->28.6 C candidate. Thermal status 0
throughout. Single paired run per source, not repeat statistics.

Physical decoder parity passed: eight exact source-only capture
crops across four rotations, after decoder closure; oversized slot
fallback, expired crop rejection, fresh/inline/reverse-seek parity
and existing borrowed-image cancellation check. Four real-dance
instrumentation runs complete. Both native analysis targets still
above 60s, initial preview above 8s. No actual new Release
application build, block-active cancellation stress, high-resolution
fallback in full pipeline, second device, or production GPU
fallback validation performed.

Disposition: reverse block is a higher-value candidate than the
forward source snapshots; retain exact frozen implementation for
selective isolated Release GPU integration next. Do not merge the
whole experimental ancestor branch or enable it for CPU/privacy
paths. No reason to tune block size now. Before promotion, verify
actual Release cancel/retry/save/restore/export, high-res budget
fallback and broader device performance. Production GPU policy and
default preview opening remain unchanged.

Evidence: `/tmp/woah-a4-reverse` (cleanup inventory, reports, alpha
files, build/run logs, parity.json, comparison.json and
c5-comparison.json, scripts). No footage or signing keys committed.

## A4.4: selective Release GPU integration and lifecycle validation

Commit `23375cdc` selectively ports the reverse-block implementation
from `5f234707`, including only the required original YUV-plane copy
primitive and source-only decoder capture. Neither forward experiment
is migrated. Reverse blocks are enabled by the existing explicit isolated
Release GPU opt-in, never by the ordinary production CPU4 policy.
Forward prefetch, model, tracking, ROI schedule, missing-mask semantics,
archive provenance and iOS behavior stay unchanged. The shared Android
YUV conversion calls the same Rust converter with the same metadata;
no separate privacy algorithm changes were made.

Each reverse block contains at most four source slots, each capped at
6 MiB. A slot allocates once; unexpectedly growing plane storage falls
back rather than allocating a larger buffer. Invalidate consumed slots,
drop buffer references at span exit or cancellation, and retain the
existing exact-source fallback. These are bounds on live owned source
buffers, not an OS peak-memory or GC reclamation guarantee.

### Physical Android native tests

- Four rotations and exact crop parity passed after the selective port.
- A real decoder input encoded at 3840x2160, 25fps, 1.2 seconds
  (upscaled existing C8.3 fixture, not independent quality footage)
  exceeded the 6 MiB slot cap without allocating a source slot; the
  fallback crop was byte-exact. This was a decoder-level test, not
  full 4K dual-video end-to-end analysis.
- Deterministically cancelled the actual C5 reverse analysis immediately
  after its first source capture. Cancellation propagated, work closed,
  and anonymous cutout scratch file descriptors remained 0 before and
  after the test.
- Reinitialized the GPU in a new runner and retried the C5 lane with a
  test-only one-byte slot budget. All 59 requested reverse crops fell
  back, 63 total detail attempts/35 accepted, zero detail failures,
  267/300 visible. The 300-record alpha file matches the original
  C5 baseline byte-for-byte. This tests source-buffer fallback, not
  GPU compilation failure or backend switching.
- Three device instrumentation cases and all 45 Flutter comparison
  tests passed. No new privacy-path end-to-end run was performed.

### Actual Android Release product smoke

Locally signed, isolated `art.gaoge.dance.cutoutreleaseqa` AOT APK,
64.3 MB, no debuggable flag; normal product UI, no instrumentation entry.
APK SHA256 `5d7d5f3f2ec839c113724975cfceb22bc0aba9a30bae53c9028625ee92503b17`.
Temporary package-ID override removed after build. Original application
remains installed. This is not a distributable signed release.

C8.3 12s pair, 6s playhead, real gestures as A4.1. Effective GPU
confirmed; model compilation 2201ms. First playable observed by 8s,
analysis still running at 40s, completed by 50s after the second
drop (UI sampling upper bounds). Reference 300/300, target 251/300,
matching prior GPU Release visibility. Target log: 13 reverse blocks,
19 hits, 48 source frames, zero fallbacks, 4455672 allocated slot bytes,
11 reverse-block seeks plus one ordinary auxiliary seek. 68 detail
attempts/23 accepted, no failures. ROI decode 5712ms. Missing-frame
export remained disabled. Analysis PSS sampled at 324904 KiB, not peak.
Previous observed GPU Release completion by 53s and CPU Release by
103s are separate manual observations, not precise paired measurements.

Re-entered long-clip analysis, then cancelled and returned to the
normal comparison UI. Switched to the existing suitable 1.2s pair
and freshly selected both subjects; saved complete canvas observed by
6s after second drop, both 30/30. Export saved via MediaStore and pulled
from device; H.264/AAC, 1080x1550, 25fps, 30 video frames, actual
1.200s container duration, full FFmpeg decode passed. Export SHA256
`fa57b7e31adf0c754a71f7b01115f143a0440f8dd1f384df56468dfb3bca7aa7`.
Force-stopped the app, reopened the project, and confirmed restored
canvas 30/30 and 30/30. Release logs prove reverse-block activation on
the long clip; short export smoke at zero anchor is a product lifecycle
check, not proof of export from a complete reverse-block-heavy scene.

Evidence: `/tmp/woah-a4-integration` (APK hashes, native lifecycle
and crop reports, byte-exact fallback alpha, screenshots, actual MP4,
runtime logs and Flutter test logs). No raw footage, buffers or signing
secrets committed.

Decision: isolated GPU Release reverse-ROI integration and tested
lifecycle pass. Do not promote GPU globally: unsupported GPU/init
failure currently remains fail-closed in this isolated trial, no
atomic CPU4 restart implemented/verified, only one physical device,
and no fresh full native timing repeat after the port. A4.3 native
65.779/74.634s remains above the proposed 60s target. Next focus on
safe backend selection/fallback across an entire identity session
and bounded final acceptance, not additional forward-copy experiments.

## A4.5: effective backend locking and safe session restart

Implementation `84f1ae37`. The isolated Release GPU candidate alone
can adopt an effective CPU4 fallback before any selected observation
is published. The selected pair and each inspection carry their actual
backend. Once any selection or committed canvas has a backend, a
mismatch cannot enter the tracker. A GPU model error marks CPU as the
preferred backend for subsequent whole-pair selection in that native
session; the failed uncommitted work/partial preview is discarded.
Require the user to confirm both people again rather than silently
reuse GPU-derived selections as CPU identities. A failed GPU repair
keeps its original canvas, not a mixed-backend patch. Cancellation
and non-model errors do not select CPU. An initializer that fails
before assigning ownership now closes its local model runner.

Completed canvas archives use the actual backend. Restore matches an
exact complete permitted archive key in a single pass (CPU and GPU
in the isolated candidate); it never relabels a CPU archive as GPU
or probes one backend destructively before trying the other.
The original production package remains CPU4, preview opt-in
unchanged. No new model or tracker tuning.

### Tests and Android Release evidence

16 focused Kotlin unit tests and 47 Flutter comparison tests passed.
New cases cover backend resolution and model vs cancellation/decode
errors, exact matching of CPU and GPU archive keys, failure after
partial preview, and inspection restart feedback. This is not full
cross-platform or privacy end-to-end regression.

OnePlus 8T, isolated local-signed arm64 Release AOT builds.
Normal APK from the committed code plus local application-ID override:
`7ca18d9ee2d20dc33ff3a157781376bfbffa68a2aeb33ecf1f206a2046df7162`.
Fault-injection APK:
`e36c3b2ae595e2b02e0e67d7295b0f6e8ab26f8e9bf7dbce93b0b598a8a9d52a`.
Both use the same source commit provenance. The fault patch was local
only and is not part of the branch or normal APK. These APKs are not
distributable production packages.

- Normal GPU short pair (1.2 s) selected via product gestures:
  30/30 + 30/30, saved, effective GPU and canvas_committed=GPU.
- Injected an exception at the GPU compile try boundary on first
  short-clip inspection. Actual LiteRT logged requested=GPU,
  effective=CPU, cpu_threads=4, initial_fallback=CPU4; the whole
  short pair saved 30/30 + 30/30, canvas_committed=CPU.
- Reinstalled normal GPU candidate, force-stopped/reopened:
  canvas_restored=CPU, both 30/30. Export to MediaStore succeeded.
  Pulled file SHA256
  `709bd073c378a5ec5a7513aa914c0d7704f8b3c02862bfe5e7fd59596d3fd0c1`.
  H.264/AAC, 1080x1550, 25fps, 30 video frames, 1.200s, full decode passed.
- Injected model inference exception after actual GPU partial preview
  was published in the 12s C8.3 pair at 6s. Preview screenshot showed
  52/600 analyzed; app returned to ordinary comparison and prompted
  complete reselection. The live log showed
  QA_INJECTED_MODEL_FAILURE_AFTER_GPU_PREVIEW and restart_required=CPU4.
  Re-selected both subjects in the same native session; actual
  requested/effective CPU, 4 threads, no further GPU initialization.
  Completed reference 300/300, target 247/300, canvas_committed=CPU;
  export remained disabled. Observed complete by 107s after second
  drop, still in progress at 94s. This is not a paired performance
  benchmark. Final logcat snapshot lost the older failure lines to
  ring-buffer turnover; the live tool transcript and screenshots
  preserve those observations, and the final log retains CPU commit.
- Reinstalled the normal APK after the fault tests; original
  art.gaoge.dance app remains installed. All local source overrides
  removed, normal source tree clean.

Evidence `/tmp/woah-a45-backend`: APK hashes, local fault patch,
test and runtime logs, screenshots 01-21, actual exported MP4 and
ffprobe JSON.

Limitations: real hardware is GPU-capable; tested controlled compile
and post-preview inference exceptions rather than a naturally
unsupported device or driver hang. GPU repair failure preservation
has code/targeted test evidence, not a dedicated device fault run.
No fresh iOS build or device privacy pipeline test. No automatic
mid-analysis replay without user confirmation. Default GPU
enablement remains unapproved; only the existing opt-in Release
candidate is validated here. A4 optimization and fallback candidate
are ready for bounded final review, not unrestricted deployment.

## A4.6: final scope and paired regression review

Base , candidate . No new algorithm or optimization
in this stage. Fifteen files changed versus the product base before
this review, no Rust/model/lockfile/iOS-native or privacy strategy
changes. Shared YUV conversion and model initialization cleanup were
explicitly covered. No forward experiment or fault injection migrated.
The no-define Android Release config generates both opt-ins false.

- Entire Android native suite: 641 tests, 0 failures/errors/skips;
  Release Kotlin compilation and diagnostic compatibility check pass.
- Entire Flutter app suite: 311 tests pass.
- Actual Android ordinary comparison: five output configurations
  (A/B, scaled canvas, full 23s, inverse, silent source), cancellation,
  all H.264/AAC outputs pulled and fully decoded; YOLO initialization
  and buffer inference smoke passes.
- Physical Android privacy: 20 frame processor tests pass, two of three
  real FACE_ONLY/mixed privacy exports pass. Dynamic affine dance
  sticker width regression fails: ratio 2.0251046025104604 > 1.35.
  Rebuilt the pre-A4 base and ran the same test separately, then
  separately reran the candidate. All three failures have identical
  sampled widths [470,433,434,484,244,239,475] and assertion text.
  This establishes reproduction on the base, not universal privacy
  equivalence. Independent existing-defect issue: https://github.com/orz-i/woah/issues/43.
  No privacy threshold relaxed, no repair folded into A4.
- Current arm64 iOS Release AOT no-codesign build passes (46.2 MB);
  preview marker true, canonical model SHA-256 matches. Existing
  locked Pods reused. CocoaPods-generated Xcode phase edit reverted.
  No physical iPhone runtime evidence.
- Evidence: , including both paired APK hashes,
  regression and assertion logs, ordinary media/probes, iOS build log.

Disposition: close incremental A4 performance research at its present
isolated GPU candidate. Product preview and performance reviewed as
stacked PRs; no global GPU default, no claim of privacy or unrestricted
Release acceptance. Known existing privacy issue remains separately
open. No production signing, no merge, no store submission. Physical
GPU-unsupported hardware, driver-hang recovery, dedicated GPU repair
fault injection, and device diversity remain unverified.
