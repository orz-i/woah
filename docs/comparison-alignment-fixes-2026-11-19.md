# 舞蹈对比：自动音乐校准修复

## 行为

- 固定偏移和恒定变速仍使用共享 Rust 音乐特征，不使用舞者动作作时间依据。
- 自动应用前，以连续短窗口核验共同区间，包含首尾。存在明显的局部不同步时，不再依赖三个大窗口的平均结果。
- 近等速候选出现局部漂移时，重新拟合原乐段的速率，并再次核验；不靠缩短共同区间绕过失败片尾。
- 无法用单一偏移/速率解释的剪辑跳变返回 `inconsistent`，Flutter 显示提示并保留原校准。这里不新增分段时间映射能力。
- 重复乐段返回 `ambiguous` 时不修改项目，也不将次优候选提升为自动成功。用户进入候选试听，选择候选并分别播放参考/目标后，才可显式采用；保存为 `confirmed`。
- 试听关闭共享播放器，复用手动校准的两路独立预览，始终只播放一路声音。候选切换会重新定位并清除试听确认状态。保存失败可重试，取消不修改校准；释放预览后才重新打开共享播放器。
- 旧 `auto_review` 项目不会被删除或重写，但显示“待复核”，提供重新匹配入口，不再标作普通自动成功。

## 回归入口

```text
python tools/rust/run.py check
cd mobile/app
flutter test --no-pub
flutter analyze --no-pub lib test test_driver
cd android
gradlew :dance_native:testDebugUnitTest
```

新增 Rust 回归覆盖前段/中段/末段插入与删除、0.3%/0.5% 微小速率差、重复乐段和局部核验取消。Flutter 回归覆盖候选不自动保存、保留锚点、保存失败重试、跨项目候选失效、逐路试听/竞争操作、定位失败、两种小屏布局与两倍文字缩放、实际确认及预览释放顺序。

本修复未修改播放时钟、视频时间映射、渲染或导出管线。历史导出审计不算作修复后新导出；主机音频重放不等于手机原生解码或物理声画延迟测量。

## 本轮主机音频重放

使用冻结测试素材的 FFmpeg 解码 PCM，通过本轮构建的 Rust release C ABI 运行 31 项：7 项原矩阵、10 项小速率差、12 项剪辑插入、重复乐段与不同音乐各 1 项。无音轨检测属于原生适配器，本轮主机重放不计该项。

- 12 项中/末段 80–400 ms 插入在旧版中有 8 项误报 `matched`；修复后均为 `inconsistent` 或 `no_match`，不会自动写入项目。
- 约 18 秒真实音乐的 0.995 速率样例：最大端点映射误差从 46.460 ms 降至 4.856 ms；23 秒合成音乐的 1.003 样例从 37.000 ms 降至 3.421 ms。10 项速率样例均仍匹配，最大误差小于 35 ms。
- 原矩阵的 6 项有真值音乐匹配均保留，短片仍拒绝；重复乐段仍返回多个候选，不同音乐仍拒绝。

这些是两条已知音轨及其派生 PCM 的主机结果，不代表所有录音、长视频或手机声学输出均通过。

## 自动化结果与限制

Rust 114 项（含文档测试）、Android JVM 623 项、Flutter 应用 286 项、domain 28 项、Python 对比工具 32 项通过；Clippy 严格检查及 Flutter `lib test test_driver` 源码分析通过。Flutter 新增的 9 项测试包含横/竖小屏、两倍文字下的实际控件点击。Android 同时重新构建三种 ABI 的 Rust/JNI 库。

生产入口 `lib/main.dart` 的 Android Debug APK 已通过 `flutter build apk --debug --no-pub --target lib/main.dart` 构建，产物为 `mobile/app/build/app/outputs/flutter-apk/app-debug.apk`，未安装到设备，也未提交 APK 二进制。

一次开发中全量 Flutter 检查暴露试听队列空闲 Future 导致测试清理等待，已改为只保留进行中的任务，并完整重跑通过。无路径的 `flutter analyze` 还会扫描既有、未跟踪的 `build/audit3`、`build/premerge_audit`、`build/reaudit` 临时脚本并报告 16 项问题；未删除这些既有文件，以上源码分析不包含它们。

本轮未进行 Android/iOS 设备端运行、iOS 原生编译或新成片导出。没有连接可用真机，Windows 主机也无 Swift/iOS 构建环境；因此没有将主机/Widget 测试记作手机试听或物理声画验收。
