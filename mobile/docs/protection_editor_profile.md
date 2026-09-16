# Protection Editor Profile

The unified protection editor keeps reusable editing preferences in one local
Profile, but Profile is no longer a visible editor section. The editor UI is
organized by the real task the user is doing, while reusable defaults persist
silently in the background.

## UX

- The page is a black media workspace with no page title.
- Top chrome is limited to return on the left and a compact `导出` action on the
  right. There is no large bottom primary button.
- The media preview stays in a stable area above the editor controls and uses
  the exact current output/source aspect without decorative blurred cover fill.
- The lower workspace has four fixed tools: `保护`, `遮挡`, `画幅`, `舞段`.
  Switching tools replaces the lower control content; it never opens a drawer or
  expands/collapses a card.
- `保护` owns person targets and FULL_BODY/FACE_ONLY scope.
- `遮挡` owns mask/sticker style, strength, color, blur/mosaic detail and border.
- `画幅` owns source vs 9:16 subject follow and source/FHD/HD resolution.
- `舞段` always shows the trim range and timeline directly.
- Subject-selection guidance and cancel live in the media HUD because choosing a
  subject is an operation on the picture, not a settings form.

## Persisted fields

The local Profile stores only settings that are safe to reuse across unrelated
videos:

- project-wide privacy scope (`FULL_BODY` / `FACE_ONLY`)
- full-body effect configuration
- face-only effect configuration
- output resolution preset (`source` / `FHD` / `HD`)
- source vs 9:16 framing preference

These saved values are distributed back into their corresponding tools. There
is deliberately no Profile card or Profile settings screen in the editor.

The Profile intentionally never stores person IDs, analysis cache IDs, trim
bounds, or a follow target. If 9:16 is the saved preference, the next fresh
video opens directly in subject-selection mode and still requires the user to
choose that video's subject.

## Application rules

A saved Profile is applied only to a fresh imported project: no cached analysis,
no detected persons, no persisted privacy targets, and no active follow target.
Existing/configured projects are authoritative and are never overwritten by the
application Profile.

Changes are saved with a short debounce while editing and are flushed again
before returning from the editor or entering export. Persistence is convenience
only: unavailable or corrupt preference storage must never block editing.

The current preference key is versioned as `woah.protection_profile.v1`.
