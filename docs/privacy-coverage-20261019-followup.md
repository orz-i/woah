# Privacy coverage continuation — PLK110, 2026-10-19

This read-only device audit examines the two Android 17 diagnostic archives
from the same import session `20261019_000844_3014bc21`:

- `woah_diag_20261019_001451_20261019_000844_3014bc21.zip` —
  1280×720 landscape, `job_1792368553650`
- `woah_diag_20261019_002024_20261019_000844_3014bc21.zip` —
  810×1440 portrait, `job_1792368911196`

Both manifests identify build commit
`32555950992684bf4fa511e204a4716c75e94913`. The two
`pipeline_summary` records confirm full export success, 1391 decoded /
1391 rendered / 1391 encoded frames each. The ZIPs share historical session
JSONL shards; never count duplicate logs by archive alone.

## Device evidence and visibility limits

Each output mode still has exactly one `PRIVACY_COVERAGE_DROP` at
PTS 39,733,333 µs; neither has `PRIVACY_SEVERE_UNDERCOVERAGE`.
The remaining FULL_BODY decision has target detection index 2,
`SELECTED` / `conservative_unknown=true`, current UNSELECTED detection
index 3 with observed unprotected root ID 2, 98 corroborated foreground
core pixels, and 17 above-threshold pixels removed (82 → 65;
coverage ratio 0.79268). This passed the stricter foreground-proof guard,
but **is not final-pixel verification**. Do not suppress this warning.

At 45.933 s the observed protected root ID 0 had bbox
(159.94, 574.13, 287.56, 719.63) in 1280×720 source pixels. At
45.967 s it is REACQUIRING/unobserved with bbox approximately
(160, 626, 264, 745). There is one fresh conservative-unknown
detection at the right/bottom edge and a selected fallback for ID 0.
At 46.000–46.333 s there are 11 frames with zero fresh detections;
the track-mask fallback path resumes rather than turning off all
privacy masks. The disappearance is consistent with the observed
downward motion, but **cannot prove the picture contains no people**.
The diagnostics contain no post-shader protected RGBA frames at these
timestamps. Actual video inspection is still required.

Source-frame production/full-body input signature availability in the
newest ZIP:

| Job | Available per-frame signatures | PTS range |
| --- | ---: | --- |
| `job_1792368553650` | 689 | 23.400–46.333 s |
| `job_1792368911196` | 1391 | 0–46.333 s |

The first job's historical shards were partially rolled over before the
later ZIP snapshot. Counting **only frames present** in these traces,
1118 landscape and 2639 portrait fresh observations of identity-selected
roots each have matching `SELECTED` fresh geometry (bbox IoU ≥0.90);
no observed protected root was classed UNSELECTED within the retained
frames. This does **not** certify missing frames in the landscape archive.

## Fail-closed safety refinement

There is one separate, generalizable code safety gap: when
`preferFreshPrivacyClassPrimary` is enabled, an actively observed
identity-selected root could be omitted from the fresh SELECTED roster
(e.g. a future temporal misclassification to UNSELECTED). Its tracked
fallback is then eligible to be suppressed by an overlapping fresh
detection, potentially exposing it without reporting an empty root
mask. That contradiction is *not* observed in either current trace.

The export now checks that every **currently observed** FULL_BODY root
has a uniquely assigned fresh SELECTED detection (source bbox IoU
≥0.90), before either landscape or source-first portrait composition.
A single detection cannot satisfy two different selected roots.
When the invariant is violated, a CRITICAL
`FULL_BODY_FRESH_CLASS_CONTRADICTION_FAIL_CLOSED` event records the
job, frame, PTS, root IDs and detection indices, and the export aborts.
No identities are reassigned and conservative-unknown fresh detections
retain their existing SELECTED semantics.

This gate runs **only** for fresh-class-primary execution, not on
inference-skipped, detector-empty, LOST or REACQUIRING fallback frames.
It therefore does not reinterpret the last 11 no-detection frames or
interfere with the separately validated Kalman/coasting behavior.
The same source-space gate applies before horizontal/portrait
rendering. Independent foreground pixel subtraction now additionally
requires identical source/proto mapper configuration; invalid geometry
can only keep privacy, not authorize carving.

A replay of both available device geometry signature streams finds
**zero violations of the unique selected-detection matching rule**:
1118/1118 and 2639/2639 observed protected-root records can be
uniquely matched. That is a geometry-only confirmation, not a
new real-device run of the code.

## Validation and remaining gates

New deterministic Kotlin regression cases cover a correctly protected
fresh root, conservative-unknown SELECTED, wrong UNSELECTED class,
an absent/misaligned SELECTED detection, duplicate roots competing for
one fresh detection, unobserved REACQUIRING, nonselected protagonist,
and mismatched mask-coordinate mappers. These are synthetic tests: the
supplied diagnostics do not include the frame-39.733 mask tensors.

Python regression contracts enforce the trace-derived geometry,
no identity promotion, nonweakened coverage alarms, the export
fail-closed ordering, source protection before portrait crop, and
no restoration of obsolete identity handoff workarounds. Mac
Flutter/Dart tests and static analysis are separate from Kotlin or
Android APK validation: neither Kotlin native tests nor the APK can
be built on this Mac without the Android JDK/SDK.

**Final video privacy acceptance remains open** pending human review
of 39.700–39.833 s and 45.900–46.333 s in both output videos.
