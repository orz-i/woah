# 舞哇 / Woah · Quiet Frame v1.1 产品接入

日期：2026-10-29
基线：`master` / `ae58a12523657b6a5d854fae1a4109f46d5f531e`
实施分支：`feat/quiet-frame-vi-v1-1`
状态：**生产候选；跨平台真机视觉验收尚未通过，不合并 / 不推送 master**。

## 1. 正式品牌决议

- 正式中文名 **舞哇**，正式英文名 **Woah**；`Quiet Frame` 是 VI 设计方向，不是另一款应用名称。
- Android 在简体中文区域使用 `@string/app_name` → `舞哇`，默认英语 `Woah`；iOS `Info.plist` 默认为 `Woah`，`zh-Hans.lproj/InfoPlist.strings` 为 `舞哇`。Flutter 统一入口 `AppBrand` 同步维护品牌名称。
- 保持双侧 W 和中心留白的几何；原 v1.0 的标识绘制路径未改，仅适度放大移动系统图标中的光学占比。中文 / 英文双语字标以 v1.1 品牌资源为准；产品工作台上的常规文本并非视觉商标的重新排版。
- 隐私保护方向沿用文案「看见舞步，留住边界。」；双视频对比不强制搭配隐私文案；品牌标识或绿色状态均不构成保护完成证明。

## 2. 产品范围：双独立工作流

- **视频保护**：人物分割 / 追踪、全身与人脸效果、画幅、导出；显示人物时使用真实 segmentation mask 区域，编辑器选择状态与保护完成状态分离。
- **舞蹈对比**：参考 / 目标视频、共同音乐校准、同步播放、时间轴、偏移、导出；不增加品牌框线，不修改同步算法或控件操作方式。
- **首页**：保留既有滑动任务卡片和两个独立入口，不以 PDF 的人工编辑器示意重建 UX；品牌 W 和 Woah / 舞哇作为轻量顶部识别，保留长按品牌进入原彩蛋。Buddy 不用于人物标记。

## 3. 已接入的语义 Token

| 角色（AppTheme） | 颜色 / 含义 | 不可跨用 |
|---|---|---|
| `brandAction` | `#78DCC7`，主操作 / 播放操作 | 不代替视频用户自选颜色 |
| `brandOnAction` | `#0B1415`，薄荷底按钮前景 | 不使用白字配薄荷按钮 |
| `flowBackground` | `#0B1415`，工作台背景 | 媒体内容舞台仍保持纯黑 |
| `flowSurface` | `#142123`，工具、面板 | 不改变解码或导出像素 |
| `flowTextPrimary / Secondary` | `#F2F6F3` / `#B4C8C6` | 不使用灰字作为独立状态 |
| `editorSelection` | 当前选中态，可与品牌薄荷数值相同 | 不等于 segmentation mask 或保护成功 |
| `controlBorder / decorativeDivider` | `#607D80` / `#2D4548` | 装饰分隔不承担控件边界 |
| `statusError / statusWarning / statusSuccess` | `#FF9C9F` / `#F0C879` / `#9DD6B9` | 状态必须附明确文字或图标 |
| `effectPaletteAmber` | `#F5A623`，用户可选特效色 | **保持旧视频工程、ARGB 与渲染输出行为** |

迁移是按页面语义处理 `AppTheme.gold / coral` 的 UI 用途，旧 ARGB 效果色板中的琥珀金值保持原样。未修改分割模型、渲染器、保护算法、媒体存储与导出协议。

品牌字体目标为 IBM Plex Sans / Noto Sans SC，但本轮不将 17.8 MB 的中文变量字体嵌入安装包；默认系统字体回退，品牌主标识由矢量路径 / 栅格资产承载。需在字号缩放、不同系统字体和包体积实测之后再决定是否子集化字体。

## 4. 系统资源

- Android：`mipmap-anydpi-v26` + `mipmap-anydpi-v33` 自适应图标与 API 33 单色前景，低 API 保留各密度不烘焙圆角的 PNG；W 在 108dp 画布中光学宽度改为 59dp，仍须各启动器遮罩验证。
- iOS：按已有 `AppIcon.appiconset` 所需全部尺寸重新生成 W 图标；保留 `assets/brand/woah-icon-monochrome-1024.png` 和矢量可追溯的 Swift 生成器。**未声称完成 Icon Composer 工程**；Depth / Dark / Tinted / Clear 等系统外观仍需正式工具与真机复核。
- 两端启动页使用 Ink 暗底和静态 W；iOS `LaunchScreen.storyboard` 与 Android `LaunchTheme`/API 31 启动背景不再沿用白色模板。未添加品牌动画或强制延迟。
- 所有图标由 `tools/brand/generate_quiet_frame_icons.swift` 基于同一 W 几何生成，校验使用 `tools/brand/verify_quiet_frame_assets.py`。

## 5. 验证结果与剩余门槛

- `flutter pub get --offline`：通过；`flutter analyze --no-pub`：通过。
- 相关 Flutter 8 文件 **86/86 通过**；全量 `flutter test --no-pub` **184/184 通过**，覆盖主题、首页、导入、比较播放/时间轴/标记、导出和入口路由。
- 使用隔离 XDG Flutter 设置关闭 Swift Package Manager（不修改用户级设置），`flutter build ios --simulator --debug --no-pub`：**成功**；iOS `ibtool` 启动 storyboard 检查：通过；构建产物包含 `zh-Hans.lproj/InfoPlist.strings`。
- Android SDK / APK/JVM/真机：**未运行**。iOS 模拟器实际 UI 点击、启动截图及 iOS 真机：**未运行**。Icon Composer 项目与系统外观：**待验收**。
- 上述为代码与构建证据，不代表应用商店上架及实际视频隐私效果通过。原全链路 M0 结果不得代替此轮回归。

**合并条件**：Android 真实资源编译、至少一台 Android 实机图标与白闪检查、iOS 模拟器实际启动与系统图标外观、保护及对比工作流回归、主按钮对比度和大字体检查均通过，再请求用户允许合并及推送。
