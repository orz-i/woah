# Mobile Export Media Contract

## Goal

Export media properties are decided once before native encoding. Android and iOS
must not independently apply hidden resolution or frame-rate downgrades.

`ExportPlan` in `dance_domain` is the shared contract consumed by Flutter and
forwarded through the existing Pigeon export request.

## Resolution

- Original-aspect export starts from the source display width/height.
- The unified editor exposes a persisted `OutputResolutionPreset`: `source`,
  `fhd`, or `hd`. `source` keeps all source-derived pixels; `fhd` fits output
  inside a 1920x1080 landscape / 1080x1920 portrait envelope, while `hd` uses
  1280x720 / 720x1280.
- FHD/HD are upper bounds, not upscale requests. A 1280x720 source remains
  1280x720 when FHD is selected. Non-16:9 material keeps its aspect ratio while
  fitting inside the chosen envelope (for example 4:3 FHD becomes 1440x1080).
- Output dimensions are normalized downward to even encoder dimensions; source
  pixels are never enlarged just to satisfy an encoder shape.
- 9:16 subject reframe keeps exact 18x32 integer units. Resolution presets are
  applied after the source-derived crop geometry, so a 4K landscape source can
  produce 1206x2144 at `source`, 1080x1920 at `fhd`, or 720x1280 at `hd`.
- Flutter queries `NativeCapabilitiesDto.maxEncodeWidth/maxEncodeHeight` before
  export. If the desired geometry exceeds that encoder capability, `ExportPlan`
  performs one proportional downgrade and records
  `ExportFallbackReason.encoderDimensionLimit`.
- The export screen surfaces that capability fallback to the user.
- Native Android/iOS exporters only enforce even dimensions. They must not add
  another 1080p/1920-long-edge cap.

Examples before device capability fallback:

- 1920x1080 original + source -> 1920x1080
- 3840x2160 original + source -> 3840x2160
- 3840x2160 original + FHD -> 1920x1080
- 3840x2160 original + HD -> 1280x720
- 1920x1080 reframe 9:16 + FHD -> 594x1056 (no upscale)
- 3840x2160 reframe 9:16 + source -> 1206x2144
- 3840x2160 reframe 9:16 + FHD -> 1080x1920
- 3840x2160 reframe 9:16 + HD -> 720x1280

## Frame timing

`ExportTimingPolicy.preserveSourcePts` is the default and currently only timing
policy.

- The probed source FPS is carried as a nominal encoder hint and progress/frame
  estimate. Invalid/unknown FPS falls back to 30.
- Actual video cadence comes from source presentation timestamps (PTS).
- Android renders decoded samples using `(sourcePTS - trimStart)` so video and
  the rebased audio copier share one timeline. Duplicate or broken timestamps
  use one nominal-frame-duration monotonic fallback only.
- Android rounds the integer MediaCodec frame-rate hint, so 29.97/59.94 become
  30/60 rather than being truncated to 29/59.
- iOS encodes each decoded source video sample once and rebases its PTS by the
  trim start. It no longer resamples every source to a fixed 30fps timeline or
  pads duplicate frames to a 30fps count.
- The iOS real-media Simulator smoke fixture now uses 60fps so a regression back
  to fixed 30fps fails the gate.

Variable-frame-rate inputs therefore retain their source timing instead of being
forced into a synthetic CFR stream.

## Bitrate and codec

The current codec remains H.264 for compatibility. Quality export bitrate is
planned from output pixels and nominal cadence at approximately 0.13
bits/pixel/frame, clamped to 4-80 Mbps. This keeps 1080p30 near the previous
8 Mbps quality level while allowing 4K/high-frame-rate output to receive
proportionally more bitrate.

Codec selection (for example HEVC) is intentionally separate from this change.

## Failure and fallback policy

Resolution fallback is explicit and capability-driven. If capability discovery
is unavailable, Woah preserves the desired source-derived contract and lets the
native encoder fail explicitly instead of silently reducing media quality.

The current capability DTO does not yet expose resolution-specific maximum frame
rates. Extremely high-frame-rate combinations that an encoder cannot accept may
therefore fail rather than being silently converted. Extending capability
probing for width/height/FPS tuples is a later hardening step.
