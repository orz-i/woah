# Comparison canvas: default-on production policy

This supersedes the default-disabled preview and package-scoped GPU
candidate described in the historical Release preview and A4 reports.
Base: master f718fabc. Implementation 6a85132b; final runtime/UI source
dfc3d1d1. No model, tracking, privacy algorithm or FrameKernel change.

## Defaults and rollback

Regular Android and iOS Release builds expose 抠像画布 with no extra
build define. Android cutout uses LiteRT GPU first, with the existing
CPU4 complete-session fallback and reverse ROI blocks. No package suffix
is required for GPU selection. Debug QA backend selection stays separate;
production does not enable observation replay or detailed QA timing.
iOS keeps TensorFlowLite/XNNPACK; this change does not introduce a Metal
delegate or alter the video-protection pipeline.

Optional build rollback:
- WOAH_CUTOUT_GPU_ENABLED=false: Android cutout CPU4, canvas remains open.
- WOAH_ENABLE_CUTOUT_PREVIEW=false: disable Release canvas on both platforms.
- Native and Dart defaults match; invalid/duplicate native boolean defines
  are rejected, and iOS missing/malformed marker remains fail-closed.
- On first selection GPU initialization can fall back to actual CPU4.
  After published GPU evidence a model failure discards uncommitted
  work and requires both subjects to be reconfirmed on CPU4. A failed
  GPU repair cannot mix CPU observations into its existing cache.
- Release restores exact CPU or GPU archive keys even after CPU rollback;
  GPU archives can be played/exported, but a CPU-only session requires
  full reselection for repair. The existing build-SHA archive key remains:
  different builds can invalidate old archives, including UI-only changes.
- Missing frames still block export; warnings about subject ambiguity and
  the review before export remain.

## Validation on 2026-11-27

Android generated BuildConfig tests: default true/true, GPU rollback
true/false, canvas rollback false/false. iOS marker unit tests 4/4,
Kotlin focused tests 17/17, Flutter comparison tests 47/47.
Android arm64 AOT build from dfc3d1d1 passed, iOS arm64 Release
no-codesign build from dfc3d1d1 passed (46.2MB), true marker and
canonical YOLO SHA-256 verified. Local signing and package suffix
art.gaoge.dance.cutoutreleaseqa were temporary solely to preserve
the installed original application, not to select the backend. No
cutout enable/GPU define was used. Source overrides were reverted.

OnePlus 8T real screen gestures:
- Initial default-policy build 6a85132b: GPU requested/effective,
  12s C8.3 pair at 6s, partial preview observed by 8s, complete by
  47s, reference 300/300 and target 251/300. 13 reverse blocks,
  19 hits, 48 captures, 0 fallbacks. Missing-frame export refused.
- Local-only controlled compile exception (same production policy)
  requested GPU, effective CPU, four threads, both short tracks
  30/30 and canvas_committed=CPU. No injection code committed.
  The earlier A4 post-preview failure/reselection acceptance remains
  the evidence for runtime failure restart, not rerun in this change.
- After status-label fix 80171feb, normal GPU 30/30 + 30/30, saved,
  actual H.264/AAC 1080x1550 25fps 30-frame 1.200s export pulled and
  fully decoded. Cold startup restored GPU archive. A prior CPU
  archive from a different Git SHA was deliberately not accepted;
  no cross-version archive compatibility claim.
- Final APK after correction snackbar fix dfc3d1d1 installed and
  repeated fresh short GPU selection, 30/30 + 30/30, canvas_committed=GPU.
  Original app remains installed. All packages local test-signed.

iPhone 15 Pro iOS 17 simulator: real ComparisonScreen/native gateway
gesture driver, Debug. Two initial runs failed immediate cancel on
a second repair after success snackbar. Dismiss previous snackbar when
opening repair; a new widget assertion tests immediate reentry/cancel.
The fixed complete simulator run passed: selection, dynamic partial
preview/export-disabled state, opacity/mirror/movement, restore,
apply repair, immediate cancel repair, exit and reselect. This is
simulator acceptance, not physical iPhone Release runtime validation.
No new real-media iOS export test in this round; prior simulator
export tests documented in the previous reports.

Evidence: /tmp/woah-cutout-production (native/Flutter tests, build logs,
APK hashes, actual export, runtime logs, screenshots, failed and passing
gesture reports). Final APK SHA-256
686e1176c6fc9b356f09b0864c80d7e62663a03ff52813642c3a59db27d2d96a.
Earlier actual export SHA-256
bf68ccfd15d0d331321852e34bd112818dbd52e8f0ccf56cdb56da90e354449b.

No physical iPhone, naturally GPU-unsupported Android device, or
driver hang tested. Complex-occlusion identity quality remains
limited; issue #43 is independent. No store signing or deployment.
