#ifndef WOAH_PRIVACY_CORE_H
#define WOAH_PRIVACY_CORE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct WoahRect { float left, top, right, bottom; } WoahRect;
typedef struct WoahTransform {
    float scale, pad_left, pad_top;
    uint32_t source_width, source_height, input_size, proto_size;
} WoahTransform;
uint32_t woah_core_abi_version(void);
/* Returns zero on success; output has four elements. */
int32_t woah_core_detail_views(uint32_t width, uint32_t height, WoahRect *output);
int32_t woah_core_detail_complete(WoahRect box, WoahRect view, uint32_t width, uint32_t height);
float woah_core_iou(WoahRect a, WoahRect b);
/* Buffers must not overlap; no memory is retained or freed by this call. */
int32_t woah_core_reproject_mask(const uint8_t *mask, size_t mask_len,
    WoahTransform local, WoahTransform scene, WoahRect view, uint8_t *output, size_t output_len);
#ifdef __cplusplus
}
#endif
#endif
