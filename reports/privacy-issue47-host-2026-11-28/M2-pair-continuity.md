# M2 — independent partner-dance identity association

Base `fce2d9ee` (M1). Host-only candidate; explicit `--pair-continuity` opt-in. No mobile activation.

## Failure and evidence

The earlier crossing fixture does not provide a valid multi-person seed: sampled raw inference usually produces one near-full-frame instance. The independent C9.3 `Salsa_Dancing_Fundamentals.webm` partner fixture (80 frames, 25 fps, 960×540; source video hash in M2 summary) has preexisting source-only selected and distractor landmarks. Initial masks uniquely support rear teal dancer (ID 0) and foreground burgundy dancer (ID 1). In baseline, the rear identity becomes unobserved although its raw silhouette remains present; the foreground identity later commits the rear raw body. At frame 22 prior-to-current unwarped mask IoUs for own bodies are approximately 0.83/0.90 versus crossed 0.04/0.06. At frame 26 they are still approximately 0.66/0.65 versus crossed 0.03/0.11 against the last committed observations; nevertheless ID1 incorrectly commits the rear body. This is a tracking association failure, not evidence of an invisible head. Coarse appearance similarities are too close to provide an independent decisive signal, so no new color gate was adopted.

## Shared Rust candidate

For exactly two current detections and two protected tracks in a face-only overlap group (one selected face, no selected body), test a complete reciprocal pair using the last actual canonical masks and both current raw masks. Both prior masks must be no more than 150ms old, comparable grid/transform, and both old and current pairs have mask IoU <=0.10. Each diagonal requires raw IoU >=0.65, body IoU >=0.60, and >=0.25 margin against both competing row and column. Current detections require confidence >=0.50. Accept only a unique complete permutation, then use existing canonical commit for both; otherwise run existing association. Missing-mask commits cannot refresh stale mask evidence. The new C opt-in is default off and can only be set before session initialization; native callers unchanged. This experiment is intentionally restricted to a complete two-person scene. It cannot safely generalize to long gaps or merged/indistinguishable silhouettes.

## Results

| Case | M1 rendered | M2 rendered | M1 observed body | M2 observed body |
|---|---:|---:|---:|---:|
| Salsa rear ID0 | 25/80 | 80/80 | 21/80 | 79/80 |
| Salsa foreground ID1 | 77/80 | 80/80 | 76/80 | 80/80 |
| Original group ID4 | 120/120 | 120/120 | 107/120 | 107/120 |
| Original group ID3 | 106/120 | 106/120 | 97/120 | 97/120 |
| Original group ID2 | 108/120 | 108/120 | 108/120 | 108/120 |
| Original single affine | 18/18 | 18/18 | 15/18 | 15/18 |

Original group and single M1 candidate reports exactly equal to M2 candidate reports (including tracking state and geometry). Independent Salsa expected identity mapping at four frozen source body landmark times per identity: baseline 2 correct, 2 wrong, 4 unobserved; M2 8 correct, 0 wrong, 0 unobserved. Audit repeats inference on exact source frame hashes, requires original raw boxes, maps observed track to the uniquely matching raw mask and checks correct and rival landmark support. These are body identity points, not head rectangles or complete pixel privacy truth. Corrected foreground face path is predominantly fallback (15 direct / 19 projected / 46 estimated), so its 80/80 regions are not proof of adequate face coverage. The alternative overhead group fixture has neither annotated initial subject supported by its raw masks; do not count it as a successful holdout. No thresholds were lowered to make it pass.

Rust all-targets 200 pass, 1 ignored; strict Clippy, v1 ABI fingerprint, and Python syntax pass. Full trace and hashes in `m2-summary.json`. Raw media stay local. Next: source-reviewed head envelopes/neighbor coverage and partial-face current detection, then independent multi-person and same-outfit adversarial scenes. No mobile integration or issue closure.

## Reproduction

Use the already hashed C9.3 partner video in `testdata/comparison/real-dance-c93-partner.json`; extract all 80 frames using `ffmpeg -i salsa-short.mp4 -map 0:v:0 -q:v 2 -start_number 0 frame-%03d.jpg` and produce the 25fps manifest with each frame SHA-256 and `source_sha256`. Build the release dylib from M1 and this candidate. For each target 0 and 1:

```sh
python tools/privacy/replay_face_tracking.py --baseline "$M1_LIB" --candidate "$M2_LIB" \
  --fixture "$SALSA_MANIFEST" --target-id 0 --credible-roots --multiperson-recovery \
  --pair-continuity --output salsa-id0.json
python tools/privacy/audit_pair_identity_landmarks.py --library "$M1_LIB" \
  --fixture "$SALSA_MANIFEST" --labels testdata/comparison/real-dance-c93-partner.json \
  --replay salsa-id0.json --selected-id 0 --distractor-id 1 --output audit.json
```
