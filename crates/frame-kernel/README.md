# FrameKernel

`frame-kernel` is a platform-independent Rust library for frame geometry and
region processing. Rust consumers import it as `frame_kernel`; C consumers use
`frame_kernel_*` functions from `include/frame_kernel.h`.

The crate has no Flutter, Android, Apple framework, inference-runtime or
application-model dependencies. Native bindings, video I/O, model execution and
GPU resources belong to consumers and adapters outside this directory.

Current functionality includes overlapping detail views, tile boundary checks,
prototype-mask reprojection, cropped output-mask projection, configurable instance
segmentation postprocessing and protection-layer intent (including face supplements
for full-body targets), plus a source-PTS follow camera and generic encoder-even
ratio sizing, deterministic thresholded association, RGBA fixed-point bilinear
letterboxing, configurable tensor normalization/layout/orientation, and YUV 4:2:0
sampling with explicit crop/rotation/plane strides and BT.601/709/2020 range metadata,
an XYAH motion/covariance filter, prediction-first association metrics and
evidence-checked reservation release, plus current-scene selection scope, canonical
head crops and landmark-based face ownership gating.
Detection-head dimensions, score/coefficient offsets, tensor layouts and coordinate
units are supplied by consumers. Full stateful tracking and temporal protection
policies remain subsequent migration work; this extraction does not claim those
modules are complete.

```rust
use frame_kernel::geometry::{detail_views, Rect};

let views = detail_views(3840, 2160).expect("valid frame dimensions");
assert_eq!(views[3], Rect {
    left: 1536.0, top: 864.0, right: 3840.0, bottom: 2160.0,
});
```

Build/test independently with `cargo test --locked` from this directory.
The C ABI uses explicit dimensions and preprocessing transforms, borrowed input
buffers, and caller-owned output buffers. It retains no platform handles or
input pointers after a call. Consumers must not alias input and output memory.

This is an unpublished initial API. Keep `publish = false` until API stability,
packaging and compatibility have been reviewed separately.

Temporal region state is also shared: observed/projected/estimated geometry,
reference-size updates, asymmetric size smoothing, anchor edge agreement,
weak-measurement translation and residual position gates. Its state is a
caller-owned 64-byte value; native key maps are lifetime/transport adapters.
Flags describe evidence strength rather than a platform or detector framework.
Both face adapters request full current measured extent, including growth;
Android's old 90% floor remains only in the frozen compatibility corpus.
The shared anonymous path has private state and cannot mutate identity.


Worker completion uses the same Rust transition table on both platforms.
A cancellation request does not publish a terminal result. The native owner
closes decoders, encoders, leases and private files before releasing the pending
outcome. Successful publication seals completion against a later cancellation;
cancellation accepted before publication wins over teardown errors. Transport
adapters map outcome names; filesystem operations and platform locks stay native.
