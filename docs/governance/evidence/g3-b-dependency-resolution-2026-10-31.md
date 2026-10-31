# G3-B：实际依赖解析锁

范围仅 DEP-01 第二个／最后切片。基线为 G3-A 合并 `c19a2d1a80ec03e853a6a20720bd247ececc45df`（PR #14，head run 41195720474、merge run 41196945128 均成功）。不升级业务依赖、模型或桌面环境。

## 真实解析链和固定边界

- Flutter 3.44.2 / Dart 3.12.2：app、三个仓内验证包及 native/example（既有 analyzer 覆盖）各自提交当前 `pubspec.lock`，CI 先 `flutter pub get --enforce-lockfile --no-example`，分析／测试不隐式重新解析。这三个内部包并非对外发布库；不把应用锁策略机械推广到所有公共 package。
- iOS 当前仍走 CocoaPods（1.17.0，本地及成功 merge run 41196945128 的 `pod --version` 均确认），提交 app `Podfile.lock`，含 TensorFlowLiteSwift 2.17.0。`resolve_ios_dependencies.py` 固定解析器版本、先严格 pub get、再 `pod install --deployment`、检查 lock 字节未变。Phase 3–6 与两个 iOS workflow 复用它。现有生成的本地 Swift package 没有远程包依赖，不制造无意义的 `Package.resolved`。
- Android 维持 Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.3.20；wrapper 增加官方 distribution SHA-256。app 与 dance_native 的 Debug/Release compile/runtime、Debug 单测 compile/runtime 共六类 configuration 使用 STRICT 依赖锁；第三方 pub plugin 源码不修改，lint/buildscript 插件解析不冒充已经由这两份锁覆盖。
- ABI 选择会改变 Flutter native engine 图成员，因此仅六个明确 ABI 模块由当前 SDK `engine.stamp` 检查精确版本；embedding 仍在 Gradle 锁内。Kotlin `kotlin-stdlib-common` 为 2.3.20 的 metadata-only shim，AGP compile/runtime 一致性约束使它的图成员随入口变化；仅该模块以解析后精确 2.3.20 检查替代锁成员检查。实际 stdlib/jdk 二进制仍锁定。不是 `io.flutter:*` 或整个 Kotlin 的宽泛忽略。后续 SDK/Kotlin/AGP 变更必须复核这一有限设计，不将它当版本更新通道。
- 根 `uv.lock` 保持字节不变，`uv lock --check --offline` 通过；Linux CI 继续 `uv sync/run --locked`。Mac 不执行面向现有 Linux CUDA 索引的根环境同步。共享 Python/model 工具、权重、来源与摘要验证均保留。

## 已执行的本地验证

1. `check_dependency_locks.py --fresh-dart`：临时小目录仅复制五组 pubspec/lock，保留相对包布局；复用已有下载缓存，连续两次 offline/enforce 解析，所有锁字节不变；故意移除 app 锁必须失败。临时目录无源码／大模型／SDK 副本，退出自动删除。
2. Gradle 两模块 `verifyWoahDependencyLocks` 普通多 ABI 和 `-Ptarget-platform=android-arm64` 分别离线通过；显式检测 unresolved result，不能把解析图中的失败节点当成功。强制不存在的离线 common 版本 2.3.10 的负向检查失败，未声称已覆盖成功解析后的版本断言分支。
3. CocoaPods 1.17.0 `pod install --deployment` 成功，Pod 锁不变。这里使用现有 Pods 下载缓存；新的 CI checkout 负责干净工作目录的实际双平台构建，不把本地缓存验证称完全无缓存验证。
4. 四包 analyze、303 Flutter tests、545 Android native tests、Release Kotlin compile、两模块 lint（保留原 89 warnings）、Debug APK、37 CI Python tests、10 原契约回归、iOS phase0–6/cloud 静态检查通过。实际 iOS full gate 由本 PR 精确 head 的 CI 验收，不以静态检查代替运行。
5. 首轮 PR run 41198744489 的 Linux analyze 发现 native/example 缺 package config：此前默认 pub get 会隐式解析 example，本地缓存掩盖了 `--no-example` 的影响。本切片显式追踪已有 example lock 并严格解析，继续检查它，不 exclude example、不降门槛。fresh 小目录检查同步覆盖这条真实解析支路。
6. APK SHA-256：`cadb56573b3e7d016145e76de2fa77d8ee7f949685ec94ca7639d05eba389034`。本地日志 `/tmp/woah-g3-b`；PR 完成记录追加独立审查、exact-head 与 merge CI，不用基线绿灯代替当前提交。

## 使用与受控更新

常规验证：先按固定工具环境解析四包及 native/example，再 `python tools/ci/check_dependency_locks.py --fresh-dart`；Android `./gradlew :app:verifyWoahDependencyLocks :dance_native:verifyWoahDependencyLocks`；iOS 从仓库根运行 `python tools/ci/resolve_ios_dependencies.py`。最终 `python tools/ci/check_dependency_locks.py --check-tracked` 拒绝锁漂移。

有意升级需独立评审，先更新适用声明，再通过同一 Gradle task 的 `--write-locks` 生成两份锁、审查全部传递 diff 并运行实际多 ABI／单 ABI 验证；不要手改生成锁或使用宽泛解锁。Pub/Pods/uv 同样只在受控更新中生成新锁，普通 CI 不自动 update。

磁盘：本轮复用共享 SDK/pub/Gradle/Pods 缓存，不建立第二套下载环境。验证进程终结后，仅删除本轮 `mobile/app/build/{app/intermediates,app/tmp,dance_native/intermediates,dance_native/tmp,test_cache,unit_test_assets}`，空闲空间由 17GiB 恢复至 19GiB；最终 APK/app、报告、日志和共享缓存保留，APK 清理前后 SHA 相同。锁确保选定版本／Pub 摘要和 Gradle 分发校验，不等于所有 Maven/Pod 下载内容验证、所有插件传递 Action 或 hosted OS 镜像均完全冻结。

## 官方依据

- https://dart.dev/tools/pub/cmd/pub-get
- https://docs.gradle.org/current/userguide/dependency_locking.html
- https://guides.cocoapods.org/terminal/commands.html
- https://guides.cocoapods.org/using/pod-install-vs-update.html
- https://services.gradle.org/distributions/gradle-9.1.0-all.zip.sha256
- https://docs.astral.sh/uv/concepts/projects/sync/
