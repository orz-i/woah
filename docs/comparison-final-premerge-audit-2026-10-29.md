# Woah 舞蹈双视频对比：最终合并前审计（2026-10-29）

**结论：对当前 `origin/master` 的独立合并为 `CONDITIONAL_GO`（条件性可合并）；不是 Release/跨 crop 分支的全量验收。** 本轮未发现新的确定性 P0/P1 或可复现的比较功能 P2 代码缺陷。现有 `feat/crop-clarity-phase2-quality-performance` 与对比分支直接合并会出现 7 个实际冲突，属于明确的跨分支集成工作；功能分支尚未在 GitHub Actions 上产生 CI 回执。请不要将这两项误记为已通过。

## 1. 审计对象与证据等级

- 目标：`origin/master` = `ccb405f88b3e8779433c6da00eab8f97cea1e5b9`。
- 最新对比分支：`feat/dance-comparison-m0` = `a36a29079ffc1cd24ee58787ad2a9471ed3b8fab`；Mac 同名工作区的 HEAD 已快进对齐，ahead/behind 为 `0/0`。
- Windows Android 真机验收源码：`0a25d05b1d2271f311951b3d38c181bbeeff5a7d`。从 `0a25d05` 到 `a36a290` **仅添加/修改两份验收文档**，产品源码、工具与测试均未变化，已用 `git diff --quiet` 核对。
- Windows 验收来源：`docs/comparison-storage-lifecycle-fixes-2026-10-29.md` 和同名 JSON。它们记录 13/13 Kotlin、25/25 Flutter、OnePlus KB2000 Android 14 上正式 MainActivity 的导入切换、历史/音画预览、未保存成片保护、确认替换、取消、失败重试、后台及重启恢复。原始 `docs/session/comparison-storage-lifecycle-20261029/` 为 Git 忽略内容，本 Mac 没有该原始日志目录，因此本轮仅核验提交源码与**结构化验收回执一致性**，不将 Windows 记录说成 Mac 独立设备复测。
- 已核对 Windows 摘要标识 `validatedCommit=0a25d05`，其为最新提交祖先；JSON 可解析。报告记录 Android APK SHA-256 `198a69796d8b0827daf891c0566ba4b92e500d433f8dd927a4e58980dc887bad`，本轮无 Android 设备，未独立核对安装包哈希。

## 2. 本轮 Mac 独立复跑

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| Flutter 整个 `mobile/app/test` | **183 / 183 PASS** | `/tmp/woah-final-audit-flutter-all.log` |
| Flutter 应用完整静态分析 | **PASS，无问题** | `/tmp/woah-final-audit-flutter-analyze.log` |
| `dance_domain` Dart 测试 | **27 / 27 PASS** | `/tmp/woah-final-audit-domain-test.log` |
| `dance_native` Dart 测试 | **5 / 5 PASS** | `/tmp/woah-final-audit-native-dart-test.log` |
| `tools/comparison` Python 工具 | **30 / 30 PASS** | `/tmp/woah-final-audit-python-complete.log` |
| Swift 独立测试门禁 | **6 / 6 组 PASS** | `/tmp/woah-final-{export-spec,export-av,media-gc,export-retention,frame-index,music}.log` |
| iOS simulator Debug 正式入口构建 | **PASS**，`lib/main.dart` | `/tmp/woah-final-audit-ios-build.log` |
| iOS 17.0.1 新建 iPhone 15 模拟器 | **创建、启动、安装、launch、截图均 PASS** | `/tmp/woah-final-audit-simulator-smoke.status`、`/tmp/woah-final-audit-ios-home.png` |
| Git 源差异校验 | **PASS** | `git diff --check origin/master...HEAD` |

Python 初次使用系统 Python 缺少 NumPy/OpenCV/SciPy；后续在独立 `uv` 环境补齐全部文档所需依赖并全部通过，未修改仓库依赖。iOS 整包构建采用隔离 XDG Flutter 配置禁用 SPM、使用 CocoaPods；本机默认开启 SPM 的混合插件构建流程此前因 `file_picker_darwin` 缺失失败，**默认 SPM 构建问题没有视为修复**。本轮没有运行完整的 iOS 视频选择/播放/导出交互矩阵或物理设备。

## 3. 合并关系与复查焦点

- **直接合并到当前 `master`：Git 层面 PASS**。`master` 是比较分支的祖先；`git merge-tree --write-tree origin/master HEAD` 成功，当前目标可快进（模拟，不执行真正合并）。分支相对 master 新增约 45 个提交，覆盖正式 Flutter 入口/页面、音轨映射、对比渲染与导出、原生 Pigeon、单元/真机门禁和文档。
- 复核对比页与主隐私编辑器的入口隔离、Android/iOS Pigeon `replacePreviousUnsaved` 一致性、导出状态/取消/恢复、项目与标记持久化、iOS 导入 UUID 文件引用回收、iOS/Android 历史私有 MP4 保留和有界清理、时间映射/逐帧/VFR 及权限与前台服务边界。当前合并范围没有直接改写隐私推理/分割主体算法，Flutter 全应用测试同时覆盖旧流程。
- **与活跃 crop 分支同时集成：Git 层面 BLOCKED**。运行 `git merge-tree --write-tree feat/crop-clarity-phase2-quality-performance HEAD` 实际产生以下 7 个冲突，不能把比较分支对当前 master 的快进证明当成两个分支合并后的通过证明：
  1. `.github/workflows/ci.yml`
  2. `.github/workflows/ios-cloud.yml`
  3. `mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/ExportPipeline.kt`
  4. `mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/privacy/PrivacyOcclusionResolver.kt`
  5. `mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/render/GlRenderer.kt`
  6. `mobile/packages/dance_native/android/src/test/kotlin/art/gaoge/dance/engine/render/PrivacyRenderGlErrorPolicyTest.kt`
  7. `tools/release/verify_export_media_contract.py`

## 4. 未证明范围与合并决策

- GitHub Actions：`gh run list --branch feat/dance-comparison-m0` 未返回该分支的 CI 运行记录。未复跑全量 Release QA；**`RELEASE_AND_PRIVACY_ACCEPTANCE` 不能宣称本轮通过**。建议通过 PR/CI 或后续独立合并后完整 Release gate 验证。
- 物理 iOS：`deferred_no_device`；Photos 拒绝/权限变更、PHPicker 长时重复导入、真实后台额度耗尽/锁屏、性能发热仍需设备。iOS 模拟器启动 PASS 不能替代这些场景。
- Android：本轮 Mac 不具备 Android SDK/ADB 真机；Windows 验收限 OnePlus Android 14，Android API 24–28 SAF、自然存储耗尽、更多机型仍未验。Android 真机原始证明文件本地不可访问，只有已提交结构化回执。
- 既有合并前 3 轮共 8 项状态/生命周期缺陷，另有媒体存储 2 项 P2 修复；本轮通过完整测试复核已提交的修复状态，但**没有宣称对所有历史素材或全量 M0 / 隐私发布矩阵重新验收**。

**建议**：若本次仅合并对比分支到当前 `master`，代码与可重复的门禁支持 `CONDITIONAL_GO`，但应保留 iOS 物理测试和 Release QA 的欠账且不将其列为发布完成。若近期计划一并合并 crop，则应先在隔离集成工作树解决上列七个冲突，随后重新跑 Android 隐私/导出、Release CI 与对比功能回归，再合并。任何情况均需用户明确批准合并。

## 5. 操作安全

- Mac 主工作区 `/Users/mini/Coding/woah` 仍在 `feat/crop-clarity-phase2-quality-performance`，干净未修改。
- 对比分支工作区 `/Users/mini/Coding/woah-comparison-m0` HEAD 已安全快进，原有 66 项仅 CRLF/尾空行差异的工作树文件在操作前后字节保持不变，未将它们提交。
- 本轮在 `/Users/mini/Coding/woah-comparison-remote-audit` detached HEAD 独立复跑，不合并、不 commit、不 push。
