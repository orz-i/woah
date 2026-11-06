# Rust privacy core migration

The reusable library is FrameKernel (`frame-kernel`, Rust import `frame_kernel`)
in `crates/frame-kernel`. Its C ABI uses `frame_kernel_*` and the Clang module
`FrameKernelC`. JNI and Swift consumers stay in the mobile plugin adapter layer;
the library has no application or platform framework dependencies.

Approved scope: one Rust algorithm implementation, native Android/iOS media,
inference runtime and GPU adapters. Baseline is master 5e659270 (PR #20).
No UI or brand changes belong to these stages.

## Toolchain

Rust 1.97.1 is pinned in rust-toolchain.toml; commit Cargo.lock. Use rustup,
not an unrelated Homebrew/GNU compiler earlier on PATH. tools/rust/run.py
resolves both cargo and rustc through the pinned rustup toolchain.

Windows: host tests use x86_64-pc-windows-msvc; Android links with its NDK.
Mac: aarch64-apple-darwin host, aarch64-apple-ios-sim simulator and
aarch64-apple-ios archive build. Only install required targets and minimal
components. Toolchains may live outside the repository via RUSTUP_HOME and
CARGO_HOME. Existing global installations need not be changed.

## Delivery stages

0. Freeze 22 legacy + 27 original-size 4K + 13 focused cases and eight fixture
   hashes. Preserve protected subjects, hero6, intervals, dimensions and PTS.
1. Rust C ABI and shared geometry/pre/postprocessing. First move deterministic
   geometry, compare legacy outputs, then switch consumers behind tested adapters.
2. Stateful tracking, privacy selection/exemption, fallback/union and follow crop.
3. Same face model and shared preprocessing/postprocessing; current Vision and
   MediaPipe observations are transitional adapters, not permanent policy forks.
4. Shared export admission/cancel/drain/publication state machine, native resource
   actions and rendering conformance.
5. Switch both defaults, remove migrated duplicates, run full media acceptance.

Each stage is separately reviewed, committed and merged after its relevant gates.
Do not describe a linked library or synthetic test as a completed migration.

## Contracts

- Top-left scene coordinates, explicit actual preprocessing transform, int64
  source PTS. Distinguish identity from current-scene protection class.
- Unknown current faces protected; only current supported hero evidence exempts.
  Preserve individually selected identity constraints and existing thresholds.
- Missing region is distinct from intentional suppression and detector failure;
  renderers may not recreate suppressed fallback or ignore a privacy error.
- Core APIs own no platform buffers. Borrowed input/output lifetimes end on return;
  output buffers are supplied by the caller and cannot alias input.
- GPU or alternate inference backends need conformance evidence. Matching models
  alone does not prove matching inference, decoding or encoded pixels.

## Acceptance

The common matrix binds the existing eight materials by SHA-256. Android device
and iOS simulator each run all 62 cases. Keep the 27-case 4K sequence in one app
process, including cancel after at least 25 processed frames and immediate retry.
Independently decode every movie and check geometry, source PTS, frame count,
first/last frame, audio and privacy coverage. Keep manually reviewed hard frames
and the three existing conservative overmask observations explicit.

Identical recorded pixels/tensors/observations test the Rust core independently
of platform decoding and inference. Output file hashes need not match. No new
privacy omissions, missing frames or export failures are accepted. iOS simulator
results do not establish iPhone hardware performance. Stream 4K audits and retain
source fixtures/failure evidence without accumulating raw full-frame dumps.
