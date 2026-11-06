#ifndef FRAME_KERNEL_H
#define FRAME_KERNEL_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct FrameKernelRect { float left, top, right, bottom; } FrameKernelRect;
typedef struct FrameKernelTransform {
    float scale, pad_left, pad_top;
    uint32_t source_width, source_height, input_size, proto_size;
} FrameKernelTransform;
uint32_t frame_kernel_abi_version(void);
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
