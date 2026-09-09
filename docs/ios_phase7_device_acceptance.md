# iOS Phase 7 Physical-Device Acceptance Runbook

This runbook is intentionally prepared before an iPhone is available. Running
it is the remaining acceptance gate after Phase 7 implementation/CI completion.
Simulator or no-codesign iPhoneOS build evidence must never be entered as a
physical-device result.

## Artifact identity

Before installing anything, copy these values from the Phase 7 CI artifact and
`phase7_release_audit.json` into the test record:

- Git commit;
- app version/build;
- Bundle ID;
- deployment target;
- app executable SHA-256;
- `pubspec.lock` SHA-256;
- model contract SHA-256 and model SHA-256;
- bundled privacy-manifest inventory;
- device model / iOS version / available storage / battery state.

Do not compare results produced by different artifact identities as though they
were one acceptance run.

The acceptance record has a fixed machine-readable shape at
`tools/ios/phase7_device_acceptance_report.template.json`. After downloading the
audited Release artifact, seed a report instead of copying identity fields by
hand:

```text
python tools/ios/prepare_phase7_device_acceptance_report.py \
  --audit mobile/app/artifacts/phase7_release_audit.json \
  --output reports/ios_phase7_device_acceptance.json
```

The preparer copies commit/version/build, executable/dependency/model hashes,
privacy-manifest inventory, the Release audit SHA-256, and the repository
baseline-video SHA-256. Device/media/performance results remain `pending` until
they come from real hardware.

## Fixed media set

Always include:

1. `testdata/videos/01_sample.mp4` as the repository baseline.
2. One portrait H.264 clip with audio.
3. One landscape H.264 clip with audio.
4. One H.264 clip without audio.
5. One common variable-frame-rate H.264 phone capture.
6. One crossing/occlusion clip with selected and unselected people.
7. One FACE_ONLY clip with fast head motion and temporary face loss.
8. One mixed FULL_BODY + FACE_ONLY clip.

Items 2-8 may be private acceptance media and need not be committed, but their
SHA-256 values and basic media metadata must be recorded in the test record.

## Functional matrix

Run every applicable media item through:

- FULL_BODY export;
- FACE_ONLY export;
- mixed FULL_BODY/FACE_ONLY export;
- trim at both beginning and end;
- audio preservation and no-audio input;
- cancel during active export;
- forced failure / unavailable-output-path cleanup where the platform permits;
- portrait and landscape playback/export;
- repeated export of the same source;
- foreground -> background -> foreground while idle;
- foreground -> background -> foreground while exporting;
- interruption scenarios available on the device (screen lock, audio/session
  interruption, app switch) without deliberately bypassing iOS restrictions.

For every successful export verify H.264, 1920x1080, expected 30fps output
contract, expected trim duration, audio presence/absence, and that no final or
partial file appears before successful completion.

Every functional row in the JSON report must name the media IDs exercised and
one or more `evidence_files` (for example exported-file hashes/metadata,
diagnostic JSON, device log excerpts, or screenshots). Final acceptance rejects
a row marked `pass` if those evidence references are empty.

## Final visual privacy acceptance

Review the complete exported video, not only thumbnails or the live preview.
Record any frame where:

- a selected FULL_BODY target is unprotected;
- a selected FACE_ONLY target exposes the face beyond the accepted temporal
  recovery window;
- an unselected person is persistently masked without conservative privacy
  justification;
- a crossing/occlusion swaps selected and unselected privacy semantics;
- a stale mask/sticker remains materially detached from the intended target;
- portrait/landscape transforms move privacy coverage away from the subject.

Any unresolved privacy escape blocks physical-device acceptance.
The final `privacy_review` record must also reference its review evidence files;
setting only the result field to `pass` is insufficient.

## Performance and stability collection

For each device, collect at minimum:

- analyze FPS / effective inference backend;
- export processed FPS and wall-clock throughput;
- peak/representative memory during analyze and export;
- export progress cadence and terminal error code/message;
- app crash/hang observations;
- sustained-run behavior for at least one long clip;
- thermal state transitions observed during the sustained run;
- battery/power observations available from the test setup.

The exact instrumentation may use Xcode/Instruments, device logs, or the app's
existing diagnostics. Phase 7 does not invent synthetic pass thresholds before
real-device evidence exists; the first accepted device run establishes a
measured baseline that must be documented rather than silently normalized.

Record the measured values in the fixed report. Structural completeness can be
checked at any point without claiming acceptance:

```text
python tools/ios/verify_phase7_device_acceptance_report.py \
  reports/ios_phase7_device_acceptance.json
```

For the final physical-device gate use `--require-accepted`. That mode rejects
pending/failed functional rows, missing media hashes/metadata, absent
FPS/backend/memory/throughput/thermal observations, unresolved privacy escapes,
background/interruption failures, crashes, and hangs. The verifier deliberately
does **not** invent performance thresholds; it enforces evidence completeness
and the privacy/stability pass conditions.

## Completion record

Physical-device acceptance is complete only when all functional rows have a
recorded result, privacy review has no unresolved escapes, sustained execution
does not expose a release-blocking stability/thermal problem, and all evidence
maps back to one audited Release artifact.

Until then the project status remains:

`implementation complete pending physical-device acceptance`

