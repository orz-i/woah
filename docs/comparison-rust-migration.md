# Shared comparison core migration

Baseline: `a3821c7852db5d4050fe8b6a423e157f761575a2` (2026-11-12).

## Contract

Music correspondence is `target = roundHalfTowardsPositive(reference * scale) + offset`.
Viewing speed and movement timing are independent. Preserve early/late movements.
Intervals are `[start, end)`. A common interval must satisfy both discrete sample
lookup and the continuous source edit boundary; no native adapter may extend it.
Retain native source PTS/time bases until the documented lookup boundary. Never
infer frame adjacency from nominal FPS. Candidate ties have an explicit order.

The current feature profile is mono 16 kHz, Hann/FFT 1024, hop 160, 16 spectrum
and onset bands, and 48 pitch bands. Preserve existing matching thresholds while
migrating. Unrelated music, tempo jumps and discontinuous PCM must not become a
confident affine match. Product thresholds/messages remain separate from generic
core types. Native decoders, clocks, GPU APIs, codecs and file ownership remain
platform responsibilities.

## Stages and gates

1. Freeze contracts and shared fixture hashes; migrate time, ranges, PTS lookup
   and composition geometry into FrameKernel. Test Rust, Dart, JNI and Swift.
2. Migrate PCM normalization, features, constant/affine matching and deterministic
   candidate ordering. Replay the exact same PCM and feature inputs on both hosts.
3. Make playback/export consume shared plans and lifecycle decisions. Retain
   native resource cleanup and publication fences. Remove old production math.
4. Run the same comparison corpus on Android hardware and the iOS simulator,
   including real decoding, playback, frame stepping, cancellation, repeated
   export, saving/recovery, VFR, boundaries and 4K input. Check privacy regression.

Each stage is reviewed/validated/committed independently before merging. Record
actual checks and failures; historical reports are baseline evidence, not new runs.

## Corpus and replay

Reuse `testdata/comparison/generated/manifest.json` and existing comparison
fixtures first. Freeze a local manifest of SHA-256 values for all additional
derived and real pairs before testing both platforms. Keep original media and
private observations local. Publish compact numerical reports, not raw captures.

Cache dependencies: media+track metadata -> decoded PCM -> normalized PCM ->
features -> alignment -> composition. Keys include content hash, PTS/time base,
channel/sample format, profile and algorithm version. Layout, mirror, pan and
viewing speed do not invalidate audio analysis. Always execute native rendering,
encoding, cancellation and publication for flow tests; include cold analysis cases.

Comparison currently caps the longest export edge at 1920. Cover 4K input under
that contract; 4K output is a separate capability. The privacy 62-case matrix is
retained independently and does not count as comparison coverage.

## Initial source-confirmed differences

- Android quantizes mixed PCM to signed 16-bit and uses Sonic resampling; iOS
  requests mono Float32/16 kHz conversion from AVFoundation.
- Android histogram/score sorting leaves some ties unspecified; Swift breaks
  those ties by offset. Ambiguous first candidates can therefore differ.
- Android/Dart overlap uses a discrete end; iOS also constrains the continuous
  edit end. Scale=2 and target end=11 us gives Android/Dart end=6 vs iOS end=5.

## Verified progress

- Stage 1 merged as PR #35, main `f7b31236`. Rust 100, Android JVM 622,
  Flutter 277 and domain 28 checks passed. Windows/Mac C ABI timeline fingerprints
  agree. Real Android and iOS simulator Dart-to-Rust symbol/mapping smoke passed.
  Linux production, iOS build/archive/runtime and required governance CI passed.
- Stage 2 PR #36: streaming PCM normalization, features and both matching paths
  are shared Rust. Kotlin/Swift implementations were removed. Rust 107 and Android
  JVM 623 checks passed; existing Swift music, PCM and composition gates passed.
  Windows/Mac ABI known-music results agree (constant exact, affine max truth error
  0.594 ms on the frozen synthetic example). Full native flow regression pending.

When adding Rust source files, run `pod install` on Mac and commit the resulting
`dance_native` checksum: its script input list deliberately fingerprints the
actual Rust source inventory. Run `pod install --deployment` after that to verify
the pinned result. Source-file inventory changes also require refreshing Pods'
compiled Swift file list in an existing Mac checkout.
