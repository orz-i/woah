# S6 — iOS current head ellipse and two-axis continuity

**Original Issue #43 numeric gate PASS; all three actual simulator exports pass the original sampled width/height threshold. Retain the scoped iOS candidate. Full-frame left width ratio and approximate head-envelope boundary residual remain; broader privacy acceptance not proven.**

Baseline aef433ca (native integration c2d2d9e9). iPhone 15 Pro simulator, iOS 17.0.1, Xcode 27.2; 18-frame 720x1280 affine and two 90-frame 592x1344 real clips, all 30fps. No device or Android acceptance.

## Algorithm
- Native traces identified oversized legacy rectangle support from connected raised arms and a low full-frame face proposal near the torso. The host compact sticker square cannot directly replace a mask-clipped native ellipse.
- FrameKernel constructs an ellipse around actual connected head rows, preserving direct measured and stabilized ellipses, and uses real cell corners instead of invented cross-row rectangle corners. Unqualified topology uses conservative legacy support based on the direct detector rather than enlarged temporal radii. Current full head evidence can correct a bounded weak center without renewing identity.
- Direct detector corroboration allows a one-cell crown seam only when the next row joins both fragments. Bounded early widening and lateral branch retraction distinguish the head from shoulder/arm widening. A uniquely owned local face can override a coarse torso proposal only when inside a qualified head cap and the primary center clearly falls below it. Existing association/competitor checks remain.
- Optional per-axis render-size state contains the existing major-axis guard, smooths actual rendered radii independently, grows immediately, preserves 150ms direct lease semantics, resets on gaps/seek. Swift uses it only for one explicitly selected sticker; other paths retain prior behavior.
- iOS reuses the existing FrameKernel ROI planner and face model. Shared C APIs are available to Android but Android was not changed this turn. No #47 research reintroduced.

## Actual exports (sampled decoded yellow/changed pixels)

| Case | Baseline W/H | Cap v1 W/H | Cap v2 W/H | Final v3 W/H |
|---|---:|---:|---:|---:|
| Original | 1.214 / 1.169 | 1.176 / 1.131 | 1.176 / 1.131 | 1.133 / 1.131 |
| Reference left | 2.215 / 2.000 | 1.947 / 1.854 | 1.311 / 1.253 | 1.297 / 1.253 |
| Target right | 1.803 / 1.744 | 1.758 / 1.777 | 1.284 / 1.267 | 1.284 / 1.267 |

Final all-frame width/height ratios: original 1.1433/1.1424; left 1.3556/1.2889; right 1.2841/1.2674. The original 7-sample gate is unchanged; the all-frame left ratio is slightly above 1.35 and reported separately. Original motion errors 15.243 / 22.338px (baseline 51.243 / 47.174), 128px horizontal span; all original numeric assertions pass. 198/198 selected native regions, zero extra region IDs, three preview/control pairs per clip. All six native smoke groups pass including shared axis state transport.

Native approximate source-envelope ellipse coverage: original 18/18 full, no checked neighbor overlap; left 6/8 full, min 97.848%; right 5/8 full, min 98.984% (baseline 55.455%). Inclusive source rectangles are sparse geometric probes, not semantic privacy ground truth; residual corners are not declared harmless. Original changed>=90 pixel fraction minimum 98.076%, neighbor 0. Visual review of cap-v2 raised-arm frames confirmed the large shoulder-covering expansion is materially reduced; v3 axis guard only adds coverage relative to v2. Dark artwork may match dark input colors, so changed-pixel fraction is not identical to physical privacy coverage.

## Verification and scope decision
191 Rust tests passed, zero failed, one ignored; strict Clippy and formatting passed. Final simulator source fingerprint verified against current tree, all three outputs decoded, hashes and per-frame final audit in s6-ios-ellipse-summary.json. Raw masks and videos remain in local issue43/ios-s6 evidence directory. Preserve v3 in PR #46; avoid further tuning solely to round the 1.3556 diagnostic ratio below 1.35. Remaining: Android transport/device parity and verify unresolved coverage edges. No merge or release claim.
