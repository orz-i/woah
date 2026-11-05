# 最终候选 4K 原生矩阵

同一 PID 9989；25 项全部通过。24 个视频共 14375 帧，逐帧解码和源 PTS 检查通过。

| 用例 | 输出尺寸 | 帧数 | 导出耗时（秒） | 结果 |
| --- | --- | ---: | ---: | --- |
| group_4k_face_solid | 3840×2160 | 581 | 668.370 | passed |
| group_4k_body_solid | 3840×2160 | 581 | 599.671 | passed |
| solo_4k_body_solid | 2160×4096 | 662 | 207.082 | passed |
| solo_4k_face_sticker | 2160×4096 | 662 | 203.156 | passed |
| group_4k_body_mosaic | 3840×2160 | 581 | 589.483 | passed |
| group_4k_body_blur | 3840×2160 | 581 | 617.952 | passed |
| group_4k_body_gradient | 3840×2160 | 581 | 613.774 | passed |
| group_4k_face_mosaic | 3840×2160 | 581 | 755.101 | passed |
| group_4k_face_blur | 3840×2160 | 581 | 757.357 | passed |
| group_4k_face_gradient | 3840×2160 | 581 | 748.689 | passed |
| group_4k_face_sticker | 3840×2160 | 581 | 753.738 | passed |
| solo_4k_body_mosaic | 2160×4096 | 662 | 189.303 | passed |
| solo_4k_body_blur | 2160×4096 | 662 | 198.867 | passed |
| solo_4k_body_gradient | 2160×4096 | 662 | 190.063 | passed |
| solo_4k_face_solid | 2160×4096 | 662 | 213.165 | passed |
| solo_4k_face_mosaic | 2160×4096 | 662 | 218.363 | passed |
| solo_4k_face_blur | 2160×4096 | 662 | 209.360 | passed |
| solo_4k_face_gradient | 2160×4096 | 662 | 207.743 | passed |
| group_4k_body_mosaic_balanced | 3840×2160 | 581 | 399.948 | passed |
| solo_4k_face_sticker_balanced | 2160×4096 | 662 | 146.328 | passed |
| group_4k_body_mosaic_speed | 3840×2160 | 581 | 397.046 | passed |
| solo_4k_face_sticker_speed | 2160×4096 | 662 | 117.744 | passed |
| solo_4k_cancel | 2160×4096 | 取消，不发布 | 10.226 | passed |
| solo_4k_retry_sticker | 2160×4096 | 662 | 208.976 | passed |
| solo_4k_nonzero_trim | 2160×4096 | 40 | 15.289 | passed |
