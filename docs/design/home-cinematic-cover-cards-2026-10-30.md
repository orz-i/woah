# Woah 首页影院封面与左右滑动卡片栈（2026-10-30）

实现分支：`feat/home-cinematic-cover-cards`。首次封面接入提交：`ab0fb29`。本文件记录**最新有效设计**；之前的上下双卡布局已由用户明确要求撤回。

## 目标与选图

首页只展示已实现的两个独立工作流；恢复早期首页的中心主卡 + 横向 PageView + 相邻卡片边缘预告 + 分页点，不增加工具库、编辑器内部功能或底部导航。保持 `Woah` 单一字标和长按进入 Wavi 彩蛋的行为。

- **视频保护：** 采用 [用户指定的 ChatGPT 分享图片](https://chatgpt.com/s/m_6ae421ff14508191ae0520fbe4bc0364) 中的第一张原图。清晰主角与左右柔化配角；不再使用边界错位的人工 Mask。原始 PNG SHA-256：`c123c0e9b4a2edff3d45702ad83ec85a5964d30b5c314ea98c4646409f756863`（1536 × 1024）。
- **舞蹈对比：** 采用独立左右双视频封面，不是双人在同一场景中起舞的合照；原始 PNG SHA-256：`1b25fba72898294d0eff853dda23c51d526c2c0966c9d1172362843d491b3077`（1536 × 668）。
- 两张运行期资源位于 `mobile/app/assets/home/home-video-protection.jpg` 与 `home-dance-comparison.jpg`，各约 255/206 KiB。原始 PNG 不加入安装包。
- 图片只作为功能概念展示，**不是**隐私保护正确率、人物 Mask 或舞蹈动作分析的实际输出验证。

## 最新布局：保留原有左右滑动栈

- 移动端继续使用原生 `PageView.builder` 横向分页，`PageController.viewportFraction` 跟随宽度按 `cardWidth * 0.90 / viewportWidth` 计算；通过 `clipBehavior: Clip.none` 与左右相邻卡片露出部分体现滑动方向。
- 卡片宽度 `min(300dp, 可用宽度 × 0.88)`，高度 `(宽度 × 1.12).clamp(250dp, 336dp)`，圆角 22dp、无可见描边、底部柔和双层投影。分页指示当前卡为 17×5dp、其他为 5×5dp，点击交互为整卡单一操作。
- 首卡「视频保护」，左滑后显示「舞蹈对比」；第二张左侧可见上一张的局部。卡片仍是两个独立可执行工作流。设备仅支持一个工作流时不创建轮播页或分页点。
- **适配竖向卡比例的关键设计：** 视频保护图作为上方封面，`BoxFit.cover` 保持中心主角清晰；舞蹈对比图为横屏双视频场景，不用 `BoxFit.cover` 强裁两侧舞者，而在柔和虚化的同图背景上将完整双视频横屏影像按 2.30:1 居中显示。封面不承载原生按钮或标题。
- 顶部 Woah W 字标保留长按彩蛋；卡片下半部使用 Flutter 原生标题、描述、箭头与导入忙碌状态。文本语义及颜色来自 `AppTheme.of(context)`，深浅主题无画面反色。
- 保留 `HomeToolCatalog` 注册表，继续调用原有 `launchHomeTool`。失败提示停留首页卡片下方；取消选择及处理后返回逻辑不变。
- 分页状态由 `_HomeToolDeckState` 管理；系统浅深主题变化不重置当前页，横竖布局宽度改变时 `PageController` 保留当前页索引。

## 验收要求

- 390×844：首卡居中并能看到右侧下一张的一部分；左滑后第二卡可点击，分页点及时变化。
- 320×568：无需竖向滚动才能横滑切换，第二张仍可点击；文字放大 1.6 倍不出现布局溢出。
- iOS / Android 有双入口，非移动平台保持单入口、不显示假轮播；忙碌过程禁用重复导入，错误正常显示，原生视频选择行为不变。
- 深色 ⇄ 浅色切换和屏幕宽度变化不得重置选择页；同时观察卡片投影、说明文字和页面背景。
- `Semantics` 仍使用完整的工作流标题和说明；封面图自身不独立朗读，整卡点击区域不分割。
- Widget 测试截图没有系统中文字体，中文在图片中可能变为方框，**不能视为实机字体视觉验收**。Flutter 渲染截图和自动化测试也不代表 Android/iOS 真机画面已通过。

## 实现范围与验证

- 源码：`mobile/app/lib/features/import_video/presentation/import_video_screen.dart`。
- 回归：`mobile/app/test/features/home_workbench_test.dart` 与 `mobile/app/test/app/system_appearance_test.dart`。
- 两张已确认的封面资产、工具注册表、`pubspec.yaml` 不在本次左右滑动恢复中更改。
- 未触碰检测/追踪/分割/隐私算法、视频导出和双视频同步算法。

完整 Flutter 分析、回归测试和 iOS 构建的最终结果以本次实际运行记录为准，不能沿用上一版的 206 项计数和构建结果作为证明。

## 2026-10-30 左右滑动恢复验证回执

- `flutter analyze --no-pub`：PASS（源码与测试，0 问题）。
- `flutter test --no-pub`：206/206 PASS。专项复核覆盖 390×844 初始右侧露出下一张、左右分页点变化、第二入口点击、320×568 手势切换与 1.6 倍字体；系统暗→亮→暗及窗口宽度改变保持当前页。最后变更只增加了分页反向露出和 1.6× 字体滑动断言，专项均通过。
- Flutter golden 测试成功捕获 390×844 的四个状态：深/浅 × 保护/对比（临时文件保存在本机 `/tmp/_woah_swipe_{dark,light}_{privacy,comparison}.png`，不纳入 Git）。与先前测试相同，中文缺少字体映射，不能代替真机字体审美验收。
- `flutter build ios --simulator --debug --no-pub`：PASS，已确认 `Runner.app/Frameworks/App.framework/flutter_assets/assets/home/` 中两张原图资源与源文件字节一致。
- `xcrun simctl list devices booted`：本轮 8 秒超时，设备服务不可用，未进行 iOS 模拟器安装/启动/触控；也未进行 Android APK/真机验证。因此当前合格范围为静态、组件测试和构建，不是双平台设备最终验收。
- 仅修改本功能分支中的首页布局、对应回归和本设计说明；视频素材未被替换，检测/隐私/同步/导出算法未改变。
