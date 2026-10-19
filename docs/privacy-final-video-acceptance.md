# Woah 最终导出视频隐私验收（2026-10-19）

## 当前边界

适用导出提交：`178065ae5b7161886b928ec5ca5871885fada7ba`。对应的 Android 17 / PLK110 最终导出：

| 模式 | 诊断 job_id | 预期导出 | 帧数 |
| --- | --- | --- | ---: |
| 横屏 | `job_1792383203802` | 1280×720、30 fps | 1391 |
| 竖屏 | `job_1792383694940` | 810×1440、30 fps | 1391 |

前期分析：`docs/privacy-tail-mask-upload-audit.md`。对每种模式，仅有一个 `PRIVACY_COVERAGE_DROP`（39.733 秒，82→65，79.27%）。末尾 46.000–46.333 秒连续 11 帧没有新鲜检测，但 GL 输入 Mask 没有整体清空。46.233 秒旧的保护 ID 1 从 REACQUIRING 转入 LOST，历史 Mask 扩张。

以上都是**诊断级证据**，不是最终视频像素通过的证据。工具不会调用人脸识别、重新推理或自动认证保护正确性。

## 复核输入

需要手机上**直接导出的原始横屏和竖屏视频文件**，不是源素材，也不是从播放器截取、微信转发重编码或重新裁剪的片段。

在构建 APK 的机器上保留这两个导出视频。使用 Python 3.9+ 和 FFmpeg 工具（`ffmpeg`、`ffprobe` 两条命令均在 PATH）即可，不需要再构建 APK。

Windows PowerShell，从仓库根目录执行（修改三个示例路径）：

```powershell
py -3 tools\diagnostics\privacy_final_frame_review.py `
  --landscape "D:\Exports\woah-landscape.mp4" `
  --portrait "D:\Exports\woah-portrait.mp4" `
  --expected-commit "c259eb46f9a396e1e0ab6a039c2001f5b9d62f0d" `
  --output-dir "D:\WoahPrivateReview"
```

macOS/Linux 也可以执行：

```bash
python3 tools/diagnostics/privacy_final_frame_review.py \
  --landscape "/path/to/woah-landscape.mp4" \
  --portrait "/path/to/woah-portrait.mp4" \
  --expected-commit "c259eb46f9a396e1e0ab6a039c2001f5b9d62f0d" \
  --output-dir "/path/to/WoahPrivateReview"
```

**注意：** `--output-dir` 必须是新的或空目录、且位于 Git 仓库之外，以防止包含人物画面的 PNG 被意外提交。工具不会自动上传任何文件，不会改写原 MP4。

若缺少 FFmpeg，请在电脑上安装 FFmpeg 并确认 `ffmpeg -version` 与 `ffprobe -version` 成功。若工具提示帧数、分辨率、帧率或时间戳不符，不要跳过检查；找回对应诊断任务的完整导出文件。

## 生成内容

成功后终端显示 `PRIVACY_FINAL_FRAME_REVIEW=PREPARED_NOT_ACCEPTED`（**仅准备完成，并非验收通过**）。

输出目录：

- `index.html`：浏览器本地打开，逐张比较风险时间轴的完整分辨率最终视频 PNG。
- `review-checklist.md`：分别记录横竖屏、每个窗口的保护/误遮挡结果，记录有问题的源帧编号与 PTS。
- `manifest.json`：视频文件 SHA256、尺寸、帧数、帧率、每张 PNG 对应的原始帧编号/PTS，初始结果固定为 `NOT_REVIEWED`。
- `landscape/overlap_39s`、`landscape/end_46s`、`portrait/...`：按 **FFprobe 实际帧时间戳**筛选，再通过 FFmpeg 的**源帧序号**无损提取。这样不会因为 `-ss` 寻找关键帧、浮点时间或含 B 帧的压缩格式，把风险帧误对应到相邻时间。

工具对本次两段视频的 1391 帧/30 fps/尺寸/尾帧 PTS 做严格验证，防止错误输入。视频 SHA256 仅标识本地文件，**不能单凭哈希证明文件一定属于诊断 job_id**；仍需用户核对视频来源。`--expected-commit` 为可选的操作者声明，不会从 MP4 自动推断。未提供时 `expected_commit=null`，绝不沿用工具的旧版硬编码 SHA。2026-10-19 的第二份私密抽帧包沿用了旧工具的 `178065a` 元数据，尽管其随附诊断 ZIP 的实际构建提交为 `c259eb4`；因此这一个字段不是提交版本的可靠凭证。

## 人工验收准则

**39.633–39.900 秒：** 核对 39.733 秒前后完整连续帧。保护对象为非主角 ID 0/1/3/4，主角 ID 2 不应被误遮挡。在人物交叠处检查非主角的脸、身体或其他可识别区域有没有保护边界漏洞；另核对被剔除部分是否确属未保护的前景主角。若判断不了，不标记通过。

**45.833–46.333 秒：** 核对 45.967 秒后的最后 11 帧：仍可见的非主角是否被正确遮挡；46.233 秒附近是否出现跳变、突然变大的历史 ID 1 Mask，遮到不该遮的背景或主角。若人已经离开画面，需要确认是退出而非漏检，不能仅因检测数为零认定隐私安全。

**横竖屏对比：** 验证同一人物在不同裁切画面中保护结果一致。若某位人物在竖屏被裁切完全排除，可在清单标记“该对象不适用”并写明原因，不能直接把它当作源画面的保护成功。

**判定：** 全部风险帧均无可识别裸露、无遮挡瞬间或明显误遮挡，才可记为此专项的“人工抽帧验收通过”。抽帧并不等同于对整段视频所有编码像素的数学证明。存在漏保护或无法判断的帧，保持 `REVIEW_REQUIRED` 并反馈具体帧索引、PTS、现象，再决定下一次修复。

涉及人物隐私，建议优先在本机审阅；需要进一步协助时，只提供用户有权分享的、**最小数量的已导出画面**和 `review-checklist.md`，不要上传原始视频、训练素材或未保护原图。验收结束后妥善清理本地 PNG 副本。

## 通过后的后续工作

只有视频视觉验收通过且此前日志回归成立，才将隐私专项标记为阶段完成。之后独立处理 ID 1 旧 Mask 误遮挡和多次导出耗时波动，避免在缺乏真实画面证据的情况下调整身份、LOST 回退或 Kalman。是否向 master 合并仍由用户明确决定。
