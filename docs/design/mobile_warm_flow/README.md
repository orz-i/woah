# Woah Mobile Warm Flow — Final Design Archive

Status: **adopted / implemented**
Archived: **2026-09-03**

This directory is the visual reference archive for the mobile-first Woah flow.
The product direction is warm white + coral, media-first, restrained controls,
and bottom drawers where editing controls need extra vertical space.

The images under `final/` are intentionally compressed repository reference
thumbnails. They preserve layout, hierarchy, and interaction intent while
keeping the Git repository lightweight. Product code is the implementation
source of truth for exact sizing and runtime behavior.

## Main flow

| Step | Reference | Screen / state | Implementation status |
| --- | --- | --- | --- |
| 01 | [`01_import_home.jpg`](final/01_import_home.jpg) | Import dance clip / home | Implemented |
| 02 | [`02_trim_video.jpg`](final/02_trim_video.jpg) | Preview + timeline trim | Implemented |
| 03 | [`03_person_selection.jpg`](final/03_person_selection.jpg) | Person selection | Implemented |
| 04A | [`04_effect_editor_full_body.jpg`](final/04_effect_editor_full_body.jpg) | Effect editor — full-body protection | Implemented |
| 04B | [`05_effect_editor_face.jpg`](final/05_effect_editor_face.jpg) | Effect editor — face protection / sticker mode | Implemented |
| 05A | [`06_export_progress.jpg`](final/06_export_progress.jpg) | Export / protection in progress | Implemented |
| 05B | [`07_export_failure.jpg`](final/07_export_failure.jpg) | Export failure | Implemented |
| 06 | [`08_result_complete.jpg`](final/08_result_complete.jpg) | Completed result / share | Implemented |

## Interaction decisions captured by the archive

- Mobile is the primary product surface; desktop remains an archive-oriented
  surface and is not required to mirror this visual system.
- The media is the main stage. UI chrome should remain visually subordinate.
- Import screen hides the top system status bar; later workflow screens restore
  normal edge-to-edge system UI.
- Person selection defaults to all eligible people selected and uses direct
  canvas tapping. The duplicate candidate rail was removed.
- Trim sits between import and person selection and performs real temporal trim,
  not a UI-only selection.
- Effect editor inherits the previous full-body / face privacy choice instead of
  asking again. Face mode additionally exposes the real sticker path.
- Export progress and failure share the same warm/coral language; technical
  diagnostics remain secondary.
- Completion auto-saves to the gallery and exposes only real actions: playback,
  share, make-next, open saved media, copy the saved media URI, and diagnostics.

## Design-token baseline

Runtime tokens live in `mobile/app/lib/app/theme.dart`. The adopted visual
language uses:

- warm background / warm surface layers;
- coral primary action and selection states;
- rounded panels with light warm borders;
- dark neutral typography;
- media-first layouts with minimal persistent navigation chrome.

## Implementation checkpoints

Key commits that moved the visual references into the product:

- `a654b173581907fb7a32c88b8f8921c0b3d574b2` — import-screen reference alignment
- `643ab167f3d081ca9f66d5c4f4b0b3a5f6eba0b6` — import status-bar immersion
- `d03427d58c7f20cb83cc163a7c71be854f01a7d6` — warm person selection
- `a0072cd5fdfdc0f1b1cbb0c48974d3337d4ecf52` — trim workflow
- `e26cdcc690533aadffd785e5c5eff2531eb8a232` — adaptive effect editor
- `c08728344cac8023d20690c247fafe482da9eb00` — export progress / failure
- `fb615656ca5d725b8a1a1926b6eace8d9f4ac27b` — completion result screen
- `613d96ba61bf1f293ffceb814d6f4b24136efe86` — auxiliary warm UI cleanup

## Archive policy

Only the adopted references are kept in `final/`. Superseded dark-theme,
duplicate, or exploratory generations are intentionally excluded from this
implementation baseline so future work does not accidentally regress to an
obsolete direction.
