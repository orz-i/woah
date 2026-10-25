# Woah · M0 双视频音乐同步播放验证（Android Debug）

**状态：验证工具；不属于生产功能。** 本阶段采用恒定音乐时间偏移（手动输入），不能自动识别共同歌曲、不导出视频，也不对动作打分。

## 隔离边界

- 从干净 `master` 派生独立 `feat/dance-comparison-m0` 工作树。
- 新增 Debug manifest + Debug Activity，`androidx.media3:media3-exoplayer:1.9.2` 仅在 Debug 编译引入；Release 不包含此 Activity 与探针。
- 原匿名化导出、隐私追踪、Flutter 首页、iOS 均不改动。
- `ComparisonTimeMapping` 为小型生产可复用时间契约，单位有符号 microseconds。正偏移表示同一音乐事件在「自己」视频中出现得更晚。
- 截至 2026-10 Media3 已有更高稳定版本，但当前锁定的 `video_player_android 2.12.2` 仍使用 Media3 1.9.2（ExoPlayer/HLS/DASH/RTSP 等）。M0 优先 **模块版本一致**，不因探针强行更新整套播放器。待设备实测后另开专门依赖升级审计。

## 生成带真值的两份视频

需要 FFmpeg（带 `libx264`、AAC 编码器）、`ffprobe` 和 Python 3.10+。帧号/源 PTS 使用仓库内置的 5×7 像素字形流式绘制，无系统字体、Fontconfig 或额外 Python 依赖：

```sh
uv run python tools/comparison/generate_m0_fixtures.py
```

输出（不进入 Git）：

```text
testdata/comparison/generated/teacher.mp4
testdata/comparison/generated/learner.mp4
testdata/comparison/generated/manifest.json
```

默认老师的视频从 2s 位置出现音乐，自己从 5s 出现完全相同的伴奏，因此期望偏移 **+3,000,000µs**。两源视频从 0s 开始，印有自身帧号及源 PTS；请勿把同一音乐的开始时间当作视频 0s。

每帧还有 20 位源帧号条码，便于对录屏进行像素解码；`MUSIC` 显示相对音乐起点。生成在临时目录完成，两路编码/探测均成功后才发布 MP4 和 manifest，失败保留上一套完整产物。

可用 `--music-seconds 120` 生成长时漂移样片；默认仍为 10s 音乐。可用 `--fps 24/30/60`、`--size 1280x720` 和 `--offset-ms -1000` 生成测试变体；负偏移须保证 `teacher-lead-ms + offset-ms >= 0`。每次生成可检查 `manifest.json` 里的 sha256、实际流编码和预期偏移。

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

自动化合成测试可添加 `--load-fixtures`：将两份文件推入本应用专属存储，重启此应用并通过现有 Debug URI 参数直接加载，无需手机选片；不卸载或清数据。此选项仅用于明确提供的测试素材。

脚本默认将测试素材放进设备的 `Movies/WoahM0`，启动仅在 Debug 存在的 Activity。**需要你在系统文件选择器中手动选一次老师/自己两段视频**。也可以不传两个素材参数，用手机相册选择真实的视频（不会自动上传原始媒体）。

不需要安装脚本也可以：

```sh
adb shell am start -n art.gaoge.dance/art.gaoge.dance.engine.comparison.ComparisonM0Activity \
  --el offset_us 3000000
```

## 启动报错 Error type 3：先检查 APK，而不是反复改 am start 参数

`ComparisonM0Activity` 的完整组件是
`art.gaoge.dance/art.gaoge.dance.engine.comparison.ComparisonM0Activity`。
在 ADB 输出中简写为 `art.gaoge.dance/.engine.comparison.ComparisonM0Activity` 也是正常的；**Error type 3 不是音乐偏移符号错误**。

1. 在 Windows 构建机器从仓库根目录检查分支，并同步最新修复：

   ```powershell
   git fetch origin
   git switch feat/dance-comparison-m0
   git pull --ff-only
   git rev-parse --short HEAD
   ```

2. **确保使用该分支重新生成 Debug APK**，而不是安装旧路径或 Release APK：

   ```powershell
   cd mobile/app
   flutter pub get
   flutter build apk --debug
   cd ../..
   ```

3. 用纯 Python 检查 APK 是否**同时包含 Manifest Activity 名称和 DEX 类**（不会操作手机数据）：

   ```powershell
   python tools/comparison/verify_m0_apk.py --apk mobile/app/build/app/outputs/flutter-apk/app-debug.apk
   ```

   - `M0_APK=FAIL`：本机 APK 本身缺少入口。首先确认 Git 分支与 Debug 构建；进一步查看 Flutter Gradle 的 Manifest merger，而不是安装这个包。
   - `M0_APK=HAS_MANIFEST_NAME_AND_DEX_CLASS`：这是静态必要条件，**不代表真机已启动成功**。

4. 检查手机上**目前安装的** APK，而非仅查看源码（需允许 ADB 从设备读取已安装 APK）：

   ```powershell
   python tools/comparison/verify_m0_apk.py --inspect-device
   ```

   - `M0_DEVICE=FAIL`：已安装包不含验证 Activity 或未安装目标应用，通常是旧 APK、Release APK 或不同构建；先备份所需应用数据，再考虑是否替换。
   - 如果不能 `adb pull` 设备的 `base.apk`，报告会明确指出读取失败，不推断设备一定缺少 Activity。

5. 若本地 APK 确认正确，可明确要求安装并启动（`install -r` 尝试**保留应用数据**，不会卸载）：

   ```powershell
   python tools/comparison/verify_m0_apk.py --install --inspect-device --launch
   ```

   若提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，旧安装与 Debug 的签名可能不同。**不要直接执行 `adb uninstall`**，它会删除原应用数据；优先使用测试设备，或先完成备份/确认迁移方案。

诊断脚本根据 **编译后的 APK** 检查，而不是只查 `src/debug/AndroidManifest.xml`。它检查二进制 Manifest 中的类名以及 DEX 类型描述符；这是快速打包预检，不取代 Android 官方 `aapt` 的结构化 Manifest 检验与真实启动。静态检查通过但 `am start` 仍报错时，请保留脚本的 `LOCAL_APK`、`INSTALLED_APK`、`INSTALL`、`M0_LAUNCH` 输出以便定位。

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
- 新报告包含 `usableTeacherFrames` / 配对覆盖率、观测时长和最多 120 个 5 秒统计窗口；少于 80% 配对覆盖率不得判为元数据达标。30fps 整数微秒量化容许单帧 33,334µs。自己侧禁用音频轨道而不只将音量设为 0。
- 报告自动剔除进入探针的首约 1 秒，并在拖动、变速、修改偏移时重开观测 epoch。它不能跨不同操作拼接成一组假稳态结果。
- 对 M0 的真实显示验收，必须同时收集带时间码的设备屏幕录制；录屏可能掉帧，有疑问时补高帧率外部拍屏。报告还需附上设备型号、Android 版本、视频来源和运行速度。
- 初始工程目标：30fps CFR 稳态 P95 ≤1帧、最大 ≤2帧，无累积漂移；这只是一条 **待实测** 判据。不同 fps / VFR 按各自源帧 PTS 报告，不套用统一 33.4ms。

若双 ExoPlayer 在设备验证中无法达到真实可见同步门槛，不要靠更密集的 seek 勉强通过；进入双 MediaCodec + 单 GL 合成方案的技术评估。

## 构建环境与验证范围

历史 Mac Pi 环境无法进行 Android 真机验收。本次已在 Windows 本机完成比较功能定向 Kotlin 测试及 Debug/Release Kotlin 编译，使用本机 Flutter 构建 Debug APK，并通过 ADB 在 OnePlus 8T 采集。macOS 与 WSL Linux 的 Python 测试只代表工具契约回归；真实 MP4 编码、FFprobe 与完整解码在 Windows FFmpeg 9.0.1 上验证。具体设备结论参见本轮验收报告。

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

## 连续屏幕证据分析（2026-10-25）

设备 `screenrecord --help` 本身若返回 139，不应归咎于播放器。可用官方 scrcpy 的便携版本代替，保持手机屏幕开启，关闭电脑播放和音频转发：

```sh
scrcpy --no-control --no-playback --no-audio --max-fps=60 --time-limit=30 --record=m0.mp4
python tools/comparison/analyze_m0_screenrecord.py m0.mp4 \
  --teacher-rect 0,307,1080,607 --learner-rect 0,1083,1080,607 \
  --out m0-screen.json
```

矩形为捕获画面中视频内容的 `x,y,width,height`，示例仅适用于本次 OnePlus 8T 竖屏布局；其他设备须从截图核对，不能照抄。分析默认保留老师源时间 [1,11)s；长片须指定 `--end-source-seconds`。`--samples-out` 可保存全部解码帧号。它使用源帧码统计可见误差，输出每 5 秒窗口，永不将其写成物理面板扫描证据。编码器/合成器自身可能丢帧，结合未录屏采样和元数据对照。
