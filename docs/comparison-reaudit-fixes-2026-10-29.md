# 双视频对比二审修复（2026-10-29）

**二审的 2 项 P2 均已修复，必要回归与两端播放器检查通过。** 沿用 `feat/dance-comparison-m0` / `e286cf6`，没有提交、推送或合并。上一轮 4 项修复保留，没有重复全量 M0 或 Release QA。

后续[第三次合并前审计](comparison-premerge-third-audit-2026-10-29.md)的 2 项 P2 已修复，见[三审修复记录](comparison-third-audit-fixes-2026-10-29.md)。下述 2 项修复和已有验证结果仍然有效。

## 修复内容

- `comparison-reaudit-1`：练习设置在原生应用成功后才提交；等待期间暂停相关编辑。成功、失败和后续保存均校验播放器会话、项目和请求身份，旧请求不能恢复已关闭项目。播放响应也校验所属会话及最近一次控制请求；状态轮询不能覆盖较新的 seek。
- `comparison-reaudit-2`：音乐映射、选段重映射与锚点先保存在事务内，在原生映射、练习设置、定位和写入均完成后才提交。取消贯穿每个 await、排队写入和成功提示；已发出的保存无法撤回时，按相同写入队列补写原项目。播放器重新打开完整原快照，恢复偏移、速率、源位置、循环、镜像及缩放平移。恢复存储失败时保留错误和待保存状态，允许重试。
- 公共保存等待校准事务结束，避免后台保存旧快照覆盖刚提交的新映射；取消清理及延迟的视口更新完成前继续阻止编辑。音乐估计方式和动作抢拍／拖拍的语义没有改变。

生产改动仅为 [comparison_controller.dart](../mobile/app/lib/features/comparison/presentation/comparison_controller.dart)。新增 [异步回归测试](../mobile/app/test/features/comparison_async_regression_test.dart) 与 [设备验证入口](../mobile/app/test_driver/comparison_async_regression.dart)。原生桥接、解码、合成和导出实现未修改。

## 检查与边界

- Flutter：87 项通过，包含新增 14 项异步回归；覆盖旧镜像失败/成功、跨项目及同会话 seek、旧练习失败后的重开、应用四阶段取消、等待中的保存、并发保存、视口更新和回滚保存失败重试。
- 定向静态检查：通过。
- iOS 17.0.1 模拟器：3 项实际 Pigeon/AVFoundation 播放器检查通过，使用合成素材与内存项目库。真实原生调用完成后受控延迟 Dart 返回，以验证迟到结果隔离；取消后原生恢复 `offset=+1.2s, scale=0.8, position=2s`，随后重试成功。这不是手工选片或物理设备验证。
- Android：OnePlus KB2000 / Android 14 的 3 项真实 Pigeon/MediaCodec 播放器检查通过，使用合成素材和内存项目库；恢复位置与音乐映射及重试结果同 iOS。首次验证入口等待可见首帧在锁屏时超时，改为直接调用离屏播放器后完成；属于验证入口修正。最终正式 Debug APK 已覆盖安装，手机／本地 APK 哈希一致；正式 MainActivity 已恢复为前台入口，启动日志未见 `E/flutter` 或 `FATAL EXCEPTION`，原有 2 条项目 JSON 字节不变。
- 正式构建：Android Debug、iOS simulator Debug 和 unsigned iphoneos Debug 均通过，最终入口为 `lib/main.dart`；iOS 专用模拟器验证后已关闭，项目库基线为 0 条，验证器未调用用户项目存储。
- iOS 真机：继续为 `deferred_no_device`。实际选片／权限、锁屏与后台额度耗尽、进程中断、性能及发热仍待设备可用后补验。

证据目录：`docs/session/comparison-reaudit-fixes-20261029/`（Git 忽略）。`controller-fix.diff` 相对二审快照只包含本次控制器修复；`changed-source-manifest.json` 记录本次实现及测试的字节校验。旧审计中的失败日志作为修复前历史保留，不计为本次测试通过。

首次 iOS 准备遇到已被系统清理的 `/tmp` 模拟器集合，随后创建本任务专用 iPhone 15 / iOS 17.0.1；首次设备验证入口因未等待控制器预览就绪而超时，修正验证入口后重新运行。准备失败日志独立保留。没有把这些准备失败归为产品通过，也没有修改用户使用的模拟器或 Mac 主 crop 工作区。

最终源集合见 [final-source-manifest.json](session/comparison-reaudit-fixes-20261029/final-source-manifest.json)，Windows／Mac 按字节核对，Mac 主 crop 工作区保持干净。Android 正式 APK SHA-256：`eee322085cce4dd6a574c4779093152ce0b1c262f3802bbeaa08ea261ceaf2ec`。
