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
