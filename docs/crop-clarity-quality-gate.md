# Crop clarity phase 1: real-render A/B quality gate

**Status:** Debug capture/report tooling implemented. Real-device A/B and human image review remain acceptance gates; this document does not claim the 2x shader is perceptually better than interpolation.

## Scope and privacy boundaries

- Production output remains unchanged: full-source privacy composition (clarity before privacy effects) followed by post-crop `GL_LINEAR` scaling. Export still uses the deterministic shader and does not activate Neural SR.
- One-time **debug-only opt-in** creates a second privacy render target on selected frames. Both variants share the same decoded frame, tracked-person list, privacy classification/masks, effects, crop matrix, dimensions and PTS. Only `cropClarityScale` is changed to `1.0` for the baseline; the enhanced variant uses the original export request.
- Captured PNGs are **already privacy-composited final output frames**, not raw decoded input or masks. Nevertheless, unmasked people and background may be identifiable; treat the files as sensitive local media.
- A/B PNGs are retained in **app-private cache** and deliberately excluded from the standard Woah diagnostics ZIP. No automatic sharing/upload or main UI controls are introduced.
- Only first, two adjacent middle, and one **near-end** frame are sampled: **at most four pairs**. The late frame deliberately has a 2–6-frame guard before the metadata-estimated end because a completed 30fps export can contain 649 decoded frames even when its duration predicts 650. The last three private ZIPs at most are retained (older archives are rotated when a new QA run is armed). Quality captures are readback/PNG-heavy and must NOT be included in performance benchmarks.
- The marker is consumed for a single eligible export. Without the marker, both debug and release use the same single-pass production path; release cannot activate the capture.

## Real-device workflow (Android debug APK)

1. Use 1280×720 landscape source, choose a protagonist, enable follow 9:16, retain privacy targets, and export at the planned **810×1440 / 2×**. Also test 1080p→9:16 at ~1.78×. Keep a 4K control that should skip enhancement.
2. Before pressing Export, opt in to **one** capture with Android Debug Bridge:

   ```bash
   adb shell run-as art.gaoge.dance touch cache/crop_clarity_ab.enable
   ```

3. Export normally and let it finish. In the normal diagnostics ZIP look for `CROP_CLARITY_RENDER_ACTIVE` from `GlRenderer`: `uniform_verified=true`, `strength_uniform≈0.55` and `scale=2.0` for 720p. A `CROP_CLARITY_RENDER_UNVERIFIED` warning is **not** a pass. The event is emitted only once per compositor per export, after `glUniform1f` and `glGetUniformfv` readback.
4. List the separate private A/B ZIP:

   ```bash
   adb shell run-as art.gaoge.dance ls cache/crop_clarity_ab/
   ```

5. Copy the named ZIP into your own QA workstation (replace the placeholder):

   ```bash
   adb exec-out run-as art.gaoge.dance cat cache/crop_clarity_ab/crop_clarity_ab_<job-id>.zip > crop_clarity_ab.zip
   ```

6. Generate a **local-only** contact sheet and metrics JSON:

   ```bash
   python tools/diagnostics/crop_clarity_ab_report.py crop_clarity_ab.zip \
     --output-dir /tmp/woah-crop-clarity-review
   ```

   Open `crop_clarity_ab_contact_sheet.png`. Each row is **OFF (GL bilinear)**, **ON (current shader)**, and an amplified per-pixel **difference ×4**. `crop_clarity_ab_report.json` records changed-pixel percentage, mean absolute RGB difference, edge energy and new clipped-channel fraction. Where the two middle frames are adjacent, it also measures a **non-motion-compensated** difference in enhancement residual; it is only a flicker-screening hint, not a temporal quality proof.

7. Look for `CROP_CLARITY_AB_READY` in the normal diagnostic ZIP. It now lists `expected_frames`, `missing_frames`, `expected_pair_count`, `pair_count` and `complete` even though the photos remain private. A partial-but-readable ZIP is not a complete sampling pass. Delete the private A/B ZIP and report from every device/workstation after review. The normal diagnostics bundle deliberately never contains these images.

## Review rubric (no automatic "better" claim)

| Material | Check visually | Failure signal |
|---|---|---|
| Hair, shoes, patterned dance clothing | Fine detail without granular noise | Crunchy edges, random false texture |
| LED screens, spotlights, high-contrast stage | Clean dark/light transitions | White/dark halos, ringing or color shifts |
| Motion blur, fast turns | Detail remains temporally natural | Jitter/flicker in adjacent frames |
| Privacy targets, occlusions and mask boundaries | Blur/mosaic/solid/sticker still hides identity | Newly exposed facial/body features or shifted masks |
| Source already sufficient (4K) | No unnecessary sharpening | Activation on a 1.0× no-op crop |

Do not interpret larger edge energy or higher changed-pixel percentage as evidence of restored ground-truth details. Source compression can turn sharpening into amplified block artifacts. Accept/reject perceptual improvement only after human inspection of pairs and preferably representative contiguous-frame sequences.

## A/B comparability and limits

- In the Android post-crop path the compositor is called twice only on explicitly selected QA frames. All privacy inputs are identical; the same `visualCrop` computed **once** by the tracking follower is applied to both already-protected textures.
- The selected output is the actual enhanced branch left on the encoder surface, exactly as in non-QA exports. When QA fails, the export attempts to continue on the normal enhanced path.
- The ZIP includes `manifest.json` (`same_decoded_frame`, `same_privacy_state`, `same_crop_matrix`, crop rectangle and PTS). The report refuses other contracts or unprotected-data declarations.
- Camera/subject identity consistency and visual privacy still require the existing exported-video / diagnostic review. A/B image deltas alone cannot prove privacy, temporal stability or improvement.
- The iOS renderer emits a DEBUG `CROP_CLARITY_RENDER_ACTIVE` log after the Metal command succeeds; iOS **paired frame capture is not implemented in this phase**.
- Android Gradle/native compilation and real device capture must run on an Android toolchain/phone; the current development Mac lacks the Android SDK/JDK. Mac-side Swift parsing and isolated Python/report tests are not substitutes for native GPU acceptance.

## Reproducible verification commands

```bash
python -m unittest tools.diagnostics.test_crop_clarity_ab_report -v
python -m unittest tools.test_crop_clarity_model_tools -v
# On Android toolchain:
cd mobile/app/android && ./gradlew :dance_native:testDebugUnitTest
```

The report generates local-only artifacts and does **not** make a release-quality determination; the Phase 1 visual A/B verdict stays pending until real protected pairs are captured and reviewed.
