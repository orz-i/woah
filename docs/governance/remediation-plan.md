# Woah 整改台账

**状态更新（2026-10-31）：维护者已授权第一批本地修复。** GOV-01 的 Swift 编译、DART-01 / DART-02 及回归已实现；本地验收与待完成项见[验证记录](evidence/first-batch-local-verification-2026-10-31.md)。原审计基线见 [审计](audit-2026-10-31.md)，约束见 [工程规范草案](engineering-standard.md)。未创建 PR、push、修改仓库规则或发布版本，其余条目仍待采纳。

## 先做哪三件事

1. **恢复 iOS 红灯并收紧合并入口**：修复 GOV-01 的 Swift 编译问题，验证失败传播，再批准启用稳定 required checks。
2. **关闭两个已复现导出缺陷**：DART-01 / DART-02 用同一套 operation generation、启动/取消状态和资源接管契约修复。
3. **补原生与隐私生命周期闭环**：优先 NATIVE-01 / NATIVE-02 / PRIV-01，同时把 GEN-01、PRIV-02 的生成与发布证据接入门禁。

P0 是恢复项目可信基线的顺序，不是宣告生产事故。P1 是影响正确性、隐私边界或发布可靠性的下一批；P2 是可排期但需要可验证退出条件的偿债项。没有承诺虚构的工时或发布日期。

## 角色与状态

- **维护者**：采纳规则、PR 自审、权限与发布决策。
- **Flutter owner**：Dart 状态、路由、跨平台契约。
- **Android owner / iOS owner**：平台实现与设备证据。
- **CI owner**：构建图、依赖解析、required checks。
- **隐私与模型 owner / 桌面 owner**：数据生命周期、模型/服务边界与安装路径。
- 同一人可兼任多个角色；这些是待认领角色，不是已经指定的人名。高风险修改可邀请其他人评审，但不把第二位审批者作为当前无法满足的硬条件。

状态流：待采纳 → 已认领 → 实施中 → 待验收 → 已关闭。阻塞时记录依赖和解除条件，不用“暂缓”无限搁置。除第一批已授权的 GOV-01 编译子项、DART-01 / DART-02 外，下列条目仍为 **待采纳／未实施**；本地实现与测试不自动满足远端 CI、设备和发布退出条件。

## 优先队列

| ID | 阶段 | 建议负责人角色 | 依赖 | 完成证据摘要 |
| --- | --- | --- | --- | --- |
| GOV-01 | P0 | iOS owner + 维护者 | 无 | 当前提交两个 iOS 构建入口绿灯；失败示例被门禁阻止 |
| GOV-02 | P0 | CI owner | GOV-01 稳定后启用保护 | 常驻汇总、完整路径分类、analyze/format/lint 结果可追溯 |
| DART-01 | P1 | Flutter owner | 可与 DART-02 同一原子修复 | retry/cancel 不引用旧 job，不遗留新 job |
| DART-02 | P1 | Flutter owner | DART-01 状态契约 | dispose 后无状态写入，晚 native job 已取消或接管 |
| NATIVE-01 | P1 | Android owner + Flutter owner | 明确 job / resource owner | 确定性 preview/export 竞争测试和设备验证通过 |
| NATIVE-02 | P1，回滚子项 P2 | Android owner | 无 | I/O 不在主线程，失败/取消不留 MediaStore 半成品 |
| PRIV-01 | P1，配额子项 P2 | 隐私 owner + 两平台 owner | NATIVE-01 在用租约 | session 释放、重启孤儿清理、删除与配额测试 |
| PRIV-02 | 发布前 P1 | iOS owner | 最终 archive 构建可用 | API/原因/组件匹配，archive 中 manifest 可验证 |
| GEN-01 | P1 | Flutter owner + CI owner | DEP-01 生成工具版本 | 两 schema、三语言再生成无 diff，契约测试通过 |
| DEP-01 | P1 工具链，P2 安装收敛 | CI owner + 桌面 owner | 记录当前可用版本 | 锁解析稳定；Mac/Windows 安装不绕过权威策略 |
| DESKTOP-01 | P1 条件性 | 桌面 owner + 隐私 owner | 决定 local-only / 网络模式 | 默认 loopback；显式网络模式具有认证和限额测试 |
| MODEL-01 | P2，发布前关闭 | 模型 owner + 桌面 owner | DEP-01 安装路径 | 配置路径有效、load 前摘要验证、离线行为可解释 |
| PRIV-03 | P2 | Flutter owner + 隐私 owner | 定义错误码/日志字段 | release / 诊断包不含路径、URI、文件名或媒体 |
| ARCH-01 | P2 | Flutter owner | DART / NATIVE 关键回归先固定 | 按职责小步拆分，行为与测试不退化 |
| DOC-01 | P2 | 维护者 + 桌面 owner | DEP-01、DESKTOP-01 决策 | 干净环境按 README 能执行正确入口与安装步骤 |

每项均须在关闭时补上 **实际负责人、PR / commit、测试命令、结果和证据链接**。未有这些字段时仍为打开；文档和未来计划不是关闭证据。

## 第一批执行状态

- **DART-01 / DART-02：本地实现与回归通过，待发布验收。** 由本次授权修复任务实施，19 项新生命周期回归、全部 256 项 app 测试、5 项 native Dart DTO 测试及 analyze 通过。修复与回归本地提交为 `e71929574bed03ac3cffe07a67935bfa9aca9756`，未 push / 创建 PR；证据及持续清理失败的限制见[验证记录](evidence/first-batch-local-verification-2026-10-31.md)。
- **GOV-01：本地编译修复验收通过，待远端 CI 验收。** 两个 iOS 构建入口、两个 SDK 的 Metal 编译和完整 Phase 6 gate（仅隔离设备选择适配）全部通过，详见验证记录。本机 Xcode 27.2 与工作流声明版本不同；不把远端旧 SHA 的红灯描述为已转绿。
- **GOV-02 与仓库保护：未实施、未启用。** 本轮没有授权发布或仓库规则变更。

## P0 恢复可信基线

### GOV-01 修复当前 iOS 编译并重新确认合并条件

- 范围：只先修 `IOSComparisonMusicFeatures.swift` 的复杂表达式推导，显式中间值和类型；保持计算语义。
- 验收：现有 `python tools/ios/run_phase6_macos_gate.py` 通过，包括 simulator smoke；`mobile/app` 下 `flutter build ios --debug --no-codesign --target lib/main.dart` 通过。工具环境和模型输入与 CI 一致。
- 保留证据：当前失败 run、修复 commit、两个构建结果、相关计算单测；不得以仅静态 phase verifier 通过宣布恢复。
- 退出条件：新提交 iOS 与 Production CI 都成功，再进入 GOV-02 规则启用步骤。
- 设置变更需要维护者明确批准；本次未替用户修改 ruleset。

### GOV-02 使所有 PR 都有可信汇总结果

- 范围：加入手写 Dart format、四包 analyze、显式 Android lint；保留已有 tests/native compile/build；把 iOS gate 调用图显式化并修补路径依赖。
- 现有 phase1 在 macOS GitHub 环境会调用 phase6，重构时不得误删它所提供的 Metal / simulator / smoke 证据。
- 验收样例：只改 dance_ui、tools/ios runner、phase4 verifier、模型/Metal 输入时，应运行相应 iOS gate；纯无关文档变更可明确 not applicable，但汇总仍报告。
- 故障样例：分析失败、平台失败、取消、分类器失败、意外 skipped 均不能让汇总为绿。不要用 workflow 级 paths 跳过必需汇总。
- 先记录稳定 job 名和结果，再启用 required checks / 防 force push；单维护者用 PR 自审 + 自动 gate，不设不可满足的审批人数。
- 待新增 wrapper / 汇总 job 尚不存在；见规范的[命令入口](engineering-standard.md#已有入口和拟议验收命令)，不要把拟议名字当作现成脚本。

## P1 正确性与生命周期

### DART-01 和 DART-02 导出状态机的统一修复

- 先重放 [隔离回归](evidence/README.md)，基线预期 3 个失败断言；将必要测试移入正式测试目录应是后续修复 PR，不是本次文档提交。
- 每次启动有新 generation，并清空旧 job/output/error/terminal；新 RPC 未绑定时的 cancel 要有明确语义。
- 在所有 await / event 边界验证 owner 和 generation；晚成功的 native job 必须取消或移交，不能只丢回包。
- 验收至少覆盖：失败后立即 retry/cancel、两次启动交错、旧事件晚到、启动前/中/后返回页面、RPC 成功与失败晚到、重复 cancel/terminal。
- 退出条件：隔离失败变通过，正式 237 测试基线及新增回归通过；真机确认没有后台孤儿导出。若真机证据未完成，状态仍为待验收。

### NATIVE-01 Preview 与 export 资源交接

- 定义 segmenter / runner / buffer / Bitmap 的 owner 和借用期；在原生边界设置 drain / lease / 取消确认，不只禁 Dart 按钮。
- 以 latch / controllable executor 暂停 preview 的 buffer 写入或提交窗口，再开始 export/clear/close，验证提交、释放和 callback 次序。
- 验收：无 recycled Bitmap、已关闭 buffer、shutdown 后任务提交、重复完成或未释放资源；设备上快速改效果→导出→取消→重试不崩溃。
- 注意保持同 executor 已有的 inference/close 序列化，不能误把本发现当作需要重写全部 GPU 执行模型。

### NATIVE-02 相册写入与失败回滚

- 整文件 I/O 放到明确的 worker，结果回到正确平台线程；取消、detach 和异常只终结一次。
- 插入后 copy/close/发布任何步骤失败，删除本次新建条目；源文件不受影响。
- 验收：大视频/慢盘下 UI 响应与 StrictMode 证据；磁盘满、读失败、权限失败、取消的故障注入；媒体库无新残留 pending / 空文件。
- 未实测 ANR，不以“修复 ANR 已发生事故”描述此项。

### PRIV-01 缓存的结束释放和空间边界

- 为 analysis thumbnails、trim thumbnails、preview、项目缓存和导出临时文件分别定义 owner、TTL、配额、删除入口、在用 lease。
- 复用现有 releaseProject、失败回滚、LRU、comparison TTL，补 app session 完成/放弃/替换/删除调用链。
- 验收：连续至少 100 次合成素材导入后放弃，资源在定义上限内；重启能清除过期孤儿；用户删除生效；正在预览或导出的文件不被清理。
- “100 次”是本项目建议的压力用例，不是官方通用阈值；产品保留时间与空间配额要明确批准并写入测试。

### PRIV-02 iOS required-reason 与打包证据

- 核对 File Timestamp 调用目标 URL、用途及代码所属组件；适用容器内时间戳用途再选择 C617.1，外部文档场景另核原因。
- 修改真正拥有调用代码且会进入产物的 manifest，验证 app / SDK 归属；不以宿主声明替代 SDK 自身责任。
- 验收：最终 archive 中对应 bundle 含正确 manifest，API/原因清单与实际使用匹配；记录 archive、检查命令和报告。
- 这是发布前核验项，不是已确认商店拒审。只有源码 XML 改好而未检查产物时不能关闭。

### GEN-01 Pigeon 再生成的一致性

- 固定生成器解析环境，建立一个维护者可运行的统一入口，覆盖两 schema 的 Dart / Kotlin / Swift 六份输出。
- 生成器 wrapper 与 lock 策略是待实现内容；不手写更改 generated 文件代替 schema 修复。
- 验收：干净 checkout 连续生成两次均无非预期变化；CI 再生成后 `git diff --exit-code`；故意改 schema 不提交产物时检查失败。
- 增补 DTO、nullable、错误、取消和线程契约回归。当前没有证据证明产物已漂移，验收针对可复现性缺口。

### DEP-01 工具链与依赖解析

- 第一批统一两条 CI 的 Flutter/Dart、固定 uv 与 Action 供应链策略；记录 Gradle/JDK/Xcode 的实际版本。
- 评估当前支持的 actions.lock 工具链；能使用时由官方工具生成；不能时完整 SHA pin。reusable workflow 仍单独完整 SHA。
- 核实 iOS 正在使用的解析器和应用锁策略，Gradle 锁定适用配置；不能凭 Podfile.lock 忽略规则就认定当前 SPM 构建一定读取它。
- 桌面先按 Mac/Windows 选择并验证权威安装路径，处理根 CUDA source 约束，再让 legacy requirements 由统一策略导出或停止误用。
- 验收：全新环境重复解析无非预期 diff；独立升级 PR 包含锁差异和平台测试；普通 CI 不再跟随 latest 漂移。

### DESKTOP-01 明确本地与网络服务模式

- 本地用途默认监听 loopback；对外监听必须由使用者显式开启，不能只在文档显示 localhost。
- 网络模式明确认证、授权清理/读取、上传和队列/磁盘限额及保留规则。
- 验收：默认启动不绑定非 loopback；网络模式未经授权不能分析/读取/删除他人任务，配置限额生效；测试使用合成数据，不暴露真实媒体。
- 原审计没有运行服务或进行攻击；仅针对允许其他主机连接时的边界风险整改。

## P2 可持续维护

### MODEL-01 桌面模型来源与加载一致性

- 配置的 model_path 必须实际决定加载路径，或显式废弃并迁移，不能静默改走 vendor 权重。
- 下载 staging + 批准 SHA-256 + 原子发布 + 加载前校验；保留已有 MD5 检查事实但不以它代替新的 artifact 契约。
- 验收：配置自定义已批准模型确实加载该文件；损坏/错误摘要不加载；首次下载中断无半成品被使用；无网络时错误明确；来源、版本与权重许可单独登记。
- code/vendor license notices 继续保留；没有证据时不宣称任何权重违法。

### PRIV-03 敏感日志

- 统一错误码、脱敏字段和 release / 显式诊断策略，覆盖嵌套 error 对象，不只是修改一处字符串。
- 验收：合成敏感 path/URI/文件名注入后，release 日志与诊断包中都不可检出；可定位错误阶段但不泄漏媒体标识。

### ARCH-01 按职责拆分编辑页

- 在关键异步竞争回归完成后，以 session coordinator、player、trim、store、presentation 为可选边界，每个 PR 只迁移一个明确职责。
- 先用测试固定行为再移动；不因文件行数机械切类，不把 UI callback 搬到新文件就宣称架构问题解决。
- 验收：接口更小、资源 owner 明确、测试不回退、无新增跨层依赖；不强制换状态库或全量重写。

### DOC-01 修复真正可运行的入门路径

- 分清 desktop CLI / Web、Mac / Windows、移动构建前提；去掉不存在的 desktop/app.py / 根 requirements.txt，写入经过验证的入口。
- 验收：干净或隔离环境逐条执行文档；依赖、模型准备、端口/监听范围、启动和退出均可复现。
- 不在平台锁与服务模式未决定时草率把所有平台替换成同一条 uv sync。

## 例外与关闭记录

当前没有已批准例外。任何绕过须新增记录：

```text
例外 ID：
关联规则或发现：
影响范围与理由：
风险与补偿措施：
实际负责人：
批准人：
失效日期（明确 YYYY-MM-DD，不写“以后”）：
退出条件与复核证据：
```

不得用永久关闭 analyzer、忽略整个目录、跳过失败 iOS 或无依据刷新 golden 来“完成整改”。到期例外必须复核；关闭条目保留历史失败证据和修复后的 commit / run，防止以后重新引入。
