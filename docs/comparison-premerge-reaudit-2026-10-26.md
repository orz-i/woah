# 双视频对比二次审计（2026-10-26）

**2026-10-29 更新：两项已修复，最新结果见[二审修复记录](comparison-reaudit-fixes-2026-10-29.md)。** 以下保留二审时的缺陷与复现证据。

**二审时结论：新增 2 项 P2，建议修复后再合并。** 上一轮 4 个定点修复及其有效证据仍然成立，本次发现的是相邻异步通路的遗漏。没有修改生产代码，没有推送、提交或合并。

## 1. P2：过期练习设置与播放响应仍能改写当前项目

主位置：[comparison_controller.dart:844](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L844)，失败回滚在 848 行；同类播放返回位于 [876 行](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L876)。

`_setPractice()` 在等待原生结果前改写 `project`，等待期间没有标记为忙，也没有保存/校验会话与请求身份。用户此时可以从正式菜单新建对比；旧请求因原生播放器关闭而失败后，catch 直接 `project = previous`，把已新建的空项目还原成旧双视频项目。上一轮的 resize 检查只作用于尺寸请求，没有覆盖此通路。

正式 `ComparisonScreen` 的 Widget 复现：打开视图菜单 → 参考镜像（暂缓原生返回）→ 更多操作 → 新建对比 → 确认当前为空项目 → 返回旧镜像请求的关闭错误。实际 `project.id` 从新 ID 退回 `test_project`，两路视频再次恢复。

`_control()` 同样在 await 后直接改写当前 `playback` 和 `project.positionUs`。控制器复现中，恢复到 B 项目的 `2s` 后才返回旧 seek 的 `5s`，紧接着保存时 B 的位置被提交为 `5s`。这说明相关通路需要一起隔离，不能只补镜像的单一 catch。

建议：发出异步操作时绑定项目/播放器会话、操作身份与快照；成功、错误回滚及后续保存前都校验身份。过期请求不能恢复旧项目或覆盖新位置，回滚也不能覆盖同一项目中更晚的编辑。也可对必须串行的设置事务做明确串行化。

## 2. P2：取消自动校准后，应用阶段仍会提交结果

位置：[comparison_controller.dart:350](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L350) 到保存调用 [381 行](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L381)，以及 [matchMusic():297](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L297)。

`matchMusic()` 只在 `gateway.align()` 返回后校验 revision。分析已完成、正在等待 `setAlignment()` 应用映射时，页面仍显示可点击的“取消”。`cancelMatching()` 增加 revision，但 `_applyMusicMapping()` 没有再检查，继续改写项目、设置练习参数、seek 并保存，最后还把“已取消”的消息改成“已自动完成”。原生 `cancelAlignment` 只取消音乐分析，不负责撤销播放器的映射重建。

正式页面 Widget 复现使用正常未校准项目：先等待预览打开，再返回音乐分析结果，暂缓映射应用 → 点击可见“取消” → 确认 matching 已关闭且偏移仍为 `0s` → 返回映射成功。实际保存接口收到一次 `+3s / auto` 的项目，页面消息为“已自动完成音乐速率和位置校准”。

建议：将取消/操作身份贯穿应用、定位和保存阶段；取消后禁止旧操作提交或更新成功消息。若原生映射已发生变化，需要在所属会话仍有效时恢复原映射或重新打开，避免只丢弃 Flutter 响应而留下两端状态不一致。

## 证据和范围

- 以[上一轮 113 项清单](session/comparison-premerge-fixes-20261026/final-source-manifest.json)核对，审计开始时所有文件字节一致；本轮生产实现、常规测试和构建输入未修改。
- 新增 3 个受控时序复现：2 个实际页面 Widget 用例、1 个控制器用例，均在正确行为断言处失败，分别覆盖上述 2 类缺陷。没有把这些失败计为测试通过，也没有称为本轮真机复现。
- 原始日志：[await-repros.log](session/comparison-reaudit-20261026/await-repros.log)；结构化结果：[reproduction-results.json](session/comparison-reaudit-20261026/reproduction-results.json)；源码：[comparison_await_audit_test.dart](session/comparison-reaudit-20261026/comparison_await_audit_test.dart)。证据和复现源码保存在 Git 忽略目录，没有把有意失败的用例加入常规测试集。
- 复用上一轮有效覆盖：Flutter 65 项、Android JVM 19 项、iOS 原生播放边界 4 项、OnePlus 中断立即重试/相册保存及正式构建。这些检查不覆盖本次新增时序。未重跑全量 M0 / Release QA，未重新安装或操作手机/模拟器。
- 复查了原生导出任务隔离、相册发布/恢复、iOS 播放生命周期以及时间轴/项目相关逻辑；本轮新增的可复现问题位于 Flutter 控制器。
- iOS 真机继续为 `deferred_no_device`，实际选片/权限、后台额度耗尽/锁屏/进程中断、性能与发热仍待补。

复现命令（工作目录 `mobile/app`）：

```powershell
D:\runtimes\flutter\bin\flutter.bat test --no-pub --reporter expanded build/reaudit/comparison_await_audit_test.dart
```

清理 build 后，需要先将证据目录中的复现源码复制回上述路径；它相对导入既有测试 fake。二审修复前快照下预期为 3 个行为断言失败；当前生产代码的通过结果见上述修复记录。

本轮最新文档/源集合见 [final-source-manifest.json](session/comparison-reaudit-20261026/final-source-manifest.json) 和 [Mac 校验回执](session/comparison-reaudit-20261026/mac-source-receipt.json)。修复阶段旧清单保留为历史快照；生产代码与其相同。
