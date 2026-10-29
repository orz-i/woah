# 双视频对比第三次合并前审计（2026-10-29）

**更新：两项已修复，结果见[三审修复记录](comparison-third-audit-fixes-2026-10-29.md)。** 以下保留三审时的缺陷与复现证据。

**审计时结论：新增 2 项 P2，建议修复后再合并。** 前两轮 6 项修复保持有效；本轮生产代码和常规测试未修改，没有提交、推送或合并，没有重跑全量 M0 / Release QA。

## P2：重复进入手动校准会遗留一组预览会话

位置：[comparison_controller.dart:546](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart#L546)，以及 [comparison_screen.dart:215](../mobile/app/lib/features/comparison/presentation/comparison_screen.dart#L215)。

`enterManualCalibration()` 只在入口检查 `busy`，随后先等待 pause 和 save，到 552 行才设置 `calibrating`。这段时间正式页面的“手动校准”仍能点击。`_manual()` 对 `_manualSession` 的检查也在 await 前，返回后没有再次检查；两个调用都成功时会各创建两路 `ComparisonManualSession` 预览，后创建的会话覆盖先创建的引用。

实际页面 Widget 复现：暂缓第一次保存 → 连点两次“手动校准” → 释放保存 → 页面显示一组手动滑条 → 卸载页面。实际创建 4 个预览控制器，仅释放 2 个；第一组失去页面所有权。真实实现使用 `VideoPlayerController`，遗留控制器的关闭路径因此缺失，可能继续占用播放器与纹理资源。本轮没有把 Widget 中的计数称作真机内存或解码器测量。

建议：在第一个 await 前占用进入状态，对重复请求拒绝或复用同一 Future；完成后仍需核对页面及会话所有权，确保每一组已创建预览都有对应关闭路径。也应复查同样先 pause/save、后设置状态的画面调整入口。

证据：[entry-repros.log](session/comparison-third-audit-20261029/entry-repros.log)，观测为 `created=4, disposed=2`。

## P2：导出取消和进度轮询可重复关闭路由

位置：[comparison_export_dialog.dart:71](../mobile/app/lib/features/comparison/presentation/comparison_export_dialog.dart#L71) 和 [104 行](../mobile/app/lib/features/comparison/presentation/comparison_export_dialog.dart#L104)。

系统返回键触发 `_cancel(close: true)` 后，取消响应和已经发出的进度轮询可以各返回终态。`_cancel()` 会立即 `Navigator.pop`；`_poll()` 还会安排一个帧后 `Navigator.pop`。第一次关闭的对话框在退出动画结束前仍然 mounted，第二次调用于是把下面的对比页也弹出。`mounted` 不能代替“该对话框尚未开始关闭”的检查。

实际 `ComparisonScreen` / `ComparisonExportDialog` Widget 复现了两种顺序：进度先回、取消后回；取消先回、退出动画期间进度再回。两者都观测到 `poppedRoutes=[null, "comparison"]`，对比页剩余数量为 0，而预期仅关闭导出对话框。触发条件是返回退出时，取消响应与轮询重叠且均为终态；不表示每次取消都会误退，也没有发现本复现导致项目文件或导出文件损坏。

建议：将关闭收敛到一个幂等入口，在 pop 前同步标记 closing；忽略关闭中对话框的迟到进度和帧后回调，并确认当前路由仍属于该对话框。

证据：[export-close-repro.log](session/comparison-third-audit-20261029/export-close-repro.log)、[export-close-order-repro.log](session/comparison-third-audit-20261029/export-close-order-repro.log)。

## 检查范围与验证状态

- 开始时逐字节核对了[上轮 119 项清单](session/comparison-reaudit-fixes-20261029/final-source-manifest.json)，全部一致。本轮复查了控制器事务、源/项目切换、手动预览所有权、A/B 与时间映射，以及导出管理、取消、发布与恢复路径。
- 新增 3 个受控时序的实际页面 Widget 复现，在正确行为断言处均失败，归属上述 2 项发现；没有把这些失败计为通过。源码和结构化观测见[证据目录](session/comparison-third-audit-20261029/reproduction-results.json)。
- 前两轮修复的有效证据继续复用：相关 Flutter 87 项、Android 真机 / iOS 模拟器各 3 项播放器时序检查，以及未改动原生代码的 JVM / 播放边界检查。它们不覆盖本次新增的入口重入与路由关闭时序。
- 本轮未重新构建、安装、操作手机或运行模拟器；没有扩大到全量 M0 / Release QA。iOS 真机仍为 `deferred_no_device`，原有选片/权限、实际后台与锁屏中断、性能与发热等缺口保持待补。
- 复现文件只保存在 Git 忽略的 build 和 session 目录。重现时，将证据目录的 3 个 `.dart` 文件复制到 `mobile/app/build/audit3/`，在 `mobile/app` 分别执行 `flutter test --no-pub --reporter expanded build/audit3/<文件名>`；修复前快照预期为相应正确行为断言失败；当前通过结果见三审修复记录。

最新文档与源集合见 [final-source-manifest.json](session/comparison-third-audit-20261029/final-source-manifest.json)；上轮清单保留为审计前快照。
