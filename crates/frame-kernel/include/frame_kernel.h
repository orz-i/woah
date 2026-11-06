#ifndef FRAME_KERNEL_H
#define FRAME_KERNEL_H
#include <stddef.h>
#include <stdint.h>
#define FRAME_KERNEL_LAYER_BODY 1u
#define FRAME_KERNEL_LAYER_FACE 2u
#define FRAME_KERNEL_LAYER_STICKER 4u
#ifdef __cplusplus
extern "C" {
#endif
typedef struct FrameKernelRect { float left, top, right, bottom; } FrameKernelRect;
typedef struct FrameKernelTransform {
    float scale, pad_left, pad_top;
    uint32_t source_width, source_height, input_size, proto_size;
} FrameKernelTransform;
uint32_t frame_kernel_abi_version(void);
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
