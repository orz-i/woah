# Tracking session snapshot version 1

All fields are little endian. Counts/booleans/enums are u32, IDs/timestamps i64,
coordinates/confidences f32. No pointer or platform resource appears in a snapshot.
Rect is left/top/right/bottom. Transform is scale/pad_left/pad_top f32 followed by
source_width/source_height/input_size/proto_size u32.

- u32 version (1), region count.
- Each region: i64 ID; u32 state/missed/observation_age/age/observed;
  Rect predicted; f32 confidence/foot_y; ID vector occluders;
  mask-present u32; if present: u32 width/height, Transform, u32 byte count,
  tightly packed row-major bytes; Rect canonical anchor; u32 offscreen-dormant.
- Body class vector then face class vector. Each entry: selection u32
  (0 selected, 1 unselected), u32 detection index, residual ID vector,
  u32 conservative-unknown. Detection indices refer to the current input only.
- Suppressed ID vector; hard-class map (count, then u32 index/selection pairs).
- Motion-only evidence vector: i64 ID, u32 detection index, f32 score/bounds_iou/
  mask_iou, i64 timestamp. Motion evidence does not transfer identity ownership.

An ID vector is a count followed by that many i64 IDs. Region states are
NEW=0, ACTIVE=1, OCCLUDED=2, REACQUIRING=3, LOST=4, REMOVED=5.

The owning adapter serializes step/query/copy as one operation and releases the
session explicitly (Swift also closes on deinit). Rust copies retained input
masks, checks pool offsets, owns all tracker buffers, and validates monotonic
handle lookup. Snapshot size queries never advance the session. Invalid inputs
leave the previous tracking state/snapshot intact. No platform inference,
media, GPU, file, or UI operation is called by the core.
