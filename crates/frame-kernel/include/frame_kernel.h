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
typedef struct FrameKernelRect { float left, top, right, bottom; } FrameKernelRect;
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
