# Protection Editor Profile

The unified protection editor keeps reusable editing preferences in one local
Profile so the normal workflow stays media-first instead of repeatedly exposing
all controls.

## UX

- The editor is a full-screen black media workspace. The entire screen carries
  media context: a dimmed/blurred cover layer fills the background while an
  uncropped foreground frame remains fully visible above the active drawer.
- The default drawer snap is compact (about 24% of the viewport) and shows only
  the current protection summary. The full tool deck is not built until the
  user taps the handle/summary or pulls the drawer upward.
- Export is a floating bottom action rather than an opaque drawer footer, so it
  never consumes scroll height or clips Profile/trim controls.
- `默认 Profile` is collapsed inside the tool drawer and shows a one-line
  summary. Expanding it moves the drawer to the full editing snap and exposes
  protection scope, effect style, parameters, output framing, resolution and
  advanced effects.
- Protection targets and trim remain outside the Profile because they are
  video-specific actions.
- Entering subject selection or switching to the full source view automatically
  returns the drawer to the compact snap so the media becomes primary again.
  Subject-selection guidance and cancel live in the media HUD rather than inside
  the Profile form.

## Persisted fields

The local Profile stores only settings that are safe to reuse across unrelated
videos:

- project-wide privacy scope (`FULL_BODY` / `FACE_ONLY`)
- full-body effect configuration
- face-only effect configuration
- output resolution preset (`source` / `FHD` / `HD`)
- source vs 9:16 framing preference

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
