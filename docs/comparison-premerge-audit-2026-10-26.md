# 双视频对比合并前审计（2026-10-26）

**修复结论：初审发现的 1 项 P1、3 项 P2 均已修复并通过定向回归。** 见[修复与验证记录](comparison-premerge-fixes-2026-10-26.md)。用户当前没有 iOS 真机，物理设备验证继续标记为 `deferred_no_device`，未报告为通过。未提交、推送或合并。

## 范围与基线

- Windows 工作区：`D:\dance-anonymizer`，分支 `feat/dance-comparison-m0`，HEAD `e286cf6116357838421c047f166aa95147a66428`。
- 合并目标 `master`：`ccb405f88b3e8779433c6da00eab8f97cea1e5b9`，与本地 merge-base、`origin/master` 相同；通过 GitHub 连接核对远端分支。当前分支相对该基线有 42 个提交。
- 审计包含已提交的对比功能差异及当前未提交的后续功能，重点检查 Flutter 交互/生命周期、项目时间映射与持久化、Pigeon、Android/iOS 播放、音乐校准和导出。没有将当前 HEAD 误当作全部实现。
- 初审与 iOS 接续的 105 项工作树清单逐一核对：当时生产代码、测试及工具字节未变化，仅修改两份 iOS 文档、增加审计文档及 JSON。复现代码和日志位于 Git 忽略目录。
- 初审阶段未改动生产实现，未重新安装或操作 Android 真机。后续修复与真机验证范围见接续记录；两阶段均未提交、推送、合并或重复全量 M0 / Release QA。

## 初审问题（均已修复）

下列定位、原因与失败结果保留修复前快照。最新实现位置、常规回归测试和平台验证结果见[修复记录](comparison-premerge-fixes-2026-10-26.md)。

### 1. P1：Android 中断导出立即重试，旧 worker 抛出未处理异常

位置：[ComparisonExportManager.kt:140](../mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportManager.kt#L140)，相关状态转换为同文件 106–117 行；服务入口为 [ComparisonExportService.kt:48](../mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/comparison/ComparisonExportService.kt#L48)。

`interrupted()` 将任务立即置为可重试的终态，但旧编码 worker 可能仍在退出。此时 `retry()` 用新 ID 替换全局 `current`。旧 worker 随后进入异常清理，再次 `requireJob(oldId)`，在 catch 内抛出 `IllegalArgumentException`。服务的 `scope.launch` 只有 finally，没有接住该异常；中断恢复路径存在未处理协程异常和进程崩溃风险。

复现直接运行生产 `ComparisonExportManager`，仅替换 Android 系统边界及编码器，并用锁存器控制旧 worker 的退出顺序：开始任务 → worker 尚未退出时 interrupted → retry → 放行旧 worker。实际错误为 `导出任务已变化，请重新打开导出`。这是 JVM 状态机复现，没有声称已在手机上复现进程崩溃。

建议：将运行任务与其取消令牌、清理上下文按 ID 保留；新任务不得使旧 worker 的退出路径查询失败。旧回调只能更新所属任务，服务协程还需兜住任务异常。

证据：[android-manager-junit.xml](session/comparison-premerge-audit-20261026/android-manager-junit.xml)、[android-manager-repro.log](session/comparison-premerge-audit-20261026/android-manager-repro.log)。

### 2. P2：片尾 A/B 内暂停后播放，错误回到整段 0 秒

位置：[IOSComparisonPlayer.swift:127](../mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSComparisonPlayer.swift#L127)，Android 同样存在于 [ComparisonSession.kt:219](../mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/comparison/ComparisonSession.kt#L219)。

当当前位置仍在启用的循环内，却位于整段最后 100ms 时，循环的“不在选段内”分支不执行，随后落入整段片尾重播分支，seek 到 0。正式页面的播放按钮直接调用该控制命令，没有额外修正。用户合法选取末尾 100ms，或在较长片尾选段的最后 100ms 暂停，均会遇到此条件。

iOS 17 模拟器经真实 Pigeon/AVFoundation 验证：总长 `23,000,000us`，A/B 为 `[22,900,000, 23,000,000)`，选段后位置为 `22,900,000us`，PLAY 返回 `0us`。A/B 最短 100ms 在当前控件和控制器中合法。Android 的相同分支通过代码核对确认，本轮未重复 Android 真机复现。

建议：启用循环时，重播和恢复播放均按循环边界处理；整段片尾归零仅适用于未启用循环的状态。

证据：[native-tail-ab-report.json](session/comparison-premerge-audit-20261026/native-tail-ab-report.json)、[comparison_native_audit.dart](session/comparison-premerge-audit-20261026/comparison_native_audit.dart)。

### 3. P2：手动校准最终定位失败，仍保存校准并关闭编辑页

位置：[comparison_manual_session.dart:188](../mobile/app/lib/features/comparison/presentation/comparison_manual_session.dart#L188)、[anchors():200](../mobile/app/lib/features/comparison/presentation/comparison_manual_session.dart#L200)，调用方为 [comparison_screen.dart:337](../mobile/app/lib/features/comparison/presentation/comparison_screen.dart#L337)。

手动 seek 把异常转成 `session.error` 后正常完成 Future；`anchors()` 没有检查成功状态，继续返回现有源位置。“完成校准”点击后才发生的最终定位失败因而不会走页面的失败保留路径，控制器仍保存锚点并关闭编辑器。点击之前已有错误时按钮确实会禁用，但不能覆盖确认过程中新出现的失败。

受控视频源异常下，Session 测试实际返回旧位置 `(1,000,000, 3,000,000)`，没有报告失败；正式 `ComparisonScreen` Widget 测试在点击“完成校准”后触发最终 seek 失败，编辑器仍消失。这里使用异常注入覆盖失败路径，没有声称某个真机文件必然失败。

建议：最终锚点采集必须返回明确成功结果或传播失败；任一路最终 seek 不成功时不写入校准，保留手动页面和原项目校准。

证据：[flutter-all-repros.log](session/comparison-premerge-audit-20261026/flutter-all-repros.log)、[comparison_audit_test.dart](session/comparison-premerge-audit-20261026/comparison_audit_test.dart)。

### 4. P2：旧 resize 请求完成后可污染已切换的新项目

位置：[comparison_controller.dart:128](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L128)。

`setViewport` 只在发起请求前检查生命周期；等待 `gateway.resize` 后，无论项目 revision 是否改变，都直接覆盖 `playback`，异常路径也会把旧请求错误写到新项目。期间用户新建/切换项目、进入需要关闭播放器的流程时，旧结果已经不属于当前会话。iOS resize 本身会异步重建 composition，旧重建被 close 取消是正常情况。

两项受控异步测试分别验证：旧请求成功返回使空白新项目重新出现 texture 7；旧请求取消则在原本无错误的新项目中显示“画面调整失败，请重新打开此项目”。这是 Controller 层复现；本轮没有将其称为已在真机 UI 上复现。

建议：像 `_open` 和 `_refresh` 一样在发起时捕获 revision/请求身份，成功、失败及通知前均确认仍属于当前会话；取消计时器不足以撤销已发出的请求。

证据同上两份 Flutter 复现文件。

## 检查与证据边界

初审增加的定向复现共 6 个：Flutter 4 个、Android 管理器 1 个、iOS 原生循环 1 个，当时均按正确行为断言后失败，用来确认上述 4 项缺陷。原始复现保留在忽略目录，修复后的回归已放入常规测试目录。以下为初审命令，最新验证使用修复记录中的测试文件。

- Flutter 命令（工作目录 `mobile/app`）：`D:\runtimes\flutter\bin\flutter.bat test --no-pub --reporter expanded build/premerge_audit/comparison_audit_test.dart`。复现源码在证据目录有副本；清理 build 后需先复制回原路径，其相对导入依赖该位置。
- Android 命令（工作目录 `mobile/app/android`）：`.\gradlew.bat :dance_native:testDebugUnitTest --tests art.gaoge.dance.engine.comparison.ComparisonPremergeAuditTest -I D:\dance-anonymizer\docs\session\comparison-premerge-audit-20261026\audit.init.gradle`。忽略目录的 init script 仅为此命令增加审计测试源，不修改 Gradle 配置。
- iOS 原生复现使用 Mac 对比工作区的 iPhone 15 / iOS 17.0.1 模拟器，临时 Dart target 只读原项目并发出播放命令，没有保存项目或导出相册。结束后恢复正式 `lib/main.dart` 应用及生成配置；正式应用三个关键产物 SHA-256 与之前记录一致，原项目字节不变，测试模拟器已关闭。见 [mac-restore-receipt.json](session/comparison-premerge-audit-20261026/mac-restore-receipt.json)。
- 复用源码未变化的既有检查：相关 Flutter 47 项、静态分析、Android 对应真机功能/导出记录、iOS 原生 gates、16 阶段模拟器集成、正式 Debug 构建及独立成片验证。复用结果不能覆盖本次新增边界缺陷。详见 [iOS 接续记录](comparison-ios-2026-10-26.md) 和 [Android 导出记录](comparison-export-2026-10-26.md)。
- 本地 `git diff --check` 通过。Git 对生成桥接文件给出换行转换提示，未发现本轮引入的空白错误。

## iOS 待设备补验

状态为 `deferred_no_device`，来源为用户明确说明当前没有 iOS 真机：

- iPhone/iPad 手动选择并导入视频、实际权限提示和拒绝路径。
- 实际后台时间额度耗尽、锁屏恢复、进程被中断及相册事务恢复。
- 物理设备性能、发热和系统资源约束。

这些项没有通过模拟器结果替代。上述审计问题已修复并完成受影响回归；设备可用后补实际设备边界，不需要因此重跑整个 M0 矩阵。
