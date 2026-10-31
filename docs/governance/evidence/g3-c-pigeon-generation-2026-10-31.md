# G3-C：Pigeon 两 schema 六产物一致性

GEN-01 唯一切片，基线 G3-B 合并 `7a487c815002d56c6d0e7d2e93a83130b5dfce76`；PR15 最终 head run 41200200940 与 merge run 41201399584 均三检查成功。生成器维持已解析 Pigeon **29.0.4**，将 pubspec 的范围收紧到此精确版本，不更新锁或依赖。

## 单一入口

从仓库根执行：

```sh
# 前置：固定 Flutter 3.44.2；native pub get --enforce-lockfile --no-example
python tools/ci/pigeon_codegen.py --self-test  # CI 默认只检查，不写源码
python tools/ci/pigeon_codegen.py --write     # 有意修改 schema 后重新生成
```

两套 `pigeons/{dance_api,comparison_api}.dart` 是唯一源。入口从 native 已严格解析的 package config 定位 Pigeon 并核对版本，直接执行该生成器，不隐式 pub get／下载。两次生成各用独立临时根，只允许 schema 配置对应的 Dart、Kotlin、Swift 六路径；对比两次结果和仓库内容，任何缺失／漂移失败。`--write` 也先确认两次一致，只写六路径，不对生成代码另跑 formatter。

真实生成器负向用例在临时 schema 副本中为 ComparisonSourceDto 增加一个合法 nullable 字段，成功生成后确认旧产物比较失败；另验证缺一产物失败。不能将 schema 语法错误误当作漂移测试。临时目录自动清理，不复制源码树、模型或 SDK。

## 首次差异与审查

首次检查正确失败：六产物中只有 `comparison_api.g.dart` 与原生成输出不一致，其他五份逐字节相同。独立逐块复核及临时副本规范化证明是历史格式化差异；两副本用同一 Dart formatter 后 SHA-256 同为 `463d7f4ce29c17d3f27313a61e93d985ee86d4dc7b0d4a2277457933d64dc0a7`。codec 编号、DTO 字段／参数顺序、通道名、nullable、Swift 比较前缀均不改变。

因此仅通过生成器恢复这一个文件的原生输出，没有手改或为消红更新协议。原生生成器在 comparison Dart 两行保留尾空格，`git diff --check` 因此提示；不手工清理、不新增全局 ignore，手写 diff 检查与生成逐字节门槛分别执行。临时规范化仅用于证明，不纳入生成流程，不格式化仓库六产物。

## 验收与边界

- 真实双次生成、合法 schema 漂移及缺输出负向通过；再运行只检查入口通过。
- 七项新增 Python 单测覆盖输出边界、缺失／漂移、版本和必需 CI 接线；本地四包 analyze、303 Flutter tests、44 CI Python tests、10 原契约、phase0–6/cloud 静态检查与手写 format 全部通过。远端结果在 PR 完成记录追加。
- 原有四包 analyze/test、手写 format、完整双平台 CI 不替换／不降低；Kotlin／Swift 产物未变，不为此在低磁盘 Mac 重复大型 native 构建，实际平台重新编译由 exact-head CI 验收。
- 开工15GiB空闲；生成临时根自动删除，短期差异/规范化证据仅约数百KiB。共享缓存、模型、最终产物及真实媒体不删除。本轮测试结束后只清 `mobile/app/build/{test_cache,unit_test_assets}`，最终仍15GiB；保留约540KiB日志／差异证据。
- Pigeon 同一版本生成通信两端；其格式／wire 兼容未来变更仍需独立升级评审。本次不新增 API、改变取消契约或扩大架构治理。

官方版本依据：https://pub.dev/packages/pigeon/versions/29.0.4
