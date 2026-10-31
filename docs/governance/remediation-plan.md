# Woah 整改台账：15 项唯一归属

**范围版本：2026-10-31，按[冻结路线图 G1–G3](governance-roadmap.md)执行。** 桌面 / Web 已废弃，不再有桌面修复阶段；ARCH-01 为长期待办。P0/P1/P2 是原审计优先级，不再充当没有停止条件的滚动阶段。

原始发现和当时的证据保留在[实际审计](audit-2026-10-31.md)。本台账是状态来源；阶段卡、预算、依赖、非目标、验收／停止条件和范围变更流程只在路线图维护，避免重复出两套计划。

## 状态语义

- **已交付**：实现／文档已经合并，列出实际验证范围；不自动表示设备／发布／服务器设置也完成。
- **待验收**：明确的退出证据未齐，必须写缺口，不能用“测试绿”覆盖。
- **未实施**：未来固定阶段条目，不能因写入规范自动关闭。
- **产品退出**：不再属于支持产品，历史缺陷未修复；不是认可继续带风险运行。
- **长期待办**：不占用本轮实施预算，不自动开工。

关闭发现时仍需负责人／执行角色、PR／commit／run、实际命令／设备、结果、残留及复核依据。所有角色可由同一维护者兼任，不设置当前无法满足的第二人审批要求。

## 唯一映射

| ID | 唯一归属 | 当前状态与责任角色 | 验收／关闭边界 |
| --- | --- | --- | --- |
| GOV-01 | G1 | 编译子项已交付；服务器保护按 G1-EX-01 本轮明确延期。iOS owner／维护者 | PR7 两个 iOS 构建入口及最终远端成功；保护未启用，不能称失败合并已被服务器禁止 |
| GOV-02 | G1 | PR8 已合并，PR head 远端三检查成功；merge 证据按 G1 收尾记录。CI owner | 每个 PR 保守双平台、format/analyze/lint、always 汇总；失败／取消／意外 skipped 拒绝。无需为了完成本项再增加路径分类器 |
| DART-01 | G1 | PR7 已交付，真实资源回收边界待验收。Flutter owner | retry/cancel 使用当前 operation/job；持久原生调用失败或 start RPC 永不返回 ID 的限制保留，不虚称所有设备零孤儿 |
| DART-02 | G1 | PR7 已交付，真机与持续失败边界待验收。Flutter owner | dispose 后不更新失效 state，晚 job 接管／取消回归已过；原生 drain 完成不等同取消请求已接收 |
| NATIVE-01 | G2-A | [PR10](https://github.com/orz-i/woah/pull/10) 软件切片已合并，PR／merge CI 成功；Android 真机待验收。Android owner／Flutter owner | [交接证据](evidence/g2-a-native-resource-handoff-2026-10-31.md)：15 项确定性回归、进程级 owner／drain、Bitmap 锁内复制；没有以 JVM 通过代替真机；不是重写全部 GPU 模型 |
| NATIVE-02 | G2-B | [PR11](https://github.com/orz-i/woah/pull/11) 已合并，head／merge CI 全绿；实际 provider 验收待设备。Android owner | [相册事务证据](evidence/g2-b-gallery-save-2026-10-31.md)：25 JVM 回归，worker I/O、单次回包、本次 URI 回滚、源文件不变；真实 provider／强杀／回滚拒绝边界保留，不声称已发生过 ANR |
| PRIV-01 | G2-C | 软件已合并（PR #12 head／merge CI 成功），设备矩阵缺口按 [G2-C 证据](evidence/g2-c-regenerable-cache-2026-10-31.md) 记录。隐私／两平台 owner | 明确 session owner、晚回包隔离、真实 analysis／preview／export lease；只清可再生白名单，24h／256MiB 为 inactive 内部软策略；最终导出／源媒体等排除，不清整个 cache |
| PRIV-03 | G2-D | PR13 已合并且 head／merge CI 成功，剩余边界按 [G2-D 证据](evidence/g2-d-diagnostic-privacy-2026-10-31.md)。Flutter／隐私 owner | 日志／错误／诊断文本及文件名出口脱敏，保留安全 stage/code；既有 debug raw 质量媒体非匿名，不宣称全部诊断内容匿名；硬件缺口保留 |
| DEP-01 | G3，两个切片 | G3-A/B（PR14/15）已合并且 head／merge CI 成功；验收按 [依赖证据](evidence/g3-b-dependency-resolution-2026-10-31.md) 与 PR 完成记录。CI owner | 固定直接工具、五 Pub（含既有 example 验证）／Pod／两 Gradle／uv 真实链；ABI／metadata 精确版本守卫及 hosted OS／传递 Action 边界明确，不建设桌面安装路径或大升级 |
| GEN-01 | G3-C | PR16 已合并且 head／merge CI 成功；验收见 [生成证据](evidence/g3-c-pigeon-generation-2026-10-31.md) 与 PR 完成记录。Flutter／CI owner | 两 schema 六产物独立双生成，缺产物／合法 schema 漂移失败；首次历史格式差异已独立审查，不改变 wire 契约 |
| PRIV-02 | G3-D 最后切片 | 原因声明／实际 archive 门禁已实现，产物及最终验收见 [归档证据](evidence/g3-d-privacy-archive-2026-10-31.md) 与本 PR 完成记录。iOS owner | 自有容器 FileTimestamp/C617.1、root／native 各自声明；unsigned archive 材料不等于正式签名／商店或真机验收 |
| DESKTOP-01 | G1 产品退出记录 | 桌面产品废弃，退出修复范围。维护者 | 历史监听／鉴权问题未修复；没有证明所有旧部署下线。不得继续以受支持服务宣传 |
| MODEL-01 | G1 产品退出记录 | 桌面 loader 废弃，退出交付验收。维护者 | 桌面 model_path／下载完整性问题未修复；移动模型来源／摘要工具仍保留在 DEP-01 范围 |
| DOC-01 | G1 唯一文档 PR | 替换为废弃标记与撤部署宣传。维护者 | README 不再引导桌面安装／启动；desktop 历史标记、共享工具保留边界和未验证部署状态清楚。不修复桌面入门体验 |
| ARCH-01 | 长期待办 | 未实施，默认不启动。Flutter owner | 只有具体维护痛点和新范围批准后才选一个职责切片；不以文件行数决定全量重构 |

G1 合并／文档的工程交付完成，不将本表的“待验收／明确延期”状态自动改为已关闭。G2/G3 预算分别最多 4 个 PR，禁止通过滚动“小批次”无限延长。

## 已交付证据

### PR7：编译与导出生命周期

- [PR #7](https://github.com/orz-i/woah/pull/7)，合并 `747721d1c073170cbae4a3940778ca16e81ac07d`。
- 最终 head `96a7287` 的 [Production CI](https://github.com/orz-i/woah/actions/runs/41163397751) 与 [iOS CI](https://github.com/orz-i/woah/actions/runs/41163397758) 成功。
- [第一批本地证据](evidence/first-batch-local-verification-2026-10-31.md)：19 新生命周期回归、256 app tests、5 native Dart DTO tests；本地两个 iOS 入口／Metal／完整 Phase6 验证，以及工具链与隔离设备说明。
- 持续取消失败／RPC 不返回 ID 的边界、真机原生资源清理仍需针对性证据。原始审计复现文本保持不变，不假称原始等待契约的测试未经调整全部通过。

### PR8：可信汇总与新增检查

- [PR #8](https://github.com/orz-i/woah/pull/8)，2026-10-31 正常合并为 `c38a142732c47044084e0836a391523b7c4bc5d3`；未提交 approving review、未使用管理员绕过。
- PR head `dad7f377e4650405dd764e3ed04b76950986bbc4` 的真实 PR 事件 [run 41169895229](https://github.com/orz-i/woah/actions/runs/41169895229) 中 Android／质量检查、完整 iOS、`governance / required` 全部成功。
- 合并提交的 [push CI](https://github.com/orz-i/woah/actions/runs/41175865394) 另行验证，不把 PR head 的成功等同于 merge SHA 成功。
- [第二批本地证据](evidence/second-batch-ci-governance-2026-10-31.md)：107 手写文件 format、四包 analyze、289 Flutter tests、490 native tests、14 Python CI 回归、10 原契约回归、Release Kotlin、两模块 lint 及 APK。PR 正文追加最终远端证据，文件保留提交时快照。
- native 70／app 19 warnings 已列账，未清零；app Release lint、Pigeon 再生成、依赖完整锁、真机特殊音轨／API／provider 矩阵不因此完成。

## 尚待明确的决策与非阻断工作

1. **仓库保护：** 维护者明确本轮不启用，待本轮结束再评估；按路线图 G1-EX-01 记录延期，未应用、不重复询问，不阻止 G1 常规收口。
2. **G2 缓存策略已有限冻结：** PR12 按路线图固定再生白名单的 24h／256MiB inactive 软策略，真实 lease／在用保护、最终输出及源媒体排除；不改变持久项目恢复或把内部默认扩成产品保留承诺。后续方向变化仍需另行决定。
3. **设备／正式产物：** 缺硬件、签名或最终 archive 时标未运行／待验收，集中报告最小解除条件；不得伪称全平台验收或用无关实现填补。
4. **架构与桌面：** 无本轮额外实施额度。重新启动必须先变更范围，不能作为 G2/G3 的“顺手优化”。

## 例外与停止记录

**G1-EX-01 已获维护者明确决定：仓库保护本轮不启用，G1–G3 本轮结束或提前终止时复评，不自动延续。** 详见路线图的原因／风险／补偿／责任与复评触发。没有承诺日历截止日期，不虚构日期；它不是红灯绕过、永久 ignore、golden 放宽或已生效保护。桌面产品退出也不是运行安全例外。

其他新增实施性例外需记录：关联发现、确切范围与原因、风险、补偿、负责人、批准依据、明确失效日期、退出／复核证据。缺这些条件不得实施；到期必须复核。新增独立发现按路线图范围变更流程处理，不自动扩大当前阶段。
