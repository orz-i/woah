# Comparison cutout canvas

This branch develops a third comparison view without replacing the existing
stacked/side-by-side playback and shared music-time contract.

## Product direction

Long-press a visible person on either source, pause both videos, and drag the
selected person toward the center. The common canvas expands below reference
or above target, depending on selection order. With both subjects added, it
becomes the main synchronized playback surface. One subject per source is the
initial scope; fixed calibrated placement, independent mirror and optional
overlap preserve dance timing and real movement. Switching view must not
discard selection.

## M0 renderer contract (implemented, not user-facing)

* Shared FrameKernel: top-left display-oriented unmirrored touch coordinates;
  competing instance masks are rejected rather than selecting by bounding box
  or detector order. Mask projection uses bilinear model sampling and a soft
  probability-to-alpha ramp, not a learned video matte.
* A fixed normalized selection box maps its bottom center and height to a
  canvas placement. Reusing that box across frames preserves translation,
  jumping and crouching. No per-frame recentering or rescaling.
* Android: optional exact-source-PTS provider feeds premultiplied OES/alpha
  composition in the existing GL engine. Cutout has a separate fragment
  program; ordinary preview/export continue to use their existing path.
* iOS: Core Image composes optional alpha layers in the same renderer used by
  preview/export. Source orientation is normalized before masking; both color
  and mask mirror together. Caller must supply actual source PTS from the
  decoded frames (not nominal frame indices or requested playback time).
* Missing/ambiguous identity or mismatched PTS must hide the subject, never
  expose the source background or silently substitute another person.
* M0 fixtures test deterministic masks with decoded video pixels. These
  checks do not establish person inference quality or mobile GPU throughput.

## Original implementation plan (M0 baseline)

1. Connect the pinned YOLO segmentation results and shared tracking to an
   independent subject-analysis job per source, anchored at the selected PTS.
   Build an exact PTS cache with bounded chunks and explicit missing states.
   Validate on real human motion, crossings, occlusion and backwards replay.
2. Wire Pigeon, durable selection evidence and Flutter gesture state; implement
   inverse screen-to-source geometry including rotation, mirror and pan.
   Show drag image only as feedback, never as a substitute for a video layer.
3. Wire preview and offline export to the same cache and placement, including
   real-frame identity for iOS AVFoundation requests. Do not publish a cutout
   export from a missing, invalid, or incomplete analysis.
4. Validate Android hardware and iOS simulator plus real iPhone performance
   before enabling the mode; retain ordinary comparison and protection
   regression coverage. No new model or inference runtime is required for M0.

## Initial M0 limits (historical)

Black canvas, fixed reference-before-target draw order, no user controls or
project persistence. Neither renderer has a production subject-mask provider
yet. Android GPU output and full iOS app are not established by host tests.
No new user-visible capability is claimed by this draft.

## Local real-video M0 experiment (2026-11-26)

`tools/comparison/cutout_m0.py` connects FFmpeg-decoded frames, the repository's
SHA-256-pinned YOLO11n-seg model, FrameKernel instance decoding, independent
tracking sessions and exact source PTS to an OpenCV CPU reference compositor.
It reserves the chosen identity but renders only an unambiguous, currently
observed raw detector mask. A lost identity is hidden, not substituted.
This is a host experiment, not the Android/iOS inference or export pipeline.
It uses a bounded first-frame selection, assumes identity time alignment and
already display-oriented sources, and writes PNGs plus a per-frame manifest to
an explicitly supplied local directory. The PTS overlap is conservatively
bounded by the available source observations. It does not evaluate audio,
full-resolution matting, or two independent real recordings.

On Mac mini, using LiteRT CPU/XNNPACK 2.4.0, NumPy 2.5.5 and
opencv-python-headless 5.1.0.95, the existing 1-second, 30-frame
`testdata/videos/01_sample.mp4` yielded:

| Case | Paired frames | Reference visible | Target visible | Strict result |
| --- | ---: | ---: | ---: | --- |
| Two distinct selections in the original source | 30 | 30 | 30 | PASS |
| Original + 352x768 mirrored H.264 variant | 30 | 30 | 6 | FAIL |

In the second case the chosen target track transitions to Lost at frame 7 and
remains Lost for all 24 remaining frames, even with identity reservation.
The compositor correctly withholds the target rather than drawing a stale or
substitute mask. The result does not establish a ground-truth identity accuracy
rate. Robust re-association and actual two-recording testing remain required.

Reproduce from the repository root on macOS (output directories must not exist):

```sh
cargo build --release --locked --manifest-path crates/frame-kernel/Cargo.toml
python3 -m venv /tmp/woah-cutout-env
/tmp/woah-cutout-env/bin/pip install ai-edge-litert==2.4.0 numpy==2.5.5 opencv-python-headless==5.1.0.95
P=/tmp/woah-cutout-env/bin/python
L=crates/frame-kernel/target/release/libframe_kernel.dylib
S=testdata/videos/01_sample.mp4
$P tools/comparison/cutout_m0.py --library "$L" --reference "$S" --target "$S" \
  --reference-point .355,.855 --target-point .676,.86 --frames 30 --require-all \
  --output /tmp/woah-cutout-original
ffmpeg -i "$S" -vf 'scale=352:768,hflip' -c:v libx264 -pix_fmt yuv420p \
  -an /tmp/woah-cutout-variant.mp4
$P tools/comparison/cutout_m0.py --library "$L" --reference "$S" \
  --target /tmp/woah-cutout-variant.mp4 --reference-point .355,.855 \
  --target-point .324,.86 --frames 30 --require-all --mirror-target \
  --output /tmp/woah-cutout-variant
```

At the initial M0 commit the second command was an explicit failing regression
case. With the continuation fix below it is expected to pass. The manifest is
written before a strict nonzero exit. No derived video, mask, or image is committed.

## Local verification

* Rust full test suite: 134 passed, 1 ignored; strict Clippy passed.
* Android JNI/geometry focused JVM tests: 8 passed; no device GPU validation.
* macOS native cutout smoke: PASS with decoded source pixels, asymmetric alpha,
  mirror, fixed anchors, exact PTS and stale mask rejection; includes Swift
  transport checks. Not a full iOS application run.
* The separate legacy standalone `comparison_render` full run fails the
  last-frame navigation assertion. The same assertion (`main.swift:126`)
  reproduces with source files from master `7e82ec33` on this host, so it is
  recorded separately; this change does not alter that behavior.

The initial M0 results above preceded the native preview integration.


## Experimental native preview (2026-11-26)

The target loss was a detector observation gap: after frame 6, mirrored
H.264 target confidence was about 0.221–0.245, below 0.25. A dedicated
FrameKernel cutout session now seeds only on >=0.25; subsequent observations
>=0.20 may continue an active, recently observed identity when a unique
candidate passes raw-mask and box overlap checks (0.50 and 0.55, within
150 ms), with a separate competing-mask veto. Only the current uniquely
matched raw detector mask is returned. Weak candidates cannot seed a new
subject or directly reacquire a lost identity. Ordinary privacy inference
keeps its existing confidence threshold and session API.

Independent forward/backward sessions anchor on the same real source PTS;
reverse traversal runs on increasing elapsed time internally. Android
uses MediaExtractor PTS and exact canonical decoder frames. iOS uses the
source presentation index and decoded AVAssetReader samples. For preview,
AVVideoCompositing cannot expose the source buffer PTS directly, so the
mapped source-index lookup is checked against a bounded 32x32 color-sample
stamp; disagreement hides the layer. The stamp is a mismatch guard, not a
collision-free proof of sample identity.

The Debug-only view menu offers 抠像画布（实验）. Pause the pair, long-press
the reference and target subjects, and drag each toward the common center
drop region. The first selection is marked and the canvas drop region
appears; actual dynamic cutout is prepared after both selections. Drag
feedback is a marker, not the extracted person image. Coordinates invert
the existing pane, fit, zoom, pan and mirror geometry. Both sources are
analyzed with the pinned YOLO CPU runner; masks are bounded to a 256-pixel
long edge and cached for at most 300 source frames per source (prefix
observation bytes capped at 32 MiB per source). Missing masks are hidden.
The same native texture and synchronized clock play the canvas; clear
returns to the ordinary comparison. Active preview prevents source
replacement, calibration changes and new export. Project persistence,
single-subject live drag preview, editing/overlap controls, long-video
chunking and canvas export remain outstanding.

### Verification

| Case | Reference visible | Target visible |
| --- | ---: | ---: |
| Host original + mirrored variant, anchor 0 | 30/30 | 30/30 |
| Host original pair, anchor frame 12 | 30/30 | 30/30 |
| iPhone 15 Pro simulator iOS 17, original + mirrored variant | 30/30 | 30/30 |
| iPhone 15 Pro simulator iOS 17, original pair, anchor 400000 us | 30/30 | 30/30 |

The host middle-anchor test uses the preceding command with the original
source for both roles, target point `.676,.86`, and flags
`--reference-anchor-frame 12 --target-anchor-frame 12`.
The simulator uses `mobile/app/test_driver/comparison_cutout_smoke.dart`
as its debug entrypoint, with `source.mp4` and `variant.mp4` in the
app Documents/comparison-cutout-fixtures directory. It verifies real
Pigeon, model inference, native texture output (both halves nonempty,
top 10% black), seeks to 0/400000/800000 us, playback advance and exit.
The app writes local report.json and texture captures; no source frames
or derived media are added to Git.

Rust 137 passed/1 ignored, strict Clippy passed. Android 10 focused
JVM/JNI tests passed. Flutter comparison tests 124 passed including the
new gesture/cancellation tests; Flutter analyzer passed. macOS focused
native render/PTS/stamp smoke passed; iOS debug simulator build passed.
Android hardware was not connected. The two simulator cases use the same
short source and its derived variant, not independent dance recordings;
counts are visibility, not measured identity accuracy or quality. Real
device performance, complex motion/occlusion, rotated or edited source
timelines and broader VFR behavior still need testing. Do not enable
release or canvas export until these risks are addressed.

## Real subject drag and A/B cutout range (2026-11-26)

The debug selection interaction now uses a true instance-masked transparent
still instead of a person marker. Long press decodes the actual selected
source PTS and runs the existing pinned model. FrameKernel generates
straight RGBA from the same model-space RGB input and selected mask, rejects
letterbox padding, and returns the nonzero-alpha crop. Android encodes that
crop with Bitmap and iOS with ImageIO; the Pigeon reply contains only the
bounded PNG, dimensions and PTS. No drag images are stored in production
files or project JSON.

During a slow inspection the drag displays a progress cue. A valid drop
can wait for inspection to finish, but a canceled/invalid/ambiguous gesture
never admits a subject. The first accepted subject appears as a still
in a separate central canvas, and both source panes fit into the remaining
slots. Their hit coordinates map back into the frozen native texture
geometry. Mirror is applied to the drag image and final layer consistently.
Once both subjects are accepted, the native analysis reuses their selected
observations when the seed and source frame match. Android checks a digest
of the canonical decoder input, and iOS checks the source sample stamp.
The selected still is not used as a substitute for the dynamic layer.

If a valid A/B practice loop is enabled before entering cutout, the analysis
window is the existing overlap-relative [A,B) interval, including the actual
source sample that covers A. The two sources still use the music mapping.
Analysis, seeks and adjacent-frame stepping stay within the immutable
window; changing the practice range while active is rejected. The regular
comparison view restores on exit. The debug cutout timeline uses a simple
scrubber rather than editable A/B handles.

The per-source **300 analyzed-frame limit remains**. This is a practical
long-source/short-practice-range improvement, not full-length chunked
inference, persistent cache, or export. The first-person preview is the
paused selected frame; synchronized dynamic playback starts after both
subjects are selected. The crop uses model-input resolution (640 square
before cropping), so small subjects may have limited drag detail.

### Verification

The iPhone 15 Pro simulator (iOS 17) native smoke
`mobile/app/test_driver/comparison_cutout_smoke.dart` verified the
new inspectCutout bridge, transparent/opaque/soft alpha pixels, invalid
background selection rejection, exact PTS, analysis, actual texture output,
seek/play and restoration in three cases:

| Case | Reference still | Target still | Analysis | Reference / target visible |
| --- | ---: | ---: | ---: | --- |
| Original + mirrored variant | 486 ms | 339 ms | 5437 ms | 30/30, 30/30 |
| Original pair at 400 ms anchor | 674 ms | 669 ms | 5715 ms | 30/30, 30/30 |
| 12-second/360-frame sources, A/B 6–7 s | 590 ms | 345 ms | 5633 ms | 30/30, 30/30 |

The long fixture repeats the original one-second fixture and the mirrored
variant using FFmpeg, so it tests interval/resource handling, not long
choreography or independent identity ground truth. Reproduce by placing
`source.mp4` and `variant.mp4` into the simulator app's
Documents/comparison-cutout-fixtures directory, then:

```sh
ffmpeg -stream_loop 11 -i source.mp4 -map 0:v:0 -c copy -an long-source.mp4
ffmpeg -stream_loop 11 -i variant.mp4 -map 0:v:0 -c copy -an long-variant.mp4
```

Build the debug smoke entry as in the previous section. It writes a local
report and PNG captures. No source video, model input, still or screenshot is
committed. Simulator timing is not physical iPhone performance.

Flutter comparison regression: 127 tests passed (including 7 cutout tests).
Android focused JVM/JNI regression: 12 tests passed. Device GPU and actual
Android touch behavior remain unverified; no ADB device connected. Release
remains disabled, and draft PR remains unmerged.

The additional real-screen smoke
`mobile/app/test_driver/comparison_cutout_gesture_smoke.dart` also passed
on the iPhone 15 Pro simulator. It dispatches long-press/drag pointer
events through Flutter into the actual ComparisonScreen and native
gateway, not a mocked inference engine. First-drop texture captures had
visible reference, canvas and target regions (12,688 / 9,911 / 12,263
nonblack pixels respectively). The second drag entered the dynamic canvas,
and the exit button restored the original source panes.

## Editable canvas and layout persistence (2026-11-26)

The Debug-only dynamic canvas now offers an independent editing console.
Pause and choose the reference or target layer explicitly, including when
both people overlap. Drag changes the selected layer's normalized bottom
center; pinch or the height slider adjusts its fixed calibration scale.
Opacity, mirror, layer order, parallel/overlay presets, and black/charcoal/
light backgrounds are editable. Geometry/slider gestures show a draft
anchor/value during interaction and apply to the native video texture on
release. The editor preserves the current texture aspect while the control
panel changes its viewport size.

The domain `ComparisonCanvasLayout` is independent of ordinary source
picture settings. One complete validated layout crosses Pigeon. Android
publishes a single atomic geometry snapshot, redraws the current decoded
pair on the GL queue and acknowledges only after submission. iOS builds a
new AVFoundation composition snapshot at the current position, retaining
the same immutable PTS-keyed Alpha cache; failed edits restore the old
snapshot where possible. Both renderers use the existing FrameKernel
fixed-anchor quad and actual source color/Alpha. No editing operation
runs segmentation or tracking, and no cached mask is copied per edit.
Rendering order and background change together with the pair layout.

Only layout preferences are written into the optional `canvasLayout`
field of the existing project schema. Old projects omit the field and
retain the old behavior. Source replacement or role swap clears it.
A new cutout session reuses the saved layout after explicit selection;
person identity anchors and masks are deliberately not persisted. This
is layout restoration, not automatic subject reselection or cache reuse
across sessions. Normal comparison export is still isolated from the
experimental canvas.

### Evidence

* Flutter comparison regression: 129 tests passed, including 9 cutout
  tests (gesture, layout edits, old JSON, validation, and reuse of saved
  layout). Domain package: 28 passed. Scoped analyzer: clean.
* Android: 13 focused JVM/JNI tests passed, including immutable selected
  anchor and exact PTS cache reuse while changing geometry; no connected
  device GPU test.
* iPhone 15 Pro simulator iOS 17: all three previous real-video cases
  passed again (each role 30/30 visible); native editing smoke in the
  middle-anchor case also passed. Hiding/moving the target/reference
  produced 4,186 left / 0 right foreground pixels, reversed overlapping
  order produced different images, light background filled all 76,800
  pixels, and parallel reset restored both subjects. All five edit
  operations preserved the sampled source PTS. Native application times
  were 211, 211, 215, 545 and 218 ms (simulator only).
* The real Flutter screen + native gateway simulator gesture smoke
  passed again. After overlay, independent target mirror and drag, the
  target saved layout was x=0.68, y=0.75, height=0.70, opacity=0.5,
  mirrored=true; reference remained x=0.5, y=0.88. The fixture gateway
  isolates project writes while exercising the actual native bridge and
  render pipeline. JSON round-trip and controller save/reselect are
  separately tested; no user projects or media were modified.
* Pigeon reproducibility and iOS Debug simulator builds passed.

Still outstanding: fully live per-gesture native edits (current edits
commit on release), durable person selection with source evidence,
progressive/full-length chunked analysis (300 frames per analyzed window
remains), cutout export, independent recordings and difficult occlusions,
and real Android/iPhone performance. Keep Debug-only and draft PR.

## M4 — Frozen canvas MP4 export (2026-11-26)

The active Debug cutout canvas has its own export button and explicit
`startCutoutExport` bridge. Ordinary comparison exports are still separate
and cannot be invoked with an active cutout session. The dialog exports
only the already analyzed common interval (full common overlap or enabled
A/B practice range); it does not offer unanalyzed ranges. Output uses the
existing even-dimension, maximum-long-edge-1920, 1x H.264/AAC encoder
and reference audio (silence when absent).

Native preflight checks both source URIs, affine mapping, the exact
analyzed window, and that both role caches contain every observed frame.
The worker receives the immutable selected-mask cache and a frozen
layout (including background, order, opacity, mirror and placement).
The Android cache snapshot holds the same immutable alpha entries with
a separate layout reference; the iOS cache is a value snapshot. The
existing independent decoders and encoder are reused. Android requires
each decoded source PTS to resolve to a current mask; iOS requires exact
decoded source PTS, matching sample stamps, and both rendered layers.
A missing or mismatched layer aborts encoding, rather than exporting
ordinary source pictures, stale masks or silently blank subjects.
Both sources are streamed through SHA-256 before/after analysis and
before/after encoding; a changed source aborts publication. These scans
do not run inference, but add I/O proportional to source size.

The preview session releases its decoders during the export and retains
the analyzed cache and position. Dismissing the dialog after completion
or cancellation restores the paused canvas without selecting or inferring
again. The persisted export spec has an explicit cutout-kind flag, while
the selected masks and source digests remain in memory. Retrying within
the same process uses the same frozen job snapshot. After process death,
a cutout job without the snapshot cannot be retried as an ordinary job;
the user must select the subjects again. A source-integrity failure also
invalidates its retry snapshot. Completed local movies and the existing
save/publish/cancel lifecycle are retained; ordinary records stay backward
compatible. The existing 300-frame-per-source analysis limit remains.

### M4 evidence

- iPhone 15 Pro simulator, iOS 17: real native inference, preview layout,
  encoding, completed MP4 and preview restoration on three cases
  (original + mirror variant, mid-frame selection with cancel/retry,
  and 12-second repeated fixture with A/B 6–7 seconds and a reference
  440 Hz test tone). All 3 outputs: 320x240, H.264, 30 decoded frames,
  duration exactly 1 second, 48 kHz stereo AAC. The first two are
  silent; the third retains the reference tone. No full-length 12-second
  cutout export was attempted.
- `tools/comparison/verify_cutout_export.py` checks actual decoded RGB
  frames and audio, exact fixture video PTS, and restored-preview equality.
  Three sample times per movie, full-frame RGB mean absolute error
  0.937–1.066 on 0–255 range; subject-region MAE 2.305–2.937. The 400 ms
  resumed preview matched its pre-export counterpart byte for byte.
- Flutter comparison suite 132 passed, including export range/cache
  restoration and missing-mask admission tests; scoped analyzer clean.
- Android 23 focused cutout/export/manager JVM/JNI tests passed; actual
  MediaCodec/GLES encoding on Android hardware still unverified.
- Full macOS comparison host regression passed, including ordinary
  VFR H.264/AAC, A/B affine mapping, cancellation, export-kind recovery,
  missing-mask and mismatched-sample refusal, and fingerprint mutation.
- The simulator negative export probe changed a disposable source after
  analysis. The actual worker returned failed, outputPath=null,
  retryAvailable=false; retry was refused.
- Native app build and simulator verification only; no physical iPhone,
  Android device, or Photos/MediaStore publication verification in this
  round. Fixture material and generated media remain local, not Git data.

For the iOS smoke build the existing
`mobile/app/test_driver/comparison_cutout_smoke.dart` entrypoint. Place
the earlier source and mirror variant in its fixture directory. Generate
the 12-second reference with a synthetic audio track (on disposable
fixtures only):

```sh
ffmpeg -stream_loop 11 -i source.mp4 -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -t 12 long-source.mp4
ffmpeg -stream_loop 11 -i variant.mp4 -map 0:v:0 -c copy -an long-variant.mp4
python3 tools/comparison/verify_cutout_export.py /path/to/comparison-cutout-fixtures
```

The smoke explicitly consents to replacing prior QA export records;
use a dedicated QA app container, never a production user's export
history. The verifier also requires the disposable source-mutation
negative report. The numeric measurements are not proof of general
identity accuracy, matte quality or real-device throughput.

## Android physical-device baseline (2026-11-26)

Device: OnePlus 8T / KB2000, Android 14, arm64-v8a. Hardware AVC decoder
reported `OMX.qcom.video.decoder.avc`. The isolated, opt-in Debug package
`art.gaoge.dance.cutoutqa` does not overwrite the normal application or its
projects. All sources below are disposable derivatives of the same tracked
one-second fixture. Reference and mirrored target were scaled to 352x768
and re-encoded as H.264 for hardware decoder compatibility; the 12-second
fixtures repeat those frames, with a 440 Hz mono reference tone.

### Actual device results

| Case | Role coverage | Analysis | Export including publication |
| --- | --- | ---: | ---: |
| Mirror, first-frame anchor | 30/30 each | 7.410 s | 2.305 s |
| Same source, middle anchor, cancel/retry | 30/30 each | 7.488 s | 2.104 s |
| 12 s source, A/B [6,7) | 30/30 each | 7.773 s | 1.380 s |
| 12 s source, A/B [6,11), anchor 10 s, 1440x1080 | 150/150 each | 39.191 s | 5.136 s |

The first three actual MP4s contain 30 frames each at 320x240 and exactly
1 second of video/audio presentation; the extended one contains 150 frames
at 1440x1080 and exactly 5 seconds. All are H.264/AAC; the absent reference
audio produces stereo silence, and Android preserves the real mono
48 kHz source. iOS normalizes to stereo, so the verifier has an explicit
Android expectation, not a relaxed audio check. FFmpeg decoded the
three short movies: nine corresponding samples gave RGB MAE 0.738–0.809
(foreground MAE 1.060–1.395). The high-resolution output downsampled to
preview size gave RGB MAE 1.673–1.680 (foreground MAE 2.017–2.052).
All four resumed preview images match the pre-export image at 400 ms.
The source-mutation task failed with no output and retry unavailable.

All four files were published into MediaStore (IS_PENDING=0, QA owner);
reading each published URI returned bytes exactly equal to its local MP4.
The provider reports a 1043 ms duration on the 1-second outputs due to
raw AAC priming; the MP4 edit-list presentation duration and decoded
track durations are 1.000000 s. This is a metadata/UI discrepancy, not a
verified playback overrun. Existing ordinary encoder policy is unchanged.

The actual Flutter screen gesture smoke also passed on Android (real
pointer dispatch, alpha selection, first-person expanded canvas, second
selection, native preview, edit, save-flow and exit). Target position
became (0.68,0.75), mirror=true, opacity=0.5; reference stayed (0.5,0.88).
Native layout application in the separate 320x240 API smoke took 6–16 ms.

### Measured bottleneck

The Debug analyzer emits one URI-free `WoahCutoutPerf` summary per source
role, including nested YOLO timings. For the five-second test (300 source
frames total, two roles analyzed serially, CPU/XNNPACK 4 threads):

| Stage | Time |
| --- | ---: |
| Total native analysis wall time | 39.028 s |
| Segmentation total (includes model run) | 33.162 s |
| LiteRT model execution alone | 28.424 s |
| Canonical decode + YUV/RGBA conversion | 4.704 s |
| Shared tracking | 0.120 s |
| Alpha projection | 0.328 s |
| Source fingerprint scans | 0.008 s |
| PTS frame indexing | 0.125 s |

The model execution is about 73% of the measured native wall time.
Raw reverse-prefix mask bytes for 120 preceding frames were 17,100,800
and 18,432,000 bytes per role, close enough to the existing 32 MiB per-role
limit to be material. Cached selected alpha is 29,952 bytes/frame/role
for these fixtures (8,985,600 bytes total for 300 source frames).
These counts exclude object, row, runtime and codec overhead. A sampled
PSS during analysis was about 469 MB, not a measured peak.
No accelerator, threshold, tracking or output behavior was changed.

The next implementation should prioritize continuous anchor-outward
tracking, bounded reverse-observation spool, segmented selected-alpha
cache, and staged publication of analyzed coverage. A faster backend
needs a separate CPU/GPU equivalence and thermal/performance comparison.
Do not simply lift the 300-frame / 32 MiB limits or infer identity from
this repeated fixture.

### Reproduction

Build with an isolated debug package (normal application ID unchanged):

```sh
cd mobile/app
export ORG_GRADLE_PROJECT_WOAH_CUTOUT_QA=true
flutter build apk --debug --target-platform android-arm64 \
  --target=test_driver/comparison_cutout_smoke.dart
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

Prepare source.mp4 with `scale=352:768`, variant.mp4 with
`scale=352:768,hflip` (both H.264/yuv420p), then use the long-fixture
commands above. Stage the four files under the isolated app's
`Documents/comparison-cutout-fixtures` via `adb shell run-as art.gaoge.dance.cutoutqa`.
Launch `art.gaoge.dance.cutoutqa/art.gaoge.dance.MainActivity`.
For the five-second high-resolution probe use the same target with
`--dart-define=WOAH_CUTOUT_EXTENDED_QA=true`. The separate gesture
entry is `test_driver/comparison_cutout_gesture_smoke.dart`.
Pull local fixture results from the QA package, verify them using:

```sh
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform android
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform android --extended
```

Device log and generated media are not committed. The four QA movies
are intentionally left in the device gallery; no existing user media was
removed. Physical iPhone, genuinely independent long choreography,
occlusions, real-device full-length analysis, and production-quality
edge/identity evaluation remain outstanding.

## Stage B1: bounded, process-scoped segmented storage (2026-11-26)

- Replace the in-memory reverse-observation prefix on both native platforms with
  an anonymous, app-private scratch file. Records retain source PTS, raw detector
  rows, original masks and coordinate transforms; iOS also retains its sample
  stamp. The same forward/reverse tracking handles are used across all records.
- Selected-person alpha is stored on disk by exact source PTS. Reads use logical
  32-sample chunks with at most two resident chunks per role. The cache validates
  record integrity; a corrupt/missing record does not become a stale mask.
  Export freezes the selection/layout and holds its own lease. Android explicitly
  releases leases in session/export lifecycle paths; iOS uses object ownership.
- Open scratch files are immediately unlinked. Their storage is reclaimed after
  the last owner closes; no named scratch cache or persistent recovery manifest is
  created. This is session storage, not cross-process resume.
- Current bounded admission: up to 3600 samples per source, 512 MiB reverse
  observation scratch per role and 256 MiB alpha scratch per role, subject to
  available disk space. The exact supported duration varies with source cadence,
  detector count and resolution. Analysis still completes both directions before
  publishing the dynamic canvas. Chunk reads are synchronous; asynchronous
  prefetch/progressive readiness are not implemented yet.

### Full-interval device evidence

Used the existing one-second source repeated to 12 seconds (not independent
choreography), same 352x768 H.264 sources on both platforms, reference 440 Hz
audio, anchor at 10 seconds, no A/B restriction, 1440x1080 export.

| Metric | OnePlus 8T / Android 14 | iPhone 15 Pro simulator / iOS 17 |
| --- | ---: | ---: |
| Source samples, each role | 360 | 360 |
| Visible samples, both roles | 720/720 | 720/720 |
| Analysis wall (bridge) | 93.403 s | 64.288 s |
| Export elapsed | 13.390 s (includes publication) | 4.915 s |
| Encoded H.264 frames | 360 | 360 |
| Container duration | 12.000 s | 12.000 s |
| RGB MAE vs 320x240 preview, six samples | 1.673–1.977 | 1.330–1.343 |
| Output audio tone | 439.96 Hz | 440.00 Hz |

Android profiling: reference/target reverse spool 42,799,800 / 46,130,400
bytes, exceeding the old 32 MiB limit on both roles. Each role's alpha scratch
10,788,480 bytes; raw alpha payload per sample 29,952 bytes. Sum of native
analysis wall times 93.256 s, including model run 68.068 s (~73%), decode /
convert 11.003 s, reverse spool write/read 0.698 / 0.310 s, and alpha storage
0.213 s. The temporary records were unlinked during analysis; after exit
`/proc/<pid>/fd` contained no cutout scratch handles and no named cutout
scratch file was left in the QA cache. Android MediaStore readback matched
local MP4 bytes. Both platforms restored preview after export.

Reproduce by building the existing cutout smoke entrypoint with
`--dart-define=WOAH_CUTOUT_FULL_QA=true`, using the 12-second fixtures from
the Android baseline, then run:
```sh
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform android --full
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform ios --full
```
Android focused JNI/JVM tests: 26 passed, including 400-record chunk/lease
roundtrip, spool bounds and reverse identity across chunk boundaries.
macOS host regression passed; Flutter scoped analyzer clean. No new media
or device-local evidence is committed. A real 30–60 s independent video,
GPU equivalence, progressive/anchor-first analysis, asynchronous prefetch,
and durable cache recovery remain to be verified/implemented.

## Stage B2: anchor-first playable preview (2026-11-26)

Both native analyzers now seed the existing two directional FrameKernel
tracking handles at the selected source PTS, process the first neighborhood
around that anchor, then finish the untouched suffix and reverse prefix.
The initial preview window is the intersection of the requested comparison
window and anchor position +/- 500 ms. The source-frame predecessor is
included; the selected source PTS must still belong to the window. No
already-analyzed sample is segmented again (decoder dependencies can be
decoded again when reopening a span). The same selected identity handles
and final disk cache receive all observations, with no reseeding at the
initial-window boundary.

The native prepare operation remains outstanding until both full tracks
are sealed and their original-source fingerprints reverified. A new
debug-only getCutoutStatus reply exposes the initial immutable preview,
analyzed frame counts, and its time interval while this work continues.
The initial preview does not carry the export fingerprints. Both native
players constrain seeks, step and looping to the partial window; editing,
resize and export are gated during analysis. Flutter polls at its existing
200 ms cadence and displays processed-frame counts, then adopts the
complete result, discarding obsolete progress and cancellation replies.
Exit, background and project changes cancel analysis. The ordinary
comparison paths and model/runtime policy are unchanged.

This is two-stage readiness, not continuously growing timeline coverage:
the initial interval stays fixed until the full interval is ready.
Counts reflect materialized selected-alpha observations (a reverse spool
can be decoding before the count advances). Initial preview remains
bounded in RAM (maximum 256 source frames per role); the full cache is
still the B1 disk-backed store. Total analysis time is not improved by
the scheduling change, and opening additional decode spans can add
latency. Native preview refresh on the final transition may briefly
rebuild the player. GPU, async alpha prefetch, durable cache recovery,
and independent real choreography remain outstanding.

### Verified device evidence

Same 12-second, 360-frames-per-role disposable H.264 fixture as B1, anchor
at 10 seconds, no A/B restriction, 1440x1080 full output:

| Metric | OnePlus 8T / Android 14 | iPhone 15 Pro simulator / iOS 17 |
| --- | ---: | ---: |
| First actual playable canvas | 8.448 s | 5.421 s |
| Complete analysis | 94.428 s | 82.949 s |
| Initial common playback interval | [9.5,10.5) s | [9.5,10.5) s |
| Initial analyzed frames (each role) | 30 | 30 |
| Later observed analyzed counts | 50 / 30 | 48 / 30 |
| Final role coverage | 360 / 360 | 360 / 360 |
| Full export elapsed | 11.075 s | 7.671 s |
| Decoded video frames | 360 | 360 |
| Container duration | 12.000 s | 12.000 s |

Real playback was exercised while native work was running; seeks before
and after the initial window were clamped, playback loop stayed in the
window, export admission was rejected without replacing the existing job,
and progress increased. The first and final anchor PNGs decoded to
identical RGB pixels. Full output passed the six-sample B1 verifier;
Android MediaStore readback was byte-identical. No new raw or derived
media was committed.

Separate real cancellation probes on both platforms waited until the
initial canvas was playable, called clearCutout, verified rejection of
the pending prepare reply and restoration of ordinary source pixels,
then sought to zero and reopened the pair without any late partial
status. Android had no remaining anonymous scratch file descriptors;
iOS host inspection found no remaining scratch handle after completion.

Flutter comparison regression: 134 passed, including two new progressive
controller tests. Scoped analyzer clean, Pigeon regeneration reproducible,
Android focused JNI/JVM comparison regressions (26), macOS host comparison
regression, and native builds passed.

Reproduce the complete progressive test using the existing full QA
entrypoint with `--dart-define=WOAH_CUTOUT_FULL_QA=true` and verify:
```sh
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform android --full --progressive
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform ios --full --progressive
```
Build the same entrypoint with `--dart-define=WOAH_CUTOUT_CANCEL_QA=true`
for the standalone partial-cancellation probe, which writes
`cancel-report.json` and does not overwrite the full-run report.

The real Flutter screen gesture regression on both platforms also passed
after correcting a transition race: the final result now awaits the next
actual viewport layout callback and any debounced/native resize before
enabling edit and export. Both gesture runs observed partial progress with
the edit/export actions disabled, then entered the editor, applied and
persisted overlay/mirror/placement, and exited normally.
