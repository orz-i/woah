# Woah 首页双封面卡片 · 2026-10-30

实施分支：`feat/home-cinematic-cover-cards`（不直接改动 master）

## 目标与选图

首页只展示已实现的两个独立工作流，改为纵向排列的两张整卡点击入口。不增加工具库、编辑器内部功能或底部导航。保持 `Woah` 单一字标和长按进入 Wavi 彩蛋的行为。

- **视频保护：** 用户确认采用 [ChatGPT 分享图片](https://chatgpt.com/s/m_6ae421ff14508191ae0520fbe4bc0364) 中的第一张原图，非后来叠加不匹配人物轮廓的手工 Mask 版本。保留中央清晰主角和左右柔化背景人物，不额外绘制错位的 Mask。原始 PNG SHA-256 为 `c123c0e9b4a2edff3d45702ad83ec85a5964d30b5c314ea98c4646409f756863`（1536 × 1024）。
- **舞蹈对比：** 沿用前一轮独立左右双视频封面，不使用“两个舞者站在同一场景里”的照片；原始 PNG SHA-256 为 `1b25fba72898294d0eff853dda23c51d526c2c0966c9d1172362843d491b3077`（1536 × 668）。
- 运行资源：`mobile/app/assets/home/home-video-protection.jpg`、`home-dance-comparison.jpg`；通过 sips JPEG quality 85/86 导出，分别约 255 KiB / 206 KiB。原始 PNG 不打入安装包，避免重复体积。
- 上述封面只是创意视觉示意，不是隐私处理、人物跟踪、双视频同步准确率的验收证据。

## 首页布局

- 两卡都在同一纵向滚动容器中，用户不必横滑寻找第二项；iOS/Android 均有独立对比入口，非移动平台保留单入口。
- 每张卡片的上段为 `AspectRatio(2.30)` 封面，图片 `BoxFit.cover`、保护封面略偏上的裁切对齐，下面保留 Flutter 原生标题与动作说明。
- 统一圆角 20dp，卡片外底部阴影，无遮罩描边、卡内大按钮。整卡以覆盖在最上层的透明 InkWell 实现点击与反馈。
- `AppTheme.of(context)` 驱动底色、标题、说明和交互箭头；封面在浅色/深色下保持同图、不受 UI 主题调色影响。
- 品牌 W + Woah 顶部靠左；不新增顶部 Title、口号、Wavi 动画或“全部工具”。
- 资源和文案来自 `HomeToolCatalog`，保留对应 `HomeToolLaunch` dispatch；视频导入的 picking/probing、取消、失败和返回重置沿用原逻辑。

## 验收约束与范围

- 390 × 844 初始首屏能完整看见两张卡片；320 × 568 可滚动到第二张。
- 1.6 倍系统字号不报 RenderFlex；保持 Semantics 按钮标签且装饰封面不独立朗读。
- Android/iOS 入口互不干扰；Busy 状态防止重复触发；原生文件选取与编辑器返回重置不回归。
- 不触碰检测模型、跟踪、分割/Mask 算法、视频导出、双视频同步算法。
- Flutter widget golden 实际拍摄了深浅模式 390 × 844，组件截图中的中文出现测试环境缺字形方框，不作为真机字体视觉验收。
- iOS 设备运行与 Android 真机安装/画面验收必须另行验证；真机 QA 未完成时不将这份记录作为发布验收。

## 代码位置

- `mobile/app/lib/features/import_video/presentation/import_video_screen.dart`
- `mobile/app/lib/features/import_video/presentation/home_tool_catalog.dart`
- `mobile/app/pubspec.yaml`
- `mobile/app/assets/home/`
- `mobile/app/test/features/home_workbench_test.dart` 及相关首页/系统主题回归测试。

## 本轮本地验证

- `flutter analyze --no-pub`：PASS。
- `flutter test --no-pub`：206/206 PASS。
- Flutter Widget Test 深浅模式 390 × 844 双卡截图与 320 × 568 可滚动操作：通过。
- `flutter build ios --simulator --debug --no-pub`：Xcode 27.1 本地构建 PASS，已核对 `Runner.app` 中的两张 JPEG 与源码资产字节一致。
- `xcrun simctl list devices booted`：本轮设备服务调用超过 9 秒未响应；未执行模拟器安装、启动和实际触控 QA。构建成功不能替代运行验证。
- 视觉复核发现初始容器会垂直居中品牌字标，已修订为顶部对齐；最终深浅 390 × 844 组件截图重新采集。
