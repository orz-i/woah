# M4 — crowded identity association, research checkpoint

Base `81f4b82c` (M3.3). Explicit host-only `--crowd-clothing`. **Generalization not proven; do not enable in native consumers.**

## Failure and shared algorithm

In Swing, a passing foreground camera operator occludes the pair. The dark-suit dancer's canonical body shrinks from about 305 px high to 173 px, then to a 58 px head/upper-body fragment. The shrunken reference cannot reliably reacquire the full dancer. The shape-only ablation prevents collapse but exchanges the partners: seven wrong source landmarks, so it is rejected.

FrameKernel now computes two anonymous color descriptors from exclusive current raw mask cells in the 18–50% and 50–83% relative body-height bands, excluding overlapping masks and source padding. Each band requires at least 20 high-support cells; RGB and chroma histograms both contribute, and the weakest band/space determines similarity. These are in-video clothing cues, not face embeddings. Freeze the initial per-proposal features; never learn from an occlusion. An explicit RGB session operation transports current pixels, which are not retained. Ordinary tracking operations cannot submit a new observation into this mode without RGB; miss/prediction steps remain valid.

For protected roots, combine current clothing evidence, bounded age (2s), height and travel plausibility, and current-mask geometry. Reserve only reciprocal appearance-supported matches with both row and column margins, and veto another fresh strong raw-mask owner. Compute matches before mutating tracks. Existing association paths additionally reject implausible fragments, clearly conflicting clothing, and unsupported long gaps. Fixed seed height caused a serious original-group regression when a dancer approached the camera. The retained v4 permits bounded observed scale growth and a short raw-mask continuity fallback for a root that has already grown substantially from its seed; it does not remove the long-gap appearance requirements. No model changes and no new native behavior unless the experimental entry point is explicitly called.

## Final replay results

| Case | M3.3 regions | M4 research regions |
|---|---:|---:|
| Swing selected ID6 | 146/300 | 265/300 |
| Swing partner ID7 | 20/300 | 265/300 |
| Group ID2 | 106/120 | 106/120 |
| Group ID3 | 106/120 | 106/120 |
| Group ID4 | 120/120 | 120/120 |
| Salsa rear / front | 80/80 each | 80/80 each |
| Single affine | 18/18 | 18/18 |
| Group mirror ID5 | 109/120 | 105/120 |
| Independent Basque ID0 | 254/275 | 59/275 |
| Independent Basque ID1 | 215/275 | 97/275 |

Swing source-only landmark ownership: 6/24 → 23/24 correct, zero wrong in both, one unconfirmed in candidate. Direct body observations: 134→253 and 11→254. These are sparse source body points, not pixel-level head privacy acceptance. The original 20 previously annotated head envelopes (IDs 2/3/4) remain fully covered with zero overlap against all 39 checked neighbor rectangles. Original group canonical direct observations for ID3/4 increase from 97→103 and 107→120, respectively; the previously resolved ID2 head remains protected. Salsa and affine reports are exactly unchanged. The mirror loses four rendered regions, so full regression acceptance fails.

Basque is an independent holdout, not a successful release case. Use the first preexisting landmark sample with unique supported selected/distractor bodies (1s into original 300-frame source), then replay the remaining 275 frames without using later labels for association. Candidate sparse landmarks: 6/20 correct, zero wrong, zero ambiguous, 14 unconfirmed; baseline 9/20 correct, one wrong, seven ambiguous, three unconfirmed. The new algorithm rejects many uncertain observations but loses sustained correct identity support as viewpoint/clothing visibility changes. A broader fresh reciprocal mask fallback improved Basque regions only modestly (75/275 and 99/275) while degrading Swing to 17/24 correct and 257/300, 143/300 regions, so that experiment was withdrawn. Fixed-size and broad fallback variants are archived locally; exact hashes and replay results are in `m4-summary.json`.

The retained candidate is useful research evidence, **not a stable algorithm**. The next work should replace immutable-only appearance gating with a carefully verified current/short-term/long-term appearance model, preserve scale/pose changes, and prevent duplicate competing identities without accepting unverified clothing matches. Validate Basque and mirrored regressions before additional independent held-out footage and device integration. No claim of mobile performance or native privacy acceptance.

## Verification and reproduction

Rust all-targets: 211 passed, 1 ignored. Strict Clippy, fmt, Python syntax, ABI v1 passed. Three new tests exercise exclusive clothing sampling and top-band exclusion, same-outfit reservation ambiguity, and wrong-outfit/fragment/expired evidence. All 11 final host replays exactly reproduce the retained v4 outputs. Raw media, intermediate patches and detailed traces stay local. The prior M3.3 Production CI iOS Phase 6 job failed; this host experiment does not diagnose or resolve that failure.

Build the M3.3 baseline and current release libraries. Use original group and Salsa manifests; use the C7.3 hash-matched Swing manifest and the Basque manifest sliced at original frame 25. For Basque audit, shift original source-only reference landmark times by 1s and preserve the coordinates; Swing labels use the unchanged C7.3 target role rewrapped as reference.

```sh
python tools/privacy/replay_face_tracking.py --baseline "$M33_LIB" --candidate "$M4_LIB" \
  --fixture "$SWING_MANIFEST" --target-id 6 --credible-roots --multiperson-recovery \
  --pair-continuity --same-base-policy --pair-head --merged-edge --crowded-recovery \
  --crowded-consensus --crowd-clothing --output swing-id6.json
```

Do not close Issue #47 or promote PR #48 on this checkpoint.
