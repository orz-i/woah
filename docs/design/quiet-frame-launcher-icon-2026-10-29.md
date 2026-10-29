# Woah · Quiet Frame 桌面图标明暗与主题适配

日期：2026-10-29

代码基线：`feat/system-appearance-light-dark` / `2873aa0`

状态：**生产候选（iOS 编译及基本显示已验证，Android 需要外部构建和启动器实测）**；独立于 Flutter 页面主题的操作。

## 1. 设计冻结

- 应用显示名称始终为 `Woah`；中文正式品牌名「舞哇」不在桌面图标中重复展示。
- 三种 iOS AppIcon 都使用**同一版 Quiet Frame 原始 W 曲线**，720px 光学标识宽度、1024×1024 方形无圆角画布；由系统施加圆角遮罩。
- Light/Any 为 Pearl `#F2F6F3` + Deep Mint `#216A5E`；Dark 为 Ink `#0B1415` + Mint `#78DCC7`；Tinted 为纯灰度像素（`#101010` 背景 / `#FAFAFA` W），最终着色交给系统/用户。三者均为 **RGB PNG、无 alpha、sRGB**，不在素材中烘焙圆角、阴影或高光。
- 原 `woah-icon-default-1024.png`（Ink + Mint）与 `woah-icon-monochrome-1024.png`（透明标志）仍保留为历史/可编辑素材；新增 light、dark、tinted 品牌预览，不用于 Flutter 的每帧媒体处理。

## 2. iOS 17 / 18+ / 26+ 平台方案

使用 `Runner/Assets.xcassets/AppIcon.appiconset` 的 Apple **Single Size（1024×1024）** 外观插槽，三项值为 `Any / luminosity: dark / luminosity: tinted`。Xcode 在 iOS 17 中从 Any 构建兼容尺寸，在 iOS 18+ 的主屏幕可选择专门的 Dark 与 Tinted。AppIcon 编译产物中应同时包括 iPhone/iPad 各三项 `Assets.car` 变体。

- **iOS 17**：不支持较新 Home Screen Dark / Tinted 主题图标，使用 Any（Light）兼容图标。相比 Quiet Frame v1.1 最初的 Ink 背景图标，iOS 17 的桌面图标将改为 Pearl 背景，这是本轮有意进行的品牌浅色适配。
- **iOS 18+**：用户在主屏幕空白处长按，进入「编辑 → 自定」，可以选 Light、Dark、Auto 或 Tinted。必须在这里选择 `Auto` 才能指望桌面图标随系统夜间模式切换；仅变更系统外观不保证桌面图标变更。当用户固定 Light，Woah 桌面图标保持 Pearl 是预期行为。
- **iOS 26/27 Clear（透明玻璃）外观**：仍由系统从原生资源自动衍生效果；本轮不声称拥有独立的透明分层设计，也不把未验证的效果记为通过。
- Apple Icon Composer 支持更高级的多层 Liquid Glass、Clear/Mono 效果，但 `.icon` 文件一旦作为主 AppIcon 注册，会替换现有 Asset Catalog 并改变较老 iOS 的回退渲染。因此本轮优先使用 Apple 官方支持的 Asset Catalog，不注册未经旧系统回归的 `.icon`；**Icon Composer 分层效果属可选后续视觉升级，不是本轮桌面外观切换的前置条件**。

## 3. Android 7 / 8 / 13+

- Android 7（API 24–25）：`mipmap-{density}/ic_launcher.png` 为 Pearl + Deep Mint，`mipmap-night-{density}/ic_launcher.png` 为 Ink + Mint。密度从 mdpi 到 xxxhdpi，放入正确的 `night` 在 `density` 之前的资源限定目录。
- Android 8+（API 26）：保留自适应图标，背景读取 `@color/woah_launcher_background`，前景使用 `@drawable/woah_icon_foreground`。`values-night` 和 `drawable-night` 分别提供深色版本，形状和 59dp 光学安全范围不变。
- Android 13+（API 33）：保留 `mipmap-anydpi-v33` 的 `<monochrome>` 图层（基于同一 W 几何），**系统主题图标仅在启动器支持且用户启用时生效**；系统最终着色可能来自壁纸配色，不一定等于 Woah 薄荷色。
- 启动器通常会缓存桌面图标，对系统明暗切换是否即时更新以及其他 OEM（Pixel/三星/小米）如何显示仍需设备实测，不应因为资源正确而宣称全平台实时无缝刷新。

## 4. 可重建资产与静态检验

- 单一几何源：`tools/brand/generate_quiet_frame_icons.swift`。新增图标均来自相同 W 曲线，明确使用 sRGB 创建颜色与上下文，保证写入 PNG 的像素符合设计令牌而不是取决于 Mac 颜色配置。
- 运行 `python3 tools/brand/verify_launcher_icon_appearances.py`：校验 iOS 三个外观及真正无 alpha、灰度图、颜色值、Android mono/adaptive/夜间颜色层与旧版本 PNG。
- 运行 `python3 tools/release/verify_ios_phase0.py`、`python3 tools/brand/verify_quiet_frame_assets.py`：保持原发行与视觉合同，允许 Single Size 的隐式 1× 缩放。
- 构建 `flutter build ios --simulator --debug --no-pub`；以 `assetutil --info Runner.app/Assets.car` 复查 `UIAppearanceDark` 与 `ISAppearanceTintable` 均出现在 iPhone/iPad 资源中。

## 5. 验收与边界

- iOS 27 Simulator：Xcode Asset Catalog `actool` 无警告、iOS Debug 构建成功，`Assets.car` 三个外观 × iPhone/iPad 全部存在；Light 主屏幕真实渲染显示 Pearl + Deep Mint。系统切到 Dark 后，模拟器主屏幕图标仍显示 Light，**不能据此证明 Dark 变体失效**，因为 Home Screen 图标外观可能固定为 Light，且当前 Mac 自动化无法打开「编辑 → 自定」完成选择。**Dark/Tinted 的运行时呈现尚待实际选择相应桌面模式验证。**
- iOS 17 Simulator：Any (Light) 图标在桌面正常显示，安装、启动正常，无旧 API 资源加载失败。
- Android：静态资源校验通过，尚无本轮 APK/aapt2 构建和 Pixel/三星/小米真实启动器截图；应在外部设备分别测试关闭/开启「主题图标」、日间/夜间切换及低 API 回退。

不修改 Android 或 iOS 应用图标的运行时 API，不自行定时换图标，不使用 `UIApplication.setAlternateIconName`，也不修改媒体解码/分割/导出算法。**不合并或推送 `master`，等待最终系统图标外观验收。**
