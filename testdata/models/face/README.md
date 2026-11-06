# Shared face model source

`blaze_face_full_range.tflite` is the pinned canonical model used by both native
inference adapters. Android Gradle and tools/release/sync_ios_face_model.py copy
this exact tracked source into their application resources and verify its hash.
FrameKernel owns RGBA tensor preparation, anchors, box/landmark decoding and
weighted suppression. Native LiteRT/TensorFlowLite executes the model.

- Source: https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_full_range/float16/1/blaze_face_full_range.tflite
- SHA-256: `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`
- Model family: MediaPipe BlazeFace Sparse, full range
- Upstream license: Apache License 2.0
- Input/output contract: iOS Resources/dense-face-v1.contract.json

The historical MediaPipe task implementation is retained only in Android
instrumented tests as an independent reference. The production adapters do not
silently substitute an independent face algorithm on runtime failure.
