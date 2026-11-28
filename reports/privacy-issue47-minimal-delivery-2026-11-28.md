# Issue #47 — minimal delivery review

Base: `dd940184` (master). Original draft research at `f7f72349` is preserved on [research/archive-issue47-m6-20261128](https://github.com/orz-i/woah/tree/research/archive-issue47-m6-20261128). PR #48 is narrowed, without rewriting its history. The previous 66,635 added lines (54,574 report lines, 9,346 Rust lines, 2,714 tool lines, one mobile lockfile addition) are not the production deliverable. The retained source change `2b15676b` adds 164 lines in three existing FrameKernel tracking files, including tests. No new FFI, model, inference pass, mobile runtime, or renderer change.

## Scope and implementation

Deliver a short-time association correction for a complete two-person scene, two protected retained identities, exactly one selected FACE_ONLY identity, no full-body selection. In an overlap group, compare both last actual masks against both current masks on identical grids/transforms. Both previous masks must be within 150 ms; current confidence >=0.50, intra-pair raw-mask IoU <=0.10, own raw IoU >=0.65, row and column margins >=0.25, observed-body IoU >=0.60. Only a unique complete permutation uses the existing canonical commit; missing, merged, stale or ambiguous evidence falls back to the original path. A bbox-only observation does not refresh the mask clock. Existing Android and iOS tracking session entrypoints exercise this policy automatically under the restricted selection scope.

Long reappearance, crowded clothing/re-ID, pose/anatomical geometry, independent head detectors and offline reconnect are archived, not shipped in this PR. Their measured research improvements are not release acceptance. Existing models and privacy fallback/export behavior remain unchanged. Issue #47 stays open; the narrowed PR is not a full solution to the original multi-person privacy problem.

## Verification against master

Both master and candidate Release dylibs rebuilt. Same decoded source observations, pinned existing person/face models, no research flags. 14 target replays, 2,683 frames. Outside the two Salsa targets, all 12 controls / 2,523 candidate rows equal master, including crowded clips and single-person regression.

| Salsa target | Body observations master -> candidate | Host regions master -> candidate |
|---|---:|---:|
| rear ID0 | 21 -> 79 / 80 | 25 -> 80 / 80 |
| front ID1 | 76 -> 80 / 80 | 77 -> 80 / 80 |

Frozen source-only body landmarks: in each of the two target replays, 2/8 correct, 2 wrong, 4 unobserved/ambiguous on master; 8/8 correct on candidate. Sparse identity evidence, not full privacy coverage. Rust 193 passed, 1 ignored; Clippy warnings denied and ABI v1 smoke passed. ABI geometry SHA-256 remains `534f5c46ba485af3a11f667bf0edfd8f64dd8c6362fad846fa6c04406f3dc7ff`.

## Native verification

Both QA builds use source commit `2b15676bfc639baee4b5f4af3b6f8735b673550b` and source fingerprint `2bc52505bd081a51ae514d0e0073f9ef3e6259f38df59048b506c9f23ad2ab94`. No new assets packaged.

- **iOS**: iPhone 15 Pro simulator iOS 17.0, 6 native smoke tests passed; two FACE_ONLY sticker cases, each with first/middle/last previews and actual export. Both decoded 80 frames, 960x540, 25 fps, 3.2 s. Native recorded body observations 79/80 rear and 80/80 front, both 80/80 selected face region records; 8/8 correct source-body landmark matches per native export (16/16 total). The app input is a separately re-encoded derivative from the 80 frozen Salsa fixture JPEGs (original source hash in testdata/comparison/real-dance-c93-partner.json); derivative SHA-256 `89cbe6253ee4c34c3e5aaa3bfd1b9e561fd075e33a29bb094b6f3c83707b4057`. At eight approximate reviewed head envelopes per target, minimum recorded ellipse overlap 98.76% rear / 100% front. Decoded source/output mean-RGB absolute delta >30 affected at least 96.04% / 78.07% of each sampled target rectangle; this is a pixel-change diagnostic, not proof of complete anonymization. Maximum reviewed neighbor ellipse overlap was 55.72% / 100%; at the latter sample 99.64% of neighbor pixels changed. Neighbor over-coverage remains an actual blocker to broad privacy acceptance; this PR only changes identity association. The simulator does not establish device performance.
- **Android**: JNI/Kotlin 651 tests passed, no failures/errors/skips; Release Kotlin compiled; isolated QA APK built (package `art.gaoge.dance.privacyqa`, SHA-256 `5fcbee2491ce4591e5b34a7d582b37260fa17f919a27ce0080f36cdaefe11e97`). No ADB device connected; Android real-device preview/export is still pending. No claim of cross-platform export equivalence.

Local source, binary manifests, native traces, decoded-output audit and reproduction scripts remain in `~/Coding/woah-rust-tools/issue47-local/delivery47/` (native artifacts under `native/`). No raw media, masks, new model weights or bulk trace arrays committed. Android next: connect actual device, install the bound QA APK, run both explicit Salsa selections with the same derivative input, verify actual exported pixels and identity. Keep PR draft until this verification and review; do not close #47 or claim release-level acceptance.
