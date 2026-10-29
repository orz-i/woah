# 双视频对比：媒体存储生命周期修复（2026-10-29）

修复基线：`feat/dance-comparison-m0` / `8564e93`。修复仅回迁到此功能分支，**不合并 `master`**、不改动主 crop 工作区。对应上次远端审计报告的两个 P2。

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

测试：`mobile/app/test/features/comparison_export_test.dart` 新增取消确认、跨项目未保存导出、已保存直接新导出回归；`mobile/packages/dance_native/ios/tests/comparison_export_retention/main.swift` 验证原生确认门禁、历史文件回收、当前文件/近期文件/非托管文件不删除。`mobile/packages/dance_native/android/src/testDebug/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportManagerTest.kt` 新增三项状态及清理回归，**本轮未运行 Android/JVM 测试（Mac 无 Android SDK）**。

## 验证回执

- Flutter 3.44.2 / Dart 3.12.2：9 个相关测试文件共 **109 / 109 通过**；定向 `flutter analyze --no-pub` **通过**。
- Swift 独立：媒体/项目回收与导出保留门禁 **通过**。
- Xcode 27.1：`dance_native` CocoaPods 目标 iOS Simulator Debug **BUILD SUCCEEDED**（含新增 Swift 文件和新 Pigeon 签名）；Flutter `lib/main.dart` iOS Simulator Debug 整包使用单独 XDG Flutter 配置关闭 SPM、走 CocoaPods **构建通过**，产物 `mobile/app/build/ios/iphonesimulator/Runner.app`。
- 该机用户级 Flutter 设置开启了 SPM，默认整包构建因第三方 `file_picker_darwin` 模块在 SPM/PODS 混合集成下缺失而失败。隔离 CocoaPods 构建未更改用户全局 Flutter 设置；此为独立环境问题，不等于默认配置已修复。
- 本轮没有 Android APK、Android JVM/instrumentation、Android 真机、iOS 模拟器交互、iOS 真机、长时音视频性能的新增通过结果；沿用既有 M0 功能验收不作为本轮回归证据。

原始日志：`/tmp/woah-comparison-retention-flutter-test.log`、`/tmp/woah-comparison-retention-flutter-analyze.log`、`/tmp/woah-comparison-retention-ios-media.log`、`/tmp/woah-comparison-retention-ios-export.log`、`/tmp/woah-retention-native-pod-build.log`、`/tmp/woah-retention-cocoapods-build.log`。后续先在 Windows/Android 真机重跑 Kotlin 三项新增测试和最小导出/保存矩阵，再考虑合并。
