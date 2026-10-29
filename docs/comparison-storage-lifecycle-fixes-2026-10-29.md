# 双视频对比：媒体存储生命周期修复（2026-10-29）

修复基线：`feat/dance-comparison-m0` / `8564e93`；修复提交：`0a25d05`。修复仅回迁到此功能分支，**不合并 `master`**、不改动主 crop 工作区。对应上次远端审计报告的两个 P2。下方已补齐该提交的 Android 定向测试与 OnePlus 真机回归。

## 修复 A：反复选择视频导致 iOS 私有媒体副本堆积

**之前的现象**：成功导入经 `PHPicker` 复制到 `Application Support/WoahComparisonMedia`。重新选择参考或目标后，旧文件不再被项目引用，但无法从应用中删除，反复选片占满设备空间。

**处理**：在 `IOSComparisonProjectStore` 建立保守回收门禁，读取所有项目文件里的两路源及标记源，并保护最新导出任务冻结的两路源；只清理位于自身命名空间内、UUID 文件名、非符号链接且无任何引用的文件。不可读或损坏的项目/导出记录会关闭本次回收，防止误删。新导入文件至少保留 24 小时；`IOSComparisonPicker` 导入成功时重置副本修改时间，避免原视频的旧时间戳绕过宽限期。回收在项目加载、播放器切换完成及关闭后触发，避免保存与旧播放器释放之间的竞态。

测试：`mobile/packages/dance_native/ios/tests/comparison_files/main.swift` 独立 Swift 通过（跨项目引用、片段标记、冻结导出引用、损坏项目、符号链接、新近副本、过期副本）；没有实际进行 iOS 真机满盘/连续导入压力测试。

## 修复 B：旧导出私有 MP4 不可再访问又无法回收

**之前的现象**：Android 10 以下未另存、Android 保存相册失败、iOS 未保存 Photos 的本地成片，在新任务替换 `latest` 后可能成为无入口的私有文件；iOS 多次导出还会累计成功成片。

**处理**：

1. `DanceComparisonApi.startExport` 新增显式 `replacePreviousUnsaved` 参数；Flutter 导出页检测上一次完成但未保存的本地成片，先提示保存或确认替换，取消确认不启动。即使用户绕过 UI 调用原生 API，Android/iOS 也会拒绝无确认的替换。
2. 新任务记录成功写入并启动后，淘汰明确替换的私有 MP4；相册/文件提供者保存的副本不会删除。若 Android 前台服务拒绝启动，恢复原任务状态并保留旧本地成片。
3. 为旧版已经失去索引的 UUID 私有 MP4 增加七天宽限期后的保守回收，保持当前任务文件、近期遗留文件及无关文件。Android 原先的 MediaStore 待发布条目清理逻辑保留。
4. 两端原生、Flutter Pigeon 生成代码与测试 driver 同步升级；Android 兼容最低 API 24，清理逻辑不依赖 Java NIO API 26。

测试：`mobile/app/test/features/comparison_export_test.dart` 新增取消确认、跨项目未保存导出、已保存直接新导出回归；`mobile/packages/dance_native/ios/tests/comparison_export_retention/main.swift` 验证原生确认门禁、历史文件回收、当前文件/近期文件/非托管文件不删除。`mobile/packages/dance_native/android/src/testDebug/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportManagerTest.kt` 新增三项状态及清理回归；原 Mac 回迁轮次未运行 Android/JVM，本次 Windows/Android 补验已全部通过。

## 原回迁验证回执（Mac）

- Flutter 3.44.2 / Dart 3.12.2：9 个相关测试文件共 **109 / 109 通过**；定向 `flutter analyze --no-pub` **通过**。
- Swift 独立：媒体/项目回收与导出保留门禁 **通过**。
- Xcode 27.1：`dance_native` CocoaPods 目标 iOS Simulator Debug **BUILD SUCCEEDED**（含新增 Swift 文件和新 Pigeon 签名）；Flutter `lib/main.dart` iOS Simulator Debug 整包使用单独 XDG Flutter 配置关闭 SPM、走 CocoaPods **构建通过**，产物 `mobile/app/build/ios/iphonesimulator/Runner.app`。
- 该机用户级 Flutter 设置开启了 SPM，默认整包构建因第三方 `file_picker_darwin` 模块在 SPM/PODS 混合集成下缺失而失败。隔离 CocoaPods 构建未更改用户全局 Flutter 设置；此为独立环境问题，不等于默认配置已修复。
- 该 Mac 轮次未新增 Android 覆盖；本次 Android 补验见下一节。iOS 模拟器交互、iOS 真机及长时音视频性能未在该轮新增验证；既有 M0 结果不计入本次回归证据。

原始日志：`/tmp/woah-comparison-retention-flutter-test.log`、`/tmp/woah-comparison-retention-flutter-analyze.log`、`/tmp/woah-comparison-retention-ios-media.log`、`/tmp/woah-comparison-retention-ios-export.log`、`/tmp/woah-retention-native-pod-build.log`、`/tmp/woah-retention-cocoapods-build.log`。

## Android 补充验证（2026-10-29）

环境：Windows，分支 `feat/dance-comparison-m0`，HEAD `0a25d05`；OnePlus KB2000 / Android 14。本轮未修改产品代码，复用现有 `ux_probe.py` 中的 `d`、`s` 辅助函数，通过正式 `art.gaoge.dance/.MainActivity` 操作，未进入旧 M0 页面。

- Kotlin：`ComparisonExportManagerTest` **6/6**、`ComparisonExportTest` **7/7**，共 **13/13** 通过。新增的 `unsavedCompletedExportIsNotSilentlyOverwritten`、`confirmedReplacementReclaimsPreviousPrivateMovieAndAgedOrphans`、`savedCompletedExportNeedsNoReplacementFlag` 三项均实际运行通过。
- Flutter：`comparison_export_test.dart` 与 `comparison_entry_route_regression_test.dart` 共 **25/25** 通过，覆盖确认/取消、跨项目未保存成片、保存动作及弹窗退出竞态。原 Mac 的 109 项结果单独保留，不与本次重复相加；未修改的静态检查结果复用。
- 正式 `lib/main.dart` Debug APK 构建、覆盖安装通过，本地与手机 APK SHA-256 一致：`198a69796d8b0827daf891c0566ba4b92e500d433f8dd927a4e58980dc887bad`（272,770,962 bytes）。

Kotlin 定向命令：在 `mobile/app/android` 执行 `gradlew.bat :dance_native:testDebugUnitTest --tests art.gaoge.dance.engine.comparison.ComparisonExportManagerTest --tests art.gaoge.dance.engine.comparison.ComparisonExportTest --console=plain`。

| 真机场景 | 实际操作与检查 | 结果 |
| --- | --- | --- |
| 反复更换参考/目标 | 通过系统文件选择器依次切换两组独立合成素材，共六次换片；每次完成自动音乐校准并恢复可播放状态 | 通过 |
| 历史仍可打开 | 换片后，从历史列表分别打开两条原项目和一条带标记的合成历史项目，实际播放、暂停；两路画面与共同时间推进可见，源引用和音乐映射保留 | 通过 |
| 未保存成片保护 | 完成真实硬件编码后，触发真实相册保存失败；任务保持 `completed`、`savedUri=null`，本地 MP4 保留，界面显示失败原因及“保存视频” | 通过 |
| 取消替换 | 分别点击“返回保存”、在确认层按系统返回、取消外层导出选项；旧任务记录和 MP4 哈希均不变 | 通过 |
| 确认替换 | 点击“替换旧成片”后创建新任务，旧私有 MP4 才被删除；新任务的未保存成片仍可恢复 | 通过 |
| 保存失败与重试 | 故障保留时再次保存仍失败，未留下待发布相册条目；重启、解除故障后点击“保存视频”成功，私有/相册文件哈希一致，相册仅一条 `is_pending=0` 记录 | 通过 |
| 已保存后再次导出 | 无替换确认，直接完成 A/B 导出；淘汰旧私有副本，保留旧相册副本 | 通过 |
| 运行中取消 | 导出启动后按返回键，任务变为 `cancelled`，恢复可播放预览；本次私有成片、partial、相册条目均无残留 | 通过 |
| 退出重启恢复 | 多次 force-stop 后从 MainActivity 冷启动：未保存任务及 MP4、已保存任务及 URI、已取消任务的重试入口均恢复；最后的历史对照项目恢复 3.223s、`a=.8 / b=+1.2s` 和标记 | 通过 |

保存故障只针对本轮 UUID 命名的合成导出文件：在 `.partial.mp4` 验证并改名为最终 MP4 后，设备端轮询将其读取权限临时设为 `000`，使正式 `publish()` 的本地文件读取失败。之后恢复为 `600`，从正式按钮重试保存。未填满手机存储、撤销用户相册权限或改写任务 JSON 来伪造成功/失败状态；失败新建的 MediaStore 条目已查询确认删除。

全段样例为 23s；A/B 请求范围为 `[1.234567s, 6.765431s)`，实测容器时长 5.530875s（与请求相差 11µs，为容器/音频时间刻度取整），两者均为 1080×1402 H.264/AAC。保留原测试项目的音乐速率、偏移、布局、镜像和缩放平移；没有重复完整音视频精度实验矩阵。

初次历史播放采集遇到 UiAutomator 无法等待持续刷新的页面空闲，旧 XML 不计作通过；改为播放中截图、暂停后读取新 XML，最终以 `20-`、`21-` 证据为准。初次 inotify 故障注入不受该设备支持，也不计作保存失败通过；有效故障证据为 `04-` 和 `08-`。

## 数据恢复、证据与范围

两条原项目在测试前备份，验证结束后连同所有原字段、位置和 `updatedAtMs` **逐字节恢复**，哈希与基线一致。两条临时项目、四个系统选片测试文件、两份私有源素材及三个本轮相册成片均已清理；原无导出记录的状态已恢复。没有导出服务或实际持有的导出唤醒锁，亮屏设置原值未变。最后停在正式应用首页。

原始测试、构建、截图/XML、任务快照、文件哈希及清理回执位于 Git 忽略目录 `docs/session/comparison-storage-lifecycle-20261029/`；摘要见 [机器可读回执](comparison-storage-lifecycle-fixes-2026-10-29.json)。关键证据为 `kotlin-results.json`、`flutter-export-tests.log`、`android-build.json`、`06-`/`07-`/`08-`/`10-`/`11-`/`13-`/`15-` 结果、`20-history-playback-result.json`、`21-history-restart-result.json`、`22-cleanup-result.json` 与 `22-runtime.log`。本轮相关日志未见 `E/flutter` 或 `FATAL EXCEPTION`。

本次 Android 验证不代表 iOS PHPicker 私有副本回收已获真机覆盖，也不覆盖 Android 24–28 的系统另存为、自然存储耗尽、iOS Photos 权限或长时连续导入压力。iOS 真机保持 `deferred_no_device`。未重复全量 M0 / Release QA；上述验收阶段未提交、推送或合并，记录发布由后续提交单独执行。
