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

### Single-entry Release QA (2026-10-21)

The recommended host workflow is one command from the repository root:

```powershell
uv run python .\tools\diagnostics\run_crop_clarity_release_qa.py `
  --action all `
  --output-dir "D:\WoahReleaseQA\trial-01" `
  --scenario "same-720p-dance" `
  --acknowledge-test-install
```

The host tool builds two signed Release candidates from the same clean Git HEAD, pins both APK SHA-256 values and verifies their package IDs, versions and matching public signing-certificate fingerprints with SDK `apksigner` and `aapt`. It rechecks these identities before device installation, pins the ADB device serial in memory and (only with explicit installation authorization) sequentially replaces the installed Woah app using `adb install -r`. When the scalar collector says `ARMED`, the operator must complete three matching in-app exports for OFF and three for ON. It then compares the matched numbers and reports `REQUIRES_DEVICE_REVIEW` rather than any privacy PASS.

Use `--action build` to build only, without installing anything. Use `--action run --acknowledge-test-install` to test the exact same pinned APK pair later. The output directory must be a new empty folder outside Git. The command never uninstalls apps, clears app data, reads original video, captures raw logcat or bundles research models. Existing Android Release signing must already be configured: signature incompatibility is a hard install failure, not grounds for uninstalling the app.

Host-only simulated integration tests cover Git HEAD/dirty state, SHA tampering, expired artifacts, installation authorization, build/install failure and scalar comparison. The tag-only collector also aborts if its new marker is not observed within 20 seconds (rather than leaving a supposedly armed capture waiting the full export-series timeout). **Production CI [Run 39555893657](https://github.com/orz-i/woah/actions/runs/39555893657) PASSED** at `a107613f2c3136bf6829384b6de6dd7aa141aabf`, including Flutter, Python/host contracts, Android JVM tests, Stage 3 AndroidTest ABI compile, Debug APK build and the added `:dance_native:compileReleaseKotlin` step. That step validates Release Kotlin compilation only; it is not a signed Release APK build. **Real signed Android Release APK, OFF/ON performance and final protected-video privacy remain NOT MEASURED.**
**2026-10-21 signed APK and ADB device preflight:** The single-entry workflow now uses the locally installed Android SDK Build Tools (`apksigner verify --print-certs` and `aapt dump badging`) to inspect both generated APKs. A successful pair must use the exact `art.gaoge.dance` package ID, matching version code/name and the same publicly readable signing-certificate SHA-256 fingerprint, with neither APK signed using Android Debug credentials. These identities and the full APK-byte SHA-256 are recorded in an external manifest, then **reverified immediately before any device installation**. The workflow also acquires one `adb get-serialno` result and passes `-s` for every later install and logcat capture, checking the same serial again before each variant. A different/disconnected/unrecognized device blocks the second install. The device serial is neither committed nor written into the performance report. This does not attest the signing certificate of any previously installed app, so `adb install -r` may still fail safely when upgrade signing is incompatible.

Android Gradle Release signing remains a prerequisite: the tools inspect output APKs but do not create, read or export private signing keys. Only mock signed-APK metadata and a simulated ADB environment were exercised on this Mac (which lacks Android SDK and signing configuration). **No real Release APK has been verified or installed** by this change, and no Release performance or privacy PASS is implied.
## Executable evidence status

- Pure synthetic/mocked capture runner: marker discrimination, no global logcat clearing, wrong variant and build rejection, stdout streaming, complete 3-record emission and atomic output, duplicate/replay detection, malformed/private data veto, device/build/output mismatch, error cleanup.
- Offline Release comparator: adversarial missing timer count, fake throughput, elapsed/frame mismatches, bad mode/scale, bad thermal/PSS and cross-run source-parameter changes; existing Windows UTF-16 Logcat fixture retained.
- Source contract: compare the exact native Release payload field set with the host allowlist.
- Tests: `python3 -m unittest tools.diagnostics.test_release_apk_preflight tools.diagnostics.test_run_crop_clarity_release_qa tools.diagnostics.test_capture_crop_clarity_release_perf tools.diagnostics.test_crop_clarity_release_perf -v` and the existing all-diagnostics discovery. Host-only fixtures do not substitute for Windows SDK/ADB verification.

**No actual Android Release ON/OFF captures were created in this turn**, so no measured slowdown, thermal, quality or privacy PASS is asserted. The Stage 4 protagonist overmask at 39.733–39.900s is unresolved and the already-accepted 46s stale-mask protections remain unchanged. Full branch merge remains blocked pending the real protected video and Release benchmark, native CI and explicit approval.
