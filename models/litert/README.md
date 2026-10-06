# Release-critical LiteRT model

`yolo11n-seg-fp16.tflite` is the byte-pinned canonical YOLO11n segmentation
artifact shared by the Android and iOS release paths. It is tracked because the
release reproducibility work demonstrated that re-running Ultralytics' CPU
Conv/BN fusion can produce host-dependent low-order FLOAT32 differences even
when the checkpoint, exporter versions, graph structure, tensor shapes, and
high-order weight values agree.

The release artifact identity is therefore the tracked binary itself:

- size: `11,799,725` bytes
- SHA-256: `ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340`
- FlatBuffer core SHA-256: `881b3107910165066ba8ab8cd9c76bd5f51b781d1e224ccce91f60a904c0951c`
- source checkpoint SHA-256: `55ed65c56c91713d23e8402371c6c49a6fd84f257f7dce452e8d70e41dcbe152`

The embedded Ultralytics metadata identifies version `8.4.130`, task
`segment`, and license `AGPL-3.0`. This third-party model is not relicensed by
the repository's MIT license; redistribution must retain and comply with its
upstream license terms. See the embedded model metadata and the Ultralytics
license reference recorded there.

All other production model binaries remain ignored. Do not replace this file
without updating the tracked iOS model contract and graph/semantic evidence
together.

## Optional crop-clarity prototype

`crop-clarity-span-x2.spec.json` describes the experimental x2 neural restoration
contract. The model binary is intentionally **not tracked or required** yet. Its
architecture is SPAN-derived (upstream SPAN is Apache-2.0), but uses a smaller
12-channel/3-block depthwise-separable body and replaces the PixelShuffle head
with `RESIZE_BILINEAR + CONV_2D` so the graph can stay within Woah's reviewed
LiteRT GPU operator set.

A prototype model becomes package-eligible only when
`tools/litert/verify_crop_clarity_model.py` emits a matching
`crop-clarity-span-x2.contract.json` containing its SHA-256. Normal builds keep
the existing deterministic crop-clarity shader when that verified model is not
provisioned or cannot initialize on the strict GPU delegate.
