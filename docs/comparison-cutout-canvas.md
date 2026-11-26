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

## Remaining implementation (not enabled in production yet)

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

## Current limits

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

The second command is an explicit failing regression case. The manifest is
written before the nonzero exit. No derived video, mask, or image is committed.

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

M0 remains incomplete on actual mobile inference/rendering. Production gesture,
tracking cache, playback and export wiring remain disabled.
