# FULL_BODY 39.7s: bounded YOLO NMS visibility shadow (2026-10-20)

**Status:** diagnostic-only NMS counterfactual; **NO** privacy composition or
production detection change. **NOT SEALED / NOT VISUALLY ACCEPTED.** Starting
branch HEAD: `d2acff26c71ab8b86782313088f96603e3c661fd`.

## Existing evidence and new mechanism

The 3b4ba5a Android dual-mode run confirmed 1391-frame parity and persistent
false grey masking on the protagonist at 39.733–39.900s. The selected unknown
instance is protected and an independently observed selected ID1 exists near
that area. The current selected-vs-unselected strong foreground intersection
is zero from 39.800s onward; extending the protagonist's retained bounding
box or lowering carve thresholds cannot prove the missing pixels' ownership.

Code review reveals **an independent suppression mechanism**:
`YoloMaskDecoder.maskAwareNms` previously counted every nonzero byte in the
soft Mask for mask IoU. A YOLO sigmoid byte of 1–127 is low-probability, but
can cause suppression alongside a genuine >=128 foreground core. Two
overlapping candidate boxes can thus be treated as duplicates even when
their stronger segmented foregrounds are disjoint. Existing hard 0/255 NMS
tests never exercised this contrast.

This is a general model-postprocessing hypothesis, **not yet the proven cause
of the 39.8s defect**. Even a newly retained detection need not belong to the
protagonist and might be a protected passerby. It must **never** be used for
automatic identity promotion or carving without independent pixel truth.

## Implementation boundaries

- `YoloMaskDecoder` has a configurable **comparison-only** active-byte
  threshold. **Production default remains 1**, and the original
  threshold-1 hot loops are preserved exactly, with no change to candidate
  selection or returned Mask bytes.
- The second NMS evaluation at active threshold **128** is executed only
  with `BuildConfig.DEBUG`, a nonempty diagnostic job ID and PTS
  **39.600–39.910 seconds**. The production `kept` list is unchanged;
  only its count and model-space geometry are compared to the shadow.
  No second inference or source image capture occurs.
- The event `YOLO_NMS_VISIBLE_THRESHOLD_SHADOW` records current job ID,
  PTS, raw candidate count, legacy/strong kept counts, shadow-only and
  legacy-only counts, **at most six** shadow-only candidate model-space
  bounding boxes and confidences, and a truncation flag. It emits
  **no RGB, raw masks, tensor arrays, image crops, identity labels or
  background media**.
- `analyze_privacy_overlap_evidence.py` validates the numeric schema,
  refuses impossible candidate partitions, isolates the current job from
  historical JSONL shards, checks landscape/portrait source-space equality
  and reports `YOLO_NMS_SHADOW`. Historical bundles without the new event
  remain readable and show `NMS_SHADOW_NOT_PRESENT`, never an invented
  PASS.
- A synthetic Kotlin counterexample distinguishes two disjoint strong
  foreground cores with shared low-probability tails. A strong duplicate
  must still be suppressed at 128; a randomized bounded-support test
  checks full-scan parity at 1, 128 and 224. Android native compilation is
  needed to execute these tests.

## Expected next decision

1. Build a Debug APK from **this commit** using the existing Windows Android
   workstation. Export the **same authorized source** once in portrait and
   landscape, using the established identities/settings. Collect only the
   normal two numeric diagnostic ZIPs; do not upload original video,
   unmasked screenshots or raw masks.
2. Run the existing analyzer from the repository root, writing the report
   **outside the Git tree**:

   ```powershell
   py -3 tools\diagnostics\analyze_privacy_overlap_evidence.py `
     --landscape "D:\Diag\landscape.zip" `
     --portrait "D:\Diag\portrait.zip" `
     --output "D:\Diag\nms-shadow-report.json"
   ```

3. If `strong_only_count=0` in all six risk frames, **retire this hypothesis**
   without modifying production NMS. If there are additional strong-only
   detections, inspect anonymous geometry and source-frame pixel identity
   **independently**, with the observed protected ID1 and all unknowns
   retaining protection. Counterfactual candidate counts, source-space
   mode parity and synthetic tests alone never authorize a privacy cut.
4. Recheck protected final-video 39.7–39.9s and 46s tail after any actual
   compositor or inference change, with privacy-safe automated capture only
   if new visual evidence is essential.

## Native toolchain and seal gate

This Mac had no JDK/Android SDK. An isolated Homebrew OpenJDK17/SDK setup
was attempted through tmux, but prerequisite downloads remained stalled;
the job was stopped without installing the runtime. This is an environment
gate, **not** a native test pass. Kotlin JVM tests and APK compilation must
run on a provisioned Android build machine.

The 46-second observed-age expiration remains unchanged. The earlier
39.733s coverage warning and protagonist overmask remain. Release performance,
Crop Clarity visual QA and final-output privacy review still block full
phase sealing and master merge. This shadow is a **single bounded decision
experiment**, not the start of indefinite instrumentation.
