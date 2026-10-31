# Woah 项目治理

本轮只治理 **Android / iOS 与仍使用的构建／模型工具链**。桌面 / Web 已废弃，历史源码和许可证保留；不再进行桌面产品修复。目标是有限、可验收的 G1–G3，不是持续扩张的“下一批”。

## 当前状态

- 原始审计基线：`master@0aef51eab4ef8a8a65f29e83883b87ca1de77e46`，见[审计快照](audit-2026-10-31.md)。保留当时事实，不重写为今天状态。
- [PR #7](https://github.com/orz-i/woah/pull/7) 已合并：Swift 编译与两个导出生命周期修复；真机／持续平台调用失败边界仍有待验收项。
- [PR #8](https://github.com/orz-i/woah/pull/8) 已合并：固定 Flutter、format、四包分析、显式 Android lint、完整双平台及 fail-closed 汇总。PR exact head 的[远端检查](https://github.com/orz-i/woah/actions/runs/41169895229)全部成功；合并提交[另行复验](https://github.com/orz-i/woah/actions/runs/41175865394)。
- G1 预算仅既有 #7／#8 加一个文档 PR；G2 最多 4 个实现 PR，G3 最多 4 个。ARCH-01 是长期待办，原桌面修复阶段取消。
- **仓库保护规则本轮明确不启用。** 维护者决定本轮结束再评估，已记为 G1-EX-01；不重复询问、不应用、不作为 G1 阻断，也不宣称服务器强制门禁已生效。
- 89 条既有 lint warnings 不转成清零目标，不自动升级依赖或重写架构。

## 阅读顺序

| 文档 | 唯一用途 |
| --- | --- |
| [冻结治理路线图](governance-roadmap.md) | G1–G3 阶段卡、预算、进入／退出／停止条件、审批和范围变更 |
| [15 项整改台账](remediation-plan.md) | 每个发现的唯一归属、当前状态、已交付证据与未关闭边界 |
| [实际审计](audit-2026-10-31.md) | 2026-10-31 原始测试、复现与静态风险快照 |
| [工程规范](engineering-standard.md) | 稳定开发原则；不是自动实施所有建议的授权 |
| [第一批证据](evidence/first-batch-local-verification-2026-10-31.md) | 编译／生命周期修复、本地验收与限制 |
| [第二批证据](evidence/second-batch-ci-governance-2026-10-31.md) | CI 调用链、format/analyze/lint 与本地快照；最终远端记录见 PR8 |
| [桌面过期说明](../../desktop/README.md) | 取消部署宣传、保留历史与共享工具、旧实例未验证边界 |

## 执行原则

- 当前阶段只有一个在制实现 PR，可并行只读调查和独立审查。常规提交／push／正常 PR 收口不反复询问；方向、预算、产品策略或安全敏感事项单独确认。
- 达到冻结验收就停止并报告，不把新发现、代码行数、工具新版本或历史 warning 自动加入当前阶段。
- Shared / Face-only / Full-Body-only、实际 effective lane、同设备 golden 和既有[双线流程](../privacy_performance_dual_line_workflow.md)继续保留；不以 mock／静态绿灯替代真实平台或设备证据。
- 产品退出、工程已交付、设备待验收、服务器保护明确延期是不同状态，不混为“全部整改完成”。

官方资料检索与审计日期为 2026-10-31 UTC，不代表每个官方页面当天发布。后续代码改变时需针对性复验，不无限扩大阶段。
