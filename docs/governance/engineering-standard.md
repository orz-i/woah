# Woah 工程规范草案

**状态：提案，尚未启用。** 本文基于 [2026-10-31 审计](audit-2026-10-31.md)，面向当前以单维护者为主、Flutter / Kotlin / Swift 加 Python 桌面工具的仓库。文中“必须”是建议采纳后的项目规则，不是声称官方强制要求或仓库现已执行。

目标是把真实失败变成可复现测试和门禁，而不是先增加复杂流程。保留已有包边界、Riverpod / controller / repository、平台 smoke 与隐私性能流程；不强制全量 MVVM、换状态管理或拆成更多服务。

## 最小规则和发现映射

| 规则 | 采纳后要求 | 主要关联 |
| --- | --- | --- |
| R1 合并可信 | 当前提交必需检查成功才合并，检查有稳定名称且每个 PR 都报告 | GOV-01、GOV-02 |
| R2 异步所有权 | 每个操作有 owner、generation、取消、终态与资源释放契约 | DART-01、DART-02、NATIVE-01 |
| R3 主线程与错误 | UI 不做整文件 I/O；错误不吞取消、不遗留半成品；每次调用只终结一次 | NATIVE-02 |
| R4 隐私生命周期 | 原始媒体及衍生缓存有保留、删除、配额、脱敏和平台声明 | PRIV-01、PRIV-02、PRIV-03、DESKTOP-01 |
| R5 可复现输入 | 工具链、依赖、schema 产物、模型与 vendor 来源可追溯 | GEN-01、DEP-01、MODEL-01 |
| R6 风险驱动测试 | 修复要有失败回归；算法/隐私/加速变更不能只凭单测绿灯 | 全部，尤其 NATIVE / PRIV / MODEL |
| R7 渐进偿债 | 以职责和风险拆分；例外有 owner、失效日和退出证据 | ARCH-01、DOC-01 |

## 代码与资产边界

- **手写产品代码**：app feature、domain、repository、共享 UI、native pipeline / bridge 实现、桌面 API / engine。格式、静态分析、测试和评审适用。
- **接口源**：`mobile/packages/dance_native/pigeons/dance_api.dart` 与 `comparison_api.dart` 是 Pigeon 契约源。字段类型、nullable、错误码、线程、生命周期及兼容性在源与测试中说明。
- **生成代码**：这两套 schema 对应的 Dart / Kotlin / Swift 六份产物不手改、不当作手写复杂度或覆盖率分母。生成后不再用普通 formatter 改造输出；由固定生成器和无差异检查治理。
- **第三方代码**：`desktop/vendor/**` 不混入普通业务重构。保留许可证和 notices，记录上游 URL / revision、引入原因、本地补丁、升级/退出方案；安全修复以独立 patch 审查。
- **模型与 fixtures**：代码许可证不代表模型权重和视频素材许可。模型来源、版本、SHA-256、使用/再分发约束、预处理、输出 tensor、有效 backend 分开登记。真实用户媒体不得直接进入仓库、CI 日志或公共 artifact。
- `dance_domain` 保持纯 Dart，不依赖 Flutter、原生通道或具体 I/O。UI 调用 controller / use case，I/O 经 repository / gateway；只在业务确有复杂度时增加 domain 抽象。Flutter 官方架构建议是可适配的实践，不要求所有 app 使用完全相同层数。[Flutter 架构建议](https://docs.flutter.dev/app-architecture/recommendations)

## Dart 和 Flutter

1. 以现有 `flutter_lints` 和 analyzer 为基线，四个包分别完成依赖解析后分析。strict casts / inference / raw types 可分批增加，不能用大范围 exclude 隐藏手写代码告警；历史问题采用有到期日的窄例外。[Dart 分析](https://dart.dev/tools/analysis)
2. CI 对手写 Dart 运行 `dart format --output=none --set-exit-if-changed`；生成与 vendor 代码按自己的来源流程处理。首次格式治理单独 PR，避免和逻辑修复混合。[Dart 格式工具](https://dart.dev/tools/dart-format)
3. Future 必须 await、返回给调用者，或显式 fire-and-forget 并说明错误接收方和生存期。业务 async 方法返回 `Future<void>`，避免让调用方无法等待的 async void。`unawaited` 只表达不等待，不负责取消、错误处理或资源接管。[unawaited_futures](https://dart.dev/tools/linter-rules/unawaited_futures)
4. 每个长操作记录 generation / job ID：启动重置旧状态，晚回包只能更新其所属 generation，旧事件不能覆盖新任务。取消区分“准备中 / 请求在途 / 已绑定 native job / 已终结”，重复 cancel / completion 必须幂等。
5. await 后使用 context 检查正确的 mounted；controller 还须检查它自己的生命周期和 generation。mounted 不是底层任务取消器。销毁时取消 timer / subscription，处理 native job、player、cache lease，必要时移交更长生命周期 owner。[跨 await 使用 context](https://dart.dev/tools/linter-rules/use_build_context_synchronously)
6. retry 不复用无法清空 nullable 字段的旧状态；为状态转换写测试，而不是只测最终 success。错误采用稳定分类（取消、权限、输入无效、资源不足、内部故障），用户消息与调试上下文分离。
7. `!`、`late` 并非一概禁止；使用处必须有可解释的不变量和失败处理，不能用断言掩盖跨平台 nullable 差异。[Dart 空安全](https://dart.dev/null-safety/understanding-null-safety)

## Kotlin 和跨平台原生边界

1. suspend API 对调用方保持 main-safe；磁盘、整文件复制、解码、模型预处理等阻塞工作选择明确 dispatcher / executor。dispatcher 可注入以便确定性测试，scope 与 project / job / plugin 生命周期匹配；不要为 Flutter 插件形式化引入无用的 Android ViewModel。[Android 协程实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
2. 不吞 `CancellationException`；在 finally / use 中释放资源，取消能贯穿排队、运行和回调。detach 后不得重复回复或回调已失效 messenger。
3. 为 codec、Bitmap、buffer、LiteRT runner、GL / Metal 资源记录唯一 owner、允许线程、借用期、close 次序。preview → export 交接必须在原生层 drain / lease / 取消并确认完成，不能只靠 Dart request ID 丢回包。
4. 文件/媒体创建采用事务式步骤：临时或 pending 目标 → 写入 → 校验 → 发布；失败/取消删除半成品，且不破坏源文件。保存至系统媒体库要考虑磁盘满、provider 失败和销毁阶段。
5. Pigeon / MethodChannel 契约写清线程、nullable、错误码、取消、版本和一次终结语义。通道是异步的，不表示原生 handler 的重活自动离开主线程；遵守平台通道线程约束，适用时用 Task Queue。[Flutter 平台通道](https://docs.flutter.dev/platform-integration/platform-channels)
6. Android CI 显式运行实际 variant 的 lint；已有 Kotlin compile / APK build 不替代 lint。[Android lint](https://developer.android.com/studio/write/lint) Kotlin 手写代码遵循官方 conventions；任何第三方 formatter 是项目选择，不称官方强制。[Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html)

## Swift 和 Apple 平台

- Swift 编译器版本不等于 target language mode。先记录 target、Swift mode、依赖能力，旧模式可先按 target 打开完整并发检查，逐项修复后再决定 Swift 6 迁移；不为了“消红”全局关闭隔离。[Swift 数据竞争迁移](https://github.com/swiftlang/swift-migration-guide/blob/main/Guide.docc/EnableDataRaceSafety.md)
- 明确 MainActor UI / plugin 回调边界、actor 隔离和 Sendable 传递。新增 `@unchecked Sendable` / `@preconcurrency` 必须解释同步不变量、依赖限制、测试和退出条件；第三方 / Objective-C callback 的能力要实测，不能以编译通过替代运行安全。[渐进迁移](https://github.com/swiftlang/swift-migration-guide/blob/main/Guide.docc/IncrementalAdoption.md)
- Task 取消是协作式的；非结构化任务由创建方保存、取消并等待/接管结果。与 Flutter 端共享 operation ID 和终态协议。[Swift concurrency](https://github.com/swiftlang/swift-book/blob/main/TSPL.docc/LanguageGuide/Concurrency.md)
- 保留既有 XCTest。工具链允许时，新 Swift 单测可用 Swift Testing，UI 测试继续用 XCTest；不为迁移测试框架改动稳定业务代码。[Xcode 测试](https://developer.apple.com/documentation/xcode/testing)
- required-reason API、权限文案、app / SDK manifest 归属和最终 archive 一起审查。对本次 File Timestamp 发现先核实 URL 所属容器及实际用途，再选择原因；不能只抄原因码。[Apple required-reason 声明](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)

## 隐私 缓存 模型与桌面边界

**缓存所有权。** 每类原始媒体、未打码人像缩略图、preview、analysis、导出中间文件登记 owner、目录、敏感级别、保留目的、TTL、配额、用户删除路径和崩溃清理方式。数值由产品用途和设备验证决定，不虚构官方统一天数。清理需要在用租约；“文件在 cache 目录”不是保留政策。

**日志与诊断。** release 默认只记录错误码、阶段、匿名 job ID 和必要数值。原始路径、URI、文件名、人像、音视频、token 不直接输出；显式诊断同样脱敏并标记保留期。日志采样和性能探针不得改变隐私选择、跟踪或渲染状态。

**模型发布。** 加载前验证批准摘要，下载先写临时文件并校验再原子替换；已缓存文件同样验证。禁止在声称离线的流程中默默下载；首次准备阶段提示网络需求。变更来源、模型字节、预处理、tensor layout、delegate、线程数均按算法变更处理，不仅看文件名或版本号。保留移动端已有 SHA 工具，并修复桌面 model_path 与实际 loader 不一致。

**算法质量。** 沿用 [双线流程](../privacy_performance_dual_line_workflow.md)：Shared 改动验证 Face 与 Full Body；path-only 豁免有调用图证据；比较使用 actual/effective accelerator 与 fallback 等完整 lane；相同设备同 lane 的 accepted golden 才是通常比较基线。更换 lane 的报告不直接成为性能晋级证据；CPU4T 身份拓扑等既有 exact invariant 继续有效。阈值以该流程和冻结基线为准。

**桌面服务。** local-only 默认 loopback；对外监听是明确 opt-in 的网络产品模式，必须有认证、上传/队列/磁盘限额、清理权限、数据保留和威胁模型。随机 task ID 不代替鉴权；局域网可达也不自动等同公网暴露。Mac / Windows 安装路径分别验证，不能把带 CUDA 源的 Linux lock 未经验证推广到全部平台。

## 依赖 工具链和供应链

- 固定并记录 Flutter / Dart、Python / uv、JDK / Gradle / AGP / Kotlin、Xcode / Swift、原生解析器与 Pigeon 版本。普通 CI 不追随 latest / stable 漂移；升级独立 PR，包含兼容性、锁 diff、双平台 gate 和回滚路径。[Flutter 升级](https://docs.flutter.dev/install/upgrade)
- app 提交 `pubspec.lock`，CI 在该应用解析路径使用 lock enforcement；普通可复用 package 的锁策略不机械照搬 app。Pigeon 生成器要有确定的解析版本/工具环境。[Dart 私有文件](https://dart.dev/tools/pub/private-files)、[pub get](https://dart.dev/tools/pub/cmd/pub-get)
- 原生依赖先确认正在用 CocoaPods 还是 SPM，再保存适用的应用解析锁；Gradle 锁定适用 configuration 的传递依赖，不能把源码版本范围当完整锁。[Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
- GitHub 当前提供 `.github/workflows/actions.lock` 和 `gh actions-lock` 生成流程，可锁 action 及传递引用；先验证工具/runner 支持，文件由工具生成，不手改。它不覆盖 `jobs.*.uses` 的 reusable workflows，后者仍用完整 SHA；不能采用 lockfile 的 action 使用经来源核验的完整 SHA。[Actions dependency lockfiles](https://docs.github.com/en/actions/concepts/security/workflow-dependency-lockfiles)
- Dependabot 或等效受控更新先按生态分小 PR，不启用无测试自动合并；支持版本更新不代表各生态安全更新能力相同，pub 当前标为社区维护。工具可用性和更新策略应先核实，不在本次审计安装配置。[GitHub 支持生态](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories)

## 分层 CI 设计

以下 job 名称是**拟议名称，不是现有工作流**。部署时先验证正常与失败样例，稳定后再纳入 required checks。

| 层级 | 触发与内容 | 合并或发布要求 |
| --- | --- | --- |
| 每个 PR 的快速层 | 变更分类、手写 Dart format、四包 analyze/test、Python 契约测试、Pigeon 再生成无差异、模型 manifest/hash 规则 | 固定名称汇总；不允许新增告警或生成漂移 |
| 受影响平台层 | Android native unit + lint + Release Kotlin compile + Debug APK；iOS 静态契约 + Metal + Phase 6 simulator smoke + main.dart no-codesign build | 路径匹配完整依赖图；shared/native/workflow 变更覆盖应受影响平台 |
| 隐私 模型与发布层 | 权限/manifest/archive、模型字节与预处理、取消/失败/恢复、磁盘与内存资源、实际媒体输出、Face / Full Body 对应设备 golden | 不用 mock 单测或静态 token 检查替代真实运行证据 |
| 里程碑层 | 按既有性能流程扩展设备/有效 accelerator 矩阵、长时导出、升级/恢复、baseline 晋级 | 由冻结基线与已有质量契约验收，不臆造统一覆盖率或性能百分比 |

拟议 `governance / required` 汇总 job 使用 always-run 语义，显式检查所有依赖结果；需要执行的 job 失败、取消或意外跳过都失败。只有变更分类明确认定无关的平台 job 才可标注“not applicable”，不能把任何 skipped 都当成功。required workflow 本身不使用会让其完全不产生 check 的 path skip；分类器变化也要测试。

iOS 依赖至少覆盖 app、全部相关 package（含 dance_ui）、`tools/ios/**`、实际调用和读取的 `tools/release/**`、模型/Metal 输入及契约所读文档。采用目录级保守覆盖再优化，比遗漏 phase4 / phase6 gate 更安全。保留现有 Phase 6 隐式调用直到替代入口验证通过，随后显式化调用图，避免重复构建和“本地静态、CI 动态”的意外差异。

### 已有入口和拟议验收命令

以下路径已在本次审计确认存在。除标明“已运行”外，是后续实施验收入口，**不是本次全部执行成功**。

```bash
# 已运行；前提为 app 现有依赖解析可用
cd mobile/app
flutter analyze --no-pub
flutter test --no-pub --reporter expanded
flutter test --no-pub ../packages/dance_native/test
```

```bash
# 已有 CI 命令；本次未在本机执行完整原生验证
# 从仓库根目录，先按现有流程完成依赖与模型准备
python tools/release/verify_export_media_contract.py
python -m unittest tools.release.test_verify_export_media_contract -q
python tools/release/verify_ios_phase0.py
python tools/release/verify_ios_phase1.py
python tools/release/verify_ios_phase4.py
python -m unittest tools.release.test_verify_ios_phase4 -q

# macOS + 完整 Xcode / simulator / Flutter 环境；会构建、运行并写构建产物
python tools/ios/run_phase6_macos_gate.py
(cd mobile/app && flutter build ios --debug --no-codesign --target lib/main.dart)

# Android：已有任务
(cd mobile/app/android && ./gradlew :dance_native:testDebugUnitTest :dance_native:compileReleaseKotlin)
(cd mobile/app && flutter build apk --debug)
```

新增 lint 的拟议入口为 `cd mobile/app/android && ./gradlew :dance_native:lintDebug :app:lintDebug`；采用前先用 Gradle task 列表确认实际模块和 variant，本次未运行。新增 format 检查须枚举手写文件；新增 Pigeon 单命令 wrapper、生成锁、CI 汇总 job **尚不存在**，不得把示例名称写成现成可运行脚本。四包的独立 analyze/test 必须先在各自正确依赖上下文中解析，不能把本次 URI 缺失当成待修产品 bug。

## 测试风险矩阵

| 变更风险 | 最低新增或保留证据 |
| --- | --- |
| 导出状态 / 路由 / Future | pending 时退出、成功/失败晚回包、立即重试取消、旧事件、重复启动、dispose 后无状态写入且无孤儿 job |
| 原生资源 / preview / export | 可控暂停制造交接窗口、并发 cancel/close、单次 callback、无已回收 Bitmap / buffer / shutdown 提交 |
| 文件和持久化 | 磁盘满、权限撤销、写一半失败、原子发布/回滚、重启恢复、未知 schema 拒绝、legacy 数据兼容 |
| 缓存和隐私 | 多次导入放弃后空间有界、重启扫孤儿、用户删除、在用不误删、日志脱敏、archive manifest |
| Pigeon | 两 schema 三语言一致、nullable/enum/error round-trip、取消与线程契约 |
| 模型 / tracking / renderer | 来源与 hash、预处理 tensor、质量 fixtures、Shared 两条隐私路径、同设备同 effective lane golden |
| 桌面网络 | loopback 默认、显式网络模式鉴权、未授权读/删被拒、上传限额和保留策略 |

测试 fixture 应合成或具备授权，公开 artifact 不含用户真实媒体。覆盖率、文件长度与 PR 行数可先观察趋势；不把 80% 覆盖率、500 行文件等经验数字伪装成官方强制门槛。

## PR 与仓库规则

单维护者也通过 PR 保存可检索的风险和验收记录。自审至少覆盖“功能正确”与“失败/取消/隐私”两个视角；多人参与时再对高风险域请求 owner 评审。CODEOWNERS 只是路由规则，本身不强制审批；是否必须批准由 ruleset / branch protection 决定。[Code owners](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)

建议由维护者批准并启用：主分支经 PR 合并、可信 required checks、禁止 force push / 删除、受控例外或 bypass。不要在只有一个可用维护者时要求不可满足的第二人审批；也不要为了方便永久跳过红灯。[GitHub rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)

PR 记录最少包含：

- 对应发现 / issue 和改动目的，明确未做事项。
- 影响平台、Shared / Face-only / Full-Body-only 与 effective lane。
- 失败用例、修复后命令/结果、日志或 run 链接。
- 数据/权限/缓存/模型/生成文件影响，迁移及回滚方式。
- 未关闭风险与例外 ID、owner、失效日、退出条件。

Actions 默认最小权限 `contents: read`，需要写入的 job 单独升权；不可信 PR 与签名/发布隔离，不以特权 `pull_request_target` / `workflow_run` checkout 并执行不可信代码，不把外部表达式直接拼入 shell。Woah 当前 API 默认 token 为 read 且不能批准 PR；问题不是已经默认 write，而是工作流应把边界显式化。云平台支持时再考虑 OIDC，它不自动代替所有商店签名凭据。[Actions 安全](https://docs.github.com/en/actions/reference/security/secure-use)

## 技术债和例外

[整改台账](remediation-plan.md) 是此轮事实来源。每项有角色 owner、依赖、验收、状态和关闭证据；完成后记录实际个人、commit / PR / run。例外至少写：规则 ID、原因、影响范围、补偿措施、负责人、批准人、明确失效日期、退出条件；到期未复核即失效。

当前没有批准任何例外，也没有任何整改因写入本文而自动关闭。规范调整本身走小 PR，说明哪条规则没有有效发现问题或成本不合适，再用证据修订。
