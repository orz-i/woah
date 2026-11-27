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
