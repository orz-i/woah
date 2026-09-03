# Mobile UI/UX Planning Checkpoint

Date: **2026-09-03**
Scope: **mobile-first Woah warm/coral redesign**

## Current product state

The mobile first-party flow is visually aligned end-to-end:

1. Import dance clip
2. Preview and temporal trim
3. Select protected people
4. Edit protection effect
5. Export / export failure
6. Completion / share result

Supporting first-party surfaces have also been aligned:

- hidden device / diagnostics drawer;
- export cancellation confirmation;
- floating SnackBars;
- export/result popup menus;
- light system-bar transition behavior.

The adopted references are archived in [`final/`](final/) and indexed by
[`README.md`](README.md).

## Interaction baseline to preserve

- Media remains the primary visual stage.
- Person selection defaults to all eligible targets selected and uses direct
  canvas tapping; no duplicate candidate rail.
- Trim is a real processing boundary and propagates into analysis, preview, and
  export.
- Effect editor inherits the protection mode selected on the previous screen.
- Face mode additionally supports the real sticker path; controls must not
  expose privacy-unsafe transparency or undersized coverage.
- Export failure keeps technical details secondary to retry / return-to-edit.
- Completion uses real saved-media actions only; no fake file or cloud-link
  affordances.
- Android system-owned share/viewer/picker surfaces are not custom-skinned.

## Intentionally paused work

Cross-device detection-behavior consistency and performance optimization remain
paused while no suitable test devices are available. UI/UX work must not change
the existing detection, tracking, confidence, or rendering behavior unless that
workstream is explicitly resumed.

## Next validation phase

When Android test devices are available, validate in this order:

1. Full main-flow visual regression on small / tall / wide phone aspect ratios.
2. Status-bar and navigation-bar transitions between import and later screens.
3. Trim handle accuracy and touch ergonomics.
4. Person-selection hit targets and bottom-drawer snap behavior.
5. Full-body and face effect-editor drawer ergonomics, including sticker mode.
6. Export progress, cancellation, failure retry, and background-processing UI.
7. Result auto-save, share, open-saved-media, and saved-URI behavior.
8. Hidden developer drawer, popup menus, SnackBars, and failure states.

## Exit criterion for the UI redesign phase

The redesign can be considered visually closed after the real-device pass finds
no blocking layout, system-UI transition, touch-target, or navigation defects.
After that, return to the paused detection consistency / performance workstream
with UI changes treated as a stable baseline.
