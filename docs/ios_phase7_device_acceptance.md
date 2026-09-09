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

## Completion record

Physical-device acceptance is complete only when all functional rows have a
recorded result, privacy review has no unresolved escapes, sustained execution
does not expose a release-blocking stability/thermal problem, and all evidence
maps back to one audited Release artifact.

Until then the project status remains:

`implementation complete pending physical-device acceptance`

