# 最终 APK 的 4K 生命周期复测

同一 PID 26560，4/4 通过；3 个视频 / 1283 帧独立检查通过。

| 用例 | 原尺寸 | 实际帧数 | 耗时（秒） | 结果 |
| --- | --- | ---: | ---: | --- |
| group_4k_body_mosaic_balanced | 3840×2160 | 581 | 417.056 | passed |
| solo_4k_cancel | 2160×4096 | 取消于 25 帧 | 12.923 | passed |
| solo_4k_retry_sticker | 2160×4096 | 662 | 219.134 | passed |
| solo_4k_nonzero_trim | 2160×4096 | 40 | 16.174 | passed |
