# Protected-overlap strong observed foreground correction

Status: implementation candidate, **final-pixel validation pending**.

Previous evidence from the 2cb113e Android traces and local source/output crops
confirms the unprotected protagonist's upper body is wrongly anonymized at
39.733–39.900s. The source video additionally shows that the protected ID 1
continues to exist and cannot be erased wholesale.

This patch adds StrongObservedForegroundCarve as an additional limited core,
not a replacement for conservative-unknown classification, strict protected
Track identity, or the existing pixel-ownership proof. Only a low-confidence
conservative-unknown fresh selected instance can qualify. The fresh unselected
candidate must match a real observed nonselected Track root (existing bbox IoU
>=.90), with confidence >=.70, age >=15 frames, target confidence <=.45,
substantial overlap and depth, and at least nine current fresh-core pixels.
At each candidate texel both the *current* fresh unselected mask and the
observed unselected root mask must be >=224/255. The target's own pixel must
be >=128/255. A fresh SELECTED mask from any *other* instance vetoes that
texel; coordinate mismatches invalidate the entire additional core. The
result is eroded by one prototype pixel and needs at least nine remaining
pixels. This never changes a detected privacy class or Track ID.

The original conservative-unknown core and preceding short temporal core
remain unchanged. The output union still preserves all other selected
person masks; its existing coverage warnings and fail-closed gates remain
active. A Debug diagnostic PRIVACY_STRONG_OBSERVED_FOREGROUND_CARVE logs
only numeric counts and anonymized IDs when the new path contributes.

Risks / limits:
- The observed Track and fresh unselected detection often share an inference
  origin and are **not** independent ground-truth identity measurements. The
  strict veto against other SELECTED instances prevents direct merging
  failures, but video-pixel review remains essential to validate privacy.
- This patch cannot restore protagonist hair/arm regions where the current
  model mask has already lost foreground evidence (especially 39.833–39.900s).
  It is intended to reduce high-confidence current-mask ownership conflicts,
  not to synthesize missing segmentation.
- Do not promote a visual-privacy PASS without real Android exports of
  landscape and portrait modes and actual frames around 39.7–39.9s.
- The previously accepted 46.233s stale ceiling-mask fix is untouched.

Validation: Python contract and diagnostics suite (92 run, four skipped).
New Kotlin synthetic tests cover eligible geometry, wrong identity/weak
foreground conditions, erosion, competing protected masks, geometry mismatch,
and actual resolver composition. An isolated Kotlin 1.9 / JDK 21 compiler
validated the new helper with its Kotlin model dependencies. The full Android
project and Kotlin unit test suite are not compiled by the remote Mac
(no JDK/SDK); they must run on the Windows Android build machine.

Next: build a Debug APK from this commit, run identical two exports, verify
the event and area counts; visually review 39.700–39.900s and 45.967–46.333s
from both **final exported videos**. If any selected nonprotagonist becomes
visibly unmasked, revert this new carve without weakening other gates.
