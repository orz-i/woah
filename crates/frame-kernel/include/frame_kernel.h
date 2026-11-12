#ifndef FRAME_KERNEL_H
#define FRAME_KERNEL_H
#include <stddef.h>
#include <stdint.h>
#define FRAME_KERNEL_LAYER_BODY 1u
#define FRAME_KERNEL_LAYER_FACE 2u
#define FRAME_KERNEL_LAYER_STICKER 4u
#define FRAME_KERNEL_SCENE_BODY 1u
#define FRAME_KERNEL_SCENE_FACE 2u
#define FRAME_KERNEL_SCENE_OTHER_FACES 4u
#ifdef __cplusplus
extern "C" {
#endif
/* Worker events: 0 cancel request, 1 published success, 2 failure,
   3 cancellation acknowledged, 4 resources released.
   States: 0 running, 1 cancellation requested, 2/3/4 pending success/failure/
   cancellation, 5/6/7 terminal success/failure/cancellation. -1 invalid.
   Do not expose pending outcomes as terminal; release native resources first. */
int32_t frame_kernel_work_transition(uint32_t state, uint32_t event);
int32_t frame_kernel_work_can_replace(uint32_t state);

typedef struct FrameKernelTimeRange { int64_t start, end; uint32_t valid, reserved; } FrameKernelTimeRange;
typedef struct FrameKernelTimeMap { double scale; int64_t offset; uint32_t valid, reserved; } FrameKernelTimeMap;
typedef struct FrameKernelEditRange { int64_t start, duration; uint32_t timescale, valid; } FrameKernelEditRange;
FrameKernelEditRange frame_kernel_time_edit(int64_t start, int64_t end, double scale, int64_t offset);
/* Half ties towards positive infinity before adding offset. mode 0 maps native
   source ticks, mode 1 maps target microseconds back to reference. MIN invalid. */
int64_t frame_kernel_time_value(uint32_t mode, int64_t value, uint32_t ticks, double scale, int64_t offset);
FrameKernelTimeMap frame_kernel_time_inverse(double scale, int64_t offset);
FrameKernelTimeMap frame_kernel_time_anchors(int64_t r1, int64_t t1, int64_t r2, int64_t t2);
FrameKernelTimeRange frame_kernel_time_overlap(int64_t rs, int64_t re, int64_t ts, int64_t te, double scale, int64_t offset);
/* Sorted unique PTS; mode 0 displayed, 1 next, 2 previous displayed. Index or -1.
   The caller owns and retains len readable samples for the duration of call. */
int64_t frame_kernel_frame_index(const int64_t *pts, size_t len, int64_t time, int64_t start, int64_t end, uint32_t mode);
typedef struct FrameKernelFrameStep { int64_t value; uint32_t probe, valid; } FrameKernelFrameStep;
/* Missing previous interval uses ps=-1; probe=1 asks the decoder for its interval
   at value, then call again with that actual interval. Endpoints stay [rs,re). */
FrameKernelFrameStep frame_kernel_frame_step(int64_t fs,int64_t fe,int64_t rs,int64_t re,uint32_t forward,int64_t ps,int64_t pe);
typedef struct FrameKernelViewport { int32_t x, top, width, height; } FrameKernelViewport;
typedef struct FrameKernelQuad { float scale_x, scale_y, shift_x, shift_y; uint32_t valid; } FrameKernelQuad;
FrameKernelViewport frame_kernel_pane(int32_t width, int32_t height, int32_t gap, uint32_t reference, uint32_t side_by_side);
FrameKernelViewport frame_kernel_fit(FrameKernelViewport pane, double aspect);
FrameKernelQuad frame_kernel_quad(FrameKernelViewport pane, FrameKernelViewport fit, double zoom, double pan_x, double pan_y);

/* Shared analysis profile v1; playback time stretching stays native. */
uint64_t frame_kernel_audio_create(int64_t origin_us);
void frame_kernel_audio_destroy(uint64_t id);
/* mode 0 normalized mono/16k, mode 1 interleaved source PCM. 0 success,
   -1 invalid, -2 too long, -3 discontinuous. Caller retains samples during call. */
int32_t frame_kernel_audio_push(uint64_t id, const float *samples, size_t len,
    uint32_t mode, uint32_t rate, uint32_t channels, int64_t time_us);
/* Null/0 queries required bytes and finalizes once; fill then destroy session.
   Output is canonical little-endian FKMF v1, suitable for local replay. */
int64_t frame_kernel_audio_finish(uint64_t id, uint8_t *output, size_t capacity);
typedef struct FrameKernelMusicCandidate {
    int64_t offset; double scale, score; uint32_t windows, reserved;
    double support, residual;
} FrameKernelMusicCandidate;
/* status: 0 matched, 1 no match, 2 inconsistent, 3 ambiguous, 4 cancelled,
   5 invalid. At most 3 candidates. Callback is synchronous, 1 continue/0 cancel. */
typedef struct FrameKernelAlignment { uint32_t status, count; FrameKernelMusicCandidate candidates[3]; } FrameKernelAlignment;
FrameKernelAlignment frame_kernel_audio_align(const uint8_t *reference, size_t reference_len,
    const uint8_t *target, size_t target_len, uint32_t (*current)(void *), void *context);

typedef struct FrameKernelRect { float left, top, right, bottom; } FrameKernelRect;
typedef struct FrameKernelSceneTarget { int64_t id; uint32_t layers; } FrameKernelSceneTarget;
/* layers: 1 body, 2 face. Body always has both bits for its face supplement.
   No aliases/retained pointers. Count or -1 invalid; <=-2 need -(result+1).
   Individual/mixed absent IDs remain requested; only uniform intent maps current IDs. */
int32_t frame_kernel_scene_targets(const int64_t *roots, size_t roots_len,
    const int64_t *body, size_t body_len, const int64_t *face, size_t face_len,
    const int64_t *current, size_t current_len, uint32_t has_hero, int64_t hero,
    FrameKernelSceneTarget *output, size_t capacity);
typedef struct FrameKernelEllipse { float center_x, center_y, radius_x, radius_y; } FrameKernelEllipse;
typedef struct FrameKernelRegionMeasurement {
    FrameKernelEllipse region; FrameKernelRect anchor; int64_t timestamp_us;
    uint32_t kind, flags;
} FrameKernelRegionMeasurement;
/* Additive continuation for uniform coverage only; enabled=0 clears history.
   Output records are retained observations then current_count resolved values.
   No identity ownership/exemptions are inferred. Inputs/outputs cannot alias.
   Returns count, -1 invalid, <=-2 needs -(result+1) records. No partial writes. */
int32_t frame_kernel_continue_regions(const FrameKernelRegionMeasurement *history, size_t history_len,
    const FrameKernelRegionMeasurement *current, size_t current_len, int64_t now, uint32_t enabled,
    FrameKernelRegionMeasurement *output, size_t capacity);
int64_t frame_kernel_region_context_probe_us(int64_t timestamp_us);
typedef struct FrameKernelRegionState {
    FrameKernelEllipse output;
    float reference_radius_x, reference_radius_y, reference_width, reference_height;
    FrameKernelRect anchor; int64_t timestamp_us; uint32_t flags;
} FrameKernelRegionState;
/* Initialize state to zero. kind: 0 observed, 1 projected, 2 estimated.
   Measurement flags: 1 current observed anchor, 2 trusted local center,
   4 half-pixel lattice, 8 weak translation-only center, 16 cover measured extent.
   All state/output memory belongs to the caller; outputs cannot alias state.
   0 success, -1 invalid; invalid inputs leave state and output unchanged. */
int32_t frame_kernel_region_step(FrameKernelRegionState *state, FrameKernelRegionMeasurement measurement, FrameKernelEllipse *output);
int32_t frame_kernel_anchor_translation(FrameKernelRect previous, FrameKernelRect current, float output[2]);
typedef struct FrameKernelTransform {
    float scale, pad_left, pad_top;
    uint32_t source_width, source_height, input_size, proto_size;
} FrameKernelTransform;
uint32_t frame_kernel_abi_version(void);
typedef struct FrameKernelAssociationWeights { float bounds, mask, motion, direction, gating_threshold; } FrameKernelAssociationWeights;
typedef struct FrameKernelAssociationEvidence {
    FrameKernelRect predicted, observed, candidate;
    float mask_iou, gating_distance, velocity_x, velocity_y, history_x, history_y;
    int64_t history_age_us;
    uint32_t flags;
} FrameKernelAssociationEvidence;
FrameKernelAssociationWeights frame_kernel_association_defaults(void);
/* flags: strict unique match=1, durable non-body identity=2, current owner support=4.
 * Returns 1 release, 0 quarantine, -1 invalid. */
int32_t frame_kernel_reservation_release(uint32_t flags, uint32_t owner_groups, float bbox_iou, float mask_iou);
int32_t frame_kernel_association_score(FrameKernelAssociationEvidence evidence,
    FrameKernelAssociationWeights weights, float *output);
int32_t frame_kernel_scene_scope(const int64_t *roots, size_t roots_len, const int64_t *body, size_t body_len,
    const int64_t *face, size_t face_len, uint32_t has_hero, int64_t hero);
int32_t frame_kernel_compositor_targets(const int64_t *selected, size_t selected_len,
    const int64_t *covered, size_t covered_len, const int64_t *explicit_ids, size_t explicit_len,
    int64_t *output, size_t capacity);
int32_t frame_kernel_current_hero_index(const FrameKernelRect *detections, size_t len,
    uint32_t has_observed, FrameKernelRect observed);

typedef struct FrameKernelBoxFilter { float mean[8], covariance[64]; int64_t timestamp_us; } FrameKernelBoxFilter;
/* operations: 0 initialize, 1 predict, 2 measurement update, 3 velocity damping,
   4 Mahalanobis gating, 5 current bounds. Invalid inputs leave outputs unchanged. */
int32_t frame_kernel_box_filter_step(FrameKernelBoxFilter *state, uint32_t operation, FrameKernelRect bounds,
    int64_t pts_us, float factor, FrameKernelRect *bounds_out, float *distance_out);
/* 0 success; 1 singular; -1 invalid; no partial output on failure. */
int32_t frame_kernel_invert_matrix4(const float *input, float *output);

typedef struct FrameKernelLetterbox { float scale, width, height, left, top; } FrameKernelLetterbox;
typedef struct FrameKernelPixelSpec {
    uint32_t width, height, row_stride, channels, red, green, blue;
    uint32_t flip_rows, flip_columns, interleaved;
    float scale, bias;
} FrameKernelPixelSpec;
/* Caller-owned nonoverlapping buffers; invalid contracts return -1 before writes.
   Pixel layout: interleaved=0 planar RGB, =1 interleaved RGB. Channel indices map
   RGB/RGBA/BGR/BGRA inputs explicitly; normalization is byte*scale+bias. */
int32_t frame_kernel_letterbox(uint32_t width, uint32_t height, uint32_t ow, uint32_t oh, FrameKernelLetterbox *output);
int32_t frame_kernel_normalize_pixels(const uint8_t *input, size_t len, FrameKernelPixelSpec spec, float *output, size_t capacity);
int32_t frame_kernel_resize_rgba(const uint8_t *input, size_t len, uint32_t width, uint32_t height, uint32_t stride,
    uint8_t *output, size_t capacity, uint32_t ow, uint32_t oh, uint8_t fill, FrameKernelLetterbox *plan);
typedef struct FrameKernelPlane { const uint8_t *data; size_t len; uint32_t row_stride, pixel_stride; } FrameKernelPlane;
typedef struct FrameKernelYuvSpec {
    uint32_t width, height, crop_left, crop_top, crop_right, crop_bottom;
    uint32_t display_width, display_height, rotation, output_width, output_height;
    uint32_t flip_rows, matrix, full_range, fill;
} FrameKernelYuvSpec;
/* Matrix: 0 BT.601, 1 BT.709, 2 BT.2020. Chroma sample coordinates are luma/2.
   Native adapters supply crop, rotation, plane offsets/strides and range metadata.
   Input planes may overlap each other; output must be disjoint. */
int32_t frame_kernel_yuv420_rgba(FrameKernelPlane y, FrameKernelPlane u, FrameKernelPlane v,
    FrameKernelYuvSpec spec, uint8_t *output, size_t capacity);
typedef struct FrameKernelMatchPair { uint32_t row, column; } FrameKernelMatchPair;
/* Stable thresholded greedy matching, not an optimal Hungarian assignment.
   Returns count; -1 invalid; <=-2 needs -(result+1) pair capacity, no partial writes. */
int32_t frame_kernel_match_costs(const float *costs, size_t len, uint32_t rows, uint32_t columns,
    float max_cost, FrameKernelMatchPair *output, size_t capacity);
int32_t frame_kernel_match_rects(const FrameKernelRect *rows, uint32_t nr, const FrameKernelRect *columns,
    uint32_t nc, float max_cost, FrameKernelMatchPair *output, size_t capacity);
typedef struct FrameKernelFollowState {
    float camera_x, camera_y, target_x, target_y, velocity_x, velocity_y;
    int64_t last_pts_us;
    uint32_t initialized, has_time;
} FrameKernelFollowState;
int32_t frame_kernel_follow_reset(FrameKernelFollowState *state);
int32_t frame_kernel_follow_step(FrameKernelFollowState *state, FrameKernelRect target, int32_t has_target,
    int64_t pts_us, float source_aspect, float output_aspect, float zoom, float smooth, FrameKernelRect *output);
int32_t frame_kernel_fit_even_ratio(uint32_t width, uint32_t height, uint32_t max_height,
    uint32_t ratio_w, uint32_t ratio_h, uint32_t *output);
typedef struct FrameKernelInstanceSpec {
    uint32_t anchors, attributes, score_index, coefficient_index, channels;
    uint32_t head_transposed, proto_nhwc, coordinate_units;
} FrameKernelInstanceSpec;
typedef struct FrameKernelInstanceThresholds { float confidence, bbox_iou, mask_iou; } FrameKernelInstanceThresholds;
typedef struct FrameKernelInstanceDetection { FrameKernelRect bounds; float confidence; } FrameKernelInstanceDetection;
typedef struct FrameKernelInstanceMetrics {
    uint64_t candidate_scan_ns, suppression_ns, mask_decode_ns, mask_iou_ns, materialize_ns;
} FrameKernelInstanceMetrics;
int32_t frame_kernel_mask_bytes(const float *input, size_t len, uint8_t *output, size_t output_len);
/* Layout flags: head_transposed=0 attribute-major/1 anchor-major;
   proto_nhwc=0 planar/1 interleaved; coordinate_units=0 pixels/1 normalized/2 auto.
   No buffers may overlap. All outputs belong to the caller; nothing is retained.
   Result >=0: count; -1: invalid; <=-2: insufficient capacity, need -(result+1).
   On insufficient capacity no detections or masks are written. */
int32_t frame_kernel_decode_instances(const float *head, size_t head_len, const float *proto, size_t proto_len,
    FrameKernelInstanceSpec spec, FrameKernelTransform transform, FrameKernelInstanceThresholds thresholds,
    FrameKernelInstanceDetection *detections, size_t detection_capacity, uint8_t *masks, size_t mask_capacity,
    FrameKernelInstanceMetrics *metrics);
/* Independent owned identity sessions. Handles are monotonic IDs, never
   pointers; close/reuse of an invalid handle returns an error. Calls on a
   session serialize internally; distinct sessions can run independently. */
typedef struct FrameKernelTrackingOptions {
 uint32_t max_missed, grace, occlusion_max, scene_inliers;
 float minimum_score;
 FrameKernelAssociationWeights weights;
 float overlap, ambiguity, scene_tolerance, maximum_shift;
 uint32_t scene_compensation;
 float foot_margin;
 uint32_t erosion_radius;
} FrameKernelTrackingOptions;
typedef struct FrameKernelTrackingObservation {
 FrameKernelRect bounds;
 float confidence, foot_y;
 uint64_t mask_offset; /* UINT64_MAX for no mask; otherwise an offset in pixels. */
 uint32_t mask_width, mask_height;
 FrameKernelTransform transform;
} FrameKernelTrackingObservation;
typedef struct FrameKernelReplayPlan {
 uint32_t required, reset;
 int64_t start_us, step_us;
 uint32_t regular_count, append_target;
 int64_t target_us;
} FrameKernelReplayPlan;
/* Flags: bit0 hero, bit1 named privacy targets. Scope comes from scene_scope.
   Anchor -1 means unknown; last -1 means no live replay state. A named identity
   cannot be replayed before its observed anchor. Zero succeeds, -1 fails closed.
   Decode regular_count steps start_us + i*step_us, then target_us if append_target.
   Decoders and inference remain native; every observation feeds the same session. */
int32_t frame_kernel_replay_plan(uint32_t scope, uint32_t flags, int64_t anchor_us,
 int64_t last_us, int64_t target_us, int64_t step_us, FrameKernelReplayPlan *output);
FrameKernelTrackingOptions frame_kernel_tracking_defaults(void);
/* Identity-independent class sessions. Roots/decisions use 0 selected, 1 unselected.
   Options flags: 1 cached similarity, 2 cached mask warp, 4 count evaluations.
   Typed handles share a monotonic allocator; stale/wrong-kind IDs are invalid. */
typedef struct FrameKernelClassOptions { float minimum, single_minimum, margin; uint32_t maximum_misses, flags; } FrameKernelClassOptions;
typedef struct FrameKernelClassRoot { uint32_t index, selection; } FrameKernelClassRoot;
typedef struct FrameKernelClassDecision { uint32_t index, selection, unknown; } FrameKernelClassDecision;
uint64_t frame_kernel_class_create(FrameKernelClassOptions options);
int32_t frame_kernel_class_destroy(uint64_t id);
int32_t frame_kernel_class_reset(uint64_t id);
/* Eight counters: hard S/U, inferred S/U, unknown, prototype S/U, evaluations. */
int32_t frame_kernel_class_summary(uint64_t id, uint32_t *output8);
/* Capacity >= count is required before mutation. Returns decision count or -1.
   Every pointer is borrowed for this call; output/evaluations must be disjoint. */
int32_t frame_kernel_class_update(uint64_t id, const FrameKernelTrackingObservation *observations, size_t count,
    const uint8_t *pixels, size_t pixels_len, const FrameKernelClassRoot *roots, size_t roots_len,
    FrameKernelClassDecision *output, size_t capacity, uint32_t *evaluations);
uint64_t frame_kernel_tracking_create(FrameKernelTrackingOptions options);
int32_t frame_kernel_tracking_destroy(uint64_t session);
/* After initialization, reserve IDs below the analysis namespace floor. */
int32_t frame_kernel_tracking_reserve_id_floor(uint64_t session, int64_t minimum_next_id);
int32_t frame_kernel_tracking_selection(uint64_t session,
 const int64_t *protected_ids, size_t protected_len,
 const int64_t *body_ids, size_t body_len, const int64_t *face_ids, size_t face_len, uint32_t dormancy);
/* action: 0 initialize, 1 observe, 2 real miss, 3 inference skip. Inputs are
   borrowed only for this call; the session owns any retained observations.
   Zero succeeds; -1 rejects invalid input without replacing the old state. */
int32_t frame_kernel_tracking_step(uint64_t session, uint32_t action, int64_t timestamp_us,
 const FrameKernelTrackingObservation *observations, size_t count,
 const uint8_t *pixels, size_t pixels_len, const int64_t *assigned_ids, size_t assigned_len);
/* Snapshot v1 uses little-endian fixed-width fields, as documented in
   tracking-session-v1.md. Null/zero queries the required size. A successful
   copy returns its byte count; -1 invalid handle/buffer, -2 insufficient space.
   Query/copy never advances tracking. Output must not overlap input storage. */
int64_t frame_kernel_tracking_snapshot(uint64_t session, uint8_t *output, size_t capacity);
uint32_t frame_kernel_protection_layers(int32_t body, int32_t face, int32_t sticker);
int32_t frame_kernel_face_targets(const int64_t *body, size_t body_len,
    const int64_t *face, size_t face_len, int64_t *output, size_t capacity);
/* Returns zero on success; output has four elements. */
int32_t frame_kernel_detail_views(uint32_t width, uint32_t height, FrameKernelRect *output);
int32_t frame_kernel_detail_complete(FrameKernelRect box, FrameKernelRect view, uint32_t width, uint32_t height);
float frame_kernel_iou(FrameKernelRect a, FrameKernelRect b);
/* Buffers must not overlap; no memory is retained or freed by this call. */
int32_t frame_kernel_reproject_mask(const uint8_t *mask, size_t mask_len,
    FrameKernelTransform local, FrameKernelTransform scene, FrameKernelRect view, uint8_t *output, size_t output_len);
int32_t frame_kernel_project_mask(const uint8_t *mask, size_t mask_len, FrameKernelTransform transform,
    uint32_t source_width, uint32_t source_height, uint32_t width, uint32_t height,
    FrameKernelRect crop, uint8_t *output, size_t output_len);
/* Dense face v1: NHWC RGB [-1,1], 192 square, 2304 xywh/6-keypoint anchors.
   Output records are 17 floats: source xyxy, score, six source xy landmarks.
   Inputs/outputs must be disjoint. Returns count, -1 invalid, -2 capacity. */
/* Head localization records: xyxy, confidence, point count, six xy points (18 floats).
   Plan output: source xyxy + normalized anchor xy. Select: index, -1 no owner,
   -2 invalid. All buffers borrowed only for the call; at most 4096 candidates. */
int32_t frame_kernel_face_head_plan(FrameKernelRect person, uint32_t width, uint32_t height, float *output6);
int32_t frame_kernel_face_center(const float *row18, float *output2);
int32_t frame_kernel_face_select(const float *rows18, size_t count, FrameKernelRect view,
    float anchor_x, float anchor_y, float maximum, float separation, float *distance);
int32_t frame_kernel_face_owned(const float *row18, FrameKernelRect source, float output_size, FrameKernelRect person);
/* Region APIs below ALL return0 success,1 no supporting evidence,-1 invalid.
   Nonzero results leave outputs unchanged. Inputs/outputs are borrowed per call. */
enum { FRAME_KERNEL_REGION_OK=0, FRAME_KERNEL_REGION_NONE=1, FRAME_KERNEL_REGION_INVALID=-1 };
typedef struct FrameKernelHeadMaskSpec { uint32_t width,height; FrameKernelTransform transform; FrameKernelRect person; } FrameKernelHeadMaskSpec;
int32_t frame_kernel_detected_face_region(const float *row18,const FrameKernelRect *view,float output_size,float yaw,FrameKernelEllipse *output);
  /* Current owned face rendering. Optional CURRENT mask support may enlarge
     the detected ellipse; it never removes detector coverage or grants identity.
     Positive len requires a valid mask/spec; invalid input leaves output intact. */
  int32_t frame_kernel_current_face_region(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,FrameKernelEllipse region,FrameKernelEllipse *output);
/* Current detected face with uncertain ownership. Apply render sampling margin
   without a body mask or identity. Invalid inputs leave output unchanged. */
int32_t frame_kernel_standalone_face_region(FrameKernelEllipse region,FrameKernelTransform transform,FrameKernelEllipse *output);
int32_t frame_kernel_head_fallback(FrameKernelRect person,FrameKernelEllipse *output);
/* Complete bbox/current-mask fallback. A zero len omits current evidence; a
   null seed selects the bbox seed. Nonzero len requires a valid current mask.
   Status0 writes the whole output; invalid input leaves it unchanged. */
typedef struct FrameKernelCurrentHeadRegion { FrameKernelEllipse region; uint32_t current_mask_guided; } FrameKernelCurrentHeadRegion;
int32_t frame_kernel_current_head_region(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,const FrameKernelEllipse *seed,FrameKernelCurrentHeadRegion *output);
/* Geometry only: caller enforces the observation lease before rendering. */
int32_t frame_kernel_project_face_region(FrameKernelRect current,FrameKernelRect previous,FrameKernelEllipse trusted,int64_t age_us,FrameKernelEllipse *output);
int32_t frame_kernel_mask_head(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,FrameKernelEllipse seed,float *output2);
int32_t frame_kernel_trusted_mask_region(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,FrameKernelRect previous,FrameKernelEllipse trusted,float expansion,FrameKernelEllipse *output);
int32_t frame_kernel_grid_face_input(const uint8_t *rgba, size_t bytes, uint32_t width,
 uint32_t height, uint32_t stride, float *output, size_t length);
int32_t frame_kernel_grid_face_decode(const float *boxes, size_t box_len,
 const float *scores, size_t score_len, uint32_t width, uint32_t height,
 float minimum, float overlap, float *output, size_t capacity);
/* Observation APIs:0 success,1 no evidence,-1 invalid,-2 insufficient capacity.
   Nonzero results leave every output unchanged. All buffers are disjoint/borrowed. */
typedef struct FrameKernelFaceView { FrameKernelRect bounds; uint32_t view,known_owner; int64_t owner; } FrameKernelFaceView;
/* Current detector bbox evidence, independent of rendered landmark padding.
   Caller supplies a current observed mask. Output is 0/1 only after success0. */
int32_t frame_kernel_face_mask_owned(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,const float *row18,float yaw,uint32_t *output);
/* Current ROI/source face ownership. A nonnull view describes local ROI pixels;
   null uses source coordinates. Zero len omits mask evidence. Status0 writes0/1;
   invalid input leaves output unchanged. No identity or history is inferred. */
int32_t frame_kernel_current_face_owned(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,const float *row18,const FrameKernelRect *view,float output_size,float yaw,uint32_t *output);
int32_t frame_kernel_face_mask_support(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,FrameKernelEllipse region,uint32_t *output2);
int32_t frame_kernel_face_observation_score(const float *row18,FrameKernelRect person,float *output);
int32_t frame_kernel_face_upper_body_view(FrameKernelRect person,float *output4_xywh);
int32_t frame_kernel_face_merge_views(const FrameKernelFaceView *input,size_t count,size_t primary,uint32_t require_owner,uint32_t *output,size_t capacity,size_t *output_count);
int32_t frame_kernel_face_resolve_owners(const float *scores,size_t len,size_t people,size_t faces,const int32_t *preferred,int64_t hero,float margin,int32_t *output,size_t capacity);
int32_t frame_kernel_face_unique_owner(const float *scores,size_t count,float margin,uint32_t *output);
int32_t frame_kernel_face_occlusion_padding(const uint8_t *pixels,size_t len,FrameKernelHeadMaskSpec spec,FrameKernelEllipse seed,int64_t age,int64_t lease,FrameKernelEllipse *output);
#ifdef __cplusplus
}
#endif
#endif
