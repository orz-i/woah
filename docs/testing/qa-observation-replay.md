# QA detector observation replay

The 62-case Android and iOS matrices still exercise real native exports. Verified
detector observations can be reused across solid, mosaic, blur and sticker
treatments. This changes the test execution method, not the production algorithm.

## What is reused

Only postprocessed YOLO person detections and prototype masks, and GridFace face
observations/landmarks. Every call first prepares the actual model input from
native decoded pixels. Its exact float bits, source dimensions, inference backend
and thread settings form the cache key together with a component/environment
contract. Android and iOS caches stay on their originating device/host.

Shared Rust tracking, identity ownership, temporal continuation, current face
coverage and final privacy masks execute again on every frame. Rendering,
encoding, audio, trim endpoints, preview, gallery, cancellation, retry and
successive exports remain native operations. A cache hit cannot mark an export
successful.

## Controls and qualification

Ordinary Android packages, release builds, and iOS physical devices cannot
activate this path. Android requires a debug package ending in `.privacyqa`;
iOS requires a debug simulator build. Both additionally require a local
`qa_observation_replay.txt` containing three lines:

1. `qa-observation-v1`
2. SHA-256 of the installed APK or simulator Runner executable
3. SHA-256 of the observation component and device environment contract

Use `tools/privacy/observation_replay.py control` with the archived build manifest
and a local environment JSON containing `platform`, `device`, `os`, and
`runtime`. The contract conservatively includes model/runtime/postprocessing
sources. The separate face rendering geometry files are excluded because the
latest geometry runs after replay. Changes to included dependencies or the device
environment select a new cache namespace.

Inference writes pending immutable payloads and a local event ledger. Pending
payloads never replay. After a baseline's actual media and privacy evidence has
been reviewed, `approve` requires an explicit review receipt bound to its exact
results, source commit, reviewed case IDs and event ledger hashes. It qualifies
only actual inference events from that baseline's process and time interval.
Payload hash mismatch aborts approval. Nondeterministic conflicting payloads are
quarantined and cannot replay.

The review receipt fields are `decision: approved_observation_baseline`,
`baseline_results_sha256`, `compiled_source_commit`, `reviewed_case_ids`, and
`events_sha256`. These are evidence bindings, not an automatic privacy verdict.
Do not create a receipt before reviewing the corresponding baseline.

Approved payloads are checked for checksum, bounded shape, valid coordinates and
finite float values; each replay returns independent buffers. Missing, corrupt,
incompatible or conflicting entries fall back to actual inference. Record actual
inference, replay and rejected/conflicting counts separately. A cold/hot native
probe must verify decoded video frames and previews before enabling replay for a
full regression; report any residual pixel differences explicitly.

## Regression evidence

The frozen 8 inputs and 22 + 27 + 13 case definitions remain unchanged. A short
diagnostic or replay qualification run does not count as completing that matrix.
Fresh full-duration native media checks remain necessary after algorithm changes.
Keep independent source-face audits and focused risk spans, including the newly
found profile transition. Reuse a visual review only with an exact matching
evidence/provenance binding.

Raw observation payloads, masks, face coordinates, rendered crops and approval
files remain local ignored QA artifacts. Publish generic code, synthetic tests,
aggregate counts and hashes only. Remove the control file after testing.
