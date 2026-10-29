# Android 对比视频导出（2026-10-26）

状态：实现、组件测试、原生管线及正式页面真机验收通过，范围限下列素材与本机。

分支保持 `feat/dance-comparison-m0`，HEAD `e286cf6`。本轮未提交、推送或合并；保留本轮开始时已有的未提交练习、画面和音乐速率功能，没有重做全量 M0 / Release QA。

## 使用与行为

正式对比页「更多操作 → 导出对比视频」可选择全段或 A/B 选段。全段指播放器的共同音乐区间；A/B 端点完整有效即可导出，无需开启循环。导出前暂停并保存项目，完成或取消后恢复原项目的暂停预览。

- 固定输出 1× H.264 / AAC MP4。参考源时间作为输出时钟；目标源时间继续使用 `round(参考源时间 × timeScale) + offsetUs`，不根据人物动作重新对齐。
- 沿用当前上下/左右排列、当前预览画布比例、两路独立镜像与缩放平移。宽高取偶数、长边最多 1920，保留当前画布尺寸且不额外放大。导出面板显示实际尺寸。
- 使用参考音频；参考没有音轨时生成静音 AAC。保持源采样率，单/双声道保留，多声道下混为单声道。练习慢放速度、循环重复次数和界面控件不写入文件。
- 视频按照参考的真实 PTS 间隔输出，保留 VFR；A/B 首帧覆盖精确选段起点，结尾使用独占终点。
- 独立前台服务处理进度、后台运行、取消和重试，不启动隐私推理。完成后 Android 10+ 自动保存到 `Movies/Woah`；重复保存复用同一条目。旧版 Android 提供系统「另存为」路径，该路径本轮未做真机验收。
- 导出使用冻结设置，重试沿用该次设置。重新进入对比页时，先恢复活动导出的进度，任务结束后再打开预览解码器。进程中断后提示重试；未完成文件不作为成功文件发布。

## 实现

`ComparisonExportPlan` 冻结范围和画布，Pigeon 新增导出任务接口。`ComparisonExportPipeline` 复用现有 `ComparisonCodecVideo`、`ComparisonTimeMapping`、`ComparisonPicture` 和 `ComparisonGlEngine`；仅为编码 Surface 增加 EGL recordable 配置和呈现时间，不复制另一套合成算法。

AAC 的源时间截取按 PCM 采样边界处理。真机发现 Android AAC 编码器不报告固有延迟且首包时间为零，初版文件多出约 128ms 音频延迟。现在对实际选用的编码器/采样率/声道组合，用短已知脉冲测量一次，修正包时间；额外刷出编码尾部，并在封装时处理负时间预卷与精确 EOS。没有修改项目中的音乐偏移或速率。

实现依据：[AOSP AAC 编码时间戳](https://android.googlesource.com/platform/frameworks/av/+/master/media/codec2/components/aac/C2SoftAacEnc.cpp)、[AOSP MP4 负时间与 edit list](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/MPEG4Writer.cpp)、[MediaMuxer 终点样本约定](https://developer.android.com/reference/android/media/MediaMuxer#writeSampleData(int,%20java.nio.ByteBuffer,%20android.media.MediaCodec.BufferInfo))。

任务元数据使用独立 AtomicFile；私有完成文件原子改名后才发布。MediaStore 使用 `IS_PENDING`，失败或取消清理本次新建条目；恢复清理同时核对所属包、路径和文件名。项目 JSON 未新增导出字段，也不迁移或改写旧角色字段。

## 验证范围

Flutter 62 个唯一用例通过（原 53 项与新增 9 项导出测试）；初始化改动后原用例通过，后台恢复用例修正后单独通过。覆盖选段、半速练习仍导出 1×、冻结画面、保存失败、取消/返回、重试、保存/打开动作和活动任务先于预览恢复。静态检查无问题。

原生 30 项唯一用例通过：导出 7、音乐速率 12、画面 6、练习 PTS/循环 5。帧完整性修复后重跑相关 18 项，未变的 12 项音乐速率结果复用。Debug 构建与覆盖安装通过。真机为 OnePlus KB2000 / Android 14。

真机通过 Debug 专项 instrumentation 调用正式导出管线及前台服务，不进入旧 M0 页面。生成文件另用 FFmpeg/ffprobe、源帧二进制编码和音频互相关检查：

| 场景 | 输出 | 时长 / 帧数 | 验证结果 |
| --- | --- | --- | --- |
| 正向音乐速率差、A/B、左右、独立镜像/缩放平移 | 384×640 | 5.530864s / 143 | `a=.8, b=1.2s`；全部输出 PTS 偏差最多 1µs，抽检两路帧编码与画面变换一致 |
| 全段、上下、VFR | 384×640 | 23s / 591 | 33/66ms 帧间隔保留，音频位置无额外延迟 |
| 逆速率、负偏移、非零共同源起点 | 384×640 | 5.530864s / 166 | `a=1.25, b=-1.5s`；参考源从共同起点 1.2s 计时 |
| 无音轨参考 | 384×640 | 2.345678s / 61 | 输出有效 AAC，解码 RMS=0 |
| 真实音乐派生副本、HEVC 参考、44.1kHz | 384×640 | 5.530864s / 166 | 输出 H.264/AAC，音频互相关偏差 1 个采样（23µs） |
| 与手机画布相同的 A/B | 1080×1402 | 5.530864s / 143 | 全部帧与 PTS 完整，独立镜像/缩放/平移符合冻结设置 |
| 正式页全段及后台运行 | 1080×1402 | 23s / 591 | 实际选段/导出入口、后台完成、相册保存与预览恢复通过；练习 0.5× 不带入文件 |

有声文件的互相关偏差为 0–1 个采样（最多 62µs），相关系数 ≥0.993。音视频轨和容器的结束时间偏差最多 74µs，包含 MP4 edit list 时间刻度取整；没有此前约 128ms 的延迟或尾部缺失。真值素材中的动作拖拍内容仍来自原目标帧，未额外做人物动作对齐。

真实音乐一项使用原参考的本地副本和已知速率变换的派生目标，不代表当前两条原视频整段导出已人工验收。没有新增长时原片、来源未确认的 60fps 版本、多机型或 iOS 覆盖；这些缺口不阻塞本轮产品实现。

正式页复验发现初版 A/B 文件在相对 298766µs 处遗漏一帧（142/143），没有记为通过。原因是编码 Surface 使用 interval=0，使 Android BufferQueue 进入可替换排队帧的异步模式；现改为同步背压，在等待 GL 时排空编码器，并用有界提交/输出 PTS 队列检查每帧，遗漏、重排或未刷完时不发布文件。参考 [AOSP Surface::setSwapInterval](https://android.googlesource.com/platform/frameworks/native/+/b68dc25365978e09554dc5828f0dea8fb35faf3a/libs/gui/Surface.cpp)。修复后六种原生场景及正式页 A/B/全段共八个文件再次独立验证，A/B 为 143/143，全段为 591/591。

相册幂等、私有/相册文件哈希及服务生命周期的既有检查复用；帧修复后再次通过正式页完成导出、后台运行、保存与返回预览。Flutter 代码在此原生修复中未变，不重复无关检查。

## 正式页面与系统播放器

用户解锁后，已从正式页完成 A/B 选择与导出、全段后台导出、取消选项面板、运行中取消、冻结设置重试、返回键取消，以及完成/取消后恢复暂停预览。恢复时位置为 3.456789s、偏移 +1.2s、音乐速率 .8、练习速度 .5；随后实际播放/暂停，共同时间推进至 4.014s，确认预览解码器可继续使用。

「查看视频」接通默认 OPPO 视频，但该应用要求首次使用协议，未代用户同意。随后使用系统已有本地相册处理器实际播放修复后的文件，未更改默认应用。已查看选项、进度、完成、取消/恢复和相册播放截图；导出设置及按钮可见，无本次页面溢出。

临时三分钟重复素材仅用于留出足够时间点击运行中的取消；该任务未完成导出，不作为长时原片验收。

## 数据与产物

原两条历史项目已按本轮开始时的备份逐字节恢复，含 `updatedAtMs`、偏移、共同位置、镜像、画面和选段。临时项目、手机测试视频、任务文件及测试相册条目均清理；没有残留导出服务或实际持有的唤醒锁，亮屏和旋转设置保持原值。正式入口已启动 `art.gaoge.dance/.MainActivity`。清理后手机自动锁屏，全部页面检查在锁屏前已完成。

最终 APK、安装哈希、构建来源、源文件集合与安全摘要见 [comparison-export-2026-10-26.json](comparison-export-2026-10-26.json)。APK 位于 `mobile/app/build/app/outputs/flutter-apk/app-debug.apk`；本地与手机 SHA-256 一致：`e72f19222394696364872224c86ce672df61d2fc317799f5a7c2a0595db91b42`（272,762,648 bytes）。相关移动端来源共 56 个文件，集合 SHA-256 `ef8025bb181aedcdeb897a4553c78bff335beb395f98721516e1573dc8c9c112`。代码与证据在工作区，未提交或推送。

独立文件检查工具为 [verify_comparison_export.py](../tools/comparison/verify_comparison_export.py)：

```powershell
.venv/Scripts/python.exe tools/comparison/verify_comparison_export.py --output output.mp4 --spec frozen-job.json --reference reference.mp4 --target target.mp4 --report report.json
```

`--coded-fixtures` 额外检查 640×360、30fps 固定真值素材的帧编码及镜像/缩放/平移，真实素材不加此参数。Debug 专项入口在 `ComparisonExportInstrumentation`，必须显式传入 `comparisonExportFixtures=true` 且先准备指定测试素材；`mode=manager` 检查正式服务、取消、重试和相册保存。此入口仅存在于 Debug 源集，未运行全量 instrumentation 集合。

原 URI、项目备份、派生素材、输出视频、日志和逐文件哈希保存在 Git 忽略目录 `docs/session/comparison-export-20261026/`，不放入公开摘要。
