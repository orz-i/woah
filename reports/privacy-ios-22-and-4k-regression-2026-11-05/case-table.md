# iOS 隐私打码逐项结果

最终状态：passed_with_observations；原矩阵与4K合计49项、48条成片、17155帧。定点13项另列。

完整方法、保守多遮观察及模拟器限制见 [README.md](README.md)。

## 原 22 项

进程 47896；22 条成片；1537 帧。

| 用例 | 素材 | 区间 ms | 模式 | 输出尺寸 | 解码帧 | 结果 |
| --- | --- | --- | --- | --- | ---: | --- |
| group_all_faces | group-hd-8956295.mp4 | [0,3040) | FACE_ONLY/solid/quality | 960×540 | 76 | 通过 |
| group_face_sticker_follow | group-hd-8956295.mp4 | [0,3040) | FACE_ONLY/sticker/quality/hero6 | 720×1280 | 76 | 通过 |
| group_face_solid_follow | group-hd-8956295.mp4 | [0,3040) | FACE_ONLY/solid/quality/hero6 | 720×1280 | 76 | 通过 |
| group_full_solid | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/solid/quality | 960×540 | 40 | 通过 |
| group_full_mosaic | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/mosaic/quality | 960×540 | 40 | 通过 |
| group_full_blur | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/blur/quality | 960×540 | 40 | 通过 |
| group_full_gradient | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/gradient/quality | 960×540 | 40 | 通过 |
| group_first_solid | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/solid/quality/first | 960×540 | 40 | 通过 |
| group_4k_full | group-mirror-8956295.mp4 | [0,1600) | FULL_BODY/solid/quality | 960×540 | 40 | 通过 |
| duo_full_solid | duo-dance-6940536.mp4 | [0,1600) | FULL_BODY/solid/quality | 960×540 | 48 | 通过 |
| solo_face_sticker | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/sticker/quality | 540×1024 | 40 | 通过 |
| solo_face_mosaic | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/mosaic/quality | 540×1024 | 40 | 通过 |
| solo_face_blur | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/blur/quality | 540×1024 | 40 | 通过 |
| solo_face_solid | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/solid/quality | 540×1024 | 40 | 通过 |
| solo_face_gradient | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/gradient/quality | 540×1024 | 40 | 通过 |
| solo_face_balanced | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/sticker/balanced | 540×1024 | 40 | 通过 |
| solo_face_speed | solo-hd-7570258.mp4 | [0,1600) | FACE_ONLY/sticker/speed | 540×1024 | 40 | 通过 |
| solo_nonzero_trim | solo-hd-7570258.mp4 | [1000,2600) | FACE_ONLY/sticker/quality | 540×1024 | 40 | 通过 |
| solo_4k_sticker | solo-portrait-7570258.mp4 | [0,1600) | FACE_ONLY/sticker/quality | 540×1024 | 40 | 通过 |
| group_full_balanced | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/solid/balanced | 960×540 | 40 | 通过 |
| group_full_speed | group-hd-8956295.mp4 | [0,1600) | FULL_BODY/solid/speed | 960×540 | 40 | 通过 |
| group_full_length_follow | group-hd-8956295.mp4 | [0,23240) | FACE_ONLY/solid/quality/hero6 | 720×1280 | 581 | 通过 |

## 原尺寸 4K 27 项

进程 35683；26 条成片；15618 帧。

| 用例 | 素材 | 区间 ms | 模式 | 输出尺寸 | 解码帧 | 结果 |
| --- | --- | --- | --- | --- | ---: | --- |
| group_4k_body_solid | group-mirror-8956295.mp4 | [0,23240) | FULL_BODY/solid/quality | 3840×2160 | 581 | 通过 |
| solo_4k_body_solid | solo-portrait-7570258.mp4 | [0,26480) | FULL_BODY/solid/quality | 2160×4096 | 662 | 通过 |
| solo_4k_nonzero_trim | solo-portrait-7570258.mp4 | [1000,2600) | FACE_ONLY/sticker/quality | 2160×4096 | 40 | 通过 |
| solo_4k_cancel | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/quality | 无成片 | 0 | 通过：取消且清理完成 |
| solo_4k_retry_sticker | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/quality | 2160×4096 | 662 | 通过 |
| group_4k_face_solid | group-mirror-8956295.mp4 | [0,23240) | FACE_ONLY/solid/quality | 3840×2160 | 581 | 通过 |
| solo_4k_face_sticker | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/quality | 2160×4096 | 662 | 通过 |
| group_4k_body_mosaic | group-mirror-8956295.mp4 | [0,23240) | FULL_BODY/mosaic/quality | 3840×2160 | 581 | 通过 |
| group_4k_body_blur | group-mirror-8956295.mp4 | [0,23240) | FULL_BODY/blur/quality | 3840×2160 | 581 | 通过 |
| group_4k_body_gradient | group-mirror-8956295.mp4 | [0,23240) | FULL_BODY/gradient/quality | 3840×2160 | 581 | 通过 |
| group_4k_face_mosaic | group-mirror-8956295.mp4 | [0,23240) | FACE_ONLY/mosaic/quality | 3840×2160 | 581 | 通过 |
| group_4k_face_blur | group-mirror-8956295.mp4 | [0,23240) | FACE_ONLY/blur/quality | 3840×2160 | 581 | 通过 |
| group_4k_face_gradient | group-mirror-8956295.mp4 | [0,23240) | FACE_ONLY/gradient/quality | 3840×2160 | 581 | 通过 |
| group_4k_face_sticker | group-mirror-8956295.mp4 | [0,23240) | FACE_ONLY/sticker/quality | 3840×2160 | 581 | 通过 |
| solo_4k_body_mosaic | solo-portrait-7570258.mp4 | [0,26480) | FULL_BODY/mosaic/quality | 2160×4096 | 662 | 通过 |
| solo_4k_body_blur | solo-portrait-7570258.mp4 | [0,26480) | FULL_BODY/blur/quality | 2160×4096 | 662 | 通过 |
| solo_4k_body_gradient | solo-portrait-7570258.mp4 | [0,26480) | FULL_BODY/gradient/quality | 2160×4096 | 662 | 通过 |
| solo_4k_face_solid | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/solid/quality | 2160×4096 | 662 | 通过 |
| solo_4k_face_mosaic | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/mosaic/quality | 2160×4096 | 662 | 通过 |
| solo_4k_face_blur | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/blur/quality | 2160×4096 | 662 | 通过 |
| solo_4k_face_gradient | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/gradient/quality | 2160×4096 | 662 | 通过 |
| group_4k_body_mosaic_balanced | group-mirror-8956295.mp4 | [0,23240) | FULL_BODY/mosaic/balanced | 3840×2160 | 581 | 通过 |
| solo_4k_face_sticker_balanced | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/balanced | 2160×4096 | 662 | 通过 |
| group_4k_body_mosaic_speed | group-mirror-8956295.mp4 | [0,23240) | FULL_BODY/mosaic/speed | 3840×2160 | 581 | 通过 |
| solo_4k_face_sticker_speed | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/speed | 2160×4096 | 662 | 通过 |
| group_4k_face_custom_sticker_gallery | group-mirror-8956295.mp4 | [0,23240) | FACE_ONLY/sticker/quality/customPNG | 3840×2160 | 581 | 通过 |
| solo_4k_face_custom_sticker_gallery | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/quality/customPNG | 2160×4096 | 662 | 通过 |

## 定点及故障回归 13 项

进程 31265；12 条成片；288 帧。

| 用例 | 素材 | 区间 ms | 模式 | 输出尺寸 | 解码帧 | 结果 |
| --- | --- | --- | --- | --- | ---: | --- |
| group_4k_body_face_union | group-mirror-8956295.mp4 | [16000,17600) | FULL_BODY/solid/quality | 3840×2160 | 40 | 通过 |
| solo_4k_body_face_union | solo-portrait-7570258.mp4 | [7800,9400) | FULL_BODY/solid/quality | 2160×4096 | 40 | 通过 |
| solo_4k_raised_arm_sticker | solo-portrait-7570258.mp4 | [21440,23040) | FACE_ONLY/sticker/quality | 2160×4096 | 40 | 通过 |
| solo_4k_raised_arm_solid | solo-portrait-7570258.mp4 | [21440,23040) | FACE_ONLY/solid/quality | 2160×4096 | 40 | 通过 |
| solo_4k_raised_arm_body | solo-portrait-7570258.mp4 | [21440,23040) | FULL_BODY/solid/quality | 2160×4096 | 40 | 通过 |
| group_hd_overlap_face | group-hd-8956295.mp4 | [13720,13760) | FACE_ONLY/solid/quality | 1920×1080 | 1 | 通过 |
| group_hd_overlap_body | group-hd-8956295.mp4 | [13720,13760) | FULL_BODY/solid/quality | 1920×1080 | 1 | 通过 |
| group_4k_face_solid | group-mirror-8956295.mp4 | [5600,6000) | FACE_ONLY/solid/quality | 3840×2160 | 10 | 通过 |
| group_4k_body_solid | group-mirror-8956295.mp4 | [11400,11800) | FULL_BODY/solid/quality | 3840×2160 | 10 | 通过 |
| group_4k_tail_face | group-mirror-8956295.mp4 | [22840,23240) | FACE_ONLY/solid/quality | 3840×2160 | 10 | 通过 |
| solo_4k_custom_gallery_probe | solo-portrait-7570258.mp4 | [0,640) | FACE_ONLY/sticker/quality/customPNG | 2160×4096 | 16 | 通过 |
| solo_4k_cancel_probe | solo-portrait-7570258.mp4 | [0,26480) | FACE_ONLY/sticker/quality | 无成片 | 0 | 通过：取消且清理完成 |
| solo_4k_immediate_retry_probe | solo-portrait-7570258.mp4 | [1000,2600) | FACE_ONLY/sticker/quality | 2160×4096 | 40 | 通过 |

三类保守多遮行为见 [known-observations.json](known-observations.json)。表中通过不表示零误遮或任意素材的像素级完整分割。
