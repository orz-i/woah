# Shared head localization

The Android source-resolution head ROI planner, landmark center, owner-area gate,
and distance/ambiguity selector now live in `frame-kernel::face_localization`.
Kotlin and Swift transport observations to the same C ABI. Candidates have no
identity; only current YOLO people compete for ownership. A rejected candidate
uses the existing conservative protection fallback and grants no identity exemption.

The canonical crop uses max(2.20 * width, 0.90 * height), bounded by the shorter
frame edge, shifted into the source frame. The head anchor is body center X and
body top + 0.22 * height. Selection uses normalized distance <= 0.22 and requires
0.04 separation from the next plausible face. Eye midpoint, nose and mouth define
the localization center when four landmarks are available. These are existing
Android rules, not thresholds fitted independently for iOS.

This addresses a real iOS raised-arm export defect in the frozen solo fixture:
source frames 556-559 (22.24-22.36 seconds) accepted a waist false positive and
stopped drawing head fallback. Source frame 554 (22.16 seconds) also accepted a
raised-hand false positive. Both observed coordinates are regression inputs in
Rust and the Swift native host tests. iOS now preserves model landmarks and maps
them from detail crops to source coordinates before ownership selection.

Validation is staged. Passing automatic face audits is insufficient: the source
Vision face cache missed this visible partial face. Explicit source-timed pixel
and manual checks are required before acceptance. This document does not claim
final 62-case Android/iOS acceptance or resolution of the separate hero overmask.

## Current-owner reservation evidence

Actual simulator preview observations exposed a separate follow-target overmask.
At6.12 seconds, target6 had an unambiguous0.788 box IoU and0.768 mask IoU but was
reserved by the neighboring[4,5] group because its bounding box touched the group's
edge. Both group members already had different current observations and neither
could match this candidate under the existing score/identity gates.

The shared tracker now permits release of that direct reservation only for a
strict reciprocal-best non-body match, with the existing0.50 box/0.45 mask gates,
and only when every owner has a committed current observation and fails both the
ordinary minimum match score and canonical identity evidence for this candidate.
Missing or stale owners keep the reservation. No thresholds are lowered.

`replay-ios-hero-v1.bin` freezes166 actual simulator inputs and pre-fix output
states. It is failure evidence, not an independent desired-behavior oracle. All13
source frames153-165 were viewed, and the expected teal-subject detection indices
are recorded in its manifest. The regression requires target6's current observed
box and exact current mask on each frame. The original960-step independent
native corpus continues to match every state unchanged. Correcting other earlier
reservation misses can change later group cadence in this new failure capture;
full device privacy auditing remains required before acceptance.
