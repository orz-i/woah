# Woah M0 Android 真机验收 · 2026-10-25

## 结论

**M0_GATE = REJECT_CURRENT_DUAL_EXOPLAYER_COORDINATOR。** 最终 APK 在本机 30fps 正偏移的长/短片与所测交互路径达到屏幕采样目标，但 **24fps、−1s、720p 的有效组合失败**：384 个屏幕样本 P95/最大 = **83.333ms**，约 2 个实际源帧；同场景计划帧 P95/最大 = **83.334ms**。24fps 每帧 41.667ms，本轮按其源帧间隔衡量，未套用 30fps 的 33ms 容限。

AAC 解码再次验证该组合的音乐真值精确为 −1,000,000µs（相关 0.99996516），元数据配对覆盖率 99.6%，可读时间码与条码一致。失败同时出现在计划帧与连续捕获像素，足以否决当前协调实现；不能把 30fps 单组合的成功推广为 M0 全面通过。尚未把 24fps、负偏移或 AAC 启动/seek 相位逐项隔离为唯一根因。当前源码使用两套独立 renderer/audio clocks，缺少共同帧调度，下一阶段优先验证共同调度的替代样机。

这是一项 Debug 后端实验，未交付正式双视频产品入口、自动音乐匹配或导出。M1 自动音乐对齐尚未开始。

## 分支与构建

- 开发分支：`feat/dance-comparison-m0`；接手基线 `7567d1cdcf765b3c65a72c76d2502b8f3a115362`，已通过 HTTPS fetch 核对当时远程同 SHA。SSH 身份验证失败后仅替换传输方式，没有改 remote、reset 或合并 master。
- 素材/诊断工具修复：`d05dd668b617f2c3292c66c832d92c24eaec2b11`。
- 最终测试 APK 源码：`66db56aea8eb6409651a6282965d6e5132cd1e13`，回退不利的音频轨道实验，保留窗口诊断。
- 最终 APK：`mobile/app/build/app/outputs/flutter-apk/app-debug.apk`，240,487,588 bytes；SHA256 `11466b8c58ab7e015392ed931f1c95e211af39276e8debe344c140f9d474dae0`。本地与 `adb pull` 已安装 base.apk 哈希一致。以 `install -r` 安装，未卸载或清应用数据。
- 设备：OnePlus KB2000 / OnePlus 8T，Android 14 / API 34，SoC `SM8250`，物理分辨率 1080×2400；Media3 1.9.2。应用日志显示两路 `OMX.qcom.video.decoder.avc`，AAC 为 `c2.android.aac.decoder`。
- 仅运行比较相关测试、Debug APK 构建及 Debug/Release Kotlin 编译。未执行全量 Release QA、推送、PR、合并或其他工作区清理。

## Windows 素材生成故障与修复

本机 Gyan FFmpeg 9.0.1 shared full build 配置包含 Fontconfig / FreeType / HarfBuzz。320×180、3 帧的最小 `drawtext` 路径出现 `Fontconfig error: Cannot load default config file: File not found`，返回 `0xC0000005`；移除 `drawtext` 的同尺寸同帧率对照返回 0。证据隔离到字体滤镜路径，未将 Fontconfig 武断认定为 native 崩溃唯一根因。

`m0_pixels.py` 使用 Python 标准库内置 5×7 像素字形，逐帧绘制角色、源帧号、整数源 PTS、相对音乐时间及 20 位帧码。RGB 帧流式输入 FFmpeg H.264 编码，不查找系统字体、不修改系统配置、不新增依赖；单帧内存有界。两路临时编码/探测成功后才发布，manifest 作为最后的原子提交标记；失败保留上一套完整文件并清理本次临时目录。

默认生成目录最终仅含 teacher.mp4、learner.mp4、manifest.json。老师/自己：H.264 yuv420p、640×360、30/1fps，360 / 450 帧，视频与 AAC 均从 0 起始，时长 12 / 15 秒，AAC 16kHz 单声道。所有视频帧的 PTS 单调，间距 33,333–33,334µs；两文件完整解码无错误。

默认 teacher SHA256 `433c1329c541ac6bcea0d6d4b8390c199b79c62ee99a5923e3a122e2fc34e433`；learner SHA256 `bb22c5f102881b9858c76bb5a4d4e9a9991d69178267f2750ae26d090384e83b`。

AAC 解码后的 3、6、9 秒三个 250ms 窗口，在 ±10ms 搜索范围的峰值偏移均为 **+3,000,000µs**，归一化相关分别 0.99995577、0.99998677、0.99996560。此值仅用于验证合成真值，不是 M1 匹配置信度。另完成 1280×720/24fps/−1s 与 1920×1080/60fps/+3s 的真实编码及完整解码；120 秒音乐版本为 122 / 125 秒。

## 跨平台与编译证据

- Windows `uv run`、WSL Rocky Linux、macOS Python 3.12：19/19 工具测试通过。macOS 仅在独立 `/tmp/woah-m0-regression.*` 目录运行，未修改其他工作区。
- 三平台同一音乐 PCM SHA256：`c0785f3e2186c2b9cc022ea1f682ee4343ed5b03c4d6db07ed46e58374cb3dd9`。
- 三平台 640×360 teacher 第 31 帧 RGB SHA256：`f3dc44618769db4dac72c1fa099457711aeb12534bc92cd7993b203ace6811af`。
- `:dance_native:testDebugUnitTest --tests '*Comparison*'`：10/10；包含新增长时漂移窗口/导出上限检查。
- `:dance_native:compileDebugKotlin`、`:dance_native:compileReleaseKotlin` 均通过；Flutter Debug APK 构建通过。后续最终源码只回退音频轨道禁用并修改诊断字段，重新完成 Debug 构建；Probe/TimeMapping 测试代码及实现未变，复用有效结果。
- Linux/macOS 未运行真实 FFmpeg 编码；真实编码与解码覆盖来自 Windows，不能将 Python 合约测试表述为其他平台 MP4 端到端成功。

## 采集方法与边界

本机系统 `/system/bin/screenrecord` 连 `--help` 都返回 139；720×1600 与 1080×2400 录制同样失败。先采带请求开始/结束时间的屏幕截图，再使用[官方 scrcpy 5.0.1 便携包](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1)（下载 zip SHA256 `b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5`）。不转发或重定向音频，手机屏幕保持开启，电脑不播放画面，不需要额外手机授权。

屏幕分析直接读取实际合成像素中的源帧码，使用每个编码帧的 FFprobe 时间戳。不是 OCR，也不使用播放器位置文字推算画面。矩形为本设备竖屏 teacher `(0,307,1080,607)`、learner `(0,1083,1080,607)`；首张 screenshot 和若干录屏帧人工核对可读时间码与条码一致。分析保留 teacher 源时间 [1,11)s（短片）或 [1,121)s（长片）；排除起止端点。

计划帧 PTS / releaseTimeNs 与屏幕像素统计分开。计划帧报告新增配对覆盖率、观测时长及最多 120 个 5 秒窗口；覆盖率不足 80% 不给元数据达标结论。30fps 整数微秒量化按一帧 33,334µs 容限。屏幕采样可能受合成器相位和录制丢帧影响，**物理面板扫描仍未证明**；只有拒绝当前后端的结论，不虚报物理显示验收通过。

## 对照实验

| APK / 条件 | 速度 | 稳态屏幕样本 | 可见 P95 / 最大 |
|---|---:|---:|---:|
| 旧基线 `7567d1c` | 1× | 364 | 33.333 / 33.333ms |
| 旧基线 `7567d1c` | 0.75× | 494 | 33.333 / 33.333ms |
| 旧基线 `7567d1c` | 0.5× | 470 | **66.667 / 66.667ms** |
| 禁用自己音轨 `d05dd66` | 1× | 335 | **133.333 / 133.333ms** |
| 禁用自己音轨 `d05dd66` | 0.75× | 518 | 33.333 / 33.333ms |
| 禁用自己音轨 `d05dd66` | 0.5× | 490 | 33.333 / 33.333ms |

旧基线 0.5× 的计划帧元数据最大约 1 帧，而连续屏幕捕获显示 2 帧；独立截图实验 34 个 0.5× 稳态样本同样 P95=2 帧。禁用自己音轨后，1× 计划帧 P95=100ms，可见 P95=133.333ms，表明单独改音频时钟并不能解决共同调度。已回退此实验，没有用高频 seek 隐藏误差。

## 最终版本长时与交互结果

| 场景 | 有效屏幕样本 | 屏幕 P95 / 最大 ms | 计划 P95 / 最大 ms | 计划帧配对覆盖率 | 屏幕判定 |
|---|---:|---:|---:|---:|---|
| 30fps / +3s / 1× / 长片 | 4,036 | 33.333 / 33.333 | 33.334 / 33.334 | 99.7% | 本次在目标内 |
| 30fps / +3s / 0.75× / 长片 | 7,014 | 33.333 / 33.333 | 33.334 / 33.334 | 77.4% | 本次在目标内 |
| 30fps / +3s / 0.5× / 长片 | 7,330 | 33.333 / 33.333 | 0.000 / 0.000 | 49.2% | 本次在目标内 |
| 30fps / +3s / 1× / 短片 | 388 | 33.333 / 33.333 | 33.334 / 33.334 | 98.8% | 本次在目标内 |
| 30fps / +3s / 0.75× / 短片 | 508 | 33.333 / 33.333 | 33.334 / 33.334 | 99.7% | 本次在目标内 |
| 30fps / +3s / 0.5× / 短片 | 558 | 33.333 / 33.333 | 33.334 / 33.334 | 99.7% | 本次在目标内 |
| 30fps / 0.5× / seek + 偏移恢复后 | 226 | 33.333 / 33.333 | 0.000 / 0.000 | 70.8% | 本次在目标内 |
| 24fps / −1s / 720p / 1× | 384 | 83.333 / 83.333 | 83.334 / 83.334 | 99.6% | 未达标 |
| 60fps / +3s / 1080p / 1× | 595 | 16.667 / 16.667 | 0.000 / 0.000 | 100.0% | 本次在目标内 |

最终 APK 共分析 **21,039 个有效录屏样本**。三档长片分别录制 130/169/250 秒，观察 teacher 源时间 [1,121)s；该范围内屏幕误差始终不超过 1 个 30fps 帧，首尾窗口未见误差持续累积扩大。各阶段末 PSS 124,243 / 125,881 / 124,305KiB（约 121–123MiB）；所有有效报告 `sourceError=null`。这只描述本轮观测范围，不推断任意时长或所有素材。

0.75× / 0.5× 长片的计划配对覆盖率仅 77.4% / 49.2%，尤其 0.5× 的“计划误差 0”来自被选中的半数样本，不能当成全体帧零误差。80% 是本工具的保守完整性检查，不是经标定的置信度。屏幕 P95 是有效编码帧样本的百分位，原始时间戳和样本均保留，可重新分析采样权重。

一轮慢速交互采集在页面未就绪时点击，最终诊断实际为 1×、无有效稳态样本；该轮已明确排除。有效 v2 先核验 UI 显示 0.5×，再依次播放、seek 50%、回 0%、偏移 +100ms 再恢复；只分析最后一次操作后的 8 秒片段。此为采集输入修正，不是播放器故障修复。

16 个交互观测阶段完成，所有已定义点位、冻结及两侧推进断言通过：

- 暂停 seek 0% / 50% / 90% 分别显示帧 (0,90) / (180,270) / (324,414)；±10 秒为 (300,390) 后回到 (0,90)。
- +100ms 显示 (0,93)，恢复后 (0,90)；+10ms 显示邻帧 (0,91)，恢复后 (0,90)。这验证的是时间映射及视频量化，不代表已实现精确逐帧功能。
- 连续 seek 10%→50%→90% 最终仍为 (324,414)，未见旧请求覆盖最终目标。
- 暂停后 (56,147) 保持，Home→返回同一 Activity 后仍为 (56,147)；继续播放后两侧均推进，再播放中 seek 后恢复到近目标且相对误差不超过 1 帧。
- 通过 ADB 自动加载切换 24fps 负偏移和 1080p60 正偏移源，无源加载异常；未声称这验证了系统文件选择器的 URI 权限恢复。

本轮未覆盖真实用户舞蹈、VFR、混合 fps、外部高帧率拍屏、锁屏授权与旋转恢复；在已有有效失败场景下，没有将缺项或构建成功当作验收通过。手机最后恢复为默认 30fps 合成素材、偏移 +3s、已暂停的 M0 页面，无需用户操作。

## 下一阶段

1. **继续 M0，不进入 M1 产品集成。** 将 24fps/−1s 本轮失败固定为回归场景；补 24fps 正偏移与 30fps 负偏移的最小对照，进一步隔离启动/seek 的音频时钟因素。数学时间映射与合成真值已经分别验证，不修改动作时序来消除误差。
2. **做共同调度的 Debug 替代样机。** 两路 MediaCodec 解码保留源 PTS，使用唯一音频主时钟，经 `ComparisonTimeMapping` 为两源选择实际帧覆盖区间；两侧 seek flush/就绪共同屏障，单 GL 合成一次提交两幅画面。与本轮双 ExoPlayer 对照，禁止通过高频 seek 掩盖错位或卡顿。
3. 替代样机重跑本报告 30/24/60fps、正负偏移、三档速度、seek/偏移/恢复，并补 VFR/混合 fps 与真实舞蹈。只有该后端形成可靠设备通过结论，才推进 M1 PCM/STFT 音乐对齐；现有 `AudioTrackCopier` 仍只是压缩音轨复制器。

这些是下一阶段建议，本轮没有实现或宣称交付双 MediaCodec/GL、新产品入口、自动音乐匹配或导出。

## 可复现入口与本地证据

生成和加载：

```powershell
uv run python tools/comparison/generate_m0_fixtures.py
uv run python tools/comparison/run_m0_android.py --teacher testdata/comparison/generated/teacher.mp4 --learner testdata/comparison/generated/learner.mp4 --load-fixtures --offset-us 3000000
```

完整原始 JSON、录屏、时间码截图、构建日志、操作脚本、工具配置及 SHA 清单在本机 `docs/session/comparison-m0-20261025/`（仓库忽略目录，不上传用户媒体）。该目录内 `capture-final-long.py` / `run-controls.py` 为本次明确设备布局的操作记录，不是跨设备通用 UI 自动化。

- [机器可读验收数据](comparison-m0-device-2026-10-25.json)
- [24fps 负偏移原始诊断](session/comparison-m0-20261025/profile-24-negative.json) · [屏幕分析](session/comparison-m0-20261025/profile-24-negative-screen.json) · [连续录屏](session/comparison-m0-20261025/profile-24-negative.mp4) · [时间码见证帧](session/comparison-m0-20261025/profile-24-negative-witness.png)
- [最终 APK 安装/哈希预检](session/comparison-m0-20261025/final-apk-preflight.log) · [定向 Kotlin 日志](session/comparison-m0-20261025/gradle-comparison.log) · [最终 Debug 构建日志](session/comparison-m0-20261025/flutter-build-final.log)
- [1× 长片](session/comparison-m0-20261025/final-long-1x.mp4) · [0.75× 长片](session/comparison-m0-20261025/final-long-075x.mp4) · [0.5× 长片](session/comparison-m0-20261025/final-long-05x.mp4)
- [交互数据](session/comparison-m0-20261025/controls-results.json) · [慢速交互录屏](session/comparison-m0-20261025/final-slow-seek-v2.mp4)
- [全部证据 SHA256 索引](session/comparison-m0-20261025/evidence-index.json)
