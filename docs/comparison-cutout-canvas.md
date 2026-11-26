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
