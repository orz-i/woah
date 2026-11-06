# Captured region-tracking replay

`group-hd-hero-190.zip` contains 190 post-inference observations from the existing
public `group-hd-8956295.mp4` fixture (SHA-256
`d95c0ba298ab0701c01e349185afa049d51ae1037b6907c960d6e1b8fcd01cd5`).
It contains detection geometry, low-resolution segmentation masks, source PTS,
initial identity assignments and pre-fix tracker snapshots, without video pixels.

Capture: OnePlus 8T / Android 14, CPU quality export, source PTS 0..7,560,000 us
at 40,000 us increments. Source was the selection phase at 1035ff02 with the
opt-in debug recorder; association scoring had not yet moved to Rust. The
baseline replay was verified against every captured identity, float rectangle,
state, observation flag and observation age before changing reservation policy.

Archive SHA-256:
`3f55195ed577c83f87e2247c091f77e199d9560553ba358964fecb7e55de3ebf`.

Each ZIP entry is a gzip-compressed big-endian `DataOutputStream` record. The
versioned writer is `TrackerReplayCapture.kt`; `CapturedTrackerReplayTest.kt`
is its reader. Header magic is `FKTR` (`0x464B5452`), version 1. Mask planes
and mapper dimensions are preserved so association uses the real inputs.

The pre-fix snapshots record 22 unobserved frames for hero ID 6. Source frames
162..168 were manually inspected: the blue-top subject is independently visible
and supported by a current detection. The regression requires an observed match
to that subject, never a camera proxy or privacy-class prototype exemption.
The captured snapshots are failure evidence, not post-fix expected lifecycle.
