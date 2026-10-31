# 导出控制器的隔离复现证据

本目录保存原始审计证据及[第一批本地修复验收](first-batch-local-verification-2026-10-31.md)，不是生产测试目录。原始审计适用基线为 `master@0aef51eab4ef8a8a65f29e83883b87ca1de77e46`，关联 [DART-01 与 DART-02](../audit-2026-10-31.md#dart-01-重试启动窗口取消旧任务)。

[export-lifecycle-repro.dart.txt](export-lifecycle-repro.dart.txt) 从已有 export test 的 fake repository 和 project fixture 裁出，只增加三个审计断言。所有视频路径、job ID 和 diagnostic path 都是测试假值；不读取或上传用户视频，不调用真实原生导出。保留 `.dart.txt` 扩展名是为了明确它不自动成为应用测试门禁。

## 本次结果

2026-10-31，Flutter 3.44.2 / Dart 3.12.2，app 的已有 package resolution：

| 断言 | 基线实际结果 |
| --- | --- |
| retry cancellation must not target previous failed job | 取消目标为旧 `preview-toggle-job`；新 RPC 返回后 state 的 job 为 `second-job`，状态仍 cancelled |
| dispose during startExport must not access disposed state | Future 失败：`Tried to use ExportController after dispose`，控制器 139 行及 catch 146 行 |
| retry must not retain previous job id while launching | 启动窗口仍保留旧 `preview-toggle-job`，而非 null |

**0 passed / 3 failed，exit 1，预期就是失败；没有编译错误。** 这些断言证明控制器行为，不证明真实 native job 一定持续运行，也不推断所有 release 构建会以同样方式崩溃。正式修复还须覆盖资源接管、晚事件、重复启动及设备行为。

## 如何重放

从仓库根目录执行，确保选用 Flutter 3.44.2 且 `mobile/app` 的依赖已经正确解析。以下只把文本证据复制到临时目录，不改源码或生产测试：

```bash
repo_root="$PWD"
evidence_dir="$(mktemp -d)"
cp "$repo_root/docs/governance/evidence/export-lifecycle-repro.dart.txt" \
  "$evidence_dir/export_lifecycle_repro_test.dart"
(
  cd "$repo_root/mobile/app"
  flutter test --no-pub --reporter expanded \
    "$evidence_dir/export_lifecycle_repro_test.dart"
)
```

首次机器没有 app 依赖缓存时，`--no-pub` 不能自行解决依赖；先按经维护者确认的安装/锁策略准备环境，再执行。不要把依赖 URI 缺失当成本复现的产品故障。

审计实际执行的等价命令为从 `mobile/app` 用已安装的 Flutter 3.44.2 运行 `/tmp/woah_audit_export_minimal_test.dart`。机器临时日志 `/tmp/woah-audit-minimal.log` 记录 `AUDIT_MINIMAL_EXIT=1`；上面的可移植命令保留相同测试逻辑。

修复 PR 可将精简且符合项目测试风格的用例迁入正式 test 目录，补上更多状态/资源断言，并记录由失败到通过的证据。原审计阶段没有这样迁移；第一批修复已新增正式生命周期测试，详见[本地验收](first-batch-local-verification-2026-10-31.md)。原始文本仍保留不变，没有让 CI 永久接受失败测试。
