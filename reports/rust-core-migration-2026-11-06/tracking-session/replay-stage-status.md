# Stateful tracking and anchored preview stage

FrameKernel now owns complete track transitions, identity association, masks,
class/motion evidence and bounded reacquisition. Kotlin and Swift consume the
same owned-session C ABI. An independent frozen Android corpus covers 960
transitions; Rust, actual JNI and actual Swift transport checks agree.

Named preview requests replay real source observations from the actual recorded
analysis timestamp. Backward seeks and selection changes reset replay; a missing
legacy anchor is rejected. New entrants cannot reuse absent analysis IDs. This
repairs the previous late full-length follow preview failure without lowering
identity matching thresholds.

Both platforms completed the 22 legacy API cases, plus cancellation/cleanup,
immediate custom-sticker retry and negative/reimport checks on the same eight
frozen sources. Each archive contains 23 movies including that retry; independent
media checks pass. Android and iOS also produced correct first/middle/tail/6.6s
preview controls and a 581-frame 720x1280 full-length follow output. The embedded
binary source is ba2f848f7da02596c39a7995eff75505334cabaf; later commits change host
verification/auditing, not those native binary inputs.

This is an ongoing stage, not final acceptance. Keep these open findings visible:

- iOS conservatively covers the intended unmasked hero at 6.6 seconds; Android
  preserves the hero there. The new common face model still reproduces this, so
  it is not solved by model replacement. Remaining observation/class/fallback
  policy must be unified and retested before declaring matching behavior.
- Raw Android face auditing flags three non-solid full-body frame-39 ellipse
  checks at 87.6%. Bound source/solid/mosaic/blur/gradient enlargement shows the
  whole face protected by the combined body mask; the face-ellipse metric alone
  omits that contribution. Raw automatic failures are preserved for manual
  resolution, not relabeled as automatic passes.
- Native integer-microsecond rounding differs by one microsecond for the 29.97
  fps duo. Auditing now allows only a unique source scan frame within one
  microsecond; larger gaps and ambiguous neighbors still fail. It records all
  such matches. Media frame counts and original source PTS are checked separately.

Every review image is now decoded sequentially from frame zero. Random seeking
in the Mac OpenCV build could otherwise attach a different frame to a review.
CI rejects changed frozen native expectations and missing Rust adapter calls;
macOS also runs the 960-step transport, nine Golden cases and fail-closed host
suite. Final Android-device/iOS-simulator 62-case and 4K acceptance remain pending.
