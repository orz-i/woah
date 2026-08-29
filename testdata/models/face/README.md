# Face benchmark model fixture

`blaze_face_full_range.tflite` is used only by Android instrumentation benchmarks.
It is not copied into the production `dance_native` assets and is not part of the
current YOLO runtime path.

- Source: `https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_full_range/float16/1/blaze_face_full_range.tflite`
- SHA-256: `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`
- Model family: MediaPipe BlazeFace Sparse, full range
- Upstream license: Apache License 2.0
- Purpose: offline Android `androidTest` face detection/association benchmark only

The benchmark verifies this hash before running so a silently changed model does
not invalidate latency or association comparisons.
