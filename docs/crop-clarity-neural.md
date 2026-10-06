# Crop clarity neural restoration

This document defines the neural follow-up to Woah's deterministic crop-clarity
fallback. The product behavior stays automatic: portrait subject-follow exports
request restoration only when the final crop contains fewer source pixels than
the output contract.

## Current status

- `ExportPlan.cropClarityScale` and the deterministic GPU shader are production-ready fallback infrastructure.
- Neural x2 authoring/runtime scaffolding exists but is **not connected to production export yet**.
- No neural model is required by normal builds. A model is packaged only when a generated SHA contract matches it.
- Neural inference requests GPU only; Woah never retries neural inference on CPU. Because LiteRT may internally partition/fallback, full GPU residency must be proven by the real-device promotion gate before production use.

## Model contract

`models/litert/crop-clarity-span-x2.spec.json` is the static architecture contract.

- Input: `float32 [1,192,192,3]`, NHWC RGB, `[0,1]`
- Output: `float32 [1,384,384,3]`, NHWC RGB
- Scale: x2 only
- Architecture: SPAN-derived parameter-free attention, 12 features, 3 blocks
- SPAB convolutions: depthwise 3×3 + pointwise 1×1 to reduce mobile MACs
- Upsampling: `RESIZE_BILINEAR + CONV_2D`
- PixelShuffle / `DEPTH_TO_SPACE` is intentionally excluded from the model

The design is inspired by the Apache-2.0 SPAN project. It preserves SPAN's
`(out3 + residual) * (sigmoid(out3) - 0.5)` parameter-free attention pattern,
but intentionally uses fewer channels/blocks and depthwise-separable spatial
convolutions for the mobile export workload:
`https://github.com/hongyuanyu/SPAN`.

A TensorFlow 2.21 structural export with random weights (never promoted) has
proven the exact graph: 27,620-byte FlatBuffer, 4,011 trainable parameters and
~309.879M MAC per 192×192 tile. The converted graph contains only `ADD v1`,
`CONCATENATION v1`, `CONV_2D v1`, `DEPTHWISE_CONV_2D v1`, `LOGISTIC v1`,
`MUL v1`, `RESIZE_BILINEAR v3`, and `SUB v1`. A three-step fixture-only training
smoke was also exported and passed the same graph verifier, proving trained
weights do not alter the reviewed operator contract. The verifier checks both
operator names and versions so a future converter upgrade cannot silently move
the graph outside the reviewed GPU contract.

## Why the upstream model is not used unchanged

Upstream SPAN uses a convolution followed by PixelShuffle. The public LiteRT GPU
delegate operator list includes `CONV_2D`, `LOGISTIC`, `ADD`, `SUB`, `MUL`,
`CONCATENATION`, `RESIZE_BILINEAR`, and `TRANSPOSE_CONV`, but does not list
`DEPTH_TO_SPACE`. Woah therefore uses an NHWC graph whose x2 head is bilinear
resize plus convolution so the whole graph can stay on the GPU delegate.

## Tooling

Create an isolated authoring environment first (these packages are not app
runtime dependencies):

```bash
python3.12 -m venv /tmp/woah-sr
/tmp/woah-sr/bin/pip install -r tools/litert/crop_clarity_requirements.txt
```

Author/export an untrained graph for operator validation:

```bash
python tools/litert/crop_clarity_span_x2.py \
  --output /tmp/crop-clarity-span-x2.tflite
python tools/litert/verify_crop_clarity_model.py \
  /tmp/crop-clarity-span-x2.tflite \
  --contract-out /tmp/crop-clarity-span-x2.contract.json
```

Train with a licensed HR corpus:

```bash
python tools/litert/train_crop_clarity_span_x2.py \
  --data-dir /path/to/hr/frames \
  --steps 100000 \
  --output-weights /tmp/crop-clarity.weights.h5
```

`--smoke-fixtures` exists only to validate the training plumbing. Those weights
must never be promoted.

The training degradation intentionally samples blur, variable resize chains,
JPEG compression, and sensor/compression-like noise. Production training should
extend this with the real Woah source distribution and H.264/H.265 frame
extraction.

## Local model provisioning

A prototype becomes package-eligible only after the verifier emits
`models/litert/crop-clarity-span-x2.contract.json` next to the model and the SHA
matches exactly. `tools/setup_models.py --android` stages it for Android;
`tools/release/sync_ios_crop_clarity_model.py` stages the same bytes for iOS.
Invalid or stale optional assets are removed rather than used.

## Runtime architecture

Android:

- `CropClarityBackendPolicy`: off / deterministic shader / neural LiteRT GPU
- Neural activation begins at 1.25×; milder enlargement stays on the shader to avoid model startup and tile cost.
- `LiteRtCropClarityRestorer`: GPU-targeted `Accelerator.GPU`; static op verification + real-device profiling are required to prove full residency
- `CropClarityTilePlanner`: overlapping 192 px tiles with seam-free retained cores

IOS:

- `IOSCropClarityRestorer`: same tensor contract, strict Metal delegate

The runtime classes intentionally expose tile inference without performing a GL
readback. Production integration must not use a frame-wide CPU readback/upload
loop. The current Kotlin `TensorBuffer` API exposes typed read/write calls, while
LiteRT 2.2's C++ `TensorBuffer` additionally exposes zero-copy
`CreateFromGlTexture`, `CreateFromGlBuffer`, and `CreateFromAhwb`. The preferred
Android production path is therefore a small C++/JNI bridge that wraps the
existing crop texture (or an interoperable graphics buffer) directly for GPU
inference and returns a GPU-backed output texture/buffer. The neural backend can
replace the shader only after that handoff strategy and real-device performance
pass the promotion gates below.

A host-side LiteRT 2.2 Metal direction check on Apple Silicon compiled the same
trained smoke graph fully through the Metal GPU path. After warmup, model-only
latency was about 1.0–1.2 ms per 192×192 tile (CPU was ~17 ms). A representative
608×1080 portrait crop requires 28 overlapping tiles, so zero-copy I/O is
material: CPU readback/upload would erase much of the GPU advantage. This host
measurement is evidence for architecture viability only, not a mobile promotion
gate.

Reproduce model-only host timing with:

```bash
python tools/litert/benchmark_crop_clarity_model.py \
  /tmp/crop-clarity-span-x2.tflite --accelerator gpu
```

## Promotion gates

1. TFLite verifier passes exact IO and GPU operator allowlist.
2. Model is byte-pinned by SHA-256.
3. Android strict-GPU compilation succeeds on representative Adreno/Mali devices.
4. iPhone Metal delegate succeeds on representative devices.
5. Real-video A/B beats the deterministic fallback on crop detail without temporal flicker.
6. Export throughput, peak memory, thermals, and cancellation remain acceptable.
7. Privacy regions are no less protected than the deterministic fallback.
8. Only after all gates pass may the neural backend become the default for `cropClarityScale > 1`.
