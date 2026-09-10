# Release-critical LiteRT model

`yolo11n-seg-fp16.tflite` is the byte-pinned canonical YOLO11n segmentation
artifact shared by the Android and iOS release paths. It is tracked because the
Phase 7 release investigation demonstrated that re-running Ultralytics' CPU
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
without updating the tracked iOS model contract, graph/semantic evidence, and
release acceptance evidence together.
