# Woah FULL_BODY — independent per-instance ownership feasibility, Stage 1

2026-10-20. Branch: `feat/crop-clarity-phase2-quality-performance`; baseline HEAD `eff04e5131ea4ebefb548ef6ca30665192622457`.

**Stage 1 verdict: AUTOMATED ADVERSARIAL EVIDENCE GATE IMPLEMENTED; NO DEVICE MODEL INTEGRATED; PRIVACY NOT ACCEPTED.** The original 39.733–39.900-second false grey masking persists. The 46-second stale-mask fix must remain intact. This stage neither ships a new ML model nor enables any pixel unmasking. No source video, screenshots, source RGB, YOLO mask arrays or user-identifiable media entered this work.

## Why independent segmentation is necessary, but not sufficient

Recent real Android 17 paired exports provided the following falsifiers:

- YOLO prototype signal directly above the contracted protagonist bbox is sparse (only 1–4 >=224 proto cells in two risk frames and none in other risk frames). Box extension is not justified.
- Legacy mask-aware NMS byte>=1 versus counterfactual >=128 retained precisely the same candidate set on all 10 audited focus frames in **both** orientations. The NMS shadow is retired.
- Kalman box continuity, crop geometry, foot-Y depth and face-only appearance matching do not attest per-pixel FULL_BODY root ownership; the previously attempted strong foreground carve was unable to produce a single new qualified pixel. Independent protected root ID1 genuinely exists nearby.
- Without an independently credible, current census of the protected people, a perfectly prompted object mask may still cover a protected person who crosses into the previous protagonist region.

A candidate that does not reliably separate **who** is in a pixel must not remove the privacy protection there.

### Shortlisted technical approach

**MediaPipe Interactive Image Segmenter / MagicTouch** is an *investigation candidate*, **not an adopted runtime**. The official task accepts object strokes/points on a frame, estimates the prompted object area and returns a per-pixel confidence mask. It is conceptually independent of the current YOLO11-seg instance-mask head; Woah already declares `com.google.mediapipe:tasks-vision:1.0.0` for unrelated face support, but **no compatible interactive-segmentation task/model/driver or version-specific API integration has been verified here**. Model artifact, license, API compatibility, SHA, image format and full memory cost must be checked first. Official MagicTouch benchmarks list **208.2 ms CPU / 580.6 ms GPU on Pixel 10** for the task pipeline, far over a **real-time** 30 fps frame interval (33.3 ms). Offline export can be slower than real time, but it might only be viable as a *sparse risk-frame proposal* if measured on the actual Android target; full-frame video application has no demonstrated acceptable throughput.

Official sources:

- https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter
- https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android
- https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter

Ordinary person **semantic** segmentation cannot separate two people solely through a shared `person` category, so it cannot be the ownership authority. Do not reinstall deprecated SAM2/ONNX paths or change YOLO post-processing based on the old logs.

## Executable Stage 1 artifact

`tools/diagnostics/independent_instance_mask_gate.py` is an **isolated, standard-library-only, in-memory evaluator**, with no data-file reader, decoder, frame renderer, Android call path or output Mask. Its prospective model contract is typed as `IndependentEvidence`. It accepts ephemeral synthetic byte masks *inside an automated test* but exports only aggregate integers plus `privacy_visual_acceptance=NOT_PROVEN` and `pixel_carve_authorization=NEVER`.

The gate enforces:

1. **Exact source-space contracts:** original source top-down width/height (not portrait crop), consistent current-frame PTS, bounded two previously observed seed timestamps, identical protagonist-root/prompt-root IDs. Any mismatch/reacquisition ambiguity defaults to no candidate.
2. **Independent segmentation provenance:** whitelist of a separately prompted model family or an explicitly **synthetic** model; YOLO's own instance mask, a generic semantic person model or an unverified model ID is rejected. Whitelist membership is a declaration, **not** cryptographic model attestation.
3. **Selected occupancy completeness:** every protected root must have current observation, a valid bbox, its current tracked/selected Mask and a separately proposed protected-instance mask. Missing evidence, unexpected extra selected IDs, malformed geometry or failure to claim a complete protected census block the entire proposal. The completeness **claim cannot currently be substantiated by Woah for actual occlusions**.
4. **Dual negative veto:** refuse every pixel within 2 source cells of a protected person's bbox, existing selected Mask **or independently proposed protected Mask**. Conservative unknown regions remain protected by default.
5. **Only interior positive candidates:** a prompted protagonist mask value >=240 in a 3×3 connected local neighborhood, within an existing conservative-unknown region with mask>=128, may be *counted as a hypothetical candidate*. No mask is returned, and no candidate enters privacy rendering.
6. **Synthetic-only truth check:** when a fully known synthetic ground-truth protected mask is provided, count would-be wrongly cleared protected pixels and stop on any positive count. Without truth, the status is `UNLABELED_HYPOTHESIS_NO_PRIVACY_ACCEPTANCE`; even with zero synthetic errors the status remains `SYNTHETIC_NO_FALSE_CLEAR_NOT_VIDEO_ACCEPTED`.

Thresholds and margins above are **research-only screening choices**, not product tunables or evidence that real-world outputs are safe.

### Reproducible local tests

```bash
python3 -m unittest tools.diagnostics.test_independent_instance_mask_gate -v
python3 -m unittest discover -s tools/diagnostics -p 'test_*.py' -q
```

The test module programmatically generates rectangles on 32×24 numerical planes; it reads or saves no source media. It covers missing selected masks/roots/bboxes, contradictory candidate identities, stale PTS, incorrect crop coordinates, weak or textureless proposals, protected-person/box overlap, cross-person confusions, and **96 automated adversarial intrusions** where a model's external completeness assertion is falsely trusted.

**Measured synthetic falsification:** 96/96 engineered stale-or-wrong-protected-position cases produced positive would-be false-clear counts; maximum **12 synthetic pixels** in one 32×24 case. This is deliberately constructed negative testing, **not a real-world failure-rate estimate**. The evaluator correctly reports `SYNTHETIC_FALSE_CLEAR_BLOCKS_MODEL` and `NEVER` authorization.

## Next-stage gates: not eligible for production integration yet

| Milestone | Required proof | Present status |
| --- | --- | --- |
| A. Independent model compatibility | Licensed model artifact with pinned SHA, supported task runtime, LiteRT/MediaPipe version/API and on-device image geometry contract | NOT STARTED |
| B. Representative automated evaluation | Synthetic known positive/negative identities, near-identical costumes, occluded protected person, disocclusion, all selected roots; *zero* protected false-clears including unobserved protected people | NOT PROVEN by synthetic exercise alone |
| C. Real frame pixel ownership | Independently reviewed protected output and per-instance identity in 39.733–39.900s; model's selected-person census must be externally corroborated | NOT AVAILABLE in numeric ZIPs |
| D. Performance | Bound additional frame processing, Android 17 actual memory/GPU/CPU dispatch and thermal impact; keep normal export unchanged when no added model is eligible | NOT MEASURED |
| E. Product sealing | Paired landscape/portrait, 46s stale mask nonregression, Crop Clarity quality + Release OFF/ON benchmark, native Gradle and end-to-end build/CI, final privacy frame review | BLOCKED |

**Engineering decision:** Stage 1 validates a fail-closed interface and confirms that a single automatically prompted foreground mask **cannot safely carve the unknown-selected region** when real protected occupancy is incomplete. Before any native integration, first demonstrate the independent model can disambiguate all selected people and has reliable current-frame negative evidence. Where that is impossible, retaining the grey privacy overlay is the only defensible automatic default. Do not generate more YOLO/NMS/box-threshold diagnostics; they are falsified for this fixture.
