# 第一批本地修复与验收（2026-10-31）

范围仅 GOV-01 的 Swift 编译、DART-01 / DART-02 导出生命周期及回归。基线 `0aef51eab4ef8a8a65f29e83883b87ca1de77e46`，本地分支 `fix/export-lifecycle-ios-build-20261031`；修复与回归提交 `e71929574bed03ac3cffe07a67935bfa9aca9756`。未 push、开 PR、触发远端 CI、启用仓库保护或实施其他审计整改。

## 实现

- Swift FFT 功率数组、单频带求和、总功率与均值显式使用 Double 中间值；不改变计算顺序、窗口、阈值或归一化算法。
- 每次导出由独立 operation 对象作为 generation / 资源 owner，包含 starting、running、cancelling、terminal 阶段。重试构造新 state，清除旧 job ID、输出、错误、预览、计数和 plan，保留用户预览偏好。
- 能力查询尚未提交 native job 时取消阻止提交；提交后取消等待真正的 job ID 再取消，不能错误取消上一次 job。重复启动忽略，重复取消共享同一 Future。
- native 早于 RPC 回包的事件按 job ID 暂存；只重放匹配事件，终态锁存，旧 job / 旧 operation 不修改新状态。取消进行中仍维持原有“成功取消优先，失败取消重放已收到终态”语义。
- dispose 取消订阅；pending start owner 继续接收晚 ID 并取消，不读写失效 state。已确认两个 native coordinator 的取消信号可重复调用，故 dispose 清理仅对同一 ID 重试一次。
- 同步 StateNotifier listener 可在发布 plan 或 job ID 时取消 / dispose；发布后的第二次检查和统一事件路由防止重入绕开保护。

**明确限制：**取消 RPC 成功表示 native 接受取消信号，不证明 encoder / 文件资源已经全部 drain。若平台通道连续两次拒绝 dispose 清理，job ID 仅在短生命周期 operation 与日志中保留，不是持久 registry 或可恢复接管；不能保证 native job 已停止。此失败路径有测试，不被标成成功，也没有无限后台重试。若 native start RPC 永不返回 ID，取消 Future 将保持等待；当前 API 没有可取消的 request ID，因此不能承诺断联下及时取消或持久恢复。UI 继续等待取消结果、失败时留在当前页的既有测试仍保留。原审计 NATIVE-01 / NATIVE-02 仍打开。

## 可重放回归

`mobile/app/test/features/export_lifecycle_test.dart` 将审计的三个失败断言升级为受控 Completer / fake-native owner 测试，共 19 项，覆盖：

- failed → retry 首个 await 前清旧 job；pending retry cancel 仅针对新 ID；
- completed 重试清输出、计数、plan，保留预览偏好；
- capabilities 等待时 cancel / dispose，以及旧 capability 回包遇到新 generation；
- native start 等待时 dispose、晚成功 / 失败和真实 autoDispose provider 最后一个 listener 离开；
- dispose 取消瞬态失败后同 ID 重试、持续失败被真实暴露；
- 重复 start / cancel；早到终态、无关 job、旧 job / 晚进度忽略；
- pending-start cancel 失败恢复真正 job、重试取消；
- cancel 失败重放已延迟终态，dispose 与在途 cancel 共用 Future / 订阅只取消一次；
- plan 发布时 listener cancel / dispose，以及 job ID 发布时取消和已缓冲 terminal 的竞争。

原始 `export-lifecycle-repro.dart.txt` 保留字节不变并按原 README 复制到 `/tmp/woah-fix-original-audit-test.dart` 重跑（增加 `--timeout 5s` 避免等待环）：**2 PASS / 1 timeout，exit1**。第一项旧测试在返回新 ID 前直接 await cancel，无法满足“等待 ID 才确认取消成功”的新契约；其余两个原断言直接通过。

临时副本 `/tmp/woah-fix-ordered-audit-test.dart` **只改以下 await 顺序，所有三个原 expect 不改**，运行结果 **3 PASS / exit0**。实际取消目标 `[second-job]`，最终 job ID 为 null、状态 cancelled；原证据文件未修改。正式 regression suite 还检查 fake-native activeJobs 为空。

```dart
// 旧：await controller.cancelExport(); 然后才 complete('second-job')。
final cancellation = controller.cancelExport();
repository.startCompleter!.complete('second-job');
await retry;
await cancellation;
```


`tools/ios/test_comparison_music_features.swift` 是无需 Flutter / 用户媒体的独立 Swift 回归：合成静音、440Hz 单音 FFT 主峰、frame 时间戳 / 数量、有限输出和 NaN 输入拒绝。

## 本地命令与结果

工具：Flutter 3.44.2 / Dart 3.12.2；本机 Xcode **27.2 (27B52)**，不同于工作流声明的 `macos-26 / Xcode 26`。所有 Apple 命令仅设置进程级 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`，不改全局 xcode-select。

| 检查 | 结果 |
| --- | --- |
| `cd mobile/app && flutter analyze --no-pub` | PASS |
| `cd mobile/app && flutter test --no-pub --reporter expanded` | PASS：256 |
| `cd mobile/app && flutter test --no-pub ../packages/dance_native/test --reporter expanded` | PASS：5 |
| `dart format` 两个变更 Dart 文件、`git diff --check` | PASS |
| `xcrun swiftc mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSComparisonMusicFeatures.swift tools/ios/test_comparison_music_features.swift -o /tmp/woah-comparison-music-regression && /tmp/woah-comparison-music-regression` | PASS，`IOS_COMPARISON_MUSIC_FEATURES_REGRESSION=PASS` |
| export media contract verifier + 4 Python unit tests，iOS phase0–6 verifier + phase4 6 unit tests | PASS |
| `flutter build ios --simulator --debug --no-pub --target lib/ios_phase6_smoke_main.dart` | PASS |
| `flutter build ios --debug --no-codesign --no-pub --target lib/main.dart` | PASS |
| `python3 tools/ios/run_phase6_macos_gate.py` 首次原样运行 | FAIL exit1：缺 Metal Toolchain，门禁正确传播失败；没有视为编译修复失败或跳过此检查 |
| 安装后完整 Phase 6 gate / isolated simulator smoke | **PASS / exit0**：两个 SDK Metal 离线编译、phase3 Metal、phase4 export、phase5 golden trace、phase6 privacy class 全部通过；`IOS_PHASE6_MACOS_GATE=PASS`。仅按下述适配固定隔离设备 |
| 工具链更新后、最终行为源码的 production main no-codesign build 复验 | **PASS / exit0**，69.5 秒 |

首次 `--no-pub` 构建曾因本地生成 SwiftPackage 依赖为空而报 `file_picker_darwin` 缺失；`flutter pub get --offline` 使用既有缓存恢复，不升级依赖锁。Flutter / CocoaPods 对 project.pbxproj 产生的 18 行删除（移除自动生成的 Embed Pods Frameworks phase）已用精确预期内容与初始 HEAD 比较，仅还原本轮生成差异，不混入修复提交。后续标准 Flutter 构建会按本机 CocoaPods 依赖再次生成该差异；原构建入口和功能配置保持基线不变。依赖锁、原门禁脚本无 diff。

## 工具链与设备边界

用户单独授权安装 Xcode 27.2 的 Metal Toolchain 与匹配 CoreSimulator，不增加 runtime、不重置设备、不清应用数据。license check 已通过，未接受新条款或输入管理员密码。

Metal Toolchain 27B47 经 Apple `xcodebuild -downloadComponent MetalToolchain` 下载 838.9 MB，exit0；实际 `xcrun -sdk iphoneos metal --version` 为 Apple metal 32023.922，两 SDK 离线编译通过。没有下载额外 iOS runtime。

CoreSimulator 从 1174.9.2 变为 1174.21。首次 `-runFirstLaunch` 返回 75 并报告需要授权；随后的只读 `-checkFirstLaunchStatus` 返回 0，普通 `xcrun simctl` 可用。保留这两个观察，不把中间返回值隐去或据此重复安装。

新建独立 `Woah Fix Gate 20261031` simulator（既有 iOS 27.0 runtime），UDID `0B2BE7A5-56C3-4FCB-A5C4-1A86D830CCCD`，仅在此设备安装 smoke app。原 Booted iPhone 18 Pro 在组件更新后变为 Shutdown，随后已恢复 boot；不触碰原设备应用数据。第一次旧组件 smoke 因工具链更新而主动中止，仅得到 Metal phase3 marker，不计为 smoke 通过。最终 gate 通过后专用设备已 shutdown，并仅删除本次创建的该设备；再次确认原 iPhone 18 Pro 仍 Booted。其他设备和应用数据未删除。

## 完整门禁的隔离设备适配

可复现适配器源码：[run-isolated-phase6-gate.py.txt](run-isolated-phase6-gate.py.txt)。它 import 仓库原 gate；Metal 编译、Flutter 构建、phase verifier 均原样运行，仅把最后 smoke 子进程导向同一适配器的 `--smoke` 模式，import 原 smoke 并把 `choose_iphones()` 固定为明确指定且名为 `Woah Fix Gate ...` 的设备。原四类 PASS marker、异常和非零退出传播不变；不替换 xcrun，不删测试，不修改仓库 gate 文件。

原脚本 Git blob：`tools/ios/run_phase6_macos_gate.py` = `c404b0d9f53da4841b2d6327df45fb73990c521a`；`tools/ios/run_phase4_simulator_smoke.py` = `9598fcb50a1d82fac9cae025ef42069e7ebd5de7`。

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
export PATH=/Users/mini/Coding/flutter-3.44.2/bin:$PATH
export RUNNER_TEMP=/tmp/woah-fix-full-gate
export WOAH_REPO=/Users/mini/Coding/woah
# 本次 UDID 已在验收后清理。重放时用已安装 runtime 创建自己的隔离设备：
export WOAH_GATE_UDID="$(xcrun simctl create 'Woah Fix Gate Replay' com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro com.apple.CoreSimulator.SimRuntime.iOS-27-0)"
python3 docs/governance/evidence/run-isolated-phase6-gate.py.txt
```

这属于“同 gate 检查，隔离设备选择适配”，不是字节完全不改地执行原 wrapper。原 wrapper 的自动候选回退会 shutdown 其他现有设备，所以不在有 Memora 运行的机器上直接完成该路径。

## 发布状态

本地检查不等于远端 CI 已绿。远端 master 仍指向旧提交，原失败 run 没有被本轮操作替换；GOV-01 的远端验收与 GOV-02 仓库规则启用均未完成。未做实体 iPhone / Android 资源竞争、商店签名、release archive 或用户真实媒体测试。
