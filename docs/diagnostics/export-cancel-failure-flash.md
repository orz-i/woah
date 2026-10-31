# 导出取消时瞬时显示失败画面的竞态调查

日期：2026-10-30
工作分支：fix/export-cancel-failure-flash
状态：受控复现与 Flutter 侧修复验证通过；Android 设备级验证未完成

## 用户可观察现象

用户在 Woah 的隐私导出页点击底部停止按钮，确认“取消处理”后，返回编辑器前偶尔闪出红色错误内容，仅持续很短时间，难以手动截图。此问题不应被描述为某一特定视频时间戳的特例。

## 代码级证据与复现

- 当前 Android ExportCoordinator.cancelJob 会立即设置取消标志并发布 cancelled 状态。
- 原 ExportPipeline 在清理时无条件把 Throwable 转为 failed 并发布 errorMessage；ExportForegroundService 的异常兜底同样无条件发布 failed。显式取消时释放解码器、GPU、muxer 等资源仍可能产生异常，因此取消状态可能被随后到达的 failed 覆盖。
- 原 Flutter ExportController 没有区分“用户已确认取消”与普通处理中原生终态：只要接收到 failed/errorMessage，ExportScreen 就会在等待 cancelJob 返回期间构建 export-failure-summary；等待结束后才 context.pop()，因此形成短暂错误画面。
- 在 Mac Flutter Widget Test 中，用模拟 native repository 延迟取消响应，并在取消期间补发 failed 状态，**修复前测试失败**：期望 export-failure-summary 不存在，但实际找到了失败面板。日志为 /tmp/woah-cancel-race-red-test.log。
- 修复后同一测试通过，且加入取消中迟到 completed、取消成功后的迟到 failed/completed、取消 API 失败并恢复真正错误的回归，避免把有效失败静默吞掉。

注意：上述测试证明该状态竞态确实存在，并能导致短暂失败面板；它不等同于获取了用户现场那一闪的 Flutter 原生堆栈。因此“红色错误闪动”的最终设备归因仍需稳定 ADB 后复核。

## 本轮修复范围

1. Flutter：导出取消请求发起后暂时拒收该任务的迟到原生进度和终态，直到取消 RPC 成功或失败。成功时清理 errorMessage 与预览并保持 cancelled；失败时恢复订阅，并重放取消期间被暂存的 failed/completed 终态，不掩盖真正失败。
2. Android 原生：新增统一终态分类 ExportTerminalStatus。处理管线和前台服务在 cancellation flag 为 true 时，保留 cancelled、清理 errorCode/errorMessage/outputUri/currentPreviewPath，避免把取消期间的清理异常误标为 failed；没有取消请求的真正错误仍为 failed。
3. Kotlin 单元测试 ExportTerminalStatusTest 覆盖“取消中的异常应为 cancelled 并清理错误字段”“非取消的异常应保留错误信息”。
4. 保留原导出媒体、隐私算法、渲染参数、画面效果、cancelJob 确认对话框、结果页面和 native API 协议。没有变更导出内容或任务序列化格式。

## Mac / Android 真机调查记录

- macOS USB 枚举识别出 OnePlus 8T，但 ADB 37.0.1 的 USB 传输在出现一次短暂 device 状态后不稳定，日志存在 usb_read / usb_write e00002ed 和 transport terminated。
- 安装官方 Android Platform-Tools，并测试 37.0.1 原生与 libusb 模式；另在 /tmp 下单独下载官方 36.0.2 再测，仍未获得持续在线的 adb devices，不能启动手机页面、录制导出取消、读取 logcat 堆栈。
- Mac 没有 Android SDK、JDK、Android Studio 或 Kotlin CLI，故暂时不能本地运行 Android Kotlin 单测或构建 Android APK；避免安装大型 SDK 破坏用户此前的磁盘清理成果。
- USB 设备和 Android 调试设备是不同层面的状态。“物理插入”不等于 ADB 可稳定通讯。本轮没有取得手机中的任何隐私视频画面或导出素材。

## 验证状态与下一步

- Flutter Analyze：本轮 0 issues。
- Flutter 全量测试：234/234 PASS；导出专项测试新增取消竞态回归并通过。
- iOS Simulator Debug：使用临时 CocoaPods 兼容模式构建成功，Flutter 全局 Swift Package Manager 配置及跟踪的 Xcode 工程均已恢复；仅证明 Dart/共享 UI 构建。
- Android Kotlin 单测：新增测试文件，**未运行**，Android APK 编译及真机点击取消也**未验证**。
- 对新 Kotlin 终态分类辅助类进行了独立 Kotlin/JVM 编译与两种分支的逻辑断言，结果 PASS。该独立测试使用与生成 DTO 同字段的简化类型，只用于核对辅助类语法与逻辑，不能代替完整 Android Gradle 编译和模块单测。

下一轮在拥有 Android SDK/JDK 的构建环境中执行 Android 编译与 Kotlin 单元测试。将新 APK 安装到 OnePlus，导入真实视频，进入隐私导出，分别在准备、处理中和接近完成时确认取消。观察是否不再闪出失败页、是否成功返回编辑器、是否无后台残留任务。稳定 ADB 后，使用有界 screenrecord 和 logcat 捕获取消窗口，不需要人工瞬时截图。若仍出现完整 Flutter 红屏或堆栈，按堆栈重新定位，不能仅凭本轮竞态修复宣称最终设备问题已彻底解决。


## 2026-10-31 OnePlus 8T 恢复连接后的真机复核

- **ADB 实际连通：** Android Platform-Tools 37.0.1 识别 OnePlus 8T / Android 14、1080×2400 / 480dpi；旧版已安装 Woah 0.1.0、Debuggable，最后更新时间 2026-10-30 20:45:31。连接仍偶尔断开，但本轮已成功运行命令、启动应用、导入视频、取消任务和拉取图像。
- **隐私友好素材：** 取仓库内 `home-video-protection.jpg` 的居中舞者区域，通过 ffmpeg 制作 540×960、20fps、30 秒 H.264、约 183KB 的本地合成视频。经 ADB 放入 `Movies/WoahQA`，通过 Android 文件选择器导入，隐私编辑器成功识别并默认保护 1 名人物。没有使用、导出或备份用户手机上的私人素材。
- **旧版真实取消试验：** 2026-10-31 08:05:58 开始导出；输出目标 540×960@20fps。导出进度约 64% 时点击停止并确认取消，08:07:52.819 记录到 `[ExportController] Cancelling job`，native pipeline 随后上报统计 `decoded=387, rendered=387, encoded=387`，页面返回隐私编辑器且没有崩溃。
- **瞬时画面证据：** Android 自带 `screenrecord` 在该设备直接退出 139，无法生成可用视频。改用 ADB 连续 `screencap`，在确认取消前后采样 32 帧。本轮 32 帧均未包含红色错误屏；合成接触图位于 Mac `/tmp/woah-cancel-device-qa/old-burst-contact-sheet.jpg`，原始 32 帧保存在 `/tmp/woah-cancel-device-qa/old-burst/`。只能表述为该次没有复现，不能推断所有偶发闪屏均已消失。
- **日志证据：** `/tmp/woah-cancel-device-qa/app-logcat-live.txt` 保存测试 App PID 的事件。该次取消窗口可见取消操作及 native pipeline telemetry，未捕获 Flutter widget exception / AndroidRuntime fatal stack。不能将系统通用媒体编解码日志当成确定的红屏根因。
- **善后：** 已停止后台 logcat，会话关闭；仅删除这轮新建的 `Movies/WoahQA` 合成视频和取消画面采样目录，没有清除 App 数据、安装其他 APK 或动用户已有视频。手机仍保留原 Debug APK。
- **版本限定：** 这次真实操作运行的是设备**旧版 APK**，不是修复分支的新 Kotlin/Dart 代码，因此只能确认旧版的一次取消表现。修复代码的 Flutter 受控竞态测试 234/234、独立 Kotlin/JVM 终态分类断言和 iOS 编译已通过，但完整 Android Gradle 编译以及 Android 新版真机再测仍缺失。
- **后续验收：** 在具备 Android SDK/JDK 的构建环境编译 `fix/export-cancel-failure-flash`，运行 `ExportTerminalStatusTest`，安装 Debug APK（保留 App 数据），以相同合成视频分别在刚开始处理、处理约 50%、接近结束时取消。采集 Flutter 异常、native 取消终态与 UI 过渡，确保无失败页闪屏、无结果页误跳，取消失败时仍可提示真实错误。若仍出现红色 Flutter 异常，按实际堆栈另行定位。
