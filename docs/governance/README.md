# Woah 项目治理

本目录把 2026 年 10 月 31 日的实际审计、工程规范草案和整改验收放在一起。结论是：**先恢复失败的 iOS 构建并建立可信合并门禁，再修复已复现的导出异步缺陷及原生资源、隐私生命周期问题；不要先做全量架构重写。**

## 当前状态

- 审计基线：`master@0aef51eab4ef8a8a65f29e83883b87ca1de77e46`，仓库 [orz-i/woah](https://github.com/orz-i/woah)。
- 第一批 [PR #7](https://github.com/orz-i/woah/pull/7) 的最终 head `96a7287` 已通过 [Production CI](https://github.com/orz-i/woah/actions/runs/41163397751) 与 [iOS Cloud CI](https://github.com/orz-i/woah/actions/runs/41163397758)。维护者授权后于 2026-10-31 合并为 `747721d1c073170cbae4a3940778ca16e81ac07d`。两项导出生命周期修复的真机／持续清理失败边界仍见验证记录。
- 第二批正在实施 GOV-02：每个 PR 保守运行双平台、四包 analyze、手写 Dart format、显式 Android lint 和 fail-closed 汇总；[第二批验证记录](evidence/second-batch-ci-governance-2026-10-31.md) 区分本地、远端与未完成项。仓库保护规则尚未启用。
- [第一批本地验证记录](evidence/first-batch-local-verification-2026-10-31.md) 记录通过项、原始审计测试的等待契约差异、工具链更新、隔离设备适配及尚未关闭的原生清理限制。
- **规范是待维护者采纳的提案，不代表已执行或已启用。** 整改状态见下表所链接的台账；审计时 iOS CI 仍失败。
- 证据日期与官方资料检索日期均为 **2026-10-31 UTC**，不代表所有官方页面都在当天发布。后续提交改变了代码时，应重新验证对应结论。

## 阅读顺序

| 文档 | 用途 |
| --- | --- |
| [实际审计](audit-2026-10-31.md) | 快照、实际测试、复现与静态风险，明确未验证的边界 |
| [工程规范草案](engineering-standard.md) | 将审计发现映射成可执行的开发、评审、CI 和发布规则 |
| [整改台账](remediation-plan.md) | 优先级、负责人角色、依赖、验收标准和退出条件 |
| [第一批本地验收](evidence/first-batch-local-verification-2026-10-31.md) | 修复提交、19 项生命周期回归、iOS 同 gate 隔离验证与发布边界 |
| [第二批 CI 治理](evidence/second-batch-ci-governance-2026-10-31.md) | 常驻汇总、双平台调用链、format/analyze/lint 与验收边界 |

优先级中的 P0 是本项目建议的**立即恢复合并可信度**阶段，不等同于已发生线上重大事故。台账负责人均是角色建议，不是已分派给某个人的承诺。

## 最先推进的三项

1. 修复当前 iOS 编译红灯，验证 required checks 能阻止失败合并。
2. 修复 retry/cancel 与 dispose 晚回包两项已复现导出缺陷。
3. 补齐原生资源交接、缓存释放和主线程 I/O 的生命周期约束。

## 应保留的基础

Woah 已有 `app / dance_domain / dance_native / dance_ui` 分层、纯 Dart 领域模型、Flutter 测试、Android 原生单测与编译、iOS 分阶段契约和 smoke gate、模型哈希校验，以及 [Face 与 Full Body 双线性能流程](../privacy_performance_dual_line_workflow.md)。这些是治理基础，而不是要替换掉的历史包袱。

尤其保留既有 Shared / Face-only / Full-Body-only 分类、实际硬件执行通道、同设备同通道 golden 和里程碑设备门禁。不要用“测试绿了”“配置了 GPU”或静态字符串契约替代隐私正确性与真机证据。

## 采纳方式

1. 维护者先确认整改范围和角色归属。
2. 每项修复独立成可回滚 PR，附对应发现 ID、失败到通过的证据与风险测试。
3. CI 自身稳定后，再由有权限的维护者批准启用 required checks / ruleset；文档不能代替设置。
4. 例外必须有原因、风险、补偿措施、负责人和明确失效日期；没有永久豁免。
5. 完成一项后更新台账证据，不把“写了规范”当作“债务已清零”。
