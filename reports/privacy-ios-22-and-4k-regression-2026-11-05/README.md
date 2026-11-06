> 提交信息见 [COMMIT_RECORD.md](COMMIT_RECORD.md)。本文中的工作树状态和分支记录为验证时快照。

# iOS 隐私打码：22 项旧矩阵与原尺寸 4K 回归（2026-11-05）

最终状态：**passed_with_observations**。2026-11-05 至 11-06 在 iOS 17.0.1 模拟器上完成最终修复版本的整批回归，汇总检查错误为 0。

| 验证批次 | 通过用例 | 成片 | 完整解码帧数 |
| --- | ---: | ---: | ---: |
| 原 22 项 | 22/22 | 22 | 1,537 |
| 原尺寸 4K | 27/27 | 26 | 15,618 |
| 定点及故障回归 | 13/13 | 12 | 288 |

原矩阵与 4K 合计 **49 项、48 条成片、17,155 帧**；取消用例不生成成片。逐项结果见 [case-table.md](case-table.md)，机器汇总见 [test-summary.json](test-summary.json)。结果包含下文三项保守多遮观察；在本轮检查范围内未发现新增漏遮、导出失败或丢帧。

三批运行使用相同的原生二进制、Swift 源码、QA 入口和 8 份素材绑定。原 22 项定义与用户提供分支逐字一致，见 [legacy-matrix-binding.json](legacy-matrix-binding.json)。六组原生基础检查在定点与旧矩阵两批均通过；跟随豁免的 161 个候选已逐一对照，均为指定主角 6。Flutter 277 项、音频审计 4 项及现有主机回归检查通过。

4K 的 27 项在同一进程 PID 35683 连续执行，耗时约 2 小时 49 分钟；5 秒采样的 App RSS 峰值约 **2.20 GiB**、系统整体交换占用峰值约 **4.10 GiB**、可用磁盘最低约 **29.59 GiB**。这是模拟器环境的功能与资源记录，不代表 iPhone 的编码性能。

工作区：`/Users/mini/Coding/woah`
分支：`codex/ios-22-and-4k-regression-20261105`
基线：保留 `codex/ios-identity-indeterminate-truth-20261105`，并合入远端主分支 `67df0b9b`（包含上一轮 Android 4K 修复）。合并点为 `186113fe`。修复工作仍在本地工作区。

## 验证范围

- 原 22 项：逐项沿用旧矩阵的素材、模式、主角 ID、裁剪区间和输出尺寸。全长跟随仍为 FACE_ONLY、主角 6、581 帧，不以缩短时长或改为全身遮挡替代。
- 新 27 项：Android 4K 的 25 个用例，加两条全长自定义 PNG 贴纸导出及相册保存校验。
- 横屏输入/输出为 **3840×2160、25fps、23.24 秒、581 帧**。
- 竖屏输入/输出为 **2160×4096、25fps、26.48 秒、662 帧**。保留素材原尺寸。
- 全长覆盖全身的纯色/马赛克/模糊/渐变，以及人脸的纯色/马赛克/模糊/渐变/内置贴纸；覆盖 quality/balanced/speed、非零起点裁剪、取消后立即重试、连续导出和自定义贴纸。
- QA 入口在实际模拟器 App 中调用生产原生 API，实际执行解码、分析、跟随、打码、编码及相册写入。软件测试入口不替代真机的编码能力与性能验证。

4K 先执行两条全长全身纯色，用实际编码像素核对脸部补充保护；随后执行“裁剪完成 → 至少处理 25 帧后取消 → 立即全长重试”，再连续执行其余模式。全部 27 项在同一 App 进程中执行。

## 同一批素材

素材复用此前已下载的公开测试文件，并在每次运行前对 Mac 文件和模拟器副本逐一校验 SHA-256。完整清单保存在每次运行的 `run-metadata.json` 中。

| 素材 | SHA-256 |
| --- | --- |
| group-mirror-8956295.mp4 | 376c2ae73c92c23dceb042d27450aaa45ba2f44a3d7b0a5f3c26e9bea6c2dd73 |
| solo-portrait-7570258.mp4 | f401df9259f7fcbf7b01121cbb720e744e0dbba6e3ebce2f64027d37c873d6a1 |

其余为同批 group-hd、solo-hd、duo、无人物视频、1024 字节损坏视频及 806 字节自定义 PNG。来源及准备脚本沿用 [原 iOS 报告](../privacy-ios-regression-2026-11-04/README.md)。

## 本轮发现与修复

1. **全长多人跟随在 103/581 帧中止。** 对“保护除主角外的所有人脸”单独处理当前帧的保护类别；丢失的身份仍保持 LOST，不借新人物或主角冒认旧身份。当前未知观察默认保护，仅当前严格匹配且分割掩码支持的人脸可以获得主角豁免。单独选人、混合模式继续保留原来的身份约束。
2. **遮挡侧脸只盖住一部分。** 增加当前身体上部的局部人脸检测，保留检测视图来源和人物归属，避免重复框制造错误豁免；根据当前 yaw 补齐侧脸的面颊和下颌。第 343 帧的已知非主角侧脸纳入独立检查。定位执行失败会阻止新类别路径发布视频。
3. **4K 尾帧裤腿出现人脸遮挡。** 定位层已省略完全被前景挡住的猜测头部，合成层却又补回默认头部。修复两层衔接；当前真实检测到的人脸优先保护。
4. **4K 取消后所有立即重试报 EXPORT_BUSY。** 取消状态先到、旧运行时尚未退出。现在立即接受后继请求，并等待旧解码器、写入器和缓存租约退场后开始处理；保持有效任务的忙碌保护、取消排队任务、失败预约回收及首个终态不可覆盖。临时文件仅按本任务注册的精确路径清理。
5. **4K 长片临时对象累积。** 原生导出在 detached Swift task 中连续处理帧，Core Image/Vision 的 Objective-C 临时对象未按帧回收。全长多人 4K 的交换占用涨至约 31 GiB、磁盘余量跌至约 7 GiB，已主动停止该次运行并保留资源证据。现在每帧建立 autoreleasepool，保留原有时间戳、帧数和异常传播；新回归增加交换空间采样。
6. **竖屏 4K 抬手后漏脸。** 在全长素材第 560–563 帧确认贴纸落在手臂上，检测恢复后仍受位置平滑限制。单人全选也使用当前帧保护；全景未检测到人脸时增加上半身局部视图；真实人脸不受预测位置限速、尺寸不被平滑缩小。在检测暂时丢失的单人全选场景，额外保留受当前掩码支持的最近真实人脸区域，最多 800 毫秒、不自续租、不替代当前帧的头部回退、不授予身份或主角豁免；场景变为多人时退出。新增这一段的纯色、贴纸、全身回归及半遮挡人脸像素点。
7. **全身分割遗漏脸部。** 在全长纯色成片发现横屏31、竖屏27个人脸核心区域未充分覆盖，其中一帧的镜中脸完全未遮挡。全身隐私掩码现在合并对应的人脸保护，继续保持未选择人物的边界。新增实际 Metal 反例和两段各40帧的定点回归。
8. **原尺寸多人细节与合成成本。** 沿用 Android 的四个重叠细节视图、同一检测阈值、完整边界过滤及全场景掩码映射。掩码先在原型空间合并，再按行投影；横竖 4K 和跟随裁剪的逐字节一致性检查通过。

## 检查方法

- 独立完整解码每条成片，与源视频的逐帧时间戳、完整帧数、首尾帧和分辨率比较。
- 检查 MP4 fast-start、黑/绿帧、音轨保留；将解码音频与源裁剪波形对齐，核对相关性、音量和时间偏移。原始 solo 素材为静音 AAC，静音结果只证明音轨与静音保留，不作为可观测的同步信号。另保留现有原生基础检查的 440Hz 非静音控制样例：裁剪和立即重试均为 36 帧、0.6 秒，波形相关性约 0.99999、音量比例约 1.0005，见 [audio-control.json](audio-control.json)。周期音调不能唯一确定绝对音画偏移。
- 纯色模式检查已知易漏侧脸、镜中人脸和裤腿位置，并扫描全部人脸遮挡帧的内部空洞。
- 使用源视频上的 macOS Vision 结果检查每个可检测人脸的核心区域。纯色模式核对实际成片的红色像素比例；其他模式核对逐帧记录的保护椭圆并检查实际成片的效果。几何覆盖不等于实际像素证明，源检测器也会漏掉半遮挡脸，因此另保留已知漏遮点与画面对照。跟随主角的每个未遮挡候选均生成源画面/导出画面对照供检查。
- 自定义贴纸成片保存到模拟器相册后，从该模拟器的媒体目录独立校验原始 MP4 的 SHA-256。测试仅授予相册添加权限，并先检查 App 的用途声明。
- 记录每次运行的二进制、Swift 源码、QA 入口及素材绑定，以及进程 ID、内存和可用磁盘空间。4K 连续矩阵在同一进程执行。

原身份安全反例、实际 Metal/AVFoundation、缓存、日志边界检查与 Flutter 277 项测试保留。新增用例覆盖身体框内的非主角脸、当前掩码的豁免条件、定位错误、排队取消、立即重试和预约失败后的再次启动。

## 已知精度限制

FACE_ONLY 的保守头部回退仍可能对被遮挡的下半身片段多遮一小块。例如多人素材第 576 帧，YOLO 只看到背景人物腿部，回退区域落在腿旁；第 580 帧的既定裤腿误遮点已经清除。

单人抬手第 551–559 帧的人脸暂时漏检时，受当前掩码支持的短时真实人脸补充区域与当前头部回退取并集。因此脸得到保护，但贴纸可能出现两个、手臂或背景会被多遮；补充区域不替代当前回退，避免移除对移动中人脸的保护。

单独选人用例 `group_first_solid` 中，原选中 ID 0 仍位于左侧镜面；第 39 帧的新观察也被现有未知类别保护规则额外遮挡。该处前景额外遮挡面积在早期回归与最终运行均为 8,040 像素，见 [selected-first-observation.json](selected-first-observation.json)。保留未知类别默认保护，因此单独选人也不保证每帧只遮一个身体。

三项精度限制的证据记录在 [known-observations.json](known-observations.json)。全身分割轮廓仍受模型精度影响，不声称像素级完整轮廓或所有帧零误遮。

## 最终运行与产物

- [旧矩阵配置](configs/complete_22.json)：`complete_22`
- [4K 配置](configs/complete_4k_v2.json)：`complete_4k_v2`
- [定点与故障回归配置](configs/complete_preflight_v3.json)：`complete_preflight_v3`

每次运行位于 `artifacts/<run_id>/`，含原始结果、控制台、成片、裁剪/保护区域轨迹、源码绑定和资源采样。独立检查位于 `media-audits/<run_id>/` 与 `face-audits/<run_id>/`。早期失败保留在单独运行目录中，不计入通过数。

`artifacts/` 为本机证据，不纳入 Git；不提交原始 MP4、App、依赖缓存和逐帧原始像素。完整成片、轨迹和构建产物保留在 Mac 的 `/Users/mini/Coding/woah/reports/privacy-ios-22-and-4k-regression-2026-11-05/artifacts/`，轻量报告副本未包含的产物请在 Mac 查阅。

当前修复尚未提交，工作区仍在上述专用分支。改动清单及哈希见 [source-state.json](source-state.json)，可审查补丁见 [working-tree.patch](artifacts/working-tree.patch)，改动文件快照见 [source-snapshot.tar.gz](artifacts/source-snapshot.tar.gz)。证据绑定清单见 [evidence-manifest.json](evidence-manifest.json)。

## 复跑入口

使用 Xcode 完整开发目录、Flutter 3.44.2、CocoaPods、已启动的 iOS 17 模拟器，以及 FFmpeg。模型同步脚本为 `tools/release/sync_ios_yolo_model.py` 和 `sync_ios_crop_clarity_model.py`。测试 App 入口为 `mobile/app/lib/ios_privacy_regression_20261104_main.dart`。

构建采用 Debug Flutter 加原生 Swift `-O`，隔离构建路径在本报告的 `artifacts/optimized-build`。原生优化避免将 Debug 的循环开销当作生产算法成本；测试仍是模拟器环境。`--optimized-native` 仅记录构建方式，不能代替实际的 `-O` 构建。安装包和模型核对记录见 [installed-build-binding.json](installed-build-binding.json)。

本轮使用下列构建顺序。Flutter 的 QA 入口需要先写入生成配置；CocoaPods 安装会更新自动生成的构建阶段。本轮安装依赖产生的工程阶段改动已回退，不计入隐私修复；复跑仍需先执行 `pod install`。

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
export PATH=/Users/mini/Coding/flutter-3.44.2/bin:/opt/homebrew/bin:$PATH
# 从仓库根目录开始；UDID 换为已启动的模拟器。
export WOAH_QA_UDID=EE228BE6-0F7F-48E1-9892-313B7CFFBB63
export WOAH_QA_BUILD="$PWD/reports/privacy-ios-22-and-4k-regression-2026-11-05/artifacts/rebuild"
cd mobile/app
flutter pub get
flutter build ios --debug --simulator --config-only \
  --target lib/ios_privacy_regression_20261104_main.dart
(cd ios && pod install)
xcodebuild -workspace ios/Runner.xcworkspace -scheme Runner \
  -configuration Debug -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$WOAH_QA_UDID" \
  -derivedDataPath "$WOAH_QA_BUILD/DerivedData" \
  BUILD_DIR="$WOAH_QA_BUILD/Products" \
  SWIFT_OPTIMIZATION_LEVEL=-O SWIFT_COMPILATION_MODE=wholemodule \
  GCC_OPTIMIZATION_LEVEL=s ONLY_ACTIVE_ARCH=YES ARCHS=arm64 build
cd ../..
```

下面的 `<built-Runner.app>` 对应 `$WOAH_QA_BUILD/Products/Debug-iphonesimulator/Runner.app`。

```bash
python3 tools/ios/run_privacy_regression.py \
  reports/privacy-ios-22-and-4k-regression-2026-11-05/configs/complete_22.json \
  --udid <simulator-udid> --install --optimized-native --app <built-Runner.app>
python3 tools/ios/run_privacy_regression.py \
  reports/privacy-ios-22-and-4k-regression-2026-11-05/configs/complete_4k_v2.json \
  --udid <simulator-udid> --optimized-native --app <built-Runner.app>
python3 tools/ios/audit_privacy_regression.py <run_id>
python3 tools/ios/audit_privacy_face_cores.py <run_id>
```

复跑时复制配置并改用新的 `run_id`，保留旧成片及失败记录。审计 Python 环境需要 numpy、opencv-python-headless；源视频人脸扫描工具为 `tools/ios/privacy_source_faces.swift`。

磁盘处理记录见 [cleanup.json](cleanup.json)：已核实属于本 Woah 工作区、且不再有构建进程使用的旧 Xcode 缓存回收约 691 MiB。
