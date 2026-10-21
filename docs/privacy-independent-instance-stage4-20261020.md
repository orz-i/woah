# Woah Stage 4 — pixel ownership identifiability and protected-export evidence

2026-10-20. Branch `feat/crop-clarity-phase2-quality-performance`, start HEAD `f4c6d5dbfd255983391b87ef6a87373488ae8378`.

## Engineering verdict

**Stage 4 decision: a single independent MagicTouch/protagonist Mask plus currently available YOLO, tracked-bbox, and fresh-selected class observations is INSUFFICIENT to safely remove gray privacy coverage at 39.733–39.900 s. No production pixel carving is authorized.** This is a bounded falsification of *that specific evidence shortcut*, not a statement that independent instance segmentation is universally impossible.

Stage 3 is closed for synthetic CPU feasibility. A real PLK110 Android 17 run measured CPU `segment` median 508.078 ms, highest per-run P95 657.995 ms, and ~480–491 MiB final process PSS. The last figure is **not** model-only or peak RAM. Do not repeat routine Stage 3 CPU rounds or add full-frame MagicTouch inference to production.

Stage 4 adds two **isolated research / acceptance artifacts only**:

1. A six-frame **paired-world observational-ambiguity witness**, which proves that the *available model/track inputs* cannot always certify protected-person pixel identity.
2. A fix to the already existing **local final exported-video frame review**, removing a known incompatible Windows FFmpeg `-vsync` option and verifying output frame counts. This prepares source-of-truth protected-video review; it does **not** automatically decide identity or privacy correctness.

There is **no change** to the Android/iOS compositor, privacy classes, Kalman state, tracking masks, segmentation confidence thresholds, model assets, model API or user interface. No source or protected video was read, packaged or committed for this stage.

## A. Exact paired-world ambiguity (no videos or models)

Run:

```bash
python3 tools/diagnostics/stage4_ownership_ambiguity_witness.py
python3 -m unittest tools.diagnostics.test_stage4_ownership_ambiguity_witness -v
```

The script generates two **fully synthetic**, 32×24 byte-mask universes for each of the six historically investigated 39.733333 / 39.766666 / 39.800000 / 39.833333 / 39.866666 / 39.900000-second timestamps. Each pair has the **same** original source-space timestamp, YOLO-like conservative unknown Mask, independently prompted protagonist Mask, selected-person tracked Mask, separate selected-class Mask, protected bbox, claimed census, current protagonist identity and previous observed protagonist-root timestamps.

Only the simulated, *unobservable* protected-person ground truth differs:

- **World A:** protected person's true visible pixels match the observed selected-person Mask near the image's right side. A hypothetical gap in the protection area happens to affect no truly protected pixels.
- **World B:** a missed or mislocated protected person actually occupies the protagonist's previously visible region. The observed inputs are unchanged. That exact same hypothetical gap would expose protected-person pixels.

The existing Stage 1 `independent_instance_mask_gate.evaluate` evaluates each world *for diagnostics only*. For the fixed fixture, both suggest **143 hypothetical pixels**, but World A has **0 synthetic protected false clears**, while World B has **49 synthetic protected false clears** at each of six constructed timestamps. These values have **no units in real video pixel space**, are not measured Woah errors and are **not a production mask**. The executable returns ONLY safe integer/string/boolean aggregates with `pixel_carve_authorization=NEVER`, `real_video_privacy_acceptance=NOT_PROVEN`. It does not emit per-pixel coordinates, mask files, identifying RGB or video.

**Key inference:** When two different physical worlds yield identical available observations, an algorithm relying only on those observations **cannot know** which one occurred in the real clip. Even an `independently_verified_selected_census=True` flag is insufficient if its underlying source is not independently trustworthy. This witness does not establish which of the two worlds is present in the real 39.7-second export. It specifically refutes automatic clearing based only on the current MagicTouch candidate Mask and incompletely verified protected-person observations.

## B. Actual final export frame extraction: Windows compatibility

The existing **`tools/diagnostics/privacy_final_frame_review.py`** validates complete 1391-frame 30fps landscape (1280×720) and portrait (810×1440) outputs, locates risk frame PTS at 39.733s and 45.967–46.333s, and creates a **local-only** frame review page/checklist outside the Git worktree. It was still calling FFmpeg with `-vsync 0`, which had been rejected by the Windows FFmpeg binary in a prior Woah diagnostic attempt.

The Stage 4 change reuses the `woah_auto_privacy_capture.py` strategy:

- exact `select=eq(n\,index)...` on decoded-frame order, **not** a guessed `-ss` timestamp;
- `setpts=N/TB` to give **only the selected frames** a dense one-second presentation spacing, followed by `-r 1` for portable PNG image2 output;
- never use `-vsync` or `-fps_mode`;
- preserve original *source-relative* frame PTS from a separate `ffprobe` pass in `manifest.json`;
- compare count of extracted files against selected source frame indices, and refuse missing or extra outputs.

The prior tests plus two Stage 4 tests verify the FFmpeg command and privacy boundaries. In a **separate FFmpeg 7.1.5 Linux environment**, generated video (no people or user media) was tested twice: (a) a 60-frame 30fps 128×72 synthetic color-video with indices 3, 6, and 29 produced exactly **3 PNG files**; (b) a 1391-frame 30fps source (same frame count as the Woah export fixture) with indices 1189–1197 and 1375–1390 produced exactly **9** overlap and **16** end-window PNG files, respectively. The optional in-repository real-FFmpeg test runs only where `ffmpeg` and `ffprobe` are installed; the Mac lacks both and skips it. **Windows actual video decoding has not been run this stage.**

This final-output-only review is not a source pixel ownership oracle. It prepares protected images for **private review**, may still display recognizable people, and sets `visual_acceptance=NOT_REVIEWED` in its manifest. No images are uploaded or returned by this script, and user consent/sharing permission remains necessary for any external inspection. It must not be confused with the other local helper `woah_auto_privacy_capture.py`, whose ZIP also includes **unprotected original source crops** and has a stricter sharing risk.

Example for a local future final-export check (do not run until you have the actual corresponding complete outputs):

```powershell
uv run python .\tools\diagnostics\privacy_final_frame_review.py `
  --landscape "D:\WoahReview\landscape.mp4" `
  --portrait "D:\WoahReview\portrait.mp4" `
  --output-dir "D:\WoahReview\private-stage4-review"
```

**No new user material or manual step is requested now.** Without verified original-to-export source alignment and independently credible protected-person pixel ground truth, this review alone cannot authorize any unmasking.

## Stage 4 gates

| Gate | Evidence | Status |
| --- | --- | --- |
| Stage 3 public model SHA and synthetic CPU API | Actual PLK110 numeric report, three independently tagged runs | PASS for synthetic CPU-only experiment |
| Model-only identification of protected occupancy | Six indistinguishable synthetic observable-world pairs with contradictory protected truth | **NO-GO as sole evidence** |
| Original source vs protected export identity at 39.733–39.900s | Real paired pixel-level ground truth not supplied by numeric ZIPs | NOT PROVEN |
| Visible selected/unselected/unknown ownership | Independent external current-frame instance labelling, including occluded protected persons | NOT AVAILABLE |
| Source-space / portrait final-frame alignment | Full output metadata, actual final-frame review still required | NOT ACCEPTED |
| Windows FFmpeg `-vsync` portability | Option removed, command-contract tests and isolated FFmpeg 7.1.5 synthetic extraction checked | SOURCE FIX, WINDOWS EXPORT NOT YET TESTED |
| MagicTouch v2 distribution/consent, GPU and memory/thermal | Version-specific review + measurement | UNRESOLVED |
| 46s stale-mask nonregression, release Crop Clarity OFF/ON, Android native CI | Previous numeric regression only; final product gates remain | NOT SEALED |

### Engineering decision / next admissible evidence

- **Do not** ship MagicTouch, re-enable SAM2/ONNX, widen YOLO boxes, alter NMS, or carve unknown/protected regions by stale identity/appearance/foot-depth inference.
- **Only** a separately validated source-space person-identity truth that accounts for *all* visible protected people (and expressly marks invisible/ambiguous intersections as not clearable) could justify evaluating a new independent model-based corrective mask. A protected final-output screenshot or coarse 4×4 cell alone is not enough.
- A real candidate would need zero protected-person false-clears on held-out overlap frames, observable benefit on the protagonist, landscape/portrait source-space parity, preserved 46-second expiration behavior, and acceptable Release performance. Without these prerequisites, **retain fail-closed gray coverage even when visually undesirable**, and continue treating it as an unresolved product issue.
- No further Stage 4 model implementation is recommended until this *new type of independent evidence* is available. Move on to non-risky release engineering separately rather than indefinite mask-threshold experiments. Do not merge master or mark privacy PASS.

This is a bounded evidence/acceptance advance, **not a fix** for 39.733–39.900s protagonist overmask.
