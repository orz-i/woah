# Independent instance Stage 2 — MagicTouch model artifact and Android compatibility

2026-10-20. Branch: feat/crop-clarity-phase2-quality-performance. Stage 1 base c054cb5b682aa83665f438e86c02df40186e2764 was pushed and verified before this work.

**Decision: MODEL ARTIFACT PINNED AND STRUCTURALLY VERIFIED; MODEL NOT INTEGRATED; PRIVACY NOT ACCEPTED; PRODUCT NOT SEALED.**

This Stage 2 changes only public-model provenance tooling, tests and documents. It does not add a new Android/iOS dependency or model file, run segmentation on user media, remove a protected Mask, or alter the export pipeline.

## Official source and interface

Google Interactive Image Segmenter / MagicTouch is a separately prompted object segmenter, not a person-ID classifier. Official Android task code initializes InteractiveSegmenter via BaseOptions and setModelAssetPath, calls setImage(MPImage) and segment(strokes), and produces a per-pixel floating-point confidence mask. Positive/negative strokes select an object region, but do not prove that the object is the originally chosen protagonist or that every protected person is fully accounted for.

Woah already pins com.google.mediapipe:tasks-vision:1.0.0 for a different face-support task. Google's public Android sample imports InteractiveSegmenter, InteractiveSegmenterOptions and Stroke. **This is source-level API consistency only, not proof the exact 1.0.0 AAR has been compiled with Woah.** JDK / Android SDK remain unavailable on this Mac, and official Maven downloads timed out. Full Gradle compilation, a task initialization/inference smoke and correct CPU/GPU native behavior are still unverified. No dependency changes are authorized by this report.

References:
- https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android
- https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter
- https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/interactive_segmentation/android/app/src/main/java/com/mediapipe/example/interactivesegmentation/InteractiveSegmentationHelper.kt
- https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf

## Actual pinned model file — verified only in private /tmp

Immutable source (never use latest):
https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task

- Downloaded into /tmp/woah_stage2_mediapipe/magictouch-int8-v1.task via bounded HTTP range requests.
- Exact bytes: 30,525,312.
- SHA-256: 38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03.
- Full-file MD5 eff9593362906a47112886311c6d34c6 also matches the HEAD ETag from the exact numbered Google Cloud Storage object. SHA-256 remains the primary integrity pin.
- ZIP structure and CRC passed. The bundle has four entries:
  1. interactive_segmentation_decoder.int8.tflite — 2,268,000 bytes
  2. interactive_segmentation_encoder.int8.tflite — 28,256,696 bytes
  3. manifest.pb — 50 bytes
  4. metadata.pb — 12 bytes.
- Both TFLite members passed TFL3 identifier inspection. A separate read-only FlatBuffers I/O header inspection (without inference) found encoder input **[1,768,768,3]** and five feature outputs **[1,384,384,16], [1,192,192,24], [1,96,96,40], [1,48,48,80], [1,24,24,192]**. The decoder declares these five feature inputs plus another **[1,768,768,3]** input, and one output **[1,768,768,1]**. These are declared shapes, not runtime results. The committed offline verification script pins the full artifact and TFL3 members rather than reproducing the ad hoc FlatBuffers tensor traversal. Normalization, point/stroke encoding, confidence semantics, source geometry mapping and Android execution remain **UNVERIFIED**.
- The repository contains ONLY the small immutable JSON pin: tools/diagnostics/baselines/mediapipe_magictouch_int8_1.lock.json.
- Absolutely no model weights, user video, screenshot, RGB, original source frames or raw YOLO Mask are committed or sent into ordinary diagnostics.

## Security contract / executable verification

tools/diagnostics/verify_interactive_segmenter_asset.py validates only offline public model archive structure and provenance: exact upstream versioned HTTPS URL, expected 30,525,312 bytes, SHA-256, GCS MD5 ETag cross-check, current pinned Android library coordinate, intended-use/licensing review hold, expected doc shape 768x768x3, ZIP member safety, TFLite signatures and CRC.

Command from repository root, with complete model already in /tmp:

    python3 tools/diagnostics/verify_interactive_segmenter_asset.py --artifact /tmp/woah_stage2_mediapipe/magictouch-int8-v1.task

Result is PINNED_STRUCTURAL_INTEGRITY_ONLY and explicitly states android_api_abi=NOT_VERIFIED_BY_ARCHIVE, model_license_scope=REQUIRES_VERSION_SPECIFIC_REVIEW, privacy_visual_acceptance=NOT_PROVEN and pixel_carve_authorization=NEVER. The tool is not a downloader or inference implementation.

Unit tests construct synthetic tiny ZIP/TFLite-header fixtures in external temporary directories; they test wrong SHA and size, invalid upstream/mutable model URL, wrong input spec, unexpected metadata, missing model bytes, archive path traversal, malformed TFLite, and rejecting model files inside the Git worktree. The tests never process a user image.

Run:

    python3 -m unittest tools.diagnostics.test_verify_interactive_segmenter_asset -v
    python3 -m unittest discover -s tools/diagnostics -p 'test_*.py' -q

## Important version + licensing caveat

The 2026 official overview describes a current 768x768x3 Int8 MagicTouch. Its Pixel 10 full-task averages are CPU **208.2 ms** and GPU **580.6 ms**. These are not Woah PLK110 / Android 17 measurements. Across 1391 frames, simple serial extrapolation would be about 290 s CPU or 808 s GPU for this task alone, excluding other export work. Offline export is allowed to be slower than playback, but full-clip throughput, peak GPU/RAM and thermal safety are unverified.

The linked **2023 model card** instead describes an earlier 6 MB 512x512x4 architecture, says Apache 2.0 for that historical model, identifies closely packed objects/fine features as potential failure cases and explicitly places human segmentation without human consent outside its intended use. The older card must not be treated as a verified model tensor spec, license or scope approval for the pinned 2026 v2 binary. Current v2 redistribution and use in Woah need a version-specific permission / scope review before any app packaging. **NO automatic approval follows from the historical Apache reference.**

## Gate decisions

| Gate | Evidence | Status |
| --- | --- | --- |
| Exact public artifact bytes/version/ZIP/TFLite signature | Full official v1 bundle verified by SHA and CRC, pinned lock | PASS — structure/provenance only |
| Resolved tasks-vision 1.0.0 API/ABI | Official source signatures appear compatible, not built on Android | NOT VERIFIED |
| Declared model inputs/outputs | Downloaded encoder/decoder tensor headers include 768-pixel image input, 5 feature levels and single-channel 768 output | PARTIAL: shape declarations inspected only |
| Model prompts, same-source PTS, CPU/GPU/memory | No Android device model execution or source-space mapping performed | NOT VERIFIED |
| Version-specific model licensing and consent fit | Historical card differs from v2 and flags nonconsensual human segmentation | REVIEW REQUIRED |
| Independent protected-person occupancy | Stage 1 falsification: 96/96 deliberately wrong/missing occupancy synthetic layouts generated protected false-clear hypotheses | FAIL / NO CARVE AUTHORITY |
| Real final video 39.733–39.900s and 46s | Current source-space metrics only; no accepted final protected pixels | BLOCKED |
| Crop Clarity Release OFF/ON / full CI / master merge | Remaining product requirements and explicit merge approval outstanding | BLOCKED |

**Stage 2 engineering disposition:** The model *artifact provenance* gate can be closed. The *deployment and identity-safety* gates cannot. MagicTouch is a candidate for an isolated, synthetic-image Android smoke only after confirming the actual Android ABI and version-specific use rights, and cannot be wired to PrivacyOcclusionResolver or used to clear selected/unknown Masks until independently verified current protected occupancy and real pixel-level privacy acceptance are available. Do not resume SAM2/ONNX, NMS, YOLO bbox expansion or speculative tracking-based privacy holes.
