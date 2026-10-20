# b80e60b Android 17 full-body privacy overlap: real-device A/B review

Status: **visual NOT ACCEPTED for protagonist overmask; no verified identity leak
in the 11 sampled risk frames; no full-video privacy pass.**

## Scoped inputs and provenance

On 2026-10-20, two successful Android Debug export diagnostics reported
the actual APK commit b80e60b195f5dbb7194f282329de63cdfab5746d.

| Scope | Landscape | Portrait |
| --- | --- | --- |
| Job | job_1792456596794 | job_1792456317807 |
| Final output | 1280 × 720 | 810 × 1440 |
| Source | 1280 × 720 @ 30fps | same |
| Decoded / rendered / encoded | 1391 / 1391 / 1391 | same |
| GPU fallback | none | none |
| Strong observed foreground carve | **0** | **0** |
| Privacy coverage drop | 1 | 1 |
| Severe undercoverage / GL fail-closed | none seen | none seen |
| New aggregate unknown-overlap audits | 41 | 41 |

The full-body production Track and privacy input signatures match
**1391/1391 source frames across landscape and portrait** after
filtering JSONL events by each job ID. The session's landscape diagnostic
contains history from both jobs, which must not be double-counted.

The user additionally supplied an automatic frame capture ZIP containing
11 time-aligned **source crops**, 11 landscape exported crops, and 11
portrait final exported frame images. The six target timestamps
39.733–39.900s agree to 1 microsecond. Its numerical report records the
older 2cb113e git SHA as an operator assertion; this is **not proof**
that a newer final video was exported by that exact build. The APK
SHA above comes from the *separate* real device manifests.

**Sensitive images are intentionally not committed, displayed or archived
in Git.** Only aggregate findings are preserved in this document.

## Final-pixel regression result

Side-by-side visual review confirms the white-shirt protagonist's
hair, hands/arms and upper body acquire a large unintended grey privacy
overlay at 39.733s. The overlay covers much of the body through 39.900s
and disappears at 39.933s, while the person is still visibly present.
Both output modes exhibit the regression. Other people in the sampled
crops remain visibly anonymized; this is not a guarantee that all
non-protagonist privacy is correct in unreviewed frames.

For a fixed **58 × 85 pixel upper hair-adjacent region**
(source x=520–578, y=285–370), the percentage whose source vs
landscape-export max-channel RGB difference is >=24:

| PTS | RGB difference >=24 |
| --- | ---: |
| 39.700s | 0.0% |
| 39.733s | 10.9% |
| 39.767s | 26.3% |
| 39.800s | **66.9%** |
| 39.833s | 60.6% |
| 39.900s | 47.6% |
| 39.933s | 0.0% |

These percentages are image-difference diagnostics, **not** identity
probabilities or an automatic pixel ownership mask.

## Native root cause boundary

The conservative-unknown selected detection #2 appears between 39.733
and 39.900s as the protagonist current detection #3 loses upper-body
segmentation coverage. The latest frame audit shows:

| PTS | Selected proto within target bbox | Selected still protected | Protected & current strong protagonist |
| --- | ---: | ---: | ---: |
| 39.733 | 81 | 64 | 23 |
| 39.767 | 141 | 125 | 31 |
| 39.800 | 160 | 157 | 7 |
| 39.833 | 149 | 149 | 0 |
| 39.867 | 142 | 142 | 0 |
| 39.900 | 140 | 140 | 0 |

The b80e60b strict correction emitted **no**
PRIVACY_STRONG_OBSERVED_FOREGROUND_CARVE event; its reported
strong_observed_foreground_core_pixels remained zero in both jobs.
At 39.733s coverage stayed 65/82, or 79.27%.

The existing diagnostics do not distinguish whether the new correction
failed a *gate* (current observed Track age/confidence/state,
bbox/depth) or failed inside its *pixel proof* (current raw probabilities
>=224, selected competitor veto, then erosion/minimum support).
Therefore this is **not a justified occasion to lower the 224-byte
strong threshold, drop the competitor veto or reclassify the observed
protected root ID1 as UNSELECTED**. A protected person remains
independently present in the source scene.

Next patch is diagnostic-only: append per-gate failed requirement codes
and strict-threshold source-proto **aggregate counts** to the existing
Debug-only PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT event. It preserves
the previous full 4 × 4 schema, never exports images/Mask arrays, and
keeps older diagnostic bundles compatible with the offline analyzer.
This makes the next normal dual-mode diagnostics sufficient to locate
why the correction contributed zero pixels.

## 46s tail guard

The current paired logs again show that at 46.333s the GL uploaded
privacy Mask has 41 contour proto pixels, but only **23 source-visible
sampled pixels**. The long-lost protected root ID1 (178 frames since
observation) is explicitly marked expired, not reintroduced. This
supports **no telemetry regression** of the prior ceiling ghost fix.
The 39.6–39.97s automatic capture did **not** include 46s output
frames, so there is no fresh final-pixel visual acceptance of the tail.

## Next acceptance gate

Build one new Android Debug APK from the diagnostic-only follow-up.
Export the same source to portrait and landscape and provide the
ordinary pair of diagnostic ZIPs. The updated analyzer can compare
strong foreground gate failure codes, the counts at thresholds 192
and 224, any independent protected-selected veto, and actual strict
core pixels between modes.

The correction can only be promoted after a separately reviewed final
video test confirms improved protagonist visibility with **no newly
exposed protected people** in the same and adjacent frames. Do not
use the 4 × 4 aggregates alone as proof of pixel identity.
