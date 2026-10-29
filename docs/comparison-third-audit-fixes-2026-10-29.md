# 双视频对比三审修复（2026-10-29）

**三审的 2 项 P2 均已修复，必要回归与正式构建核对通过。** 沿用 `feat/dance-comparison-m0` / `e286cf6`，没有提交、推送或合并，没有重复全量 M0 / Release QA。前两轮 6 项修复保留。

## 修复

- `comparison-third-audit-1`：手动校准和画面调整在第一次 await 前共享进入锁，重复点击和跨模式请求均被拒绝。每次暂停、保存及关闭播放器返回后检查项目和会话身份，准备完成后才进入对应模式。页面在创建手动预览前再次确认模式及现有会话；失败和退出会释放进入状态，允许重试。进入画面调整时会先应用等待期间更新的画面尺寸。
- `comparison-third-audit-2`：导出对话框通过同一个幂等关闭入口退出，关闭前同步标记状态、停止轮询，并确认该对话框仍为当前路由。取消、轮询以及迟到响应不再分别 pop；系统返回在已有取消操作期间也能保留退出意图。取消失败时保留对话框并允许重试。

生产改动仅在 [comparison_controller.dart](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart)、[comparison_screen.dart](../mobile/app/lib/features/comparison/presentation/comparison_screen.dart) 和 [comparison_export_dialog.dart](../mobile/app/lib/features/comparison/presentation/comparison_export_dialog.dart)。项目 schema、音乐映射、动作时序和原生播放／导出后端未修改。

## 必要验证

- 新增 [11 项回归](../mobile/app/test/features/comparison_entry_route_regression_test.dart)：实际页面重复进入后只创建 2 个预览，卸载时全部释放；两种导出取消／轮询返回顺序均只弹出对话框，保留比较页；同时覆盖保存失败、原生关闭失败、进入期间退出、两模式互斥、延迟尺寸更新、取消中返回及失败重试。
- 相关 Flutter 共 98 项通过，定向静态检查无问题。范围包括正式比较页、音乐速率和双锚点、镜像和缩放、循环和标记、导出准备、已有异步隔离与生命周期回归。
- Android 正式 Debug 构建和 OnePlus 覆盖安装／启动检查通过，手机与本地 APK 哈希一致，原有 2 条项目文件字节保持不变，启动日志未见 `E/flutter` 或 `FATAL EXCEPTION`。iOS simulator Debug 和 unsigned iphoneos Debug 编译通过，入口均为 `lib/main.dart`；本轮没有运行 iOS 模拟器或真机。
- 本轮没有重跑真实素材播放和导出矩阵；原生代码未修改，保留之前有效验证。Widget 的预览计数不作为真机内存或解码器占用测量。
- iOS 物理设备继续为 `deferred_no_device`；权限、真实选片、锁屏／后台额度耗尽／进程中断以及物理性能与发热等原有缺口保持待补。

证据保存在 Git 忽略目录 `docs/session/comparison-third-audit-fixes-20261029/`：`flutter-entry-route.log`、`flutter-final.log`、`flutter-analyze.log`、`production-fix.diff` 及各端构建回执。三审原始失败证据保持为修复前记录，没有将失败计为通过。

最终源清单及同步核对回执见 [final-source-manifest.json](session/comparison-third-audit-fixes-20261029/final-source-manifest.json) 和 [Mac 核对回执](session/comparison-third-audit-fixes-20261029/mac-source-receipt.json)。Mac 对比工作区为 `/Users/mini/Coding/woah-comparison-m0`，主 crop 工作区保持干净。

最终 Android APK SHA-256：`2ade29729c88bcde78fbcbc40b709a440d8d89914563bacc92a301b6d217907e`。
