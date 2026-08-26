# Dance Anonymizer Test Dataset Specification

This directory contains the standard dataset specification and manifests used for validating the dance anonymization pipeline.

## Benchmark Clip Manifest (manifest.json)

The test clips are curated to systematically validate core vision and privacy invariants:

| Clip ID | Scenario | Resolution | Duration | Invariant Tested |
|---|---|---|---|---|
| solo_fast_spin_1080p | Solo fast spin | 1920x1080 | 5.0s | Mask warping under rapid rotational movement |
| duo_cross_path_1080p | Two dancers crossing | 1920x1080 | 6.0s | Left-to-right ID stability & Hungarian matching |
| fast_runner_720p | Fast sprinter | 1280x720 | 4.0s | Kalman velocity propagation under inference stride 2/3 |
| person_exit_reenter_1080p | Exit and re-entry | 1920x1080 | 7.0s | Track purging after 30 lost frames; no ghost tracks |
| portrait_dance_9x16 | Vertical portrait | 1080x1920 | 5.0s | 9:16 letterbox padding and coordinate transformations |
| long_sequence_60s | Long endurance clip | 1920x1080 | 60.0s | Direct buffer memory reuse, zero leak, ForegroundService |

## Evaluation Metrics

1. **Exposure Frames (Gate Privacy)**:
   - Target: Exposure Frames = 0.
   - Any frame where a selected person is visible but lacks an active privacy mask constitutes a privacy violation.

2. **Tracking Accuracy (MOTA / IDF1)**:
   - MOTA >= 90%
   - IDF1 >= 90%
   - Zero identity swaps during normal dance routines.

3. **Media Pipeline Integrity**:
   - Monotonic PTS timestamps across all decoded frames.
   - Moov atom present for web playback compatibility.
   - Audio/Video duration synchronization delta < 100ms.

## Generating Synthetic Test Data

To generate synthetic MP4 clips for local benchmarking without downloading large external video files:

uv run python tools/download_testdata.py --generate-synthetic