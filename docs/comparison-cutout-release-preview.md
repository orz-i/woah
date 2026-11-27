# Experimental comparison cutout Release preview

This branch is a product-only opt-in from master. None of the host-only
identity research or probe APIs are included. It is not an unrestricted
release acceptance or a change of the default shipped configuration.

## Build contract

Keep the same build-time switch in Flutter and native bridges:

```sh
flutter build apk --release --dart-define=WOAH_ENABLE_CUTOUT_PREVIEW=true
flutter build ios --release --no-codesign --dart-define=WOAH_ENABLE_CUTOUT_PREVIEW=true
```

Omitting the define keeps Release closed. Android decodes the Gradle
`dart-defines` property into a single boolean. Runner writes only that
boolean into its signed app resources from Xcode `DART_DEFINES`; missing
or malformed marker is disabled. Duplicate keys are rejected. Debug
behavior is unchanged, and neither Android QA assets nor other debug
entry points are enabled by the preview flag.

## Local evidence, Android

Physical OnePlus 8T (KB2000), normal Flutter AOT Release main entry point,
arm64. A local-only package suffix and ephemeral QA signing isolate the
test installation from the normal installed Woah. Those overrides are
not in the branch. Do not distribute the locally signed APK.

- Comparison widget/controller tests: 45/45.
- First opt-in build exposed the menu but failed native restore because
  the native bridge was still guarded by Debug. This was fixed in
  `3bfbe944`, then the APK was rebuilt and installed.
- Two C8.3 reference/target windows trimmed to [2.8, 4.0) s, 25 fps,
  manual alignment at zero offset; actual device picker used.
- Long press and drag each selected person into the canvas; native
  analysis and save: reference 30/30, target 30/30.
- Played the two-person canvas. Export dialog showed album save success;
  MediaStore had a new MP4, pulled from the device and verified with
  ffprobe: H.264 1080x1550, 25 fps, 30 frames, 1.200 s video, AAC 1.200 s,
  835488 bytes. Full FFmpeg decode passed.
- Forced app termination, reopened the project and the canvas:
  UI reported restored, reference 30/30, target 30/30.
- APK SHA-256 `241515ead644d2b9dd94657c5861de5f0f4c8469bfc0ccbebaed1de0142f878e`.
  Export SHA-256 `64e6c07fa17cd7c1f5b58f6d7130849a62232a1396dde471ab725c84a80af942`.
- Separate Gradle invocation without the Dart define generated
  `CUTOUT_PREVIEW_ENABLED = false`. iOS marker unit tests (3) passed.
- Local evidence in `/tmp/woah-cutout-release-qa` (build logs, screenshots,
  APK and export). Do not commit the signing material or recordings.

## Remaining acceptance

This is a smoke test on an explicitly suitable 1.2-second interval.
Completeness is not identity correctness. The separate algorithm research
has observed wrong-person choices in crowded scenes; these cannot be
covered by reporting mask counts. Keep experimental UI wording, retain
strict missing-frame export behavior, and validate representative
single-person/low-occlusion clips, cancellation and repair, longer ranges,
resource use, export masks, and cold restores before broad enablement.
iOS requires actual Release compile and physical-device evidence;
simulator Debug is not equivalent. No merge to master, store submission
or production signing is implied.

## Local iOS Release build evidence

The physical-device arm64 Release AOT build with `--no-codesign` succeeded
(46.2 MB), with the experimental Dart define. The built Runner.app contains
`WoahCutoutPreview.flag` = true and the pinned YOLO model
SHA-256 `ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340`.
CocoaPods lock verification passed after reusing the existing local Pods
copy whose Manifest.lock and Podfile.lock were byte-identical. The initial
duplicate TensorFlow git download was stopped; this is not a clean
network-cache build. CocoaPods automatically removed an unused generated
embed phase during resolution; the generated project edit is not committed.
No signed installation or iPhone runtime validation was performed.

Android also verified a live layout opacity change to 50%, then reset
the control. This is separate from the original export measurement.

## R2 product acceptance — full 12s and repair, Android Release / iOS simulator

Release product branch only; no research algorithms enabled. Commits
`d54234cd` and `e00f9740` add the supported-use/identity warning,
actionable missing-frame guidance, full-width status feedback and repair
completion guidance. Android normal app entry, actual device picker and
long-press/drag gestures, isolated locally signed arm64 Release APK.

- Full C8.3 pair, 960x540, 25 fps, 12 s, manual zero-offset alignment,
  seed at 6 s: both subjects selected; partial preview played while export
  disabled; final 300/300 reference and 253/300 target. First playable
  was observed by 29 s, final by 103 s (manual observation bounds, not
  instrumented exact timestamps). LiteRT effective CPU, four threads.
  Analysis PSS sampled at 373388 KiB; after exiting 251507 KiB (not a
  peak or a comprehensive leak test).
- Missing-frame export button remained disabled. Target-only repair at
  playhead 5.708 s in [4.71,6.71] s committed; reference count stayed 300,
  target count changed 253 to 249. This is functional evidence, not a
  quality improvement or dense identity validation. A subsequent repair
  cancelled during processing retained 300/249 after settling. Re-entered
  analysis, selected both subjects, cancelled, and returned to normal
  playback without late canvas resurrection in the observed interval.
- Final rebuilt APK: short 1.2 s at end anchor had reference 24/30,
  target 30/30; missing guidance rendered on its own full-width line.
  Retried at zero anchor and got 30/30 for both, archive saved, new
  export review warning displayed; actual MediaStore export H.264/AAC,
  1080x1550, 25 fps, 30 frames, exact 1.200 s container duration; full
  FFmpeg decode passed. APK SHA-256
  `d4d65b7156187e4b8dd796d4ad211201ec22deeae88798991d283d1153d9635e`;
  output SHA-256
  `a3b447e23cbee3c66bc712dd710c158c156c89d139c17ccba227efebc1f25b90`.
- iPhone 15 Pro simulator, Debug smoke entry from `d54234cd`, same full
  C8.3 fixtures at 6 s: first playable 4.744 s, complete 48.161 s,
  300/300 and 257/300; incomplete archive and native export refused,
  normal comparison restored. Not an iPhone Release runtime test.
- Both Android rebuilds passed 45 Flutter comparison tests. No physical
  iPhone available. Final UI-only change not rebuilt for iOS Release in
  this round; prior arm64 Release compile is documented above.
- Evidence: `/tmp/woah-cutout-release-r2` (screenshots, simulator report,
  build logs, actual pulled MP4, meminfo). No media or local signing
  overrides committed. Keep default production opt-in disabled.
