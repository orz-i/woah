# Camera-only bounded identity recovery

## Diagnostic correction — stationary protagonist

The original-video protagonist remained on screen at nearly the same position. A long
`HELD` interval is therefore **not itself a camera error**; holding the existing crop is
often the correct behavior. The `ID 2 -> ID 8` temporal handoff at 20.033s made the
camera move, but did **not** prove the identity was recovered.

The tracking bbox of ID 2 became vertically truncated near 18s (its top moved from
about 258px at 17.0s to about 390px at 18.0s, without comparable horizontal
movement). That is consistent with detector/association fragmentation rather
than the person leaving the scene.

ID 8 was concurrently detected alongside selected ID 2 at 5.800, 13.400, 13.533
and 13.567s. The previous 0.8-second negative-identity window had expired by
the 18.2s loss, allowing a potentially wrong camera-only handoff. The corrected
policy remembers **all concurrently observed track IDs during the entire export**
and excludes them from short and temporal camera handoffs. This includes
duplicate detections: when an ID was simultaneously observed, geometry alone
cannot establish that it is the selected protagonist.

For this stationary case, a continued HOLD is safer and can be **correct**.
Judge success by correct subject framing and no wrong-person movement, not
by reducing the number of HOLD events. A new export should no longer report
an ID 2 -> ID 8 handoff. The expiry event lists
`historical_co_observed_excluded_ids` for diagnostics.

This defensive follow guard does **not** repair the underlying detector/tracker
identity loss. It does not modify privacy tracking, masks, shader strength, or
the exported frame geometry.

The 2026-10-09 PLK110 real-device diagnostics showed that the selected protagonist track ID 2 was last observed at 18.233s, became LOST at 18.267s and was removed at 18.767s; the tracking signal and portrait crop then remained HELD through the rest of the export. This is separate from the accepted crop-clarity shader. The existing single-frame strict handoff window is 1.1s and does not cover the first later plausible observation at 19.5s.

## Guarded fix

- Do not touch TrackManager, selected privacy IDs, face/full-body effects, or the privacy-first post-crop pipeline. Only update `reframeIdentityTrackId` (camera-follow identity) when evidence passes.
- Preserve the existing short-window strict handoff. After that, accept a later handoff *only between 1.1 and 2.6 seconds from the last directly observed protagonist*, with at least **three separately timed observations** of one observed ACTIVE candidate spanning **at least 250ms**. Any ambiguous similarly strong candidate, distant or implausibly sized body, low-confidence detection, or teleport-like jump blocks handoff.
- Exclude all selected privacy targets and **every other track ever observed concurrently** with the protagonist during the export. This evidence does not expire after 0.8s; if co-observation was duplicate detection rather than two distinct people, automatic reassignment is still identity-ambiguous.
- If no uniquely supported continuation passes by 2.6s, **stay HELD** rather than jump to another dancer. On subsequent genuine protagonist observation, clear pending camera recovery state. Never reclassify privacy masks.
- Emit `AUTO_REFRAME_TEMPORAL_CANDIDATE`, a `AUTO_REFRAME_ID_HANDOFF` with `handoff_mode=TEMPORAL_CONFIRMED` and evidence details when successful, and `AUTO_REFRAME_RECOVERY_EXPIRED` once if no recovery occurs.

## Evidence and acceptance limit

The observed candidate ID 8 has detector evidence near 19.5, 20.1 and 20.2s; fixture tests mirror their rough boxes, but **similarity is not identity proof**. A separate historical-identity fixture now vetoes ID 8 because it was co-observed much earlier with ID 2. No Android native compilation or real-device follow-video acceptance is available on the development Mac. A controlled follow camera handoff should be considered accepted only after visual review of a new real export around 18–23s: camera must not jump to a different person and privacy for other subjects must remain intact. A prolonged HOLD can be correct if evidence is ambiguous.

### Verification

```sh
python -m unittest tools.test_follow_camera_recovery_contract -v
# On an Android-capable workstation:
cd mobile/app/android
./gradlew :dance_native:testDebugUnitTest
cd ..
flutter build apk --debug
```

Use the *existing* 1280×720 dance video with protagonist ID 2 to reproduce. Review `AUTO_REFRAME_TEMPORAL_CANDIDATE`, `AUTO_REFRAME_ID_HANDOFF`, `AUTO_REFRAME_RECOVERY_EXPIRED` and `AUTO_REFRAME_SAMPLE`. Debug A/B opt-in is unrelated to this camera fix, and is not required for the initial follow recovery check.
