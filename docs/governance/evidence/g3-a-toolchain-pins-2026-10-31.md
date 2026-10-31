# G3-A：直接 CI 工具输入固定

范围仅 DEP-01 第一个切片，不升级运行栈、不改变 gate／权限／模型字节。基线 master `85f21cb0248785b5cc8c923912426ae9c2f8b1e5`；版本取自成功 [run 41193985249](https://github.com/orz-i/woah/actions/runs/41193985249)。

- Flutter 3.44.2 保持；Linux Python 3.11.17、macOS Python 3.11.9 分别固定原成功版本，不强行统一平台。
- uv 0.13.6、Temurin 17.0.21+9 取自成功日志；普通 `uv run` 改为 `--locked`，不允许隐式改锁。
- 三 workflow 的直接 Actions 固定完整 SHA。checkout/setup-python/setup-uv/setup-java/flutter/upload 来自基线日志；BrowserStack 专用 cache/download 的 v4 引用经官方仓库 ref API 核验。保留原主版本和逻辑，不触发 BrowserStack、使用其凭据或新增权限。
- 5 个新增 Python 回归验证直接 Action 非浮动、各平台工具版本及 locked uv；既有质量门槛保持。

## 明确边界

官方 actions.lock 入门页当前注明 GitHub Enterprise Cloud。本仓库未确认该资格，因此采用完整 SHA fallback，不安装扩展，不手写 actions.lock。固定外层 Action 不固定其 composite 内部引用；Flutter Action 内部仍为 `actions/cache@v5`，托管 runner 镜像也会更新。本 PR 不宣称传递闭包、OS 镜像或全环境字节完全固定，不通过 vendoring 第三方工具扩展治理范围。

https://docs.github.com/en/actions/how-tos/secure-your-work/lock-workflow-dependencies
https://docs.github.com/en/actions/concepts/security/workflow-dependency-lockfiles
https://docs.astral.sh/uv/concepts/projects/sync/

本地 31 项 CI Python 回归及 iOS phase0–6、cloud 静态检查通过；旧 BrowserStack verifier 的 `download-artifact@v4` 字面断言调整为完整 SHA 形式，并增加移除下载步骤的负向测试。此次未改业务代码，不为固定 workflow 再复制大型本地构建；独立审查及 exact-head、merge 双平台完整 CI 证据在 PR 完成记录追加，不能用基线成功代替本提交成功。无需模型重导出或本地复制大型环境；保留现有缓存和最终产物。
