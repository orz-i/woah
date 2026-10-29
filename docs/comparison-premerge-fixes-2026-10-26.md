# 双视频对比审计修复（2026-10-26）

**初审的 1 项 P1、3 项 P2 均已修复，并通过对应回归。** Android Debug APK 已覆盖安装到 OnePlus KB2000；iOS 正式模拟器和 unsigned iphoneos Debug 构建通过。iOS 真机验证继续标记为 `deferred_no_device`。未提交、推送或合并，未重复全量 M0 / Release QA。

后续[二次审计](comparison-premerge-reaudit-2026-10-26.md)新增的 2 项 P2 已于 2026-10-29 修复，见[二审修复记录](comparison-reaudit-fixes-2026-10-29.md)。下述 4 项原修复及其验证记录保持有效。

## 修复

| 初审项 | 变更与结果 | 回归 |
| --- | --- | --- |
| P1 Android 导出中断立即重试 | worker 在同一锁内取得所属任务与取消令牌；进度、退出清理、通知、相册发布按任务 ID 隔离，旧任务不会改写重试任务。服务异常兜底及销毁/超时中断也绑定所属任务。 | 管理器 3 项；OnePlus 实际编码中断后立即重试、相册保存及保存去重通过。 |
| P2 片尾 A/B 播放归零 | Android/iOS 在启用循环时先完整处理选段规则；整段片尾归零分支只用于未启用循环的状态。 | Android 真实 Session 命令分支 4 项；iOS 真实 Pigeon/AVFoundation 4 项。 |
| P2 最终锚点定位失败仍保存 | 独立记录两路 seek 是否成功；最终采集任一路失败均拒绝锚点。失败时保留编辑页和原校准，首锚点错误可继续重试。 | Session、正式页面 Widget 及首锚点重试覆盖；确认失败后没有新增保存。 |
| P2 旧 resize 回写新项目 | 返回结果及错误前校验项目 revision、播放器打开身份和最新 viewport 请求身份，并检查关闭/校准/导出状态。 | 旧成功结果、旧取消错误、新旧尺寸请求乱序均通过。 |

生产实现位于 [导出管理器](../mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportManager.kt)、[导出服务](../mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportService.kt)、[Android 播放会话](../mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/comparison/ComparisonSession.kt)、[iOS 播放器](../mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSComparisonPlayer.swift)、[手动校准会话](../mobile/app/lib/features/comparison/presentation/comparison_manual_session.dart)和[页面控制器](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart)。音乐速率、偏移、动作时序、项目 schema 和导出编码管线未修改。

新增回归已放入常规测试目录：[Flutter 生命周期](../mobile/app/test/features/comparison_lifecycle_regression_test.dart)、[Android 导出管理器](../mobile/packages/dance_native/android/src/testDebug/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportManagerTest.kt)、[Android 播放命令](../mobile/packages/dance_native/android/src/testDebug/kotlin/art/gaoge/dance/engine/comparison/ComparisonSessionPlaybackTest.kt)。独立 iOS [播放边界入口](../mobile/app/test_driver/comparison_playback_regression.dart)只使用合成素材，不读写用户项目；Android 沿用 Debug instrumentation，新增 `mode=manager-interrupted`，未增加 Release 入口。

## 必要验证

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| Flutter 相关 5 个测试文件 | 65/65 通过，包含 6 项新增失败路径与生命周期回归。 | [最终日志](session/comparison-premerge-fixes-20261026/flutter-final-regression.log) |
| Flutter 定向静态检查 | 对比功能、新回归测试及 iOS 边界入口无问题。 | [analyze](session/comparison-premerge-fixes-20261026/flutter-analyze.log) |
| Android JVM | 19/19 通过：管理器 3、Session 播放 4、既有导出 7、练习/真实帧导航 5。平台/渲染边界使用 mock，没有把 JVM 结果称为真机画面验收。 | [JUnit 汇总](session/comparison-premerge-fixes-20261026/android-junit-summary.json) |
| Android Debug 构建与安装 | 成功；本地 APK 与手机已安装 APK SHA-256 相同。 | [构建](session/comparison-premerge-fixes-20261026/android-device-build.log)、[真机回执](session/comparison-premerge-fixes-20261026/android-run-receipt.json) |
| OnePlus Android 14 导出 | 正式前台服务编码中断后立即重试完成，保留 `target = 0.8 × reference + 1.2s` 音乐映射、上下布局、参考镜像及两路缩放平移。23 秒 / 1080×1800 / H.264 + AAC；相册副本哈希相同，重复保存同一 URI。 | [管理器报告](session/comparison-premerge-fixes-20261026/android-manager-report.json)、[实际文件编码](session/comparison-premerge-fixes-20261026/android-movie-metadata.json) |
| iOS 17 原生播放回归 | 4/4 通过：最后 100ms 的 A/B 内播放停留 `22.9s`；较长片尾选段从当前位置恢复；选段外回 `20s` 的 A 点；关闭循环后从 0 重播。仍使用 `target = 0.8 × reference + 1.2s` 映射。 | [原生报告](session/comparison-premerge-fixes-20261026/ios-playback-regression.json) |
| iOS 正式 Debug 构建 | `lib/main.dart` 的模拟器、unsigned iphoneos 均通过；最终安装正式应用，生成入口恢复，测试模拟器关闭。 | [iOS 回执与产物哈希](session/comparison-premerge-fixes-20261026/ios-run-state.json) |

OnePlus 两条原项目记录及导出目录/状态已按文件哈希核对恢复，合成测试相册行和临时素材已清理，对比导出服务未残留。iOS 独立模拟器中的原项目哈希保持不变。未操作或覆盖 Mac 原 crop 工作区；对比代码和记录同步在 `/Users/mini/Coding/woah-comparison-m0`。

## 接续边界

分支仍为 `feat/dance-comparison-m0`，HEAD 仍为 `e286cf6`，包括修复在内的后续实现仍为未提交工作树。修复阶段同步以 [修复后源清单](session/comparison-premerge-fixes-20261026/final-source-manifest.json)及 [Mac 校验回执](session/comparison-premerge-fixes-20261026/mac-source-receipt.json)为准；先前 iOS 接续和初审清单保留为历史快照。二审后的最新文档/源清单见[二审记录](comparison-premerge-reaudit-2026-10-26.md)，生产代码未再变化。

原[合并前审计](comparison-premerge-audit-2026-10-26.md)的 4 项发现已关闭。iPhone/iPad 实际选片和权限、后台额度耗尽/锁屏/进程中断、性能与发热仍待设备可用后补验，未报告为通过。
