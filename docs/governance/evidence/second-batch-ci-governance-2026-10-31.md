# 第二批：可信 CI 汇总与质量检查

日期：2026-10-31 UTC。对应 GOV-02；维护者授权“合并，然后开始下一批次的治理”。第一批 [PR #7](https://github.com/orz-i/woah/pull/7) 已合并为 `747721d1c073170cbae4a3940778ca16e81ac07d`，本批从该提交创建独立分支 `chore/ci-governance-20261031`。

**状态：本地验证通过，最终 head 的远端 CI 尚待公开 PR 验证。** 仓库 ruleset / required checks 未启用，本批不自动合并、不发布版本。

## 范围与设计

- 保留 Production CI 的 Python 契约、四包 Flutter 测试、Android native unit、Release Kotlin compile 和 Debug APK 构建。
- 固定 Production Flutter 为现有 iOS 基线 3.44.2（Dart 3.12.2），新增四包 analyze、107 个已跟踪手写 Dart 文件 format 检查，仅精确排除两个 Pigeon Dart 产物。生成一致性 GEN-01 仍未完成。
- 初始 format 检查 9 文件未通过；以纯格式提交 `99733bc` 整理，无生成文件改动、无业务逻辑重构。schema 仅布局改变，不重新生成／手改六份输出。
- 显式运行 `:dance_native:lintDebug :app:lintDebug`。app 原先 `abortOnError=false` 会掩盖错误，已改为 true；没有新增 lint baseline、ignore 或 suppression，也没有提高 minSdk。
- 每个主分支 push、每个面向 main/master 的 PR 均执行双平台，包括纯文档改动。暂不做路径分类，彻底消除 dance_ui、tools/ios、phase4、模型／Metal 输入漏触发；代价是文档 PR 也消耗构建时间。后续只有带测试的分类器才能优化此成本。
- iOS 改为同仓库同 revision 的 reusable workflow，保留手动 dispatch，自动入口由 Production CI 调用一次。无 `pull_request_target`、无 `secrets: inherit`，token 显式 `contents: read`。
- 常驻 `governance / required` job：job-level `always()`、直接 needs 列举两条必要 job，仅都为 success 才成功。failure、cancelled、skipped、neutral、未知／遗漏结果、坏 JSON 都失败。即使整个 workflow 被取消导致汇总无法完成，也不产生成功检查。
- caller 与 callee concurrency group 使用不同前缀，避免 reusable workflow 的 `github.workflow` 上下文导致自取消。

## Apple 调用链迁移

迁移前：`ios-cloud.yml → verify_ios_phase1.py → 仅 Darwin + GITHUB_ACTIONS 时隐式 run_phase6_macos_gate.py`。

迁移后：`ci.yml 的 ios job → ios-cloud.yml → 独立命名步骤 Run full iOS Phase 6 gate → run_phase6_macos_gate.py`。

- Phase 1 恢复文档承诺的 host-independent static verifier。
- Phase 3/4/5/6 验证器的旧 hook 断言改为检查显式 Phase 6 调用；各 phase 其余断言保持不变。
- 原 `run_phase6_macos_gate.py`、Metal 编译、model provisioning、simulator runner、smoke 断言完全未改；production main.dart iPhoneOS build、archive/upload 仍保留。
- 新测试逐个移除工作流中的显式 gate，确认四个 phase 验证器都失败；另检查 gate 恰好一次、没有可跳过关键步骤的 if、没有 continue-on-error。

## lint 首次暴露的错误与局部修复

native 首次真实 lint：4 errors、70 warnings。app 独立检查：0 errors、19 warnings。局部修复提交 `b3c847d` 后，两模块 lint 均 0 errors；70/19 warnings 数量不变。

1. VideoDecoder：minSdk 24 路径调用 API 29 `MediaFormat.removeKey`。删除覆盖前冗余删除，继续由 `setInteger` 写入 Surface 色彩格式；没有提高最低系统版本。
2. AudioTrackCopier：Extractor flags 与 Codec flags 不是同一语义，原加密位 2 会被误当 codec-config，partial 位 4 会被误当 EOS。普通／SYNC 样本明确映射到 Codec 常量；当前没有解密／partial access-unit 组装能力，因此非支持位明确失败，不再错误透传。不是增加 DRM 或 partial 音频支持。异常沿现有导出失败与 finally 清理路径传播。
3. ComparisonNativeApi：URI 权限提取为小函数，只持久化系统 picker 已返回的 read/write 组合，0 grant 不请求。忽略其他 Intent flags，不扩大访问。
4. debug-only ComparisonGlM0Activity：用 ContextCompat 的显式 flags 重载统一兼容旧 Android；保留原 DUMP 发送者权限及 EXPORTED（供 ADB/system 调试），没有开放普通 app 访问选中媒体。

新增 6 项 Kotlin 测试覆盖普通／SYNC、加密、partial、未知音频位和 URI grant 组合；另有 3 项源码边界检查。它们不替代真实 API 24/29/33+ 设备、文档 provider 或特殊音轨矩阵。

## 本地证据

环境：Mac 上既有 Flutter 3.44.2 / Dart 3.12.2、Temurin 17.0.21、Android SDK 36；不改全局 Xcode 选择、不触碰 Memora 模拟器。

| 检查 | 实际结果 |
| --- | --- |
| 手写 Dart format | 107 文件，0 变化，通过 |
| 四包 analyze | dance_domain / dance_ui / dance_native / app 全部通过 |
| 四包 Flutter tests | 27 / 1 / 5 / 256 全部通过 |
| CI / lint 边界 Python 回归 | 14 tests，通过；含 49 种 job 结果组合、CLI 坏输入及四个 Apple gate 删除回归 |
| iOS 静态 phase0/1/3/4/5/6、cloud verifier | 通过 |
| shared export + phase4 regression | 10 tests，通过 |
| Android native tests / Release Kotlin / 双模块 lint | 490 tests，0 failures/errors/skipped；Release compile 通过；native/app lint 均 0 errors，保留 70/19 warnings |
| Debug APK | `flutter build apk --debug` 通过，exit 0 |
| 新工作流远端 Apple gate / GitHub 汇总 | 尚待公开 PR 的最终 head 验证，不复用第一批结果冒充本批通过 |

可重放命令（仓库根目录，先按现有流程解析四包和准备模型）：

```bash
python tools/ci/check_dart_format.py
for package in mobile/packages/dance_domain mobile/packages/dance_ui mobile/packages/dance_native mobile/app; do
  (cd "$package" && flutter analyze --no-pub && flutter test --no-pub)
done
uv run python -m unittest discover -s tools/ci -p 'test_*.py' -v
(cd mobile/app/android && ./gradlew :dance_native:testDebugUnitTest :dance_native:compileReleaseKotlin :dance_native:lintDebug :app:lintDebug)
(cd mobile/app && flutter build apk --debug)
```

Mac 本地 Python 回归使用隔离 `uv run --no-project --with pyyaml==6.0.3`，没有尝试在 Mac 解析 root Linux CUDA 依赖。远端 Linux CI 继续使用已有 `uv sync --locked`，PyYAML 已在原项目锁中。

## 未关闭项

- lint warnings 仍是待排期债务，不宣称清零。native 初始分类：UseKtx 39、SetTextI18n 9、InlinedApi 8、GradleDependency 6、NewerVersionAvailable 2、DiscouragedApi 2，以及 DefaultLocale、AndroidGradlePluginVersion、ObsoleteSdkInt、StaticFieldLeak 各 1。app：IconLauncherShape 10、UnusedResources 3、SelectedPhotoAccess 2，以及 ScopedStorage、AndroidGradlePluginVersion、ObsoleteSdkInt、MonochromeLauncherIcon 各 1。隐私／生命周期警告优先复核，不将全部当噪声。
- app `checkReleaseBuilds=false` 未在本批变更；明确启用的是 Debug variant 的显式 lint，发布 variant 验收仍需独立证据。
- uv latest / Actions 供应链锁、原生应用锁、Pigeon 再生成是 DEP-01 / GEN-01 后续范围；固定 Flutter 不等于依赖治理已完成。
- 没有启用 GitHub required checks / ruleset，故“汇总存在”尚不等于服务器强制阻止红灯合并。
- NATIVE-01/02、PRIV-01/02/03 等原审计项不因本批门禁而自动关闭。

## 官方依据（检索于 2026-10-31）

- [GitHub reusable workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)：同仓库相对路径复用、workflow_call。
- [GitHub expressions](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions)：always 与状态检查。
- [Dart format](https://dart.dev/tools/dart-format)：不写入检查与退出码。
- [Android lint](https://developer.android.com/studio/write/lint)：显式执行与错误阻断。
- [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)：sample flags 含义。
- [ContextCompat](https://developer.android.com/reference/androidx/core/content/ContextCompat)：显式 receiver flags 与发送者权限。
