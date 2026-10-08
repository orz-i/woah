# Crop clarity phase 2 — scene-driven QA and Release performance

**Implementation status:** tooling available on branch `feat/crop-clarity-phase2-quality-performance`. Native Android build, device image review, and Release performance measurements must still be performed on representative devices; no performance or visual-quality PASS is claimed by implementation alone.

## Boundaries

- Automatic crop restoration policy and shader `strength = (scale - 1) * 0.55` are **unchanged**. No Neural SR, UI, real-time user controls, or model training.
- Phase 2 scene capture is **one-shot Android Debug only**, and only if a privacy target was selected and a follow-crop requires restoration. No unstaged media ever enters the normal diagnostics ZIP.
- Up to 12 pairs of *already privacy-composited* exported frames are stored in an isolated, app-private ZIP. The normal encode surface is restored to the unchanged enhanced output. Captures are expensive and not suitable for performance measurement.
- The scene selector uses small luminance aggregates from the already-read 640x640 model input and bbox/movement metadata from the existing tracker. Raw RGB is not saved or uploaded. These are **heuristics**, not ground-truth detectors or a promise every video has all scene classes.

## A. Scene-driven A/B and temporal gate

A scene is considered reviewable only when its sampled luma suggests actual image content (not an almost-black title/outro); stale luma over 16 frames is rejected. Before storing a chosen pair, the renderer also checks a sparse sample of the **already-protected baseline output** and vetoes nearly black frames. Such vetoed selections are reported as missing captures, not silently marked complete. The online selector prioritizes:

1. overlap between an observed protected track and an observed unprotected track (`bbox IoU >= 0.08`);
2. fast tracked protagonist motion (`>= 0.012` of source dimensions per frame), starting a **5-frame consecutive burst**;
3. strong brightness contrast on the existing 640px inference image (`p90 - p10 >= 96`);
4. a content anchor and, when rapid motion never occurs, a late-enough fallback temporal burst.

Events have a minimum 12-frame spacing except within a burst. The source of the content metric and each sample's scores are preserved as a *metadata summary* alongside the A/B images, never as an unprotected image. Max 12 pairs. Some categories may be absent and this is surfaced in the report; the selector must not fabricate overlap or motion evidence.

**Important:** the selector operates online without buffering full video. Its decisions are best-effort, not a retrospective global optimum. A 5-frame contiguous sequence gives a limited time-domain screening window, not proof of zero flicker across the full film.

### Device workflow

Build/install a **Debug APK** from this branch. For an eligible portrait-follow export with selected privacy targets:

```powershell
adb shell run-as art.gaoge.dance touch cache/crop_clarity_ab.enable
```

Export as normal. The regular diagnostic bundle should include `CROP_CLARITY_RENDER_ACTIVE` (`uniform_verified=true`) and, if armed, `CROP_CLARITY_SCENE_SELECTED` / `CROP_CLARITY_AB_PAIR_CAPTURED` / `CROP_CLARITY_AB_READY`. The last event has the dynamic list `expected_frames`, missing captures, `scene_counts` and number of temporal samples.

Find the *separate* private ZIP:

```powershell
adb shell run-as art.gaoge.dance ls -lh cache/crop_clarity_ab/
```

On **Windows PowerShell**, do **not** redirect `adb exec-out ... > image.zip` (PowerShell 5 can silently transcode binary data). Use this Python code as a `-c` one-liner instead, substituting the listed private ZIP filename:

```powershell
uv run python -c "import subprocess,pathlib,zipfile; p=pathlib.Path('crop_clarity_ab_phase2.zip'); r=subprocess.run(['adb','exec-out','run-as','art.gaoge.dance','cat','cache/crop_clarity_ab/<actual-private-file>.zip'],capture_output=True); r.check_returncode(); p.write_bytes(r.stdout); z=zipfile.ZipFile(p); print('bytes:',p.stat().st_size,'ZIP CRC:',z.testzip(),'images:',len(z.namelist())-1); z.close()"
```

A healthy file starts with `50 4b 03 04` and ZIP CRC prints `None`. Do not upload raw video or original mask intermediates. The protected pairs may still show unmasked people/background, so handle as sensitive media and delete after review.

Generate contact sheet and metrics locally from repository root:

```powershell
uv run python tools/diagnostics/crop_clarity_ab_report.py crop_clarity_ab_phase2.zip --output-dir crop_clarity_phase2_review
```

For schema 2, the report includes `scene_counts`, per-pair contrast/overlap/motion evidence, `temporal_runs`, and adjacent-frame residual changes on near-static pixels. **Near-static pixel filtering is not camera-motion compensation**, so it is only a flicker-screening hint. Human review should check identity masking, halo/overshoot, hair, clothing, LED backgrounds, rapid turns, and adjacent frames. An empty category or fewer than five consecutive frames leaves that sub-gate **unverified**.

The previous schema 1 A/B ZIP is still accepted for historical comparisons.

## B. Android Release OFF/ON paired benchmark

**Do not time the Debug APK.** Release builds omit CPU shadow probes, full-export YUV diagnostics, and the A/B capture path. The app's normal release behavior is unchanged. A **build-time-only** switch sets the *native export's* crop clarity scale to 1.0 for the OFF variant, without changing portrait size, follow target, masks, effect selection or project output preset. Do not distribute the OFF build as production.

On the Android build workstation, from `mobile/app`:

```powershell
flutter pub get
flutter build apk --release --dart-define=WOAH_BENCHMARK_CROP_CLARITY_OFF=true
Copy-Item build/app/outputs/flutter-apk/app-release.apk app-release-clarity-off.apk
flutter build apk --release
Copy-Item build/app/outputs/flutter-apk/app-release.apk app-release-clarity-on.apk
```

Use **exactly the same commit, phone, source file, privacy choices, trim, bitrate, profile and output target**. Disable the export live-preview toggle and background tasks, allow temperature to settle, and run at least three completed exports per mode after warmup. Alternating runs/interleaving installations is ideal to reduce battery/thermal drift; keep a note of starting battery/thermal state. Benchmark 720p→2x, 1080p→~1.78x, and a 4K control where both are 1.0x.

For each build, clear logcat before its series of exports and collect after three completed runs, while the same build is still installed:

```powershell
adb install -r .\app-release-clarity-off.apk
adb logcat -c
# In the app, perform 3 matching complete exports. Do not enable Debug QA captures.
adb logcat -d -v brief -s 'WoahExportPerf:I' '*:S' | Out-File -Encoding utf8 release_off.log

adb install -r .\app-release-clarity-on.apk
adb logcat -c
# In the app, perform 3 matching complete exports.
adb logcat -d -v brief -s 'WoahExportPerf:I' '*:S' | Out-File -Encoding utf8 release_on.log
```

`WoahExportPerf` emits **one small JSON record per successful export** including commit, device model, geometry, count parity, native scale, total processing wall time, throughput, render CPU dispatch P50/P95, end-of-run thermal status and end sampled PSS. It never logs private media paths or frame images.

At repository root:

```powershell
uv run python tools/diagnostics/crop_clarity_release_perf.py --off mobile/app/release_off.log --on mobile/app/release_on.log --scenario same-720p-dance --output mobile/app/crop_clarity_perf_720p.json --min-runs 3 --max-slowdown-percent 10
```

The default 10% slowdown is a **provisional test budget**, not an already achieved result or a product guarantee. To review a 4K no-op control use `--control-noop`; both runs must show native scale 1.0.

The parser rejects Debug builds, YOLO fallback, incomplete exports, unknown or divergent Git commits, different devices/output geometry/FPS/privacy-target counts, bad scale modes and significant frame-count differences. Different `--scenario` labels are a **manual assertion** about source identity because neither log contains the input video data or its SHA. The script reports median/p95 wall-clock duration, throughput change and CPU dispatch timers; it deliberately **does not claim measured GPU kernel duration, peak memory or thermal safety**. Single-run data produces `REPEAT_REQUIRED`.

### Release acceptance (manual)

- Each mode: three or more completed matching exports with decoded/rendered/encoded frame-count parity and requested GPU inference, no fallback.
- Paired median end-to-end time regression within a mutually agreed budget, and no unexpected large P95 render submission regressions. Supplement with actual GPU tools (Perfetto/AGI) if the wall-time change suggests a GPU bottleneck.
- Thermals and memory must be checked on real devices; end-state thermal/PSS fields are not peak measurements.
- 4K no-op should not activate enhancement. Complex motion/privacy quality is evaluated independently with Debug A/B and actual output video.

## Automated and deferred gates

- `python -m unittest tools.diagnostics.test_crop_clarity_ab_report tools.diagnostics.test_crop_clarity_release_perf`
- Flutter app controller tests for the build-time OFF contract.
- Android JVM `CropClaritySceneSamplerTest` for luma rejection, overlap/motion choice, five consecutive frames, bounded storage and stale evidence.
- Full Android Gradle compilation/instrumented real-device rendering is **pending** until a workstation with Java/Android SDK and a phone is available. The current Mac development environment lacks Java/Android SDK, so passing Flutter/Python/Swift tests cannot substitute for the Android native gate.
