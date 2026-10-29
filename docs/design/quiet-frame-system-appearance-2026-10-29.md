# Woah / Quiet Frame — 跟随系统的明暗主题

日期：2026-10-29
基线：`master` / `cf61a7ae73deff3bfc065a1d9eba823707c5bf81`
分支：`feat/system-appearance-light-dark`
状态：双主题实施候选；Android 真实构建 / 真机视觉和部分动态跨页面交互仍需进一步验收，不得自动合并。

## 一、方案结论

**默认且唯一的外观来源：设备系统主题。** Flutter `MaterialApp.router` 同时提供 `theme`、`darkTheme` 并指定 `ThemeMode.system`；通过系统 `platformBrightness` 在运行时更新界面。没有每天几点切换的自定义规则，也不访问定位、日出日落数据、通知或后台调度。

- **Android 10+** 系统深色模式可通过设置、快捷开关或系统自己的夜间计划切换；部分机型省电模式也会切换系统主题。Woah 只响应 OS 报告的 light / dark，不主动判断当前时刻或电池状态。Android 9 及更早机型若未提供对应的系统外观选择，则按平台返回的外观显示。
- **iOS** 用户在设置中选择浅色、深色、自动，Woah 使用 iOS 的系统外观偏好。保留正常系统动态选择，不在 `Info.plist` 强制 `UIUserInterfaceStyle`。
- **不增加应用内“手动浅色/深色/自动”开关**；减少与系统设定冲突及单独持久化的负担。之后如有实际需求可扩展 `ThemeMode`，但那是独立产品决策。

外部设计依据：Flutter 官方 `MaterialApp.themeMode` 与 `darkTheme` 文档；Android 官方 Dark Theme 指南；Apple Dark Mode / Appearance 指南。

## 二、配色与职责

| 用途 | 深色 Quiet Frame | 浅色 Quiet Frame |
| --- | --- | --- |
| 工作区背景 | `#0B1415` Ink | `#F2F6F3` Pearl |
| 面板 | `#142123` Surface | `#FFFFFF` |
| 主文字 | `#F2F6F3` | `#142123` |
| 次文字 | `#B4C8C6` | `#435E59` |
| 主操作 / 当前选择 | `#78DCC7` Mint | `#216A5E` Deep Mint |
| 操作底上文字 | `#0B1415` | `#FFFFFF` |
| 控件边界 | `#607D80` | `#718F85` |

浅色主题必须保留充足对比度：主操作文字/背景至少 4.5:1；浅色面板正文至少 4.5:1；必要控件边界至少 3:1。应用中的 `AppTheme.of(context)` 读取 Material 主题，是一个**基于 BuildContext 的语义颜色表**。不能使用全局可变颜色静态量，防止弹窗、不同路由和复用控件在 OS 切换时保留旧值。

- 人物遮挡颜色的 ARGB、视频帧、特效强度、segmentation mask、主角跟随与导出都不由 UI 主题计算；琥珀金用户特效依然为 `0xFFF5A623`。
- 视频预览及真实视频舞台保持黑底，叠在视频上的文字保留高对比亮色，不因浅色模式变成难以辨认的黑字。
- Wavi 是隐藏的彩蛋舞台，延续夜色氛围，与主 UI 的明暗主题相互独立。

## 三、原生启动屏与系统栏

- Android：`values/` + `values-night/` 配置浅色/深色启动背景，`drawable/` + `drawable-night/` 为对应 W 颜色。API 31+ 原生 SplashScreen 引用同一夜间资源，并配有 `values-night-v31/` 的主题。自适应应用图标与用户导出素材均未改。
- iOS：通过 `WoahLaunchBackground.colorset` 和 `LaunchImage.imageset` 的 `luminosity: dark` 变体，让 storyboard 上的 W 标识和背景随系统主题配置。桌面图标后续追加独立的 Light / Dark / Tinted 资产；详见 `quiet-frame-launcher-icon-2026-10-29.md`。
- System UI：顶部状态栏保持编辑器既有沉浸规则，底部系统导航图标按照当前主题切换深浅；Android 35+ edge-to-edge 行为的导航背景仍需真机复核，不将 setSystemUIOverlayStyle 对背景颜色的请求等同于已生效。

## 四、验收矩阵与限制

1. 单元 / Widget 测试：深色 → 浅色 → 深色在首页动态生效，页面状态和入口不丢；主控件选中态及文字保持可读；Wavi 固定暗色。
2. Android：不同 Android API（最低 24、常用 29/33/35+）启动背景是否跟随系统；自动夜间模式正在运行时实时切换；状态栏、底部导航图标；保护编辑器和双视频对比媒体舞台不被反转。缺少 SDK / 真机时记录为 `NOT_PROVEN`。
3. iOS Simulator：至少一台 iPhone 的浅色/深色启动与切换实拍；iOS 真机仍需确认相册、系统文件选择器、媒体导出与原生控件显示。
4. 兼容性：大字号、小屏、横屏、浮层弹窗、系统返回及工作流状态保持；不改变媒体/隐私计算路径；构建产物需一致且无白闪。

最终放行须同时具备真实 APK 构建、Android 真机与 iOS 模拟器截图证据；本报告只记录已实际执行的结果，不将旧的深色 UI 基线等同于双主题验收。

## 五、本轮 Mac 验证回执

- Flutter 3.44.2 / Dart 3.12.2：`flutter analyze --no-pub` **通过**；全量 `flutter test --no-pub` **189 / 189 通过**。新增的系统亮度测试验证首页在 `dark → light → dark` 过程无重启切换，双视频功能卡的选中页也不丢失；Wavi 彩蛋保持独立的暗色舞台。
- 浅色配色经 sRGB 计算：主文字/白底约 **16.52:1**、次文字/白底约 **7.04:1**、Deep Mint/白字约 **6.39:1**、控件边界/白底约 **3.52:1**。这些是纯色值，对照片/视频像素上的半透明 UI 仍需实际观察。
- Xcode 27.1：正式 `lib/main.dart` iOS Simulator Debug 包构建**通过**；源自 `WoahLaunchBackground.colorset` 与 `LaunchImage.imageset` 的暗色外观资源通过 `ibtool` 语法检查及 Xcode 资源编译。
- iPhone 18 Pro / iOS 27.0：系统浅色启动，应用不退出切深色，再切浅色；进程保持存活，首页颜色按预期切换。图片 `/tmp/woah-system-appearance-ios-evidence/01-light-home.png`、`02-dark-home.png`、`03-light-again.png`。
- iPhone 15 Pro / iOS 17.0：正式 Debug 包安装，浅色启动、运行中切深色；首页实际截图显示两种色系正常，进程仍在。图片 `04-ios17-light.png`、`05-ios17-dark.png`。
- 静态跨平台验证：`tools/brand/verify_system_appearance.py`、`verify_quiet_frame_assets.py`、`verify_wavi_assets.py` 均通过，核对系统模式、Android `-night`/API31 资源、iOS 变体、品牌图标及原特效色。

**未覆盖**：Android APK 资源编译及夜间模式真机切换（当前 Mac 无配置的 Android SDK）；iOS 真机、文件选取器/编辑器运行中深浅切换、慢速设备冷启动闪屏、系统放大字体交互。这些在合并前仍是待验收项，不视为通过。
