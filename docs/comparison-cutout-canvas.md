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

## Stage B3: actual accelerator comparison (2026-11-26)

The Android benchmark is an opt-in instrumented test using the same
CanonicalYuvInferenceDecoder, YOLO11n-seg, selected-mask FrameKernel
tracking, anchor-first ordering, and final disk cache as the canvas.
It compares exact source PTS and selected alpha, not a dummy input
or just the model invocation return time. The report keeps only
aggregate metrics (no source paths, frames, masks, or tensors).

Test device: OnePlus 8T / KB2000, Android 14, Qualcomm hardware AVC.
The two 3-second sources each have 90 real decoded H.264 presentation
samples, made by repeating the same one-second source and its mirror.
Seed at 1s, pinned model SHA-256
`ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340`.
CPU4, GPU, CPU6 ran serially in that order. Battery sensor temperature
rose from approximately 25.2 to 27.1 C; this is one device and one
trial, not a thermal steady-state benchmark or independent choreography.

| Metric | CPU4 | GPU | CPU6 |
| --- | ---: | ---: | ---: |
| Actual runtime | CPU | GPU | CPU |
| Initialization | 0.102 s | 2.072 s | 0.118 s |
| Two source inspections | 0.563 s | 0.487 s | 0.721 s |
| Initial neighborhood analysis | 8.082 s | 5.459 s | 8.848 s |
| Both full analysis (excluding readback of final alpha cache) | 25.749 s | 14.387 s | 25.175 s |
| Total wall including initialization and cache readback | 26.467 s | 17.007 s | 26.078 s |
| Visible frames | 180/180 | 180/180 | 180/180 |
| Mean binary alpha IoU vs CPU4 (threshold 128) | reference | 0.995148 | 1 |
| Minimum alpha IoU vs CPU4 | reference | 0.976190 | 1 |
| Missing/extra selected frames vs CPU4 | reference | 0 / 0 | 0 / 0 |
| Exact alpha frames vs CPU4 | reference | 0 | 180 |
| Mean abs alpha error in threshold-union region (0–255) | reference | 0.4423 | 0 |

GPU complete analysis is 1.79x faster, and whole benchmark wall time is
1.56x faster. The GPU area ratio relative to CPU ranges from 0.9880 to
1.0244. CPU6's 2.2% analysis improvement is too small to justify changing
thread count. These measurements establish agreement with CPU on this
fixture, not segmentation ground truth or subject identity under difficult
occlusion. The GPU compiled model is effective GPU according to LiteRT;
that does not establish full GPU residency of every operation. Model
invoke alone understates GPU latency because reading its output can
synchronize execution: combined invocation + output read was about
18.31 s on CPU4 and 6.19 s on GPU for the two lanes.

### Isolated Android application GPU path

An explicit Gradle property selects GPU **only** for the isolated Debug
`.cutoutqa` application. Normal builds remain CPU4. GPU initialization
fallback is rejected in this trial instead of mixing CPU/GPU observations
within a selected identity session. During successful GPU selection the
same initialized model runner is leased across reference/target
inspection and full analysis; no repeated compilation for each selected
role. Idle runners are released on source/playhead/practice changes,
exit, background or session close; in-flight work retains exclusive
ownership and closes on completion/cancellation. CPU retains its original
short inspection runtime lifetime. No privacy export policy, model, mask
threshold, tracking algorithm or native encoder is changed.

On the real application GPU path with the full B2 fixture, 12 seconds,
720 total source frames, anchor 10 seconds, 1440x1080 output:

| Metric | Prior B2 CPU4 | B3 GPU |
| --- | ---: | ---: |
| First dynamic preview after prepare | 8.448 s | 4.996 s |
| Full analysis | 94.428 s | 49.705 s |
| Export including publication | 11.075 s | 10.547 s |
| Valid selected frames | 720/720 | 720/720 |

GPU cold compilation was ~2.04 s; the successful first inspection took
2.267 s and the second inspection 0.200 s (no new compile). The first
preview timing starts after those inspections, not from the first press.
The smoke also intentionally rejects an invalid background hit, which
causes a separate compile before the valid selection. Full analysis time
falls 47.4%, first-preview analysis time 40.9%. Both lanes logged GPU.
Full 360-frame MP4 passed the existing progressive verifier, including
12.000s duration, source PTS, 439.96 Hz tone, anchor equality, preview
restore, and MediaStore readback byte equality. Preview/export RGB MAE
1.700–1.977. Six corresponding CPU/GPU **decoded** output samples
downscaled to 320x240 gave whole-frame RGB MAE 0.208–0.359 and selected
foreground-region MAE 1.212–1.726 (0–255; encoder and geometry differences
included). GPU cancellation after first partial preview passed:
one compiled runtime, first preview 4.889s, cancellation and restored
ordinary playback 0.403s, late reply rejected, reopen clean, no remaining
cutout scratch handles or LiteRT runner thread observed.

The new main bottlenecks include output synchronization/readback
(20.603s summed in the full GPU run) and decode/conversion (14.475s).
Do not infer GPU model time solely from the asynchronous invocation
counter or sum overlapping per-role wall timers. GPU still needs
independent long choreography and multi-device thermal testing before
default promotion.

### iOS simulator diagnostic

The existing strict-backend video probe was exercised on three real
frames (0, 400, 800ms) from the same source. XNNPACK median model invoke
40.257ms, Metal 21.237ms; actual Metal backend reported, but five person
candidates on every sample versus three on XNNPACK, with some extended
boxes. Current Metal configuration allows precision loss. Core ML
strict probe failed on this simulator. These are detection summaries,
not selected-alpha equivalence or physical iPhone measurements.
iOS comparison remains XNNPACK. A physical iPhone is needed before
promoting Metal or Core ML.

### Reproduce

From the app's Android Gradle directory, build the isolated instrumentation
APK:
```sh
export ORG_GRADLE_PROJECT_WOAH_CUTOUT_QA=true
./gradlew :dance_native:assembleDebugAndroidTest
```
Install the resulting `art.gaoge.dance.engine.cutoutqa.test` APK. Stage
disposable 3-second H.264 fixtures `reference.mp4` and `target.mp4` in
that package's `files/comparison-cutout-backend-qa` via run-as. Run:
```sh
adb shell am instrument -w -r \
  -e class art.gaoge.dance.engine.comparison.ComparisonCutoutBackendInstrumentedTest#sameVideoCpuGpuSelectedAlpha \
  -e cutoutBackendQa true -e limitUs 3000000 -e anchorUs 1000000 \
  art.gaoge.dance.engine.cutoutqa.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as art.gaoge.dance.engine.cutoutqa.test \
  cat files/comparison-cutout-backend-qa/report.json
```
To exercise GPU in the existing isolated application QA flow:
```sh
export ORG_GRADLE_PROJECT_WOAH_CUTOUT_QA=true
export ORG_GRADLE_PROJECT_WOAH_CUTOUT_QA_BACKEND=gpu
flutter build apk --debug --target-platform android-arm64 \
  --target=test_driver/comparison_cutout_smoke.dart \
  --dart-define=WOAH_CUTOUT_FULL_QA=true
```
Use the prior 12s fixture and `--full --progressive` verifier.
Use `WOAH_CUTOUT_CANCEL_QA=true` in place of the full define to verify
cancellation. Without both the isolated package and the explicit GPU
property the ordinary application remains CPU4. For simulator backend
diagnostics, build `test_driver/comparison_cutout_ios_backend_smoke.dart`
with a disposable source.mp4 in the same QA Documents directory.

All generated media, reports and traces remain local. No user application
or project data was replaced. A QA MP4 remains in the device gallery.

## Stage B4: saved canvas restoration (2026-11-26)

The project view continues to open as ordinary comparison. Entering
"抠像画布（实验）" first attempts a saved canvas for that project ID;
a valid cache restores the selected subjects and current project's layout
without calling a detector. "重新选人（清除缓存）" explicitly removes the
archive before showing selection. Exiting the canvas does not erase
the completed analysis. A missing/expired/invalid archive falls back
to ordinary selection. Incomplete analysis remains preview-only and
is never saved as a reusable archive. A storage failure does not
invalidate the current complete in-memory preview or export.

Both platforms store only complete selected-alpha tracks, source PTS,
selected normalized coordinates and fixed anchor boxes in a separate
app-private cache. iOS additionally retains the existing 32x32 sampled
RGB stamp per source sample for the compositor's mismatch guard; it is
not a unique PTS proof. There is no raw detector prefix, thumbnail,
source movie, or model tensor in the archive. Layout preferences remain
in the existing project JSON; project schema is unchanged. The cache
is not a backup or long-term portable project format. Android uses
app cache; iOS uses Library/Caches with backup exclusion and file
protection. Four project archives / 512 MiB total, oldest-first
eviction and 30-day idle expiry on cache maintenance; OS may evict
earlier. Atomic staging can briefly require space for the old and new
archive. No new persistent cache is created for partial analysis.

Archive admission validates the format/algorithm contract version,
native build identity (Android Git revision / iOS executable hash),
OS, inference backend, actual bundled model digest, both original
source SHA-256 fingerprints, reference origin, affine music mapping,
mirror settings and exact common/A-B interval. The whole file checksum
is checked before reconstruction; expected PTS are independently
enumerated from the current sources, records are bounded, and source
fingerprints are checked again before publication. This is corruption/
staleness detection, not an adversarial authentication protocol.
Incompatible builds conservatively require new analysis. Cache loss
requires manual reselection, not an automatic identity guess.

Reconstruction streams records into the existing anonymous B1
scratch store; no all-video alpha array is loaded into RAM. The
existing frozen-cache and exact decoded-PTS export checks remain.
Cancellation and project switches reject late restore results.
The user can clear a project's archive explicitly. Other stale
archives are evictable. Export job snapshots after process death still
require starting a new export from the restored canvas rather than
silently retrying an old job.

### Actual process restart evidence

12-second repeated H.264 source pair, A/B [6,9)s, selection at 7s,
90 real samples per role, edited canvas layout. Write archive in one
process; terminate the application; start the **same installed build**
again, open at 7.4s and restore without inspection or inference.
Then seek to 7s, compare anchor pixels and export the restored cache.
The fixture derives from the same short source; no independent
choreography or real iPhone evidence is claimed.

| Metric | OnePlus 8T Android 14 | iPhone 15 Pro simulator iOS 17 |
| --- | ---: | ---: |
| Original analysis (CPU) | 24.565s | 14.164s |
| Archive write | 0.121s | 0.030s |
| Archive size | ~5.1 MiB | ~5.7 MiB |
| Restore including playable state | 0.588s | 0.279s |
| Role coverage | 90/90 each | 90/90 each |
| Original/restored anchor RGB | identical | identical |
| Restored H.264/AAC output | 90 frames, 3.000s | 90 frames, 3.000s |
| Export elapsed (320x240) | 2.696s | 1.232s |

The Android report used distinct process IDs 27838 and 28628; iOS
48894 and 48982. Android restart log has zero model initializations.
Both outputs retain the 440 Hz reference audio and exact frame PTS.
All six independently decoded preview/export samples passed the verifier
(Android RGB MAE 0.744–0.809; iOS 0.937–1.039); resumed preview identical.
Android published file bytes match the local MP4. Both platforms
rejected the corrupted archive, changed affine offset, and source
mutation at identical URI; explicit forget then returned no cache.
The first Android negative-probe run used an incorrect Dart cache
directory; fixed the test path and repeated the complete two-process
probe. The final result passed. Only disposable fixture copies were
modified. Android archive directory empty and no scratch descriptors
observed at the end. Physical iPhone, cache behavior after OS eviction,
and long independent choreography remain outstanding.

Flutter comparison tests 137 passed (restore without re-inference,
incomplete/failed save, late restore cancellation and prior regressions).
Android focused JNI/JVM 27 passed (new archive exact PTS/alpha roundtrip,
checksum, mapping, cancellation and explicit forget). Pigeon output
reproducible; scoped analyzer and simulator build passed.

### Reproduce

Build the existing smoke entrypoint with:
```sh
flutter build apk --debug --target-platform android-arm64 \
  --target=test_driver/comparison_cutout_smoke.dart \
  --dart-define=WOAH_CUTOUT_PERSIST_QA=true
```
Use the isolated cutoutqa package (as in prior sections), or the
iOS simulator target. Stage the existing long-source/long-variant
fixtures. Remove only the QA fixture directory's archive-report.json
to start a fresh run. First launch waits until report phase "saved";
terminate the app without uninstalling, then launch the same binary.
The report must reach phase "finished" and have different process IDs.
Verify:
```sh
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform android --archive
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform ios --archive
```
Pull the published Android file to archive-published.mp4 for byte
comparison. The real Flutter gesture smoke now additionally exercises
saved-canvas reentry through the view menu, editable layout recovery,
and explicit clear/reselect. Generated media and device reports stay
local; the device QA export remains in MediaStore.

Final actual Flutter screen gesture regression passed on both Android
and iOS: initial selection, complete analysis, edited layout, exit,
restore via the normal view menu without a selection overlay, editing
the restored canvas, exit, and explicit clear/reselect. The QA cache
was removed at the end of the reselect test.

## Stage B5: independent real-dance quality baseline (2026-11-26)

**Result: real-scene identity quality is NOT accepted. Do not promote
the Android GPU default or remove the experimental flag.** Both native
platforms can analyze the full 30-second input; tracking/segmentation
quality, not cache capacity or encoding, is the release blocker.

### Fixture provenance and scope

`testdata/comparison/real-dance-b5.json` records the source URLs, authors,
CC BY 3.0 attribution/license links, exact downloaded and tested file
SHA-256, transform, seed coordinates and sparse visual checkpoints.
No downloaded video, sampled RGB, masks or rendered contact sheets
are committed. The videos are separate independent choreographies,
not synchronized performances of the same dance: this is a cutout
identity/quality stress pair, not a music-alignment benchmark.

- Reference: [The guys break dancing in the street near the mall](https://commons.wikimedia.org/wiki/File:The_guys_break_dancing_in_the_street_near_the_mall.webm),
  KazanCity1, CC BY 3.0, original source; continuous source seconds
  37–67. Selected black-clad active dancer; foreground audience
  obstruction, inversion, floor work and spectators.
- Target: [VOGUE DANCE SHOW - GOOD FOOT STUDIO 2016](https://commons.wikimedia.org/wiki/File:VOGUE_DANCE_SHOW_-_GOOD_FOOT_STUDIO_2016.webm),
  Good Foot Dance Studio, CC BY 3.0, Wikimedia 720p VP9 transcode;
  continuous source seconds 48–78. Selected left dancer at 3s;
  dark stage, small subjects, floor work and illumination changes.
- Re-encode each to display-oriented 768x432 H.264/yuv420p,
  25fps, 30.000s, 750 frames, mute original audio. Cadence normalization
  may drop or duplicate original samples; no intentional clip repetition.
  Anchor at 3s, reference (.42,.29), target (.46,.56).
- A downloaded aerial cultural-dance clip was screened out because
  subject scale/viewpoint was not representative of practice. It is
  not counted in the two evaluated lanes.

### Native evidence (same pinned YOLO model, unchanged tracker)

| Metric | Android CPU4 | Android GPU | iOS simulator XNNPACK |
| --- | ---: | ---: | ---: |
| Full analysis | 181.587s | 115.884s | 104.872s |
| Initial neighborhood / playable probe | 7.756s | 4.803s | 3.814s |
| Breaking frames with output alpha | 409/750 | 259/750 | 472/750 |
| Vogue frames with output alpha | 146/750 | 151/750 | 114/750 |
| Breaking longest missing interval | 6.040s | 9.760s | not measured |
| Vogue longest missing interval | 19.680s | 18.960s | not measured |
| Breaking selected body point hits (10 visual samples) | 3 | 3 | not measured |
| Breaking distractor point hits (10 visual samples) | 1 | 2 | not measured |
| Vogue selected body point hits (10 visual samples) | 5 | 5 | not measured |

The Android rows are the same instrumented native B2 analyzer on
one OnePlus 8T, with GPU followed by CPU4. The iOS row is the actual
player/bridge/texture path on the iPhone 15 Pro simulator, not a
physical device. Timing scopes are not identical across OSes. The
Android battery sensor moved from about 26.3 C to 32.5 C during the
run; no thermal steady-state claim. GPU completed 1.57x faster
in this one trial. The device instrumentation passed as a diagnostic,
not as a release quality gate.

For Breaking the two Android backends jointly output 220 samples
(mean binary alpha IoU 0.996136), but 189 CPU-only and 39 GPU-only
samples disagree on availability. For Vogue the joint-visible IoU
is 0.994962 on 144 samples; two CPU-only and seven GPU-only samples.
A high IoU **conditional on both having a mask** is not identity
validation: both can select the same wrong person. The actual
Android mask at Breaking 18s covers the white-shirt spectator on
both CPU and GPU; GPU also does so at 20s. The black-clad dancer is
visually present in the source. These are observed incorrect-person
outputs, not merely missing masks. Review of the source and raw
host detection at those checkpoints gives distinct current
selected candidates at confidence 0.831 and 0.751; the host
decoder/runtime differs from the mobile backends, so this is
supporting diagnosis rather than proof of the device's exact raw
tensor result. At 10s, 21s, 26s and 27s the selected point also
belongs to a distinct raw host detection, while the GPU tracker
has no output. Vogue selected detections at 13s, 17s, 24s and
27s likewise exist in host observations while the native GPU
has no output. Missing detections and ambiguous touches do
also occur, particularly in low light and inversion. Do not
attribute every loss to tracking or count missing masks as
correct identity.

The real iOS player completed 750/750 source PTS indexing per role,
published the initial window, sought sampled frames, refused both
incomplete export and archive save, and restored ordinary playback.
No successful export is claimed for this incomplete real pair.

### Actual same-observation algorithm experiment

`experiment_cutout_appearance.py` is an explicitly **host-only**
prototype, independent of the privacy tracker. It compares current
FrameKernel association and an experimental selected-instance lane
on the same raw detector observations with the pinned model,
forward/backward seed, masked color distribution, local motion,
and competing-candidate margin. It never outputs a stale mask,
does not use facial recognition, and does not change any mobile
runtime or default threshold.

| Host experiment metric | Current FrameKernel | Appearance prototype |
| --- | ---: | ---: |
| Breaking output frames | 170/750 | 284/750 |
| Breaking selected point hits | 2/10 | 4/10 |
| Breaking longest missing span | 14.240s | 18.440s |
| Vogue output frames | 62/750 | 652/750 |
| Vogue selected point hits | 2/10 | 10/10 |
| Vogue longest missing span | 23.000s | 2.680s |

All prototype point checks of the ten manually sampled distractor
locations in each lane were negative. Sparse point annotations are
approximate visual evidence, not dense masks, ground-truth tracking,
or proof of no switches. The prototype's Breaking overlap with
legacy is often low and the long missing span worsens; higher output
counts cannot alone justify promotion. Host decoding/model numerics
differ from Android/iOS, so compare the two host columns with each
other, not host values directly with device values. Vogue improvement
demonstrates the need for additional identity evidence, not a ready
algorithm. No threshold fitting or production fallback policy is
approved on these two clips alone.

Next work: separate the cutout-specific association policy from the
privacy protection tracker, add appearance/temporal evidence with
stable identity ownership across rapid pose changes, and provide
an explicit user-confirmed re-anchor/local reanalysis workflow for
ambiguous gaps. Evaluate on independent hold-out choreography and
dense manually verified identity/edge samples; tune only after
checking mistaken associations. The currently recorded wrong-person
output remains a product blocker. No production runtime change was
made in B5.

### Reproduce

Download the exact resources recorded in the manifest into a
local QA directory, check downloaded SHA-256, then:
```sh
ffmpeg -v error -y -ss 37 -i breakdance.webm -t 30 -map 0:v:0 \
  -vf 'fps=25,scale=768:432,setsar=1' -an -c:v libx264 -preset fast \
  -crf 20 -pix_fmt yuv420p -movflags +faststart break30.mp4
ffmpeg -v error -y -ss 48 -i vogue.webm -t 30 \
  -vf 'fps=25,scale=768:432,setsar=1' -an -c:v libx264 -preset fast \
  -crf 20 -pix_fmt yuv420p -movflags +faststart vogue30.mp4
```
Verify 750 frames and exact 30s with ffprobe. Transcoded bytes may
depend on FFmpeg version. Build the isolated instrumentation APK
as in B3. Stage the two MP4s as reference.mp4 / target.mp4 in
the test package's files/comparison-real-dance-qa directory.
```sh
adb shell am instrument -w -r \
  -e class art.gaoge.dance.engine.comparison.ComparisonCutoutBackendInstrumentedTest#realDanceQuality \
  -e realDanceQa true -e limitUs 30000000 -e anchorUs 3000000 \
  -e referenceX .42 -e referenceY .29 -e targetX .46 -e targetY .56 \
  art.gaoge.dance.engine.cutoutqa.test/androidx.test.runner.AndroidJUnitRunner
```
Pull report.json and the four *.alpha files from that explicit QA
directory. These bounded binary records are test-only selected
alpha evidence; remove them after review. Never run this evidence
mode on unrelated personal sources. Use:
```sh
python tools/comparison/review_real_dance.py /path/to/evidence \
  --reference break30.mp4 --target vogue30.mp4 \
  --annotations testdata/comparison/real-dance-b5.json --output /tmp/dance-review
python tools/comparison/experiment_cutout_appearance.py \
  --reference break30.mp4 --target vogue30.mp4 \
  --annotations testdata/comparison/real-dance-b5.json \
  --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --output /tmp/dance-appearance
```
Add --probe-only to the experiment for raw detection availability
at the sparse checkpoints. Build the existing iOS simulator smoke
entry with WOAH_CUTOUT_REAL_QA=true and stage real-reference.mp4 /
real-target.mp4 in its QA Documents folder. The iOS report explicitly
checks the incomplete export/cache rejection rather than asserting
all frames have an alpha. Do not commit local evidence media.

## Stage C1: independent dance identity association (2026-11-26)

The B5 failure exposed a mismatch between privacy coverage tracking
and selected-subject extraction. The experimental comparison flow now
uses a dedicated FrameKernel `DanceCutout` lane and a separate C
handle registry. The legacy tracking ABI and all privacy tracking
policies remain unchanged. Both mobile adapters call the same new
Rust implementation.

- Mask-scoped 29-bin visible-color histogram from the *actual* model
  input, using only reliable mask pixels inside the letterbox content.
  Android bottom-up RGBA and iOS top-down normalized RGB have a shared
  feature implementation and a transport-parity test. No face embedding,
  named identity recognition, new inference model or stored RGB is used.
- Retain the immutable selected anchor and initial competing-person
  color descriptors. Learn up to 12 positive exemplars only from
  close, strong, sufficiently distinct observations; keep a slowly
  adapting recent descriptor. Reject a candidate contradicted by
  the anchor competitors independently of its old-box overlap.
- Combine appearance, box and current-mask overlap, local velocity
  and candidate competition. Use two consecutive current observations
  for reacquisition after gaps over 160ms; stop automatic reacquisition
  after four seconds without a supported observation. Never render
  a predicted, cached previous-frame or missing mask. This is a
  development policy, not validated general-purpose re-identification.
- Preserve exact source PTS and one independent forward/reverse lane
  per selection. The reverse scratch record adds 29 floats per
  detection (116 bytes); no raw color frames are retained in the spool.
  The archive compatibility key changes to dance-association-v2 so
  previously analyzed alpha cannot bypass the new algorithm.
- Existing incomplete archive and export refusal remains. No GPU
  default promotion or removal of the experimental Debug restriction.

Same-observation host comparison is reproducible using
`tools/comparison/compare_dance_identity.py`, which verifies the fixture
SHA-256 and calls both the legacy and new shared C association on
identical detector observations. The thresholds were developed using
the two B5 clips, so these results are development-set evidence, not
a held-out generalization test.

| Host shared implementation | Legacy | C1 |
| --- | ---: | ---: |
| Breaking output samples | 170/750 | 648/750 |
| Breaking selected point checks | 2/10 | 9/10 |
| Breaking longest missing run | 14.240s | 1.000s |
| Vogue output samples | 62/750 | 563/750 |
| Vogue selected point checks | 2/10 | 9/10 |
| Vogue longest missing run | 23.000s | 2.520s |

On the original one-second source and mirror derivative with the
host-valid first-frame seed, both legacy and C1 retained 30/30 per
role. The 400ms mirrored host touch was not a valid strong detector
seed; the short comparison was rerun at 0 rather than changing the
selection threshold.

Real device results below use the same independent 30-second B5
clips and 750 source samples per role, not artificially repeated
videos. These are **output-mask counts, not correct-identity counts**.
The Android GPU and CPU now both correctly cover the selected dancer
at the prior explicit Breaking 18s and 20s wrong-person checks.
However inspection of the new selected-mask overlay revealed another
incorrect association to the white-shirt spectator at 27s on both
Android backends. The original 27s distractor point was on a different
spectator; an additional point at (.36,.25) is now tracked in the
manifest and reported separately from the original ten point checks.
This is a remaining identity failure, not a passed release gate.
The new approach also cannot recover all low-light/fast-pose detector
misses or certify edge quality. iOS real-scene output is verified via
the player/texture path, not dense mask identity annotation.

Reproduce the host comparison:
```sh
python tools/comparison/compare_dance_identity.py \
  --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --manifest testdata/comparison/real-dance-b5.json \
  --media /path/to/exact/tested/mp4s --output /tmp/dance-identity-c1 --sheets
```
Repeat the existing B5 Android instrumentation and iOS real-scene
smoke with the new build. Raw QA masks and contact sheets stay local.

### C1 native measurements

| Backend | Breaking B5 -> C1 (750) | Vogue B5 -> C1 (750) | Full analysis B5 -> C1 |
| --- | ---: | ---: | ---: |
| Android CPU4 | 409 -> 668 | 146 -> 550 | 181.587 -> 212.572s |
| Android GPU (QA) | 259 -> 651 | 151 -> 541 | 115.884 -> 168.673s |
| iOS XNNPACK simulator | 472 -> 554 | 114 -> 572 | 104.872 -> 114.702s |

Android longest missing runs (Breaking / Vogue): CPU 6.040 / 19.680s
-> 0.360 / 2.480s; GPU 9.760 / 18.960s -> 1.200 / 2.480s.
The original ten selected-point checks hit 7/10 (Breaking) and 8/10
(Vogue) on each Android backend; the original distractor points hit
0/10 on each clip, but the supplemental Breaking 27s spectator check
fails on both backends. CPU/GPU visibility disagreements on Breaking
fell from 228 to 25; jointly visible mean IoU is 0.99086 (minimum
0.39529). Vogue has nine visibility disagreements and conditional
mean IoU 0.99307. These are not proof of identity correctness.
The full analysis timings increased, with more emitted masks and
appearance processing; runs were not thermally controlled. No speed
improvement is claimed. C1 iOS native smoke also confirmed incomplete
archive/export refusal and ordinary playback restoration; it did not
produce a completed C1 export of either incomplete real-dance clip.

Validation: full Rust suite 142 passed, 1 ignored; five focused C1
tests passed including the additional RGBA/planar transport parity test;
Clippy all-targets clean; Android focused 27 passed and native real-scene
instrumentation passed; iOS simulator build and real-scene smoke passed;
Python scripts compile. Fixed the existing handwritten Pigeon schema
Dart formatting failure and checked all handwritten Dart formatting.

Disposition: keep C1 experimental and PR draft. Next identity stage
should preserve competing-person ownership over time and provide a
user-confirmed local re-anchor/repair path, then evaluate held-out
multi-person dance footage with dense identity/edge annotations.
Do not promote GPU or remove incomplete-analysis refusal based on
output-mask counts.

## Stage C2: converge selected-subject association on the shared Tracker

C1 demonstrated useful appearance evidence but created an independent selected
identity state machine, velocity estimator, raw-mask IoU and session registry.
C2 removes that architectural duplication. The comparison-specific adapter
now holds the seed, monotonic source-PTS direction and unconfirmed recovery
candidate; it delegates to the existing `Tracker` and common `Track` objects.
The appearance module is independent of the caller. Each track can carry an
optional appearance memory. The privacy profile does not set this memory and
its existing geometric association, occlusion-group, coverage and prediction
policy are not changed.

- Reuses Track IDs, initialization, canonical Kalman/XYAH prediction,
  bound_prediction, raw-mask overlap, one-to-one assignment, reciprocal
  ranking and observation commit. The prior dedicated dance identity struct
  and exponential velocity predictor are removed.
- The cutout association profile maintains competing people as ordinary Track
  IDs, uses strong reciprocal ownership to reserve their current instances,
  and only then considers the selected row. A selected candidate still
  requires appearance and geometry support and the existing two-observation
  recovery policy. No previous/predicted render mask is emitted by cutout.
  This uses the existing assignment/track primitives, not the privacy
  occlusion-class policy. Appearance is not enabled in privacy tracking.
- Both C ABI modes now share the same bounded session registry; cross-mode
  tracking calls and destruction are rejected. Old mobile-only JNI/Kotlin
  adapters for the pre-C1 legacy cutout association are removed. The old
  reference C ABI is retained only for host A/B and its existing Rust
  regression cases; it is not a second runtime used by the canvas.
- B4 persisted alpha archives use a new shared-identity-v3 compatibility
  key; pre-C2 results cannot bypass the changed association profile.
  No appearance descriptor is added to the durable archive.

The additional regression with two same-appearance people verifies that a
continuous competing owner retains its ID when detection order changes and
it moves into the selected person's previous location. While the selected
person is missing the output is unavailable rather than the competitor's mask.

### Same-observation host comparison

Use the previous C1 release dylib built from c420f2ff and the current release
dylib. The script decodes/segments each sample once and passes **identical
current** appearance and detector observations to both implementations:
```sh
python tools/comparison/compare_dance_identity.py \
  --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --baseline-library /path/to/c1/libframe_kernel.dylib \
  --manifest testdata/comparison/real-dance-b5.json \
  --media /path/to/exact/tested/mp4s --output /tmp/dance-identity-c2 --sheets
```
Report records both binary hashes. The two development fixtures are not
independent holdout evidence. With the prior C1 binary and C2, Breaking
648/750 -> 648/750 current masks, longest gap 1.000 -> 0.640s, 9/10
selected-point hits unchanged; Vogue 563/750 -> 556/750, longest gap
2.520 -> 2.680s, 9/10 hits unchanged. The original short source and mirror
pair each retained 30/30 masks at the valid first-frame anchor. These
metrics are output availability and sparse visual checks, not identity
ground truth. User-confirmed local re-anchor remains a follow-up.

### Native C2 observations

| Backend | C1 Breaking -> C2 | C1 Vogue -> C2 |
| --- | ---: | ---: |
| Android GPU QA | 651 -> 632/750 | 541 -> 532/750 |
| iOS simulator XNNPACK | 554 -> 585/750 | 572 -> 593/750 |

Android GPU analysis 171.339s (C1 168.673s), longest missing span
0.720s / 2.640s. Breaking selected-point hits 8/10 (C1 7/10),
Vogue 8/10 (unchanged). The earlier wrong-person checkpoints at
18s, 20s and the supplemental 27s check now hit the selected dancer
without hitting the annotated spectator on GPU. The new conservative
competing-owner policy rejects some formerly emitted masks; not every
lost or accepted sample has ground-truth identity annotation. iOS
analysis 115.686s, actual partial preview and complete analysis, and
refusal to export an incomplete canvas all passed.

Rust full regression: 144 passed, 1 ignored; Clippy all targets passed.
Android focused 27 tests passed. Four Android native reference corpus
tests (JNI session, state/mask prediction, full tracking transitions
and scene motion) passed against the frozen references. The previous
iOS CI deployment lockfile mismatch was traced to the podspec's Rust
source-file list; the Podfile.lock checksum was updated without changing
dependency versions and local pod install --deployment passed.

The independent development clips still have gaps, and neither
continuous identity ground truth nor independent held-out quality
acceptance has been established. Keep GPU opt-in and draft/Debug
restrictions. C2 is a shared-core migration, not a release acceptance.

### C2 follow-up: completed CPU evidence

The previously outstanding Android CPU instrumented run also finished:
659/750 Breaking and 533/750 Vogue output masks, 227.266s full analysis.
Longest missing runs 0.320s / 2.560s, selected-point checks 8/10 each,
original distractor hits 0/10 each. The 18s, 20s and supplemental 27s
Breaking checkpoints hit the selected dancer, and the annotated white-shirt
spectator was rejected on **both** CPU and GPU. This does not establish
correct identity throughout all frames.

## Stage C3: explicit local subject correction

The completed experimental canvas exposes a local repair action next to
the canvas editor/export actions. Pause/seek the canvas to the frame where
the user can confidently identify the desired subject; select reference
or target and a comparison-time interval containing that position. The
native player temporarily renders the original pair, preserving the
existing cache; only the chosen role accepts long-press selection. The
user inspects the extracted still and explicitly confirms before inference.

The first implementation allows a single role and interval (at most 10s
and 600 source samples). Native translates the interval through the same
affine music mapping and enumerates the exact source PTS, including the
predecessor sample covering the left boundary if it falls between frames.
This is frame-boundary replacement, not a sub-frame splice.
The existing shared Tracker runs with the newly confirmed anchor only
in that interval; no automatic reseeding or third tracking implementation.
The original geometry anchor remains fixed to avoid resizing/repositioning
at the splice; if the initial global selection is wrong, use clear/reselect.
After inference, replace the interval's exact PTS records in a new
anonymous bounded store, leaving the other role and outside records
unchanged. Missing new observations replace old ones explicitly, not
via stale-mask fallback. The original seed frame must still be present
for cache sealing. Reconstruction copies the selected role's cache
records but does not rerun inference outside the repair interval.

The old cache remains available until the replacement is validated.
Validate the subset of exact PTS, dimensions, source fingerprint and
record counts, and iOS source stamp agreement. Recheck both source
fingerprints before publication. Only the latest valid session may
publish. During correction, ordinary playback seek/step, resize, canvas
editing, archive save and cutout export are blocked. Cancel before
publication returns the old cache; the native commit is atomic, so a
cancel after commit returns the new canvas. Failure leaves the original
cache available. The old durable archive is invalidated before publishing
the patch (a cancellation racing that point may require saving again);
the controller saves a new archive only when both tracks are complete.
No separate correction history/undo or portable correction recipe is
persisted. Any remaining missing frames continue to block strict export.
No privacy tracker or inference backend policy changed.

### Device evidence

Both native Debug apps ran `WOAH_CUTOUT_REPAIR_QA=true`:

| Metric | OnePlus 8T Android 14 | iOS 17 simulator |
| --- | ---: | ---: |
| Source window | [6,9)s, 90 frames per role | [6,9)s, 90 frames per role |
| Repaired reference range | [7.6,8.4)s, 24 frames | [7.6,8.4)s, 24 frames |
| Repair incl. inspection/cache publication | 5.254s | 2.321s |
| Repaired full-window coverage | 90/90 + 90/90 | 90/90 + 90/90 |
| Export elapsed after reopening archive | 4.100s | 1.450s |
| Output | 320x240, 90 H.264 frames, 3.000s | 320x240, 90 H.264 frames, 3.000s |

Cancellation before applying was pixel-identical; sampled outside patch
frames at 7.0s and 8.8s are pixel-identical before/after, and reopening
the saved patched cache preserves the seam sample. Independent FFmpeg
verification of the actual exported files confirmed 90 frame timestamps,
440 Hz reference tone, 3.000s duration and preview/export MAE 0.744–0.809
(Android) / 0.937–1.039 (iOS). Android published MediaStore bytes equal
the local MP4. iOS export is simulator local-file evidence, not Photos.

The second probe used the independent real B5 clips at [2,5)s, seed 3s.
It intentionally selected a red-shirt spectator for [3.8,4.24)s, then
selected the original black-clad dancer at 4s with an explicit fresh
anchor. Both platforms produced a different wrong-person image and then
the corrected anchor exactly matched the original correct anchor; the
sample outside patch at 3s remained byte-identical throughout. Reference
75/75, target 27/75 Android and 30/75 iOS remained unchanged after
correction. This proves a deliberate local replacement, not autonomous
recovery from every real tracking failure; the real pair is incomplete
and no full real-pair export is claimed.

Flutter comparison suite 139 passed including two new controller tests;
the targeted actual-widget repair interaction passed, including explicit
confirmation and no pair reanalysis. Android focused JNI/JVM 28 passed
including exact patch/cancel/other-role/placement invariants. macOS
comparison host regression passed including patch roundtrip and cancellation.
Pigeon regeneration reproducible and scoped Flutter analyzer clean.
Physical iPhone, long independent holdouts, dense identity/edge quality,
automatic gap navigation and post-commit undo remain outstanding.

Reproduce with the prior QA fixture staging plus the two B5 sources named
real-reference.mp4 / real-target.mp4. Build the existing cutout smoke
entry with `--dart-define=WOAH_CUTOUT_REPAIR_QA=true`. Pull only its
repair-* QA outputs and, on Android, the published file as repair-published.mp4.
```sh
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform android --repair
python3 tools/comparison/verify_cutout_export.py /path/to/results --platform ios --repair
```
No source videos or screenshots committed. The QA exported video remains
in the isolated test app/MediaStore. No existing user media was modified.

The real Flutter gesture smoke passed on both platforms after reentry
from the saved canvas: range dialog, correct role's drag, explicit
repair confirmation, preview restored, a second repair cancelled, then
ordinary exit and explicit clear/reselect. The controller test also
covers a late correction reply after cancellation; actual device
cancel was exercised before applying, not during active model inference.

## Stage C4: performance and alpha projection fidelity

The shared tracking policy, raw model, source sample cadence, association
thresholds and privacy output policy are unchanged. C4 optimizes the actual
math: exact raw-mask IoU via high-bit word popcount (same >=128 and
empty-union semantics), cheap necessary gates before walking full masks,
reuse of the appearance bank score, and mask-scoped lazy color bins.
Alpha projection precomputes its separable coordinate mapping and handles
fully transparent/opaque neighborhoods without repeating bilinear math.
At the same output grid the numerical result is unchanged.

Android GPU comparison analysis additionally overlaps one next-frame
decode/conversion with the current inference and association using two
caller-owned 640x640 RGBA slots. The producer owns the decoder on one
thread; the consumer processes exact PTS in order and releases a slot
before it can be reused. The producer closes the decoder before the
span returns. No concurrent model use, skipped samples, or new
privacy-path decoder behavior is introduced. CPU prefetch is disabled
in the final policy after the measured contention regression; CPU retains
serial decode, GPU remains isolated QA opt-in.

Both platforms choose a shared display-oriented alpha grid (maximum
long edge 384, then 320 or 256) according to worst-case source frame
count and initial preview sample count. Each role remains within its
256 MiB scratch budget including a conservative 3200 byte per-frame
stamp/record reserve. The initial alpha payload budget is 18 MiB per
role including that reserve. At 384 the raw alpha footprint is about
2.25x the old 256 grid; two 32-frame LRU chunks are still bounded.
Local repair uses the exact existing grid so it can replace records
without resampling their neighbors. The archive compatibility key
includes alpha-grid-v2; older alpha archives are invalidated rather
than silently mixing grids. No learned matte or RGB-guided foreground
estimation was introduced.

### Same-observation and projection evidence

Baseline library built from C2/C3 unchanged core, candidate C4,
same pinned model and the two B5 30s source clips. Five alternating
host trials, all raw selected instance indices (including -1)
identical for all 1500 source samples. On every selected sample,
optimized fixed-grid alpha was byte-exact at the original 256 grid.

| Metric | Breaking | Vogue |
| --- | ---: | ---: |
| Selected indices identical | 750/750 | 750/750 |
| Fixed-grid alpha exact | 648/648 | 556/556 |
| Tracker median baseline | 424.428ms | 49.522ms |
| Tracker median C4 | 170.733ms | 29.290ms |
| Alpha generation baseline 256 (all selected) | 146.816ms | 193.368ms |
| Alpha generation C4 256 | 29.520ms | 31.960ms |
| Alpha generation C4 384 | 61.514ms | 66.701ms |
| Dense-reference edge samples | 82 | 68 |
| Edge MAE 256 vs dense 1024 reference | 13.900 | 20.985 |
| Edge MAE 384 vs dense 1024 reference | 6.776 | 10.749 |

The dense projection uses the same raw detector mask and confidence-to-alpha
mapping. The metric measures interpolation fidelity on the union of
non-binary edge regions; it is not human-annotated matte accuracy.
The source model's 160x160 prototype grid is unchanged. No evidence
of recovered undetected fingers, hair, or occluded limbs is claimed.

Reproduce the host run with the two exact B5 files and the saved prior
release library:
```sh
python tools/comparison/benchmark_cutout_core.py \
  --baseline-library /path/to/c3/libframe_kernel.dylib \
  --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --manifest testdata/comparison/real-dance-b5.json \
  --media /path/to/exact/tested/mp4s --output /tmp/c4-core.json
```

### Android paired device benchmark

OnePlus 8T, 6-second continuous initial segment of each B5 clip,
150 frames per role at 25fps, anchor 3s, actual GPU then CPU4. All
300 availability flags identical between the old grid and the
new grid on each backend. Both candidate grids were 384x216
(82,944 bytes/sample). At a common 768x432 interpolation grid, mean
threshold-128 IoU old/new was 0.99013/0.96327 (GPU Breaking/Vogue)
and 0.98959/0.96248 (CPU Breaking/Vogue); grid changes alter boundary
values even with equal source identity. The host same-observation
comparison above is the exact identity-index check.

| Analysis metric | C3 serial 256 | C4 trial prefetch 384 |
| --- | ---: | ---: |
| GPU full analysis | 26.120s | 21.178s |
| GPU initial neighborhood | 5.835s | 4.844s |
| CPU4 full analysis | 38.364s | 42.320s |
| CPU4 initial neighborhood | 7.742s | 8.684s |

GPU per-role summed tracking stage 271 -> 99ms, projection
385 -> 97ms despite the larger grid, appearance observation construction
616 -> 262ms. The GPU's total decoder conversion service time rose
to 10.126s under overlap, but consumer wait for decode was 2.603s
(the old serial decode+conversion was 7.666s). These timings overlap
and must not be summed as wall time. CPU prefetch regressed in this
single device trial and is not the final CPU policy. Battery sensor
baseline start/end 27.7/29.1 C, trial start/end 28.1/29.5 C; these are
not thermally controlled repeated end-to-end trials. The measured GPU
gain applies to this device and input only; no cross-device promise.

Final CPU serial 384-grid retest: 47.853s full analysis, 8.778s
initial neighborhood, 143/150 Breaking and 42/150 Vogue visible.
All 300 availability flags still agree with the serial 256 baseline.
Device sensor 30.7 -> 31.4 C after preceding QA. This run is slower,
not evidence of CPU end-to-end acceleration. The CPU profile retains
its original serial decoder policy rather than adopting CPU prefetch;
the exact host core speedup is independently measured. Thermal control
and CPU model/decoder throughput remain unresolved.

### Integration and regression

Actual Android CPU app (final serial policy) and iOS simulator completed
the prior C3 repair/restore/export smoke at the new adaptive grid.
Android initial-preview cancellation passed (late result rejected,
ordinary playback restored, reopen clean); Android repair 4.974s,
iOS repair 2.403s. Both 3-second, 90-frame 320x240 H.264/AAC outputs
independently decoded with exact PTS and 440Hz tone; unchanged outside
samples, repair cancel, repaired archive restore, and distinct
real-person replacement all passed. Android MediaStore file bytes
matched the local MP4. iOS local file only. GPU prefetch mid-inference
cancellation was not independently repeated in C4. Rust full suite
147 passed/1 ignored plus two focused exactness tests passed, Clippy
clean, Android 28 focused + 3 privacy reference regressions passed,
macOS host comparison passed, Flutter smoke analyzer clean. No raw
media or masks were committed. Retain experimental Debug/draft and
GPU opt-in; independent dense identity/edge holdouts and physical
iPhone release evidence remain outstanding.

## Stage C5: frozen independent real-dance holdout (2026-11-26)

**Decision: C5 ran to completion; release quality is NOT accepted.**
Frozen runtime/core commit `2a954b08cb1ba0bbb2ee15fa7f90ccb3f48a4ad0`;
no detector weights, confidence thresholds, association policy or
production rendering changes in this stage. The new files are QA
configuration, visual annotations and local evaluation tools.

### Independent source fixtures and predeclared checks

`testdata/comparison/real-dance-c5-holdout.json` records the original
resource URL, license, attribution, exact download and test MP4 hashes,
continuous source interval, normalized seed and visual annotations.

- **Baganda traditional dance 01**, Kateregga1, CC BY-SA 4.0:
  https://commons.wikimedia.org/wiki/File:Baganda_traditional_dance_01.webm .
  Original seconds [12,24), selected foreground striped-costume dancer
  with white waist sash; similar orange-sash dancer nearby, drums and
  other foreground/background people. Rapid turn/garment changes.
- **Parejas bailando Danzon**, Koffermejia, CC0 1.0:
  https://commons.wikimedia.org/wiki/File:Parejas_bailando_Danzon.webm .
  Wikimedia 720p VP9 transcode, source [4,16), selected near-camera
  white-dress dancer with dark waist belt; close embrace, rotating
  partner, foreground passer-by and multiple similar white outfits.

Each fixture is a single continuous 12s segment, 960x540 H.264 at
25fps, 300 decoded PTS per role; cadence normalization can drop or
duplicate source samples but no intentional repetition. Audio removed.
Anchor at 3.0s; reference seed (.565,.47), target (.413,.50).
These different choreographies test identity/cutout, not musical
alignment. Before reviewing output masks, independently inspect the
unoverlaid source images and freeze 12 selected-person interior points
and 12 different-person points per role, plus 4 manually approximated
visible silhouettes per role at 0/3/6/9s on a 640x360 labeling grid.
Manifest SHA-256 d87ff0d57c3322386c664faee09ffb91fcd9968619a7039ea79e5d438e4769e5.
No ground-truth claim for fine edges, occluded limbs, or every frame.
No source videos, derived alpha or screenshots are committed. If
redistributing the Baganda derived video/visuals, retain its CC BY-SA
attribution and license.

### Actual native holdout results

| Metric | Android CPU4 | Android effective GPU (QA) | iOS 17 simulator |
| --- | ---: | ---: | ---: |
| Baganda current alpha output | 231/300 | 227/300 | 231/300 |
| Danzon current alpha output | 300/300 | 300/300 | 300/300 |
| Full analysis | 92.580s | 57.105s | 44.310s |
| Initial neighborhood / playable | 9.421s | 7.369s | 4.128s |
| Baganda longest missing interval | 0.760s | 0.840s | not measured |
| Baganda missing intervals | 13 | 13 | not measured |
| Baganda selected point hits | 8/12 | 8/12 | visually sampled |
| Danzon selected point hits | 12/12 | 12/12 | visually sampled |
| Annotated distractor hits (both roles) | 0/24 | 0/24 | visually sampled |

OnePlus 8T Android 14, GPU then CPU4, battery sensor 28.8 -> 31.4 C.
Actual GPU backend, no fallback. Not a thermally controlled repeated
performance test. All 300 source PTS in each lane were read back and
checked against the 25fps index. Danzon has no unavailable masks;
the CPU/GPU joint-mask IoU averages 0.99647, minimum 0.93710.
Baganda: 227 joint-visible, 4 CPU-only, 0 GPU-only, joint mean IoU
0.98948, minimum 0.74230; two joint masks below 0.90. Availability
and conditional IoU do not establish identity correctness.

iOS real app player/texture path inspected both subjects, published
the initial window, processed all 600 indexed samples, captured 24
exact PTS canvas checkpoints at roughly 0.5s spacing, refused incomplete
archive/export, and restored ordinary playback. Visual review confirms
the near-camera white-belt dancer remains selected in those samples;
Baganda intermittently disappears, and the skirt/body sometimes has
holes or partial limbs. The partner's hand/arm can remain near the
selected white-dress shoulder. No iOS raw-alpha metric or successful
full independent-pair export is claimed. No physical iPhone.

### Approximate visible-contour quality (Android)

Threshold the actual alpha at 128 after interpolation to the manual
640x360 annotation grid. IoU with a manually drawn approximate visible
silhouette, and boundary F1 with a 4-pixel tolerance, per sample.
Missing alpha is zero; report all and available samples separately.
Do not treat the contours as dense professional segmentation labels.

| Scene/metric | CPU4 | GPU |
| --- | ---: | ---: |
| Baganda contour IoU all 4 (2 missing) | 0.35869 | 0.35807 |
| Baganda contour IoU 2 available | 0.71739 | 0.71615 |
| Baganda boundary F1 2 available | 0.59496 | 0.59431 |
| Danzon contour IoU all 4 | 0.80748 | 0.80791 |
| Danzon boundary F1 all 4 | 0.66681 | 0.66857 |

At Baganda 6/7/8/9s both native backends have no selected alpha
at the annotated visible point, despite the subject still being
visually present in the raw video. At 0s the CPU visible contour
IoU is about 0.593, at 3s about 0.842. The sample counts are small,
so neither "zero wrong people" nor complete real-world edge quality
is established. The remaining substantial loss includes raw
segmentation failures and partially missing silhouettes.

### Post-freeze diagnosis: source scale vs association

The host independent raw-model probe uses the SAME pinned YOLO model,
unchanged .25 seed/.20 nonseed thresholds, and the exact test MP4
samples. This is independent of native runtime tensors. For Baganda,
at 6/7/8/9s the annotated selected point has no raw candidate even
at .20. At 6s and 9s the best raw instance contour IoU is respectively
0.00081 and 0.0. Thus these failures cannot be repaired by accepting
a different track ID or merely changing the tracker threshold.

After recording frozen C5 outcomes, run a single exploratory fixed
source ROI (x=336,y=92,w=384,h=421 at 960x540) containing the chosen
dancer around the original anchor, with the same model, thresholds,
and reverse mapping into original source coordinates. The fixed ROI
is manually specified, not automatic production localization. It
recovers all four failed selected-point checkpoints at 6–9s, confidence
0.777/0.495/0.706/0.857. At 6s/9s, best raw contour IoU rises to
0.892/0.870. The crop does miss the previously valid 2s point, so do
not replace the full-frame observation with a crop unconditionally.
This is a diagnostic hypothesis test on a now-seen fixture, not
a new held-out result or production fix.

Next engineering work should improve selected-subject observations
when full-frame masks are missing/fragmentary, using bounded
source-space detail ROI and current-mask remapping, shared model and
FrameKernel, competing-person checks and reliable full-frame fallback.
Do not infer identity from a crop position alone or extrapolate stale
masks. Measure added model invocations and latency before adoption,
then repeat on a fresh independent holdout. GPU default promotion and
release remain blocked; no reason to add a second tracker or tune this
holdout until it passes.

### Reproduce

Download the exact resources and check hashes from the manifest.
```sh
ffmpeg -v error -y -ss 12 -i baganda.webm -t 12 -map 0:v:0 \
  -vf 'fps=25,scale=960:540,setsar=1' -an -c:v libx264 -preset fast \
  -crf 19 -pix_fmt yuv420p -movflags +faststart baganda12.mp4
ffmpeg -v error -y -ss 4 -i danzon.webm -t 12 -map 0:v:0 \
  -vf 'fps=25,scale=960:540,setsar=1' -an -c:v libx264 -preset fast \
  -crf 19 -pix_fmt yuv420p -movflags +faststart danzon12.mp4
```
Stage them as reference.mp4 / target.mp4 in the isolated Android
instrumentation package's files/comparison-real-dance-qa directory.
Use the C4 instrumentation with `-e limitUs 12000000 -e anchorUs 3000000
-e referenceX .565 -e referenceY .47 -e targetX .413 -e targetY .50`.
Pull report.json and four alpha records into the local evidence directory.
```sh
python tools/comparison/review_holdout.py /path/to/evidence \
  --manifest testdata/comparison/real-dance-c5-holdout.json \
  --media /path/to/mp4s --output /tmp/c5-review
python tools/comparison/probe_holdout_observations.py \
  --manifest testdata/comparison/real-dance-c5-holdout.json \
  --media /path/to/mp4s --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --output /tmp/c5-raw.json --reference-roi 336 92 384 421
```
The iOS QA Dart driver `WOAH_CUTOUT_REAL_QA=true` now accepts
optional real-dance-config.json in the fixture directory. Stage
real-reference.mp4 / real-target.mp4 and use configuration
durationUs=12000000, cadence=25, anchorUs=3000000,
referenceSeed=[.565,.47], targetSeed=[.413,.50],
samplesUs = floor(i*500000/40000)*40000 for i=0..23.
Without config, existing B5 behavior remains. Capture names include
exact microseconds for a configured run. Actual Android and iOS
instrumentation/QA finished successfully, which is evidence execution,
not release-quality acceptance. Scoped Dart analyzer and Python syntax
checks passed.

## Stage C6: bounded adaptive current-frame detail recovery

C5 identified a detector-observation bottleneck, not a missing tracker.
C6 adds an adaptive source ROI on the shared FrameKernel Tracker.
No new model, second persistent tracker, or changed privacy policy.

### Algorithm and ownership contract

- Full-frame YOLO and canonical selected-person association always run
  first. On a valid full-frame match, return it immediately and do not
  run detail inference.
- For a missing match with a small subject (last confirmed long side
  <=48% of source long side) and recent full observation (<=1.2s),
  derive a bounded source-space ROI from the existing observed bbox
  and bounded Kalman prediction. Expand the region with age, clamp it
  to the source. No manual fixed ROI or global color-only re-ID.
- FrameKernel returns a transactional -3 request without advancing
  the committed source PTS. Native reopens the original source at the
  exact requested PTS, crops display-oriented pixels, and runs the
  same YOLO once with the existing strong 0.25 threshold.
- Reuse FrameKernel detail_complete and reproject_mask to map local
  candidates to the full scene's canonical proto grid. Reject crop-edge
  truncation, empty masks and strong duplicates of original candidates.
  Preserve all original full rows; appended local rows must pass the
  same multi-person ownership, motion, appearance and candidate margin.
- **Non-destructive output-only detail:** the stored full-only next
  state is the sole committed identity state. A temporary copy of that
  same prior Tracker evaluates appended current observations; never
  commit its track table, prediction, competing IDs, or appearance
  bank. At most a single current-candidate confirmation record is
  carried forward, without a new motion filter/track. The local
  candidate is eligible only for the missing frame. An already
  valid full-frame observation cannot be replaced or modified by ROI.
  All full-only selected outputs are retained byte-exact on identical
  observations. A failed detail attempt commits the stored full-only
  missing state. Repeated or mismatched PTS/count is rejected.
- Per-role budget min(160, ceil(sample count/4)), one ROI per requested
  source sample across both directions. After budget exhaustion, or a
  long unsupported full gap, continue original tracking, no stale
  alpha. Native re-decodes exact PTS rather than persisting raw RGB,
  including reverse-prefix samples. Android canonical YUV crop uses
  inverse display rotation and actual decoded crop bounds; iOS uses
  the original oriented CI image with top-left ROI remapped into
  source pixels and the existing source stamp check.
- The temporary source reader is closed after each attempt. This
  protects lifecycle and memory bounds but adds decoder startup and
  seek latency. Crop/decoder/inference failures fall back to the
  original current full observation; cancellation is not swallowed.
  Local masks currently remap to the existing 160 proto grid; this
  improves recall but is not a learned matte or full-resolution
  edge refinement. Cache key detail-overlay-v2 prevents reuse of
  previous analysis under a different policy.
- Debug experimental feature only, GPU remains isolated QA opt-in.
  Strict complete-export and archive checks remain unchanged.

### Why the initial implementation was rejected

The first implementation appended local candidates and committed the
resulting association state. Although the Baganda host improved from
238 to 294/300 and iOS from 231 to 289/300, the earlier independent
Vogue development clip regressed from 556 to 89/750 in host replay.
This exposed identity-memory/ownership contamination. Do not retain
that implementation or interpret its device results as final C6.
The final policy isolates detail evaluation from the canonical state.
The new regression compares track IDs, observed bounds, states,
last_seen and appearance bank after successive local fills against
a parallel full-only session, and confirms the same next full
association. This is a temporary transaction on the same shared
algorithm, not a second long-lived tracker.

### Final same-observation host replay (C5 development fixture)

Full-frame observations calculated once, both policies consume the
exact same full detections/features; additional model invocations
only on shared Tracker's adaptive request. No source videos or raw
observations committed.

| Metric | Full-only | Final detail overlay |
| --- | ---: | ---: |
| Baganda output masks | 238/300 | 290/300 |
| Baganda selected point hits | 8/12 | 11/12 |
| Baganda distractor hits | 0/12 | 0/12 |
| Baganda longest missing run | 0.760s | 0.120s |
| Baganda detail attempts / accepted | 0 / 0 | 62 / 52 |
| Baganda prior valid alpha byte-exact | baseline | 238/238 |
| Danzon output masks | 299/300 | 300/300 |
| Danzon selected point hits | 12/12 | 12/12 |
| Danzon detail attempts / accepted | 0 / 0 | 1 / 1 |
| Danzon prior valid alpha byte-exact | baseline | 299/299 |

At Baganda 6s and 9s the previous zero approximate contour IoU
recovers to 0.874 and 0.851 respectively; the 8s point still fails.
The C5 fixture was previously seen for diagnosis, not a fresh
holdout. Sparse points and approximate silhouettes are not full
identity or matting ground truth. The host timings isolate the
replay/extra inference after precomputing full observations; they
are not an end-to-end mobile throughput measurement.

Reproduce:
```sh
python tools/comparison/replay_adaptive_detail.py \
  --manifest testdata/comparison/real-dance-c5-holdout.json \
  --media /path/to/exact/tested/mp4s \
  --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --output /tmp/c6-detail
```
The same script accepts the B5 manifest and exact media. It asserts
that all prior valid full-only alpha samples are preserved byte-exact.
The Android instrumentation supports `-e detailEnabled false` for
a direct full-only comparison with the same installed QA build.
`review_holdout.py --baseline /path/to/prior/native/evidence` compares
per-backend exact PTS, old/new availability and joint alpha similarity.

The final B5 regression (same-observation host replay) preserves all
former full-only alpha bytes while recovering some extra samples:

| Scene | Full-only | Detail overlay | Prior valid exact | Longest gap old -> new |
| --- | ---: | ---: | ---: | ---: |
| Breaking | 648/750 | 663/750 | 648/648 | 0.640 -> 0.640s |
| Vogue | 556/750 | 584/750 | 556/556 | 2.680 -> 2.520s |

Both retain 9/10 selected-point checks, zero original annotated
distractor hits, and no new supplemental spectator hits. B5 and C5
together preserve all 1741 previously visible alpha frames across
2100 source observations. This directly catches the abandoned C6
first implementation's Vogue regression; it is not an identity
ground-truth guarantee for previously missing frames.

### Final native verification (C5 diagnostic pair)

On the OnePlus 8T (Android 14), the final GPU path recovered Baganda
227 -> 285/300 samples, retaining all 227 former valid alpha buffers
byte-exact. The CPU reference lane recovered 231 -> 287/300, retaining
all 231 byte-exact. Both hit 11/12 selected and 0/12 distractor points;
maximum missing run is 0.20s on both. Approximate all-frame contour
IoU for GPU/CPU is 0.794/0.796 (four annotated silhouettes). The GPU
Danzon lane remains 300/300 with every previous alpha byte preserved.
GPU requested and effective backend both GPU; the full pair analysis
cost increased from 57.105s to 103.039s, with 7.596s first preview.
The GPU reference used 73 detail attempts and accepted 58; its extra
source decoding took 27.971s versus 6.088s local inference. This is
an important throughput limitation, not a GPU speedup claim.

On the iOS simulator, the final reference/target visibility is
285/300 and 300/300 (previously 231/300 and 300/300). Reference detail
attempted/accepted/failed: 69/54/0; target did not require detail.
Analysis increased 44.310 -> 68.033s; first preview 4.128 -> 6.701s.
All 24 sampled canvas timestamps passed; the incomplete-pair archive
and export were correctly refused and ordinary playback restored.
The independent iOS short repair/cancel/cache-restore/export regression
also passed: 90 output frames, 3.0 seconds, 440Hz audio; outside patch
unchanged, real identity correction passed. This is not a successful
complete export of the C5 diagnostic pair.

Final shared-kernel regression: 152 tests passed, 1 ignored; Clippy
all targets with warnings denied passed. Android native comparison and
privacy reference unit tests passed (28 + 3); iOS simulator built and
ran. No new models, privacy tracking changes, or persistent second
tracker. C5 material is now seen; a new holdout, physical iPhone,
rotated-source detail validation and cancellation during an active
ROI decode remain outstanding. Keep debug-only feature scope and
CPU4 default/GPU opt-in. Next: reduce repeated exact-source decoder
startup/seek cost without persisting source RGB, then fresh quality
acceptance. Do not promote based on visibility alone.

The final complete Android instrumentation passed (CPU4 and GPU both
requested/effective as specified). CPU Danzon likewise remains 300/300
with all prior alpha buffers byte-exact. Thus the full native Android
pair comparison preserved 1058/1058 previously valid alpha buffers
across the two backend runs, with 114 added and none lost. CPU pair
analysis increased from 92.580s to 141.111s, first preview 10.801s.
Both complete run reports and per-frame reviews were checked; device
QA alpha files are temporary local evidence only.

## Stage C7.1: reuse exact source decoding for detail observations

C6 proved the bounded detail overlay's quality gain but repeatedly
created source decoders for current-frame ROI inference. C7.1 changes
source-frame ownership/transport only. The shared Tracker, model,
thresholds, 160/quarter-frame attempt budget, mask projection and
non-mutating detail overlay contract are unchanged.

- Android serial CPU forward spans borrow the decoded YUV Image until
  the current observation and optional detail crop finish. The crop
  uses the same Rust YUV converter and exact display-oriented source
  geometry, never the model-sized full-frame RGB. The source Image is
  released in the existing finally block (including cancellation);
  a scoped crop function rejects access after the callback returns.
  Only one extra model-sized direct buffer is allocated lazily.
- Android GPU keeps the existing two-buffer full-frame prefetch.
  Instead of starting another codec for every ROI, each decode span
  lazily opens an auxiliary codec, advances sequentially to ascending
  requested PTS, and flushes/seeks for reverse PTS or gaps >1.2s.
  Exact PTS and source dimensions are still checked. A failed reuse
  is retried once with a fresh decoder, then the old full-only fallback
  applies. Close the auxiliary reader at the end of each span; never
  retain it across a published partial preview or across lanes.
  This avoids repeated codec creation and GOP decoding on forward
  ROI sequences, but does not eliminate all additional GPU decoding.
- iOS forward analysis crops the current source CVPixelBuffer while
  still in the decode scope. For reverse samples, retain at most
  four exact source pixel buffers from a lazily read ascending block,
  with a 24 MiB stride*height payload budget (an unusually large
  requested frame is used alone). Release passed/unused samples,
  discard the block at reverse-span exit. Verify the original stamp
  and exact PTS, use the same orientation and crop transform.
  Neither platform persists source pixels or changes inference policy.
- Detail stage telemetry separates inline crops, auxiliary reader
  opens, reused reads, seeks/cache hits and decode/inference time.
  The archive key adds source-reuse-v1 conservatively; no old mask
  archive is silently mixed with a changed source transport.
- Android instrumentation accepts `-e detailReuse false` to run the
  previous per-request decoder mode with the same compiled model and
  tracking core. The new `detailSourceReuseParity` case checks actual
  YUV crop byte parity across rotations 0/90/180/270, ascending reads,
  reverse seek, scoped crop lifetime and decoder release on a thrown
  cancellation. Manual rotation here exercises the decoder's
  display-coordinate mapping, not a complete rotated-container UX test.

### Paired C6 / C7.1 evidence

Same C5 two 12-second fixtures, 25fps/300 samples per lane, original
3s seed, same model SHA, unchanged FrameKernel. Prior C6 data is
`084074a5`; comparisons are across runs, not a thermally controlled
repeated benchmark.

On iOS simulator C7.1 kept 285/300 + 300/300 current-mask outputs,
69 attempts and 54 accepted, 0 detail failures. Of 69 requests,
68 were inline, one reverse reader opened (four frames read).
Crop/decode 1.627s and local inference 3.579s. Full-pair analysis
68.033 -> 55.627s; first preview 6.701 -> 4.640s. All 24 exact-PTS
captured canvas images are pixel-identical to C6. Incomplete export
and archive were rejected, ordinary playback restored.

The actual Android GPU pair finished in 65.278s vs C6 103.039s;
first preview 6.478s vs 7.596s. On the Baganda lane, detail request
count/accepted stayed 73/58, no failures; auxiliary decoder opens
fell from 73 to 3, with 70 reused reads and 3 seeks. Detail decode
time 27.971 -> 3.906s (excludes separate 0.327s open and 0.022s seek
measurements); local inference 6.088 -> 5.943s. Both 300-PTS alpha
record files are byte-identical to C6, including 15 missing markers
in Baganda and all valid Danzón outputs. The target needs no detail
reader. Full GPU inference and prefetch remain unchanged.

An additional iOS actual reverse-heavy test moved both user anchors
to 11.0s (new seed points from raw source). The same 12s pair finished
with 274/300 + 300/300, 50.576s analysis; 24 exact PTS samples,
incomplete export/archive refusal and ordinary playback restoration
passed. Baganda performed 68 detail attempts, 42 accepted, 0 failed:
13 inline and 55 reverse; 17 reverse readers, 38 cache hits, 68 source
samples loaded. This exercises the actual reverse cache rather than
assuming all requests occur after the seed. Different anchor means
different selection/trajectory; no direct C6 quality/performance
comparison is claimed for this probe.

The Android CPU pair finished in 98.027s vs C6 141.111s (30.5%
lower); first preview 9.744s vs 10.801s. Baganda 69 attempts, 56
accepted, 0 failures; 67 inline crops and two reverse requests
using one auxiliary decoder, one seek. Detail decode 1.562s,
auxiliary open 0.090s, seek 0.009s, local inference 7.992s.
CPU and GPU both retained the exact same complete Alpha files as C6:
all four SHA-256 comparisons match (1200 source PTS records, including
missing markers). CPU reference/target 287/300 + 300/300; GPU
285/300 + 300/300. No new identity or edge-quality claim is made.
Both runs used the same OnePlus 8T, but battery sensor C6 28.6->31.0 C
and C7.1 27.9->30.9 C; not repeated controlled-temperature benchmarks.
Relative to the C5 full-only run, C7.1 retains a quality gain with
remaining overhead (CPU 92.580->98.027s, GPU 57.105->65.278s,
iOS sim 44.310->55.627s). No model or backend policy change.

The new Android real source parity test passed all four manual display
rotations: 12 inline and 16 reused crop byte comparisons against fresh
per-frame readers, all reverse seek comparisons exact; escaped crop
function rejected and thrown cancellation released the borrowed image
on all four orientations. This is decoder-level cancellation/lifetime
evidence, not actual active ROI inference cancellation or a full rotated
container canvas end-to-end test. Android 28 focused comparison/export
tests and both actual device instrumented tests passed. iOS actual
short repair/cancel/restore/export regression passed: 90 frames,
3.000s, 440Hz, unchanged outside patch, real subject correction,
local file (simulator). iOS physical device remains untested.
No change to shared Rust tracking/core; no new gate or model.
Keep Debug/draft, CPU4 default and GPU isolated QA. Next C7.2:
profile remaining inference/decoder service cost, especially low-yield
detail requests on older B5 clips, without losing full-only or C6
quality. Fresh independent holdout still required for release.

Reproduce with the same C5 fixture and seed staging as C6; use
`-e detailReuse true` (default), or `false` for old reopen behavior
on the same build. Compare all four streamed alpha files with C6 using
`cmp` or SHA-256; review_holdout.py supports the same baseline option.
For the direct Android test run the isolated package instrumentation
method ComparisonCutoutBackendInstrumentedTest#detailSourceReuseParity
with `-e detailSourceQa true` after staging reference.mp4. iOS reverse
probe uses the same QA config with anchorUs=11000000, referenceSeed
[.55,.60], targetSeed [.37,.52].

## Stage C7.2: bounded failure backoff for low-yield detail requests

C7.1 removed most redundant source decoding. The remaining ROI inference
yield is highly scene-dependent: Baganda 52/62, Danzón 1/1, Breaking
15/100 and Vogue 28/157 adopted local masks in the previous host replay.
Long failed runs (e.g. 30 consecutive unsuccessful Vogue requests) are
a better optimization target than indiscriminately reducing frame cadence.

Implement an optional per-direction `DetailBackoff` on the existing
FrameKernel dance session, not another identity tracker:

- Run the unchanged full-frame association on every exact source PTS.
  Only after a full match fails and the original detail ROI is eligible
  may the scheduler consider suppressing the optional local inference.
- After six consecutive attempted local observations with neither an
  accepted nor a provisional selected detail candidate, skip up to two
  eligible requests within 100ms of the failed attempt. The next request
  resumes; a source-time gap, full valid observation or no eligible ROI
  resets the streak. At 25fps, a sufficiently long failure burst has
  a six-probe/two-skipped pattern, not permanent suppression.
- A current full pending confirmation or prior detail pending candidate
  blocks throttling. Accepted/provisional detail evidence resets the
  scheduler. The full canonical tracker state is always committed exactly
  as in the existing full-only path. Skipped samples explicitly have no
  alpha; no stale mask, new identity inference, threshold change or
  interpolation of the missing person. Original per-role detail budget
  and C6 maximum ROI age/size remain unchanged.
- The scheduled C ABI returns -4 only when the full-only missing state
  has already been committed; no detail transaction remains to finish.
  Old unscheduled ABI remains for direct same-build A/B. Both native
  bridges handle -4 as a missing current frame, and report skipped count
  separately from actual decode failures and actual model invocations.
  Archive compatibility key adds detail-backoff-v1.
- This is a conservative scheduling heuristic, not a proof that an
  unobserved frame has no recoverable person. It can in principle omit
  a newly useful local observation; keep experimental release scope.
  Constants were developed from previously seen B5/C5 failure traces,
  not an independent holdout. Do not promote based on these clips alone.

### Same-observation host replay

Using the pinned model, identical full observations and identical cached
local candidates for the unscheduled and scheduled policies, on the
previously seen four clips. No raw observation cache is persisted or
committed. The replay checks all prior full-only outputs and records
any lost, changed or added detail PTS; cached replay time is not a
measure of actual local inference latency.

| Scene (source samples) | Prior ROI calls -> scheduled | Accepted ROI (unchanged) | Full selected output (unchanged) | Prior valid alpha exact |
| --- | ---: | ---: | ---: | ---: |
| Baganda (300) | 62 -> 62 | 52 | 290 | 290/290 |
| Danzón (300) | 1 -> 1 | 1 | 300 | 300/300 |
| Breaking (750) | 100 -> 97 | 15 | 663 | 663/663 |
| Vogue (750) | 157 -> 142 | 28 | 584 | 584/584 |

All 1837 formerly visible alpha buffers are byte-identical across
2100 source samples; none added or lost. Longest gaps and all sparse
selected/distractor check results are unchanged. Total detail calls
320 -> 302 (5.625% fewer); low-yield Vogue 9.55% fewer, Breaking 3%.
High-yield Baganda/Danzón unaffected. This is a small targeted reduction
in inference calls, not a claim of large end-to-end speedup or release
identity correctness.

Reproduce with the existing B5 and C5 manifests and source files:
```sh
python tools/comparison/replay_adaptive_detail.py \
  --manifest testdata/comparison/real-dance-b5.json \
  --media /path/to/exact/tested/mp4s \
  --library crates/frame-kernel/target/release/libframe_kernel.dylib \
  --output /tmp/c72-ab --scheduled --no-sheets
```
The native Android instrumentation accepts `-e detailScheduled false`
for the prior C7.1 scheduler, with the same C7.1 source reuse and model.

### Native regression

The actual C5 12-second pair on the OnePlus 8T retains all four
C7.1 Alpha files byte-for-byte (1200 source PTS records, including
missing markers). CPU4 coverage 287/300 + 300/300, GPU coverage
285/300 + 300/300. GPU requests/accepted 73/58, CPU 69/56, target
0/0 on both, skipped 0 and failures 0. Full analysis CPU 101.814s
and GPU 65.713s, vs previous C7.1 98.027s/65.278s; since no requests
were skipped, there is no C5 end-to-end speedup claim. These runs
were not repeated at controlled temperature.

iOS simulator C5 pair 55.592s, first preview 4.534s, 285/300 +
300/300, 69/54 reference requests/adopted and no skipped requests.
All 24 exact-PTS canvas images are pixel-identical to C7.1.
The actual B5 30s pair additionally exercised suppression on both
lanes: Breaking 118 requests, 12 adopted, 15 skipped, 597/750
visible; Vogue 111 requests, 17 adopted, 10 skipped, 610/750
visible. No ROI decode/inference failures, 124.550s total analysis.
The incomplete results correctly reject archive/export and restore
ordinary playback. This iOS B5 run was not compared to an unscheduled
same-input iOS raw-alpha baseline; no parity or speedup claim for it.
Rust full suite 153 passed/1 ignored, additional session-state
and pending-confirmation test passed in both timestamp directions;
Clippy all targets passed. Android 28 focused + 3 frozen privacy
reference tests passed. No privacy association policy change.

A paired, same-installed-APK Android GPU run on the first 12s of both
B5 source clips (300 exact samples per lane, original 3s selection)
compared `detailScheduled=false` and `true`, with identical decoder
reuse, model and other configuration. Both output Alpha record files
are byte-identical across all 600 samples (including missing markers).

| Metric | Unscheduled | Scheduled |
| --- | ---: | ---: |
| Breaking detail calls / accepted | 45 / 0 | 39 / 0 |
| Breaking selected outputs | 253/300 | 253/300 |
| Vogue detail calls / accepted | 75 / 10 | 75 / 10 |
| Vogue selected outputs | 102/300 | 102/300 |
| Pair analysis time | 71.550s | 69.859s |
| First preview | 8.699s | 8.308s |

Breaking skips six requests (13.3% fewer calls); Vogue skips 13
eligible requests but consumes its unchanged 75-call per-role budget
later, without more recovered frames. Thus only six model invocations
are eliminated overall (120 -> 114) in this native test. The ~2.4%
single-trial elapsed improvement is modest and not a thermally
controlled general speedup. This is an exact parity result for this
input/backend, not a general guarantee for all unseen detail frames.
The conservative scheduler is enabled in the experimental canvas;
the unscheduled instrumentation option is retained. No new threshold,
model, identity state or GPU default promotion. Proceed to independent
holdout C7.3 instead of tuning more thresholds against the development
clips. Physical iPhone, rotated-container full E2E, active model
cancellation and release-grade dense identity/edge evidence remain open.
