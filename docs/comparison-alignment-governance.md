# Music alignment governance

`crates/frame-kernel/tests/music_alignment_contract.rs` is the checked-in,
license-free PCM contract suite. It covers tail and interior edits, both edit
directions, short echoes, and repeated copies at mixed rates. Keep the inputs
and truth assertions stable while changing the matcher; add a new case rather
than weakening a failing expectation. The larger five-revision corpus and its
PCM are maintained outside this repository; its replay results are diagnostic,
not an online incident-rate estimate.

## Decision contract

- A proposal is not a match. A usable automatic result requires positive,
  bidirectional local evidence and enough coverage in both sources.
- Outcomes remain distinct: supported, conflicting, and insufficient. A search
  miss, absent audio evidence, or exhausted verification budget cannot be
  treated as support.
- A second plausible copy, including one at a different rate, requires review;
  review candidates must themselves be verified and carry playable source
  intervals.
- A verified audition interval is source time, not video overlap. Seeking and
  playback advancement must stay within that interval. Saving requires the same
  candidate to have been auditioned in both source players.
- Preserve the unique-match tolerance: <35 ms truth error at both ends. Do not
  change persisted project timing or infer audition positions from a duration.

## Required local checks

```sh
python3 tools/rust/run.py check
cargo test --release --locked --test music_alignment_contract
python3 tools/rust/run.py build
python3 tools/rust/audio_smoke.py crates/frame-kernel/target/release/libframe_kernel.dylib
PATH="/path/to/flutter/bin:$PATH" python3 tools/ci/pigeon_codegen.py
```

Also run the comparison review/controller Flutter tests and native host
regressions. Device playback, acoustic output, and physical latency require
separate real-device acceptance; host tests are not a substitute.
