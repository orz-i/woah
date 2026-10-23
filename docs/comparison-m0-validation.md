# Woah · M0 双视频音乐同步播放验证（Android Debug）

**状态：验证工具；不属于生产功能。** 本阶段采用恒定音乐时间偏移（手动输入），不能自动识别共同歌曲、不导出视频，也不对动作打分。

## 隔离边界

- 从干净 `master` 派生独立 `feat/dance-comparison-m0` 工作树。
- 新增 Debug manifest + Debug Activity，`androidx.media3:media3-exoplayer:1.9.2` 仅在 Debug 编译引入；Release 不包含此 Activity 与探针。
- 原匿名化导出、隐私追踪、Flutter 首页、iOS 均不改动。
- `ComparisonTimeMapping` 为小型生产可复用时间契约，单位有符号 microseconds。正偏移表示同一音乐事件在「自己」视频中出现得更晚。
- 截至 2026-10 Media3 已有更高稳定版本，但当前锁定的 `video_player_android 2.12.2` 仍使用 Media3 1.9.2（ExoPlayer/HLS/DASH/RTSP 等）。M0 优先 **模块版本一致**，不因探针强行更新整套播放器。待设备实测后另开专门依赖升级审计。

## 生成带真值的两份视频

需要 FFmpeg（带 `libx264`、`drawtext`）、`ffprobe` 和 Python 3：

```sh
python3 tools/comparison/generate_m0_fixtures.py
```

输出（不进入 Git）：

```text
testdata/comparison/generated/teacher.mp4
testdata/comparison/generated/learner.mp4
testdata/comparison/generated/manifest.json
```

默认老师的视频从 2s 位置出现音乐，自己从 5s 出现完全相同的伴奏，因此期望偏移 **+3,000,000µs**。两源视频从 0s 开始，印有自身帧号及源 PTS；请勿把同一音乐的开始时间当作视频 0s。

可用 `--fps 24/30/60`、`--size 1280x720` 和 `--offset-ms -1000` 生成测试变体；负偏移须保证 `teacher-lead-ms + offset-ms >= 0`。每次生成可检查 `manifest.json` 里的 sha256、实际流编码和预期偏移。

## 构建与打开

现有 Woah 应用 Debug 编译（不需要发布签名）：

```sh
cd mobile/app
flutter pub get
flutter build apk --debug
```

Android SDK 的 `adb` 连上真机后，回到仓库根目录：

```sh
python3 tools/comparison/run_m0_android.py \
  --apk mobile/app/build/app/outputs/flutter-apk/app-debug.apk \
  --teacher testdata/comparison/generated/teacher.mp4 \
  --learner testdata/comparison/generated/learner.mp4 \
  --offset-us 3000000
```

脚本将测试素材放进设备的 `Movies/WoahM0`，启动仅在 Debug 存在的 Activity。**需要你在系统文件选择器中手动选一次老师/自己两段视频**。也可以不传两个素材参数，用手机相册选择真实的视频（不会自动上传原始媒体）。

不需要安装脚本也可以：

```sh
adb shell am start -n art.gaoge.dance/art.gaoge.dance.engine.comparison.ComparisonM0Activity \
  --el offset_us 3000000
```

## M0 设备实验矩阵

使用原生按钮而非 Flutter 定时器同步两侧，轮流检查：

1. 选 30fps 同帧率真值对，在 **1× / 0.75× / 0.5×** 各播放一段，再暂停。音乐只播放老师轨；自己轨应静音。
2. 进 10 秒、退 10 秒、拖动到 0%/50%/90%，每次观察两侧是否全部就绪后同时运行。
3. 用偏移 **±10ms / ±100ms** 校准，确保老师/自己的时间映射符号正确，动作先后差异没有被人为消除。
4. 真实舞蹈：不同拍摄角度、手机收录外放、不同来源、重复副歌、不同帧率。观察缓冲、黑帧、静音和错配；没有共同音乐的情况本轮手工校准。
5. 锁屏/后台/旋转/重复打开、关闭与换源；无崩溃、ANR 或持续 codec 泄漏。

每次播放完，点击 **保存诊断**，再执行：

```sh
python3 tools/comparison/run_m0_android.py --pull-report m0-latest.json
```

报告默认在 app-specific external storage 的 `Documents/comparison-m0/latest.json`。不导出原始音轨、视频、媒体 URI；不上传任何数据。重跑保存诊断会覆盖 `latest.json`，建议每组实验 pull 后用不同文件名存档。

下载 JSON 后执行独立校验（默认 30fps，只检查计划帧元数据）：

```sh
python3 tools/comparison/evaluate_m0_report.py m0-latest.json \
  --manifest testdata/comparison/generated/manifest.json --fps 30
```

即使输出 `PLANNED_METADATA_WITHIN_TARGET`，真实屏幕显示仍为 `NOT_PROVEN`。

## 正确解读报告

- `p95AbsMediaErrorUs` 来自 **两路即将渲染帧** 的源 PTS，按 Media3 的 **计划** releaseTimeNs 配对，并减去预期音乐偏移。它**不是**物理屏幕实际呈现时间，也不代表音频匹配算法的误差。
- `p95PlannedReleaseSkewUs` 是两路计划 release time 之间的差值，不是表面像素的显示时间差。样本少时不得宣称验收成功。
- 报告自动剔除进入探针的首约 1 秒，并在拖动、变速、修改偏移时重开观测 epoch。它不能跨不同操作拼接成一组假稳态结果。
- 对 M0 的真实显示验收，必须同时收集带时间码的设备屏幕录制；录屏可能掉帧，有疑问时补高帧率外部拍屏。报告还需附上设备型号、Android 版本、视频来源和运行速度。
- 初始工程目标：30fps CFR 稳态 P95 ≤1帧、最大 ≤2帧，无累积漂移；这只是一条 **待实测** 判据。不同 fps / VFR 按各自源帧 PTS 报告，不套用统一 33.4ms。

若双 ExoPlayer 在设备验证中无法达到真实可见同步门槛，不要靠更密集的 seek 勉强通过；进入双 MediaCodec + 单 GL 合成方案的技术评估。

## 当前 Mac Pi 的构建限制

本轮 `flutter doctor -v` 已确认 Mac Pi 上 Flutter/Dart 可用，但 **Android SDK、JDK、adb 均未安装或不可发现**，没有可连接的 Android 真机。因此不能在这里证明 `compileDebugKotlin`、APK 或双视频真正播放成功；不应把本地脚本测试当成 Android 构建通过。

用户现有 Windows Android 构建环境可复用下面的命令（不要执行无关的全量 Release QA）：

```powershell
cd mobile/app
flutter pub get
cd android
.\gradlew.bat :dance_native:testDebugUnitTest
.\gradlew.bat :dance_native:compileDebugKotlin
.\gradlew.bat :dance_native:compileReleaseKotlin
cd ..
flutter build apk --debug
```

只在上述编译门槛通过、并取得真机时间码证据后，才决定双 ExoPlayer 是否可作为正式后端。

## 自动化与未通过的门槛

Android SDK/JDK 有效的环境中：

```sh
cd mobile/app/android
./gradlew :dance_native:testDebugUnitTest
./gradlew :dance_native:compileDebugKotlin
./gradlew :dance_native:compileReleaseKotlin
```

单元测试覆盖偏移正负号、非零源有效范围、无公共区间、跨 epoch 丢弃旧帧、按计划显示时间配对。这些只能验证代码逻辑，**不代替真机 codec 或屏幕像素验证**。

当前 M0 完成标准：Android Debug 构建通过；真机有完整播放/seek/变速/后台/多次打开证据；计划帧元数据与屏幕证据支持或拒绝双 ExoPlayer；正式选定 M1/M2 后端。真实视频自动音频匹配、导出和 iOS 都不属于 M0。
