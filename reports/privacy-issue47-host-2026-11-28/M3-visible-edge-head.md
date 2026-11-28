# M3 — visible top-edge head geometry and current-pair ownership

Base `02aa5f52` (M2). Host research only, `--pair-head` explicit opt-in. No additional model inference or native activation in the retained candidate. M2 baseline and M3 use the same pair association/recovery policy (`--same-base-policy`).

## Source review and failure mechanism

The independent Salsa foreground subject is predominantly rear/side-facing, often cropped by the top image edge. Low direct face detection count is not itself a failure. Eight source-only samples per subject were annotated as approximate visible head rectangles, with the other subject's head rectangle and a torso probe; labels and fixture/frame hashes are in `m3-salsa-head-labels.json`. M2 foreground square covers all eight target rectangles but frequently covers the neighbor, in part because body-box fallback radii and off-image bounds inflate the visible square. Current segmentation also leaks rear-face pixels into the foreground instance, causing false direct ownership. The earlier source identity landmarks are unchanged.

## Retained algorithm

- Complete pair of two raw current masks and exact selected canonical current observation. Two protected initial identities and two retained tracks in the host adapter. No history, annotation, or warped mask is used as current evidence.
- Only a person touching the top image boundary qualifies. Use the existing connected cap trace with a sustained lower widening, a complete cap, >=6 rows, actual source edge row, valid head center, bounded height, >=90% strong support and <=5% competing support. Both masks must have nonempty foreground evidence. The weak bbox-derived region locates the cap but does not size the sticker. Preserve all selected cap bounds with one lateral and two lower prototype cells of sampling margin; no off-image requirement inflates the visible square.
- Fresh top-edge face ownership also requires the existing detector geometry/mask gate, zero rival support at sampled unpadded-bbox evidence, and, when qualified, the detector center inside the current cap. Non-edge ownership follows the unchanged path. No additional direct face is created from a cap. For projected/estimated regions with a qualified current cap, use its compact visible square only at final host placement; do not feed it into canonical tracking, face observation clocks, or the temporal render state. Actual native ellipse/mask compositing is still untested; the legacy host ellipse remains separate from the experimental square.

## Results, sparse source-only geometric audit

| Metric, foreground ID1 | M2 | Retained M3 |
|---|---:|---:|
| Regions | 80/80 | 80/80 |
| Full approximate visible head rectangles | 8/8 | 8/8 |
| Average neighbor head rectangle overlap | 42.70% | 9.44% |
| Maximum neighbor head rectangle overlap | 100.00% | 54.55% |
| Torso probes covered | 3/8 | 0/8 |
| Direct / projected / estimated | 15 / 19 / 46 | 9 / 14 / 57 |
| New compact geometry frames | 0 | 54 |

Rear ID0 output exactly matches M2: 80/80 regions, 7/8 complete approximate rectangles (minimum 85.06%). Both identities still pass all eight frozen body-landmark ownership checks. All six comparison cases have byte-identical tracking snapshots; the rear, original group ID2/3/4 and affine candidate reports are exactly unchanged from M2. Group still has 26 unresolved target frames. A foreground merged-mask frame remains high overlap (only one raw detection; compact lane unavailable), requiring a separate partial visibility/instance separation approach rather than treating the old neighbor mask as current evidence.

Rejected experiments: the universal pair face veto regressed rear ID0 to 79/80 regions, so it was narrowed to the top-edge case. A second detector crop around the cap generated 12 hits in 54 additional calls but had mean neighbor overlap 19.65% vs 9.44% for cap-only, with equal 8/8 target rectangles; its code was removed. No detector thresholds relaxed and no extra inference retained. These sparse rectangle measurements are host placement comparisons, not complete pixel-level privacy acceptance or proof of face visibility. The next stage should address merged masks/partial visible faces and independent crowded scenes before mobile integration.

Rust all-targets 203 pass, 1 ignored; strict Clippy, fmt, ABI v1 and Python syntax pass. Summary and hashes in `m3-summary.json`; raw outputs stay local. No native runtime behavior change.

## Reproduction

Use the hashed 25fps Salsa manifest from M2 and build release libraries at M2 and M3. For each selected ID 0/1:

```sh
python tools/privacy/replay_face_tracking.py --baseline "$M2_LIB" --candidate "$M3_LIB" \
  --fixture "$SALSA_MANIFEST" --target-id 1 --credible-roots --multiperson-recovery \
  --pair-continuity --same-base-policy --pair-head --output salsa-front.json
python tools/privacy/audit_face_sticker_samples.py --replay salsa-front.json \
  --fixture "$SALSA_MANIFEST" --labels reports/privacy-issue47-host-2026-11-28/m3-salsa-head-labels.json \
  --case salsa_front --output front-audit.json
```
