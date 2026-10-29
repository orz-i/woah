# iOS 双视频对比接续记录（2026-10-26）

**状态：iOS 实现已接入，初次 iOS 17 模拟器 16 阶段集成验证通过；合并前审计的 4 项发现已修复并完成定向回归。** 最终成片通过独立像素、帧时序与音频验收；重装导致应用容器路径变化后，正式对比页成功恢复媒体、位置、音乐映射、画面设置、A/B 与标记。物理 iPhone/iPad、实际后台额度耗尽和权限弹窗仍未验证。用户当前没有 iOS 真机，已标记为待设备可用后补验，本次审计修复已收尾。未提交、推送或合并。

[合并前审计](comparison-premerge-audit-2026-10-26.md)发现的 1 项 P1 和 3 项 P2 已全部修复。修复阶段相关 Flutter 65 项、Android JVM 19 项、iOS 实际桥接播放边界 4 项均通过；正式模拟器及 unsigned iphoneos Debug 已重新构建，入口恢复为 `lib/main.dart`。以下原 16 阶段记录保留为初次接入的历史结果，最新证据和源清单见[审计修复记录](comparison-premerge-fixes-2026-10-26.md)。

后续[二次审计](comparison-premerge-reaudit-2026-10-26.md)新增的 2 项 P2 已于 2026-10-29 修复。相关 Flutter 87 项通过；Android 真机及 iOS 17 模拟器各 3 项实际播放器时序检查通过，使用受控 Dart 返回和内存项目库。最新产物和同步清单见[二审修复记录](comparison-reaudit-fixes-2026-10-29.md)。iOS 物理设备仍待补。

后续[第三次合并前审计](comparison-premerge-third-audit-2026-10-29.md)的入口重入和导出路由关闭两项 P2 已修复，见[三审修复记录](comparison-third-audit-fixes-2026-10-29.md)；相关 Flutter 98 项通过，iOS 正式模拟器及 unsigned iphoneos Debug 编译通过。本轮未重复 iOS 运行验证，真机仍待补。

## 工作区与同步边界

- Windows：`D:\dance-anonymizer`，分支 `feat/dance-comparison-m0`，HEAD `e286cf6`。
- Mac 对比工作区：`/Users/mini/Coding/woah-comparison-m0`，已核对相同分支和 HEAD。本轮没有提交、推送或合并。
- Mac 原工作区 `/Users/mini/Coding/woah` 保留 `feat/crop-clarity-phase2-quality-performance`，HEAD `506616b`；本轮没有切换或覆盖该工作区的 crop 改动。
- Mac Pi 会话为 `01a0eaa0-d976-760f-8bc5-543f91cb1a53`。原生工具的默认 cwd 仍可能显示原工作区，所有命令必须显式 `cd /Users/mini/Coding/woah-comparison-m0`。
- 使用 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`，不修改全局 `xcode-select`。

Git bundle 与工作树快照承担不同用途：`source.bundle` 保存可复现的 Git 历史/分支，`working-tree.tar.gz` 和逐文件 `working-tree-manifest.json` 保存当时未提交的实际代码；只导入 bundle 不能恢复本轮已有的 Android/Flutter 功能和新增 Swift 文件。初始工作树 manifest 有 99 项，bundle SHA-256 为 `9316710ce629ef591e50a17cad40956956d759838a7c3a447d1b9c3e4dad0325`。初次接入的最终快照以 [final-working-tree-manifest.json](session/comparison-ios-20261026/final-working-tree-manifest.json) 和 `final-working-tree.tar.gz` 为完整来源，逐文件校验记录保存在同目录 `sync-receipt.json`；初始 99 项不能当作最终集合。 审计修复阶段代码快照以 [final-source-manifest.json](session/comparison-premerge-fixes-20261026/final-source-manifest.json) 为准，旧 105 项清单保留为修复前快照。二审修复后最新清单见[二审修复记录](comparison-reaudit-fixes-2026-10-29.md)。

Mac Pi 本地到远端同步必须用 `files` 作为 Windows 来源、`paths` 作为 Mac 目标，并对已知文件显式 `overwrite:true`。不要用 `from + paths` 拼 Windows 目标，也不要把 Windows 路径放进会话来源 `paths/to`，否则会被解释成 Mac 相对路径。验证器及其新增边界回归已按此方式同步，两端 SHA-256 一致。

同步探针和初始快照保存在 Git 忽略目录 `docs/session/comparison-ios-20261026/`；这些日志、源 URI、视频和项目记录只供本地接续。

## 架构

Flutter 复用现有对比页、工程模型、手动校准、画面设置和导出面板，通过生成的 `ComparisonApi.g.swift` 连接 `IOSComparisonNativeApi`。入口开放给 iOS/Android，桌面隐藏。原有 Android 和隐私/Yolo 工作流没有被本轮 iOS 对比实现替换。

### 共享时间轴与画面

`IOSComparisonComposition` 生成一个从零开始的 `AVMutableComposition` 和共享 `AVMutableVideoComposition`。参考视频保持真实源节奏；目标视频按音乐映射 `target = timeScale × reference + offsetUs` 插入并缩放到参考时间轴。参考音频保持原速，目标音频不加入。`sourceTrackIDForFrameTiming` 指向参考 composition track，保留 VFR 源帧节奏。

`IOSComparisonPlayer` 使用同一个 `AVPlayerItem`/参考时钟和合成输出到 Flutter texture。练习速度只改变播放速度，循环、精确 seek 与逐帧依赖真实展示 PTS。`IOSComparisonFrameIndex` 负责真实源帧的前驱和邻接查询，不能把名义 fps 当实际帧时间。

模拟器诊断曾发现压缩 sample 的原始媒体 PTS 含约 0.1s 编辑列表偏移。已修复为 output sample timing；591 个可见帧索引与独立解码一致，真实模拟器逐帧、循环和恢复均通过最终 16 阶段报告。

`IOSComparisonCompositor` 通过 Core Image 将两路画面提交到同一个画布，复用左右/上下排列、黑边、分隔条及两路独立镜像、zoom/pan。轨道 `preferredTransform` 的左上原点与 Core Image 左下原点做坐标共轭，已与系统 `AVAssetImageGenerator` 对照方向；render context 的变换也单独处理。播放器和离线导出使用相同的合成设置。

### 选择、持久化与标记

`IOSComparisonPicker` 使用只暴露所选视频的 `PHPickerViewController`。`NSItemProvider` 临时文件在回调返回前同步复制到应用拥有的 `Application Support/WoahComparisonMedia` UUID 文件，不长期依赖提供方临时 URL，也不申请广泛相册读取权限。取消和过期选择请求不会覆盖新的选择。

`IOSComparisonFiles` 为拥有的媒体保存 `woah-comparison-media://UUID.ext` 稳定身份；恢复时解析到当前容器。只对明确拥有的媒体命名空间处理容器重定位，拒绝远程 scheme、伪造身份和越界符号链接，不凭同名文件迁移外部媒体。普通有效本地文件 URI 仍可作为输入。

`IOSComparisonProjectStore` 按工程原子保存 JSON，并连同 `markers[].sourceUri` 迁移源身份。损坏工程不会隐藏其他工程；不可读记录交给 Dart 现有不可读计数/重新关联流程。原工程 schema 和角色语义保持兼容。

### 仿射音乐对齐

音乐匹配通过 `AVAssetReader` 解码为单声道 16kHz 特征，复用本地 Swift 的特征、候选、速率和残差判断。映射支持偏移与连续 `timeScale`，保留真实源 PTS 起点；不使用人物动作抹平拖拍。无音轨、不一致或不支持的输入返回明确状态，可继续手动校准。

匹配在后台任务执行；取消、连续匹配和新旧请求竞争使用取消标记与 revision，过期结果不能覆盖新会话。手动双播放器后端继续走现有 `video_player`。

### 独立导出与相册

`IOSComparisonExportCoordinator` 冻结源、映射、全段/A-B 范围和画面设置，导出固定 1× H.264/AAC MP4，不继承练习慢放、循环重复和后续工程修改。`AVAssetReaderVideoCompositionOutput` 读取共享合成，`AVAssetWriter` 按参考真实 PTS 写帧，并显式确定最终帧时长和 session 片尾。

音频固定为 48kHz 双声道 AAC，来自参考源；参考无音轨、延迟开始或短于视频的部分补静音。进度、取消和冻结设置重试独立于隐私推理。`Application Support/WoahComparisonExports` 中的 metadata 原子写入，只有完整并重新检查过轨道/时长的 partial 文件才能同目录改名发布。中断记录可重试，未完成文件不会作为成片暴露。

后台仅使用有限的 `beginBackgroundTask`；到期设置取消和 `interrupted`，保留冻结任务供回前台重试。Native API 跨 Flutter engine 重建共享导出协调器，关闭播放器不会销毁活动导出。

保存相册只申请 Photos `.addOnly`。拒绝或保存失败保留本地成片，可授权后再次保存。成功 asset identifier 用于重复保存去重；真正 Photos 事务开始前持久化 fence，授权弹窗前退出仍可安全重试。仅 add-only 权限无法查询进程退出时未决事务的最终状态，此时保留防重复 fence 并报告保存结果待确认。查看视频直接通过本地文件的 `AVPlayerViewController`，不依赖相册读取或第三方协议。

## 已有验证证据

本轮检查只覆盖受影响功能，复用输入未变的有效结果，没有重跑全量 M0 或发布验收。

| 检查 | 有效结果与边界 | 本地证据 |
| --- | --- | --- |
| Windows Flutter | 原三文件组 45 项通过；新增 same-revision open 旧成功/失败两种 race 后，仅重跑 `comparison_test.dart`，26 项通过（含 24 旧 + 2 新），其余 21 项有效结果复用；共 47 个唯一用例 | [结果汇总](comparison-ios-2026-10-26.json) |
| Flutter 静态检查 | 对比功能、首页入口、导出/首页测试无问题；controller、comparison test、最终 smoke driver 定向 analyze 无问题 | [结果汇总](comparison-ios-2026-10-26.json) |
| Swift 音乐 core / PCM | 偏移、连续速率、变调/不变调、源起点、静音/歧义、PCM 16/44.1/48k 单/双声道、edit gap、取消均实际通过 | [core](session/comparison-ios-20261026/mac-music-core.log)、[PCM](session/comparison-ios-20261026/mac-music-pcm.log) |
| 共享 composition / Files | 时间/几何、VFR、rate、trim、镜像、旋转、pane clipping、源索引及容器路径/marker/工程原子性独立 gate 通过；Files/Picker/Store 使用真实 Pigeon/Flutter 做 iPhoneOS SDK typecheck 通过 | [原生 gate](session/comparison-ios-20261026/mac-composition.log) |
| 导出 core / AVFoundation | 冻结设置、非法输入、恢复、取消提交互斥及 Photos fence 通过；方向修复后重跑 AV gate，H.264/AAC、VFR、精确片尾、A-B、参考音频、静音和取消清理通过 | [导出 gate](session/comparison-ios-20261026/mac-export.log) |
| iPhoneOS / Simulator 构建 | 最终源码已恢复正式 `lib/main.dart`，产出无签名 `iphoneos/Runner.app` 与 `iphonesimulator/Runner.app`；不等于物理 iPhone 运行通过 | [device build](session/comparison-ios-20261026/mac-device-production-build.log)、[simulator build](session/comparison-ios-20261026/mac-simulator-production-build.log) |
| iOS 17 完整桥接流程 | 16 阶段全部通过：取消选片、自动音乐速率、并发取消、Texture 首帧、慢放、真实 PTS 逐帧、布局、独立画面/A-B 实际回跳、校准映射、项目/双锚点/标记恢复、双路手动视频后端、关闭重开、导出后重开、Photos 去重/本地播放 | [最终报告](session/comparison-ios-20261026/smoke-final-report.json) |
| B 帧与编辑列表索引 | 591 个压缩源索引与独立 BGRA 解码呈现时间逐项相同；首帧 0，280ms 前帧 266667µs，下一帧 333333µs；CMTime 保留至映射后换算微秒 | [帧索引/边界](session/comparison-ios-20261026/frame-index-and-cmtime.log) |
| 跨安装恢复 | 应用容器 UUID 确实改变；项目原 JSON 与两个媒体哈希未变，源和标记保持稳定身份；正式页显示两路纹理、位置 1.250s、音乐/镜像/画面/A-B 与一个标记 | [恢复结果](session/comparison-ios-20261026/restore-result.json)、[实际截图](session/comparison-ios-20261026/comparison-restored.png) |
| iOS 最终成片独立验收 | 320×240 / 1s / 26 帧，PTS、视频/音频/container 片尾误差均 0；AAC lag=0，相关系数 0.9966785。coded-fixtures 所有检查通过；成片 SHA256 `2b1d8f566a514f6c4ee190d0d9a6e007a865a0b8382b57e1cd9e32691d603468` | [成片结果](session/comparison-ios-20261026/smoke-final-export-validation.json)、[冻结设置](session/comparison-ios-20261026/smoke-final-export-record.json) |
| 验证器精确边界与旧产物兼容 | 新增 3 项边界回归通过；复验旧 Android `integrity-ab.mp4` 通过，复用旧产物验证工具兼容，非重新 M0 验收 | [调查/命令](session/comparison-ios-20261026/export-rational-validation.log)、[Android 兼容结果](session/comparison-ios-20261026/android-integrity-ab-rational-validation.json) |

实际成片首次 coded 检查误报目标帧 79：参考真实 PTS `11/6s` 经 `.8 × reference + 1.2s` 映射恰为目标帧 80 的 `8/3s`；先取整为微秒会落到前一帧。验证器现用整数源 PTS × `time_base` 的 `Fraction` 做对应关系，输出误差仍按原 20µs/100µs 边界检查，像素、音频与相关系数阈值未放宽。zoom/mirror/pan 裁掉条码低三位，不能用不可见位推断成完整帧码 87。独立检查的 26 帧所有可见目标位均正确，详见[逐帧记录](session/comparison-ios-20261026/code-check-review.txt)。生产导出与合成未因该误报修改。

## 模拟器环境与未验证范围

本机 Xcode 27.1 SDK 可用，但系统 CoreSimulator 1171.7 与 Xcode `simctl` shell wrapper 期望的 1174.9.2 不一致，wrapper 的帮助/list 调用会触发 first-launch 路径并超时。未执行全局修复、切换全局开发目录或安装 runtime。改用系统原生 `simctl`，在 `/tmp/woah-comparison-sims` 独立 device set 创建并启动 `WoahComparisonValidation` / iPhone 15 / iOS 17.0.1；没有修改既有模拟器。设备 UUID、原生二进制和状态见[环境记录](session/comparison-ios-20261026/mac-simulator-environment.json)。本机没有 `Simulator.app`，原生命令和截图仍可操作该设备。

物理 iPhone/iPad、性能与热状态未测；实际后台额度耗尽后的系统中断、PHPicker 手动选择/导入、权限弹窗交互及用户拒绝路径没有做设备验收。模拟器已验证 PHPicker 展示/取消及 Photos 正向保存、去重、本地播放，不能替代这些缺口。Swift 5 / iOS 17 API 保持不变；新 SDK 的弃用和 Swift 6 sendability 提示不属于本轮语言迁移。Flutter 还会提示 `dance_native` 暂未采用 Swift Package Manager，当前使用现有 CocoaPods 集成。

## 接续命令

Mac 所有命令先选对工作区和开发目录；以下均为接续示例，不表示新增执行结果。

```bash
cd /Users/mini/Coding/woah-comparison-m0
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
FLUTTER=/Users/mini/Coding/flutter-3.44.2/bin/flutter
SRC=mobile/packages/dance_native/ios/dance_native/Sources/dance_native

# 参数与恢复 gate：源未变时复用有效结果。
swiftc -O "$SRC/IOSComparisonExportSpec.swift" \
  mobile/packages/dance_native/ios/tests/comparison_export/main.swift \
  -o /tmp/woah-comparison-export-tests
/tmp/woah-comparison-export-tests

# 共享 composition 或编码实现变化时才重跑此受影响 gate。
swiftc -parse-as-library -D COMPARISON_EXPORT_STANDALONE \
  "$SRC/IOSComparisonModels.swift" "$SRC/IOSComparisonCompositor.swift" \
  "$SRC/IOSComparisonComposition.swift" "$SRC/IOSComparisonExportSpec.swift" \
  "$SRC/IOSComparisonExportPipeline.swift" \
  mobile/packages/dance_native/ios/tests/comparison_export_av/Fixtures.swift \
  mobile/packages/dance_native/ios/tests/comparison_export_av/main.swift \
  -o /tmp/woah-comparison-export-av
/tmp/woah-comparison-export-av

# 专项真实桥接入口；仅装入独立验证设备，先按文件头准备该应用自己的fixtures。
cd mobile/app
"$FLUTTER" build ios --debug --simulator --target=test_driver/comparison_ios_smoke.dart

# 最终交付必须恢复正式入口，避免保留专项 smoke target。
"$FLUTTER" build ios --debug --simulator --target=lib/main.dart
"$FLUTTER" build ios --debug --no-codesign --target=lib/main.dart
```

独立模拟器使用原生二进制与既有 device set，不通过不匹配的 Xcode wrapper：

```bash
SIMCTL=/Library/Developer/PrivateFrameworks/CoreSimulator.framework/Versions/A/Resources/bin/simctl
"$SIMCTL" --set /tmp/woah-comparison-sims list devices
"$SIMCTL" --set /tmp/woah-comparison-sims get_app_container \
  3B455A71-1E7F-4143-AA8E-ABF62A5E0BF0 art.gaoge.dance data
```

Windows 已执行的 Flutter 用例组和受影响工具回归：

```powershell
cd D:\dance-anonymizer\mobile\app
flutter test test/features/comparison_test.dart test/features/comparison_export_test.dart test/features/home_workbench_test.dart --reporter expanded
# 新增2项race后只重跑comparison_test.dart；其余21项结果复用。
flutter test test/features/comparison_test.dart --reporter expanded

cd D:\dance-anonymizer
.venv/Scripts/python.exe -m unittest discover -s tools/comparison -p test_verify_comparison_export.py -v
.venv/Scripts/python.exe tools/comparison/verify_comparison_export.py `
  --output docs/session/comparison-ios-20261026/smoke2-export.mp4 `
  --spec docs/session/comparison-ios-20261026/smoke2-export-record.json `
  --reference docs/session/comparison-music-rate-20261026/fixtures/teacher.mp4 `
  --target docs/session/comparison-music-rate-20261026/fixtures/learner.mp4 `
  --report docs/session/comparison-ios-20261026/smoke2-export-rational-validation.json `
  --coded-fixtures
```

重新运行 smoke 之后，应读取该轮真实报告、导出记录及成片，再判断受影响检查；不要用旧的局部通过项覆盖当前失败。历史 `smoke2`/`smoke4` 的失败已被上述修复与最终报告取代，不计为通过证据。

## 本轮修复的实际问题

- AVPlayer 每次 seek 前重建 `AVPlayerItemVideoOutput` 会触发预滚并把时钟推进到约 8.917s，吞掉精确 seek。现在同一 item 复用输出；同点重复 seek、暂停、循环和导出后重开均通过。
- 压缩读取器的原始 media PTS 含 0.1s 编辑列表偏移；使用原 PTS 会遗漏 0 帧并产生不存在的 300ms 步进点。现在读取 output sample timing、排除不可显示样本，保留 B 帧的真实呈现顺序。
- CMTime 在音乐映射前过早量化到微秒，会在精确帧边界误报目标帧。预览映射保留 CMTime 到最后换算；成片验证器保留整数 PTS 与有理数 time base。
- Flutter 同 revision 的旧 open 成功/失败不能覆盖新 open；音乐匹配的取消 revision 防止等待中的请求在 close 后重新启动。
- Native API 在 close/导出后按需创建新 Player；Flutter engine detach 释放纹理、选片和匹配，独立导出任务继续由共享协调器管理。临时 seek 日志已移除。
