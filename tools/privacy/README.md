# Shared real-media privacy regression

Run all commands from the Woah repository root. Production uses `main.dart`;
`privacy_regression_main.dart` is an opt-in QA entrypoint. The fixed matrix is
`testdata/privacy/matrix.json`: 22 legacy + 27 original-size 4K + 13 focused.
The three suites have overlapping case names, so each uses a separate run
folder and app launch. All 27 4K cases, including cancellation after at least
25 frames and immediate retry, stay in one app process.

## Build and bind the binary

Prepare the pinned Rust/Flutter/Android or Xcode environment, then build with:

```powershell
# Windows, from D:/dance-anonymizer
. .qa-runtime/rust-migration/woah-rust-env.ps1
python tools/privacy/build_qa.py android --output .qa-runtime/rust-migration/builds/STAGE
```

```bash
# Mac, from /Users/mini/Coding/woah
source /Users/mini/Coding/woah-rust-tools/env.sh
python3 tools/privacy/build_qa.py ios --output /Users/mini/Coding/woah-rust-tools/builds/STAGE --derived-data /Users/mini/Coding/woah-rust-tools/ios-build
```

The builder fingerprints source inputs before and after compilation, embeds the
fingerprint in the Dart harness, and archives the exact binary plus its source
and file-hash manifest. Changed inputs during compilation fail the binding.
Existing output folders are preserved; an unchanged, hash-valid build can be
reused. Android QA uses the separate `art.gaoge.dance.privacyqa` package.

## Run the same matrix

```powershell
python tools/privacy/run_regression.py android --prefix UNIQUE_RUN --suite all --install --media .qa-runtime/rust-migration/woah-rust-media --report reports/rust-core-migration-2026-11-06/full/android --binary .qa-runtime/rust-migration/builds/STAGE/app-debug.apk
```

```bash
python3 tools/privacy/run_regression.py ios --prefix UNIQUE_RUN --suite all --install --media reports/privacy-ios-regression-2026-11-04/artifacts/sources --report reports/rust-core-migration-2026-11-06/full/ios --binary /Users/mini/Coding/woah-rust-tools/builds/STAGE/Runner.app
```

The launcher verifies all eight fixture hashes and the archived build manifest.
The running harness checks its compiled source fingerprint before processing.
Each case checks first/middle/last previews; the full hero6 case also previews
6600 ms. Preview image bytes and dimensions are retained. Export frame progress
is persisted, with separate stall and whole-run limits. Each successful movie
and each per-frame source crop trace is retained. Android crops include source
coordinate conversion and explicit output binding; QA diagnostics omit large
raw frame/tensor dumps. Gallery checks retain an actual saved original, read
through the QA app's owned MediaStore URI on Android or checked in simulator
Photos storage on iOS.

For a stage-only check, `--case-filter` selects existing matrix cases without
changing their arguments. Such runs explicitly record `partial_stage_check`;
final 62-case acceptance must omit that option. Useful initial checks are
`group_face_solid_follow|solo_4k_custom_gallery_probe`.

A completed launcher proves API-flow and file-creation results only. Independent
full movie decode, timestamps, dimensions, audio and privacy/visual audits are
still required before acceptance. Preserve every failure and use a new run ID
for a repaired binary. Simulator results do not establish iPhone performance.
