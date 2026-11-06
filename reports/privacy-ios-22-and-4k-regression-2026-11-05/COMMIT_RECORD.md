# 分段提交与集成记录（2026-11-06）

从远端主分支`67df0b9b`建立干净分支`codex/ios-privacy-22-4k-merge-20261106`，按原生隐私实现、测试与审计、回归报告三组提交。未引入旧分支的身份研究历史、实验入口或UI/品牌改动。

- 原生实现：`08bd5ef24c09869a7719e423cb26834c1b727c1e`。
- 测试与审计：`342636ebeb2a5bcb2e873d7f39d8fac0a5d47a17`。
- 本记录随第三组报告提交；最终合并状态以GitHub PR为准。

[原生源码、资源与QA入口绑定](integration-binding.json)与此前完成49项、48条成片、17155帧的版本逐字一致，13项定点回归也使用该版本。模型按原SHA提供；二进制资源不提交Git。主机Golden入口整理为`tools/ios/host_regression/run_golden.sh`，保留9个场景及3个预期失败封闭反例。

[集成检查](integration-validation.json)通过：75项Python CI测试、4项音频审计测试、iOS Phase0–6静态检查、4组Swift主机回归、Flutter分析与格式检查，以及App272项和其他包33项测试。原报告中的App277项属于验证时完整工作树，其中未纳入本PR的UI/logging变更及其测试已排除。

README、source-state及原始运行元数据保留验证时分支和“尚未提交”状态作为历史上下文。完整视频、轨迹、日志及原始补丁继续留在Mac原工作区。原补丁基于当时的`186113fe`，本次审查使用新分支的PR差异。三类保守多遮观察和模拟器验证边界均保留。
