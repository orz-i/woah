# Face benchmark model fixture

`blaze_face_full_range.tflite` is the pinned source asset used by both Android
instrumentation benchmarks and the dormant production `MediaPipeFaceLocator`.
Gradle copies it into `src/main/assets/models/face/` and verifies its SHA-256.
No current preview/export/API call site enables the locator, so the stable YOLO
full-body runtime behavior remains unchanged.

- Source: `https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_full_range/float16/1/blaze_face_full_range.tflite`
- SHA-256: `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`
- Model family: MediaPipe BlazeFace Sparse, full range
- Upstream license: Apache License 2.0
- Purpose: offline Android `androidTest` face detection/association benchmark only

The benchmark verifies this hash before running so a silently changed model does
not invalidate latency or association comparisons.
