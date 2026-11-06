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
#ifdef __cplusplus
}
#endif
#endif
