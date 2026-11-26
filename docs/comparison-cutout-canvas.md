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
