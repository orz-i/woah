# 最终证据收集与复核

这些脚本用于本次固定源码、固定矩阵的验收归档，不属于应用运行代码。读取既有真实测试与审计产物，不会启动或替代设备测试，不会自动生成任何人工判断。

先通过仓库 `tools/privacy/run_regression.py --suite all` 完成原始测试，再以 `audit_regression.py`、`audit_known_faces.py` 生成审计，实际查看首中尾、原生预览、已知漏遮位置及每个自动候选。人工记录必须绑定图片索引的原始字节哈希；不要改变自动原始失败或待复核状态。

每个批次复核完成后运行：

```text
python collect-final-evidence.py REPOSITORY REPORT_ROOT DESTINATION --platform android --suite legacy22
```

平台可选 `android`、`ios`；批次为 `legacy22`、`original4k`、`focused13`。原始 `REPORT_ROOT` 位于各主工作区的 `reports/rust-core-migration-2026-11-06/final-v3/PLATFORM`，`DESTINATION` 为本目录上一级的 `PLATFORM`。原始视频留在报告归档，以哈希关联；归档包含原始 JSON 和人工查看的图片。

两端各三组归档完整后运行：

```text
python aggregate-final-evidence.py REPOSITORY
```

校验完整的 62 + 62 用例、相同 8 项素材、相同模型字节、同一编译源码和平台内同一安装包、27 项同进程取消重试、实际解码帧数及所有人工记录的图片哈希。缺失或不匹配时拒绝写出 `acceptance-summary.json`。本目录 `.gitattributes` 保留 JSON 原始换行，避免 Windows/macOS 拉取改变证据哈希。JSON 的空白检查保留行尾空格、文件尾空行及缩进规则，并明确将 CRLF 中的 CR 视为换行。

媒体审计中的成功取消与实际输出视频分别统计。`needs_topology_review` 保留原始状态；必须逐候选人工查看源图、输出局部及上下文，并绑定完整原始审计、候选记录、素材、视频及图片哈希，才能记录语义复核完成。其他帧数、PTS、颜色及隐私探针失败仍拒绝验收。

## v3 额外风险点复核

本轮编译源码为 `81082a70b4d1e332dff15387dc1b77f88d4549d0`，每端预览为 186 对。此前确认的顶部局部人脸漏遮必须通过实际视频及 7400/8120ms 原生预览复核。

`review-focused.py` 生成每个用例的首中尾和全部预览；`review-face-candidates.py` 生成全部原始自动候选；两者都不会自动接受结果。

所有 11 种完整 4K 非纯色模式还须查看此前源人脸扫描漏检的 556–563 帧：

```text
python review-known-effects.py RUN_DIRECTORY SOURCES_DIRECTORY KNOWN_FACE_SPEC OUTPUT_DIRECTORY --expected-source 81082a70b4d1e332dff15387dc1b77f88d4549d0
```

每端生成 88 对局部源图/实际视频图，需另存绑定索引哈希的人工判断，不增加或替换 62 项矩阵用例。`audit-final-sequence.py` 仅等待真实运行完成后生成独立审计和复核图，不会写出整体验收通过。
