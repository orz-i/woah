# Camera-only bounded identity recovery

The 2026-10-09 PLK110 real-device diagnostics showed that the selected protagonist track ID 2 was last observed at 18.233s, became LOST at 18.267s and was removed at 18.767s; the tracking signal and portrait crop then remained HELD through the rest of the export. This is separate from the accepted crop-clarity shader. The existing single-frame strict handoff window is 1.1s and does not cover the first later plausible observation at 19.5s.

## Guarded fix

- Do not touch TrackManager, selected privacy IDs, face/full-body effects, or the privacy-first post-crop pipeline. Only update `reframeIdentityTrackId` (camera-follow identity) when evidence passes.
- Preserve the existing short-window strict handoff. After that, accept a later handoff *only between 1.1 and 2.6 seconds from the last directly observed protagonist*, with at least **three separately timed observations** of one observed ACTIVE candidate spanning **at least 250ms**. Any ambiguous similarly strong candidate, distant or implausibly sized body, low-confidence detection, or teleport-like jump blocks handoff.
- Exclude all selected privacy targets, and also other people that were clearly co-observed as separate from the protagonist shortly before the loss. A former bystander cannot become the protagonist just because it is nearby.
- If no uniquely supported continuation passes by 2.6s, **stay HELD** rather than jump to another dancer. On subsequent genuine protagonist observation, clear pending camera recovery state. Never reclassify privacy masks.
- Emit `AUTO_REFRAME_TEMPORAL_CANDIDATE`, a `AUTO_REFRAME_ID_HANDOFF` with `handoff_mode=TEMPORAL_CONFIRMED` and evidence details when successful, and `AUTO_REFRAME_RECOVERY_EXPIRED` once if no recovery occurs.

## Evidence and acceptance limit

The observed candidate ID 8 has detector evidence near 19.5, 20.1 and 20.2s; fixture tests mirror their rough boxes, but **similarity is not identity proof**. No Android native compilation or real-device follow-video acceptance is available on the development Mac. A controlled follow camera handoff should be considered accepted only after visual review of a new real export around 18–23s: camera must not jump to a different person and privacy for other subjects must remain intact. A prolonged HOLD can be correct if evidence is ambiguous.

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
