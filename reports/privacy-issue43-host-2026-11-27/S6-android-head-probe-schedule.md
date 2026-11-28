# S6 Android head ROI ordering and current-mask geometry diagnosis

Baseline 2e0bb3a2. Retain the scheduling improvement; no change to FrameKernel geometry, identity, lease, or #47 in this commit.

For the same explicit current single sticker owner, two consecutive successful head probes promote current head-first. A successful narrow probe demotes; no fresh head win, rewind, >150ms gap, deselection or owner change clears/expires the preference. This is a detector scheduling hint, not a privacy lease. Both orderings require current mask/anchor/competitor validation for the wide probe. If the head probe fails, the existing narrow detector and pixel match remain available in the same frame. No pixel matching on the broad crop. Both paths reuse their own successful detector pixels to seed the existing tracker. At most one broad probe per eligible frame.

## Actual OnePlus 8T export
| Case | Previous calls | Candidate calls | Direct/predicted/estimated | Candidate sampled W/H |
|---|---:|---:|---|---:|
| Affine | 18 | 18 | 18/0/0 | 1.070/1.079 |
| Reference left | 168 | 122 | 69/18/3 | 1.347/1.347 |
| Target right | 97 | 97 | 89/1/0 | 1.400/1.416 |

All 198 frames exported. Left all-frame ratios equal sampled; unchanged direct observation count and size ratios. Single-run native pipeline wall interval 35.665s -> 33.086s, not a controlled benchmark. Original unchanged numeric gate passes; full FACE_ONLY device regression 3/3. 16 focused JVM/JNI tests pass, including 3 schedule lifecycle tests. Actual installed APK hash verified. iOS/Rust unchanged.

## Right-side source scale and geometry diagnosis
Existing eight independently reviewed approximate source head rectangles have heights 64–70px, while actual corresponding sticker heights vary 96–126px. Source rectangles are coarse and sparse, so they are not complete semantic head scale/coverage ground truth. A temporary local numerical QA trace produced a decoded output identical to the candidate (90/90, max RGB delta zero). The actual right trace has raw major radius range 17.61–21.62px (1.228), stabilized 19.45–21.62px (1.112), final 44.09–61.86px (1.403). Current-mask required major range 19.81–61.86px (includes one unobserved/predicted frame); at the peak the direct and stabilized majors are ~21.5px, yet the current required radius reaches 61.86px. This isolates the current head envelope rather than the size-state shrink rate or detector misses. Host CPU masks with near-identical bbox IoU reproduce the device requirement at most reviewed frames, including 61.86px peak. The host cap includes a widening lower shoulder/neck row (e.g. width 6 to 9 cells before the existing cutoff); no assertion that every device mask matches host or that removing such a row is always safe. Temporary diagnostics removed, exact production candidate reinstalled. The original target change probes are unchanged; semantic coverage remains unproven.

Detailed numbers and hashes in s6-android-head-probe-schedule-summary.json; raw logs and video kept locally. Do not force 1.35 by enlarging the entire sticker. Focus subsequent geometry work on the evidenced lower head junction, preserving direct measurement and sampling margin.
