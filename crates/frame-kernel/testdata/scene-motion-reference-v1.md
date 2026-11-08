# Ordered scene-motion reference

Frozen canonical Android input/output at production source fec72da327fa4524c01ed7c50f51752ca6d83059.
SceneMotionEstimator remains native there; it does not call this Rust implementation.

- Producer: SceneMotionReferenceCorpusTest. Generation requires FRAME_KERNEL_GENERATE_REFERENCE=1.
- 512 ordered cases, including sparse detections, incompatible shapes, residual outliers and tied hypotheses.
- 70,764 bytes; SHA-256 20298cd7d8262f31f6715b520f18fc23c800db8352acf13d12237b56337c64d3.
- Big-endian DataOutputStream: magic FKM1, case count; per case minimum inliers,
  maximum shift, tolerance, ordered track IDs/bounds, ordered detection bounds,
  then exact Float32 dx/dy/confidence bits and inlier count.
- Rust integration test compares every output bit and consumes the complete file.

This migration branch is not yet wired into either production tracker. Full update,
identity recovery and native consumers still require complete replay verification.