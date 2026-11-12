# 舞蹈对比 Rust 共享核心回归

Android 真机与 iOS 模拟器使用同一份冻结素材，各通过 39 项对比流程、17 个实际成片的独立审计，以及 24 张实际预览纹理的像素核对。两端另各完成 16 项隐私回归及人工画面复核，在本批素材和环境中没有发现新的未解决问题。

这是运行证据报告；合并及必要 CI 的最终状态见 [PR #38 Checks](https://github.com/orz-i/woah/pull/38/checks)。分阶段提交为 [时间与布局 #35](https://github.com/orz-i/woah/pull/35)、[音频分析与匹配 #36](https://github.com/orz-i/woah/pull/36)、[步进及生命周期 #37](https://github.com/orz-i/woah/pull/37)、[完整回归与边界修复 #38](https://github.com/orz-i/woah/pull/38)。

## 共享边界

中性的 MIT Rust 库 `frame-kernel` 负责源时间映射、公共区间、锚点、真实 PTS 查找与步进、双窗布局与缩放参数、PCM 混音/重采样、音乐特征、恒速及变速匹配、候选排序和取消/发布状态决策。Kotlin、Swift 和 Dart 通过同一 C ABI/JNI 调用这些实现。

系统解码、播放时钟、GPU/纹理、编码器、文件和相册仍由原生适配器负责。共享核心对相同 PCM 的匹配结果在 Windows/Mac 一致；原生解码得到的 PCM 和编码后的影片不要求逐字节相同。完整公共区间的匹配时间关系最大误差：Android 10.450 ms、iOS 13.957 ms，均低于固定 35 ms 验收界限。

## 同一素材与实际覆盖

完整清单 SHA-256：`b5961783b2ffcae9a74e9fd250191aff51c7a82de2eecd3b4a6e0417724566e6`。15 个视频、损坏文件和清单在两端逐一核对 SHA-256。安全元数据见 [core-matrix-v1.json](../../testdata/comparison/core-matrix-v1.json)，原视频保留在主机。

39 项包括：选择器关闭、9 项音乐匹配/取消、8 组同步播放、项目/双锚点/互换/标记恢复、独立双播放器、16 项全长/裁剪/连续导出、4K 取消后立即重试、损坏源拒绝和相册成片打开。覆盖正负偏移、保调与变调变速、真实音乐、无音轨、短片、24/30/60fps、VFR、非整帧裁剪、尾帧、镜像、缩放、平移、横纵布局、慢放和循环。

| 环境 | 流程 | 成片 | 全片解码帧 | 实际预览纹理 |
| --- | ---: | ---: | ---: | ---: |
| OnePlus 8T KB2000 / Android 14 | 39 | 17 | 4,426 | 24 |
| iPhone 15 Pro / iOS 17 模拟器 | 39 | 17 | 4,426 | 24 |

成片独立检查全部帧时间戳、首尾时长、H.264/AAC 轨道、参考音乐同步、二进制帧码和真实画面双窗的像素对应关系。最大视频 PTS 误差为 Android 1 µs、iOS 0 µs；音轨/容器尾部的采样量化误差不超过 100 µs。预览直接从 Flutter `Texture` 抓图，与独立解码的源帧比对，未使用 DTO 帧标签代替画面验证。另按每次保存返回的 MediaStore URI / PHAsset 标识，逐一读取本轮 34 个相册原件，验证原件与对应成片的字节哈希一致。

4K 输入实际编码为横屏 3840×2160、竖屏 2160×4096，包含完整时长、尾段、连续导出和取消重试。它们是同批既有公开视频升采样编码的派生素材，不代表原生相机 4K 细节。舞蹈对比沿用最长边 1920 的导出契约，本次没有增加对比 4K 输出能力。

## 发现与修复

- iOS 非整帧变速尾段先将两个目标端点取整到微秒，再缩放整段，导致精确帧边界取前一帧。Rust 现在生成保留精度的连续编辑区间；对应尾段逐帧码验证通过。
- iOS 17 在非整帧 VFR 裁剪、24fps 全长及 4K 连续导出时，合成轨道读取在尾部报 `-12815`；原始轨道均可完整解码。导出改用原始轨道读取，由 Rust 按实际 PTS 映射对应帧，复用预览的原生渲染器。三类失败均已通过实际导出和独立审计。
- Android 音乐取消此前抛桥接异常，iOS 返回 `cancelled`。Android 适配器现返回相同取消状态，并在发布候选前再次验证本次请求仍有效。

保留首轮失败记录，最终通过的成片为修复后新生成。新增实际预览抓图后，只重跑受影响的 8 组播放检查；保留并复核已有成片哈希，沿用未变化影片的审计。iOS 的首批 16 个成片在最后一次重试导出期间先行审计，最终确认记录、影片哈希均未变化后，合并最后一个影片的独立审计。

## 隐私打码未新增问题

沿用此前 [每端 62 项完整验收](../rust-core-migration-2026-11-06/final-acceptance-v9/README.md)，本轮补跑每端 13 项重点场景及 3 项既有马赛克/模糊/渐变场景。没有把旧结果记作新一轮 62 项。

| 平台 | 本轮用例 | 影片 / 取消 | 解码帧 | 原生预览对 | 人工复核板 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Android | 16 | 15 / 1 | 408 | 44 | 16 |
| iOS | 16 | 15 / 1 | 408 | 44 | 16 |

全片媒体、独立源人脸核心、已知隐私探针、预览时间和相册原件检查通过；重点检查多人重叠、举臂、身体/人脸并集、尾帧、自定义贴纸、取消和立即重试。已有的保守多遮和重叠贴纸限制仍存在。本次未改动隐私检测、跟踪或遮挡策略。

## 构建、证据与复现

Rust 110 项、Android JVM 623 项、Flutter 应用 277 项、domain 28 项、Python 对比工具 32 项通过。Windows/Mac 时间 ABI 指纹一致；Swift 主机检查加入实际 AVFoundation 编码、VFR、音频及取消清理门禁。CI 中一次 iOS Phase 6 烟测出现原生 `NATIVE_OPERATION_FAILED`；同一源码、同一断言复跑后 iOS、Linux、governance 三项均通过，失败日志保留。最终提交的门禁见 PR Checks。

数值证据：[汇总](summary.json)、[Android 对比](android-comparison.json)、[iOS 对比](ios-comparison.json)、[Android 实际预览](android-preview-pixels.json)、[iOS 实际预览](ios-preview-pixels.json)、[Android 相册](android-gallery.json)、[iOS 相册](ios-gallery.json)、[Android 隐私](android-privacy.json)、[iOS 隐私](ios-privacy.json)。原媒体、观测、人脸裁片、预览图和人工复核板留在各自主机。

构建 `mobile/app/test_driver/comparison_regression.dart`，将完整冻结清单与媒体放入应用 `Documents/comparison-core-corpus`。同一驱动通过真实原生接口运行，输出 `comparison-core-report.json`、成片与预览图。使用 `tools/comparison/verify_core_run.py` 和 `verify_core_previews.py` 独立校验；`COMPARISON_QA_FILTER=playback/` 只作明确标记的播放子集验证。隐私测试沿用 `tools/privacy/README.md` 的隔离构建与审计流程。

两端已恢复生产入口测试包；原对比项目和影片恢复并核对哈希。iOS 启动时会重新序列化最新导出记录，因此 JSON 字节序不作启动后不变的承诺。模拟器结果不证明 iPhone 硬件性能。
