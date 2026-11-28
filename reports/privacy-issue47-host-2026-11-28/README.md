# Issue #47 — M1: local FrameKernel current-face recovery

Base: merged #46 (`dd940184`); archived research: `da670362`. This Draft reintroduces short recovery and long reappearance research from `88d9c9a9` / `0a7b318d` atop the latest production geometry, adds exclusive current-mask evidence to both short recovery lanes, and requires explicit host opt-in. The retrospective partial-face experiment is deferred. Existing mobile consumers do not invoke these new APIs. The Podfile lock checksum follows the dynamic FrameKernel source inventory only.

## Shared algorithm

- Last canonically owned direct face/body and current retained prediction supply a bounded 800ms short recovery hypothesis; one terminal fresh-current continuation can reach 950ms only with independent pixel agreement. No new identity assignment or projection lease is created.
- The finish API requires packed masks of ALL raw instances in body index order and rejects support from any competing current mask at the candidate center, even when its body rectangle is misleading. Missing/inconsistent mask evidence is invalid. Added negative and positive tests verify the full-evidence contract. New exclusive finish symbols avoid silently reinterpreting archived ABI inputs.
- Long reappearance (950ms–2s) remains a separate experimental current-coverage lane using frozen garment comparisons with competing current/historical evidence; same-clothing and foreign-owner ambiguity abstain. The host manages evidence history; Rust owns geometry and comparisons. Raw video and model inference stay in the host adapter. Neither lane mutates tracking state or establishes a new canonical root.

## Local verification

Same candidate release dylib with recovery off/on; both paths share decoded person evidence. Existing rendered frames and tracking snapshots match for all successful replays. Source and output hashes in `m1-summary.json`.

| Target | Frames | Off | On | Added | Sparse head audit | Checked neighbor overlap |
|---|---:|---:|---:|---:|---|---|
| Group ID 4 | 120 | 109 | 120 | 11 | 11/11 | 0/22 |
| Group ID 3 | 120 | 98 | 106 | 8 | 8/8 | 0/16 |
| Group ID 2 | 120 | 108 | 108 | 0 | not labeled | not labeled |
| Single affine | 18 | 18 | 18 | 0 | 18/18 head points | not labeled |

The group head audit uses previously source-reviewed approximate rectangles from the archived branch, not independent semantic labels. Host square coverage is not mobile mask/compositor output. Remaining 14 + 12 unresolved target frames require visibility/ownership assessment; absence of a region is not proof that a target was hidden. A second crossing clip failed to establish either requested initial identity (IDs 1/2), so independent-scene candidate evaluation is blocked pending a valid initial selection/label set. No acceptance claim is made for similar-clothing real scenes, partial-face visibility or performance.

Rust full all-targets: 198 passed, 1 ignored; strict Clippy and v1 ABI smoke passed. Python syntax and real-model host replay passed for the group/affine cases. Mobile implementation and tests remain deferred, as requested.

## Reproduction

Use the local 960x540/15fps 120-frame group fixture and both models documented in the historical research report. The explicit experimental flag is applied only to candidate execution. For each target ID 2/3/4:

```sh
cargo build --release --manifest-path crates/frame-kernel/Cargo.toml
LIB=crates/frame-kernel/target/release/libframe_kernel.dylib
python tools/privacy/replay_face_tracking.py --baseline "$LIB" --candidate "$LIB" \
  --fixture "$GROUP_MANIFEST" --target-id 4 --credible-roots --multiperson-recovery --output group-id4.json
```

Run `tools/privacy/audit_face_sticker_samples.py` with `s42-reviewed-group-gap.json` / `s43-reviewed-reappearance.json` from `da670362` and the respective case names `group_gap` / `reappearing_id3`. Local raw outputs remain under `~/Coding/woah-rust-tools/issue47-local/` and are not committed. Next: validate independent source-only identity/visibility labels and candidate arbitration, then reassess partial face inference; only after host stability consider Android/iOS integration.
