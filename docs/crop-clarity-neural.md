# Crop clarity neural restoration

For the current deterministic-shader visual A/B gate and Android opt-in capture workflow, see [`crop-clarity-quality-gate.md`](crop-clarity-quality-gate.md). Neural SR remains a separate, unpromoted research path.

Woah keeps crop clarity automatic: portrait subject-follow exports request enhancement only when the final crop contains fewer source pixels than the output contract. The deterministic GPU shader remains the production fallback.

## Adjusted strategy: no-training first

Custom training is no longer the primary path. The selection order is:

1. prefer a legally usable lightweight pretrained x2 SR checkpoint that can be migrated into Woah's LiteRT GPU contract without changing learned weights;
2. use official SPAN x2 as the exact-conversion reference, not an automatic product default;
3. if no pretrained candidate passes mobile performance/quality gates, keep the deterministic shader in production;
4. only train Woah's custom lightweight model if we later decide the quality gain justifies owning a training lifecycle.

This keeps training optional rather than a prerequisite.

## Exact SPAN PixelShuffle migration

Official SPAN is Apache-2.0. Its inference topology uses a 48-feature, six-SPAB backbone followed by a 3x3 Conv2D and PyTorch PixelShuffle. Official pretrained checkpoints are linked from the upstream repository.

PixelShuffle must not ship unchanged because it exports as `DEPTH_TO_SPACE`, which is outside Woah's reviewed LiteRT GPU operator contract. LiteRT GPU supports `TRANSPOSE_CONV v1`, so Woah rewrites the x2 head exactly:

- main weights: the source 3x3 Conv2D + PixelShuffle are permuted into a stride-2, 6x6 `TRANSPOSE_CONV`;
- phase bias: the source Conv2D owns four independent bias phases per output channel, represented exactly by a second stride-2, 2x2 `TRANSPOSE_CONV` over a constant-one LR map;
- the constant-one map comes from a zero-kernel 1x1 `CONV_2D` with bias 1;
- no learned parameter is approximated and no fine-tuning is required.

`tools/litert/span_pretrained_x2_migration.py` is the executable proof. Random-weight validation reaches float32 `max_abs` around `3.6e-7`, well inside the `1e-4` equivalence gate. The proof head exports with only `ADD`, `CONV_2D`, and `TRANSPOSE_CONV`.

## Official SPAN performance finding

A full official-topology proof was also built with synthetic weights to validate the graph shape before obtaining the actual checkpoint. It successfully converts to LiteRT and contains only reviewed operators:

- `ADD`
- `CONCATENATION`
- `CONV_2D`
- `LOGISTIC`
- `MUL`
- `SUB`
- `TRANSPOSE_CONV`

However, the full 48-feature / six-block topology costs about **16.171G MAC per 192x192 tile**. On the current Apple Silicon host using LiteRT 2.2 Metal, median model-only latency was about **13.3 ms/tile**. A representative 608x1080 portrait crop requires 28 overlapping tiles, yielding roughly **372 ms/frame** of serial model-only work before graphics handoff and composition.

This is not a phone benchmark, but it is enough to treat original full SPAN as a **performance-risk reference** rather than assume that "pretrained" automatically means "mobile-suitable".

Therefore the preferred no-training candidate is a **lighter pretrained x2 SPAN/SPAN-F-class model** if one can be sourced with acceptable license/provenance. The same exact head rewrite can be reused when its head follows Conv2D + PixelShuffle semantics. Original SPAN remains useful for equivalence/reference validation and may still be tested on real devices, but it is not selected by default.

Static reference contract: `models/litert/crop-clarity-span-pretrained-x2.spec.json`.

## Checkpoint migration pipeline

No BasicSR code is needed in the app or in the TensorFlow conversion stage.

First extract/reparameterize a trusted official checkpoint. `extract_pretrained_span_x2.py` mirrors upstream `Conv3XC.update_params` exactly, fusing each training-time 1x1/3x3/1x1 + skip branch into the single inference 3x3 convolution used by SPAN eval mode:

```bash
python3.12 -m venv /tmp/woah-span-migrate
/tmp/woah-span-migrate/bin/pip install -r tools/litert/span_pretrained_migration_requirements.txt

/tmp/woah-span-migrate/bin/python tools/litert/extract_pretrained_span_x2.py \
  /path/to/trusted-official-span-x2.pth \
  --output /tmp/span-x2-reparameterized.npz \
  --report /tmp/span-x2-source.json
```

The extractor requires modern PyTorch `weights_only=True` loading and should still be used only with trusted official checkpoints.

Then build the full NHWC LiteRT graph without PyTorch:

```bash
/tmp/woah-span-migrate/bin/python tools/litert/span_pretrained_x2_migration.py \
  --archive /tmp/span-x2-reparameterized.npz \
  --export-full /tmp/crop-clarity-span-x2.tflite

/tmp/woah-span-migrate/bin/python tools/litert/verify_crop_clarity_model.py \
  /tmp/crop-clarity-span-x2.tflite \
  --contract-out /tmp/crop-clarity-span-x2.contract.json
```

Before promotion, the actual checkpoint still needs an end-to-end official PyTorch vs migrated-model numerical comparison and SHA-pinned source provenance.

## Custom model remains fallback research only

`models/litert/crop-clarity-span-x2.spec.json` describes the previous custom 12-channel / three-block SPAN-derived fallback. It uses depthwise-separable spatial convolutions plus `RESIZE_BILINEAR + CONV_2D`.

Its validated proof characteristics are:

- input `float32 [1,192,192,3]`, NHWC RGB `[0,1]`;
- output `float32 [1,384,384,3]`;
- 4,011 trainable parameters;
- 27,620-byte random-weight FlatBuffer;
- about 309.879M MAC per tile;
- about 1.0-1.2 ms/tile on the current Apple Silicon LiteRT 2.2 Metal host after warmup.

A representative 608x1080 crop uses 28 tiles. The custom topology is therefore much closer to the mobile compute envelope than full SPAN, but it needs training to produce meaningful SR quality. Training is retained only as an optional later fallback.

Fallback tooling remains available:

```bash
python tools/litert/extract_crop_clarity_frames.py \
  --videos-dir /path/to/licensed/videos \
  --output-dir /path/to/woah-sr-frames \
  --sample-fps 1 \
  --max-frames-per-video 120

python tools/litert/train_crop_clarity_span_x2.py \
  --data-dir /path/to/woah-sr-frames \
  --steps 100000 \
  --output-weights /tmp/crop-clarity.weights.h5
```

Fixture-only smoke weights must never be promoted.

## Runtime and provisioning

Normal builds require no Neural SR model. A candidate becomes package-eligible only after `verify_crop_clarity_model.py` emits a matching SHA contract beside the model. `tools/setup_models.py --android` stages it for Android and `tools/release/sync_ios_crop_clarity_model.py` stages the same verified bytes for iOS. Invalid or stale optional assets are removed rather than used.

Android scaffold:

- `CropClarityBackendPolicy`: off / deterministic shader / neural LiteRT GPU candidate;
- neural candidate begins at 1.25x enlargement;
- `LiteRtCropClarityRestorer` requests GPU and never explicitly retries Neural SR on CPU;
- `CropClarityTilePlanner` owns overlapping 192px tiles and exact x2 retained-core destination geometry.

IOS scaffold:

- `IOSCropClarityRestorer` uses the same tensor contract with a Metal delegate candidate.

Delegate creation is not treated as proof of complete GPU residency; LiteRT can internally partition. Static op validation plus real-device profiling remain mandatory.

Production Android integration must also avoid `glReadPixels -> FloatArray -> GPU` per tile. LiteRT C++ exposes graphics-backed TensorBuffer interoperability, so the intended production path is a small JNI bridge wrapping an existing GL texture/buffer (or interoperable graphics buffer) directly once an Android SDK/NDK environment is available for compile/device validation.

## Promotion gates

1. Select a lightweight pretrained x2 candidate with clear license/provenance, or explicitly accept original SPAN's compute cost for testing.
2. Pin checkpoint source and SHA-256.
3. Full-model source-framework vs migrated LiteRT numerical equivalence passes.
4. TFLite verifier passes exact IO and GPU op/version allowlist; `DEPTH_TO_SPACE` remains forbidden.
5. Model bytes are pinned by SHA-256 for Android and iOS.
6. Android Adreno/Mali GPU residency + zero-copy benchmark passes.
7. Real iPhone Metal benchmark passes.
8. Real-video A/B beats the deterministic shader without unacceptable temporal flicker.
9. Export throughput, peak memory, thermals and cancellation remain acceptable.
10. Privacy regions are no less protected than the deterministic fallback.
11. Only after all gates pass may Neural SR replace the shader for eligible crops.
