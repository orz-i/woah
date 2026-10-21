# Woah: Release evidence collection and comparator hardening (2026-10-20)

Branch: `feat/crop-clarity-phase2-quality-performance`. Starting local HEAD: `6a5a993cdca6fb7959c27dec0dbcd41fc8ad805c` (Stage 4 source-only checkpoint).

## Gate status

**Host-side Release collection and strict OFF/ON numeric comparison: IMPLEMENTED / SYNTHETICALLY TESTED. Real Android Release performance, final exported-video privacy and master merge: NOT ACCEPTED / BLOCKED.**

The existing Android export already writes one `WoahExportPerf` JSON logcat event after an atomically completed export. The existing Dart build-time-only `WOAH_BENCHMARK_CROP_CLARITY_OFF=true` flag disables only the additional crop-clarity restoration for an OFF benchmark build. Normal ON Release exports and all privacy masking, Kalman tracking, YOLO inference, GL compositor and export geometry are unchanged. No app, Android/iOS native source, UI, model file or test APK was modified.

The previous benchmark procedure required `adb logcat -c` (globally clearing log buffers), redirected all matching events after three exports, and trusted performance records lacking some internal consistency checks. This was fragile and manually expensive.

## Implemented host-only tools

### 1. `tools/diagnostics/capture_crop_clarity_release_perf.py`

When run *before* starting the first export, it:

- attaches a **tag-filtered** live logcat stream to `WoahExportPerf` and `WoahPerfMarker`, then injects a random 24-hex marker via standard Android shell `log`;
- ignores any record seen before that marker without touching or clearing device log buffers;
- watches for three *new, independently completed* normal Release exports under one installed mode; shows count 1/3, 2/3, 3/3 in the terminal;
- verifies known scalar keys against the **exact native `ExportPipeline.kt` performance-event allowlist** (host regression test), rather than persisting unreviewed fields or private paths;
- rejects Debug/shadow/A-B builds, YOLO non-GPU or fallback, missing/invalid render CPU dispatch samples, frame-count/throughput contradictions, wrong clarity mode, duplicate identical payload, changed output/trim/source dimensions/FPS, selection **count**, commit/device and other numeric settings;
- emits only `I/WoahExportPerf: {scalar-fields-only}` lines into a fresh UTF-8 local file **outside the Git worktree** that the existing comparator already understands;
- does not install/modify APKs, open videos, upload images, collect raw logcat dumps, clear logcat, read model weights, modify app private data, or claim privacy/quality acceptance.

**Boundary:** the user must still install the appropriate Release test build and complete three real exports for each mode; a host listener cannot operate the Woah editor or verify source-media SHA by itself. The stream marker uses standard Android shell `log`, but the *actual* Windows ADB/Release phone capture has **NOT** yet been run for this patch. If the marker utility is unavailable, the tool exits fail-closed rather than taking an unsafe manual fallback. A default 90-minute bounded timeout is supported; no background work or scheduled upload is involved.

### 2. `tools/diagnostics/crop_clarity_release_perf.py`

An existing offline comparator. Strengthened to reject:

- missing/unknown Git commits, unexpected build mode, weak/null/malformed positive dimensions, invalid trim windows, invalid/falsely declared ON/OFF restoration scales;
- a supposed GPU benchmark using CPU YOLO or a fallback path;
- differing decoded/rendered/encoded frame counts, `render_cpu_dispatch_count` not matching the actually rendered count (native `renderEffects` wraps each rendered frame);
- non-finite/negative CPU dispatch timer values or P95 below P50;
- missing/contradictory `throughput_fps` versus `rendered_frames / elapsed_ms`;
- malformed device/thermal/PSS sampling and samples with incompatible source/target dimensions, FPS, trim, output bitrate, profile, follow, privacy target **count**, or SHA commit.

Comparison still reports a **provisional budget** (default 10%, not user-approved product SLA), median time and measured end-to-end throughput only. GPU execution duration is **not measured by CPU dispatch timings**. End PSS is not peak nor model-only memory. End thermal samples do not establish long-term thermal safety. A pixel-level protected-video review and matching *actual person IDs* are independent acceptance gates.

## Windows workflow — only when ready to perform Release test

Follow `docs/crop-clarity-phase2.md` section B for building two signed Release APKs from the **same exact Git commit**. Do not distribute the OFF benchmark build.

From repo root (two separate build installations; **do not start an export until the collector reports ARMED**):

```powershell
# OFF: install the benchmark-only clarity-disabled Release build.
adb install -r .\mobile\app\app-release-clarity-off.apk
uv run python .\tools\diagnostics\capture_crop_clarity_release_perf.py `
  --mode off --output "D:\WoahReleaseQA\release_off.log" --repeats 3

# Run three otherwise identical exports in the Woah interface.
# After collector exits, install the normal clarity-enabled Release APK.
adb install -r .\mobile\app\app-release-clarity-on.apk
uv run python .\tools\diagnostics\capture_crop_clarity_release_perf.py `
  --mode on --output "D:\WoahReleaseQA\release_on.log" --repeats 3

# Complete three matching normal Release exports, then compare.
uv run python .\tools\diagnostics\crop_clarity_release_perf.py `
  --off "D:\WoahReleaseQA\release_off.log" `
  --on "D:\WoahReleaseQA\release_on.log" `
  --scenario "same-720p-dance" `
  --output "D:\WoahReleaseQA\crop_clarity_perf_720p.json" `
  --min-runs 3 --max-slowdown-percent 10
```

Incompatible signing between APKs must be resolved on the build machine. **Do not uninstall user applications, clear app data or wipe the device.** Choose a fresh output name for any restarted trial; files are not overwritten.

Note: the report's `scenario` is a human assertion that the input clip/selection and video settings are identical across builds. No media hash, pixel identity, mask bytes or decoded sample images are added to the scalar telemetry. If the saved app state or selected identities change between installations, the paired benchmark is no longer fully comparable even when the numeric metadata matches.

## Executable evidence status

- Pure synthetic/mocked capture runner: marker discrimination, no global logcat clearing, wrong variant and build rejection, stdout streaming, complete 3-record emission and atomic output, duplicate/replay detection, malformed/private data veto, device/build/output mismatch, error cleanup.
- Offline Release comparator: adversarial missing timer count, fake throughput, elapsed/frame mismatches, bad mode/scale, bad thermal/PSS and cross-run source-parameter changes; existing Windows UTF-16 Logcat fixture retained.
- Source contract: compare the exact native Release payload field set with the host allowlist.
- Tests: `python3 -m unittest tools.diagnostics.test_capture_crop_clarity_release_perf tools.diagnostics.test_crop_clarity_release_perf -v` and the existing all-diagnostics discovery.

**No actual Android Release ON/OFF captures were created in this turn**, so no measured slowdown, thermal, quality or privacy PASS is asserted. The Stage 4 protagonist overmask at 39.733–39.900s is unresolved and the already-accepted 46s stale-mask protections remain unchanged. Full branch merge remains blocked pending the real protected video and Release benchmark, native CI and explicit approval.
