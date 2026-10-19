# Woah: automatic 39.733-second privacy evidence collection

**Purpose:** replace manual 4×4 visual labeling with a local script
that generates the minimum source-vs-output frames the reviewer needs.
This is evidence capture, **not a privacy acceptance** and **not a
segmentation or identity classifier**. It does not modify the native
privacy compositor, trackers, shaders, or export pipeline.

## Ready-made offline kit

A conversation-provided `Woah_AutoPrivacyCapture_Kit.zip` includes:

- `woah_auto_privacy_capture.py` (standalone; Python 3.9+ only),
- the previously supplied anonymized `woah_numeric_report.json`
  for build `2cb113ea12ed200c60dcf50177061fe204cec155`,
- `RUN_WINDOWS.cmd` and `RUN_WINDOWS.ps1` for local GUI file picking,
- `README.txt`.

On Windows: unzip outside Git, then double-click `RUN_WINDOWS.cmd`.
Select the **original unprotected** 1280×720 source video and,
preferably, the matching original Woah landscape 1280×720 and portrait
810×1440 **final exported** videos. The latter two are optional.
This is only *file selection*, not manual pixel/identity annotation.
A ZIP `Woah_Overlap_Auto_YYYYMMDD_HHmmss.zip` appears on the desktop.

Prerequisites: Python 3.9+ launcher `py -3`, and both
`ffmpeg` / `ffprobe` visible on PATH. No OpenCV, Pillow, Gradle,
Android SDK, or network access is needed.

### Windows FFmpeg `Unrecognized option 'vsync'` compatibility fix

The first distribution of the standalone kit used `-vsync 0` when
extracting the selected PNG frames. Some Windows FFmpeg executables
reject this option, producing `WOAH_AUTO_PRIVACY_CAPTURE=FAIL` despite
working source/export videos. **Use the updated v2 ZIP and discard
or replace the old kit folder**; no APK rebuild or new original-video
export is required. The fixed script contains no `-vsync` or
`-fps_mode` options. Instead, the FFmpeg filter selects the *original
source frame indices* and uses `setpts=N/TB` to assign consecutive
synthetic one-second output times to the extracted stills, with
`-r 1`. This prevents default CFR output frame duplication/dropping
and does not modify the source frame indices or the per-frame PTS
retained from FFprobe in `manifest.json`.

An actual FFmpeg 7.1.5 synthetic 3-video regression produced all
33 PNGs **byte-identical** to the original `-vsync 0` capture,
with identical manifest content and a valid ZIP CRC. Added mock tests
reject any command that still contains `-vsync` and preserve the
existing strict extracted-frame count requirement. If an unusual
third-party `ffmpeg.EXE` also rejects the standard `setpts` filter or
`-r`, identify the executable with `where ffmpeg` and `ffmpeg -version`
rather than bypassing alignment checks.

If using the repository script directly, supply the existing numerical
report locally; the user's report is deliberately **not committed**
to this repository:

```powershell
py -3 tools\diagnostics\woah_auto_privacy_capture.py --source "D:\Video\original.mp4" --landscape "D:\Exports\landscape.mp4" --portrait "D:\Exports\portrait.mp4" --numeric-report "D:\Diag\woah_privacy_overlap_20261019_numeric_report.json" --output "D:\Review\Woah_Overlap_Auto.zip"
```

Only `--source`, `--numeric-report`, `--output` are required.
The script can run without exported-video arguments when an older
protected-frame ZIP is already available to compare, but including
those exports greatly improves source/output correlation.

If the exported timeline begins at a known, nonzero time in a longer
original video, pass `--source-offset SECONDS`; **never guess an
offset**. The report's PTS alone cannot prove source/export
correspondence. Exported files are validated against 1391 video
frames and 30fps; original input may have another frame rate but
must be a matching 1280×720 source.

## Capture and data minimization

The script uses FFprobe's per-frame best-effort timestamp and
FFmpeg's exact input-frame-index `select` filter (not inaccurate
random seeking to the nearest keyframe). It selects the six risk
PTS timestamps between 39.733 and 39.900 seconds with approximately
one hundred milliseconds of neighboring context, capped at 18 input
frames per mode.

The cropping ROI is automatically derived from the *union* of the
six selected conservative-unknown model-prototype bounding boxes
through Woah's 640→160 letterbox mapping, with limited surrounding
context for head/arm/protagonist shape. For the current evidence it
resolves to source **x=412, y=205, width=361, height=455**:

- Source: **only this small crop** from original *unprotected* frames.
- Landscape export: the exact same crop in source coordinates, for
  direct side-by-side human visual comparison.
- Optional portrait export: whole *protected* final frame, because
  the automatic crop rectangle moves and cannot safely be inferred
  from a fixed ROI without the per-frame reframe outputs.

The ZIP contains `index.html`, a `manifest.json` with frame
numbers, original timestamps, alignment deltas, ROI, input SHA256
hashes and provenance scope, plus PNGs. It contains **no full
original frames, audio, raw segmentation masks, or original MP4
files**. Video files remain unchanged and no network request is
made. The output directory must be outside the Git repository.

**Privacy warning:** Even a 361×455 unprotected source crop may show
identifiable people. The user should upload the ZIP *only if
authorized to share those images*. No script can guarantee that a
person behind the protagonist is excluded from that crop.

The manifest explicitly records
`privacy_acceptance=NOT_REVIEWED` and
`pixel_ownership_proof=NOT_INFERRED_FROM_DIAGNOSTICS`.
Input hashes help identify copies, but do not cryptographically
associate an exported video with a specific Woah diagnostic job.

## Quality control

- `tools/diagnostics/test_woah_auto_privacy_capture.py` checks the
  actual risk-frame indices, union ROI geometry, alignment
  fail-closed behavior, high-frame-rate sampling cap,
  source/video distinction, and no-upload/no-audio promises.
- FFmpeg smoke-tested offline with synthetic 1280×720 original and
  exported 1280×720, 810×1440 clips. The tool produced and CRC-checked
  PNG-only archives for all modes, including exact #1192/#1193 PTS.
- Mac has no FFmpeg installed, so real FFmpeg integration was run in
  a separate container rather than against the source machine.
  Mac Python regression tests validate pure logic.
- Actual privacy ownership **cannot** be certified until the user
  supplies the small result ZIP and a reviewer inspects the source
  and protected output frames.

After reviewing new evidence, prioritize any protected person still
present behind the protagonist: clearer foreground visibility does
**not** entitle the algorithm to remove unrelated protected pixels.
The previously fixed 46.233-second stale ceiling Mask must remain
unchanged.
