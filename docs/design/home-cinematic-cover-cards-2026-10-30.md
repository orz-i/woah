# Woah 首页影院封面与左右滑动卡片栈（2026-10-30）

实现分支：`feat/home-cinematic-cover-cards`。首次封面接入提交：`ab0fb29`。本文件记录**最新有效设计**；之前的上下双卡布局已由用户明确要求撤回。

## 目标与选图

首页只展示已实现的两个独立工作流；恢复早期首页的中心主卡 + 横向 PageView + 相邻卡片边缘预告 + 分页点，不增加工具库、编辑器内部功能或底部导航。保持 `Woah` 单一字标和长按进入 Wavi 彩蛋的行为。

- **视频保护：** 采用 [用户指定的 ChatGPT 分享图片](https://chatgpt.com/s/m_6ae421ff14508191ae0520fbe4bc0364) 中的第一张原图。清晰主角与左右柔化配角；不再使用边界错位的人工 Mask。原始 PNG SHA-256：`c123c0e9b4a2edff3d45702ad83ec85a5964d30b5c314ea98c4646409f756863`（1536 × 1024）。
- **舞蹈对比：** 采用独立左右双视频封面，不是双人在同一场景中起舞的合照；原始 PNG SHA-256：`1b25fba72898294d0eff853dda23c51d526c2c0966c9d1172362843d491b3077`（1536 × 668）。
- 两张运行期资源位于 `mobile/app/assets/home/home-video-protection.jpg` 与 `home-dance-comparison.jpg`，分别约 255/65 KiB。舞蹈对比使用由原始双视频 PNG 裁切得到的卡片专用竖幅合成图，原始 PNG 不加入安装包。
- 图片只作为功能概念展示，**不是**隐私保护正确率、人物 Mask 或舞蹈动作分析的实际输出验证。

## 最新布局：保留原有左右滑动栈

- 移动端继续使用原生 `PageView.builder` 横向分页，`PageController.viewportFraction` 跟随宽度按 `cardWidth * 0.90 / viewportWidth` 计算；通过 `clipBehavior: Clip.none` 与左右相邻卡片露出部分体现滑动方向。
- 卡片宽度 `min(300dp, 可用宽度 × 0.88)`，高度 `(宽度 × 1.12).clamp(250dp, 336dp)`，圆角 22dp、无可见描边、底部柔和双层投影。分页指示当前卡为 17×5dp、其他为 5×5dp，点击交互为整卡单一操作。
- 首卡「视频保护」，左滑后显示「舞蹈对比」；第二张左侧可见上一张的局部。卡片仍是两个独立可执行工作流。设备仅支持一个工作流时不创建轮播页或分页点。
- **适配竖向卡比例的最新方案：** 两卡统一使用 `BoxFit.cover` 铺满全部 300×336dp 卡片。视频保护保留主角居中，舞蹈对比将原始横屏双视频的左右半幅分别按人物为中心裁切成竖幅，并以原始像素的柔和延展填充画面上下；中线仍明确标示双视频。不存在独立横幅图片条或上下硬分区。
- 顶部 Woah W 字标保留长按彩蛋；卡片标题、描述、箭头与导入忙碌状态浮在封面底部，同一固定 20dp 左右/底部边距；以连续透明→Ink 深色渐变保护文字可读性，没有独立色块。相机画面上的文字固定高对比 Pearl/浅青与 Mint；页面背景及非媒体 UI 继续来自 `AppTheme.of(context)`，深浅主题不反转封面像素。
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

## 2026-10-30 全幅封面与文字叠加修订

- **用户要求：** 去掉照片与文字间的色块分区，文字浮在图片底部，并使两张卡在视觉尺寸、主体比例及文字基线方面对齐。
- **实现：** 两张卡片内都以单个 `Image.asset` 在 `StackFit.expand` 铺满整个卡片；用一个高度连续的底部渐变作为文字 scrim，而不是第二个实体面板。原生标题 `20sp / w600 / #F2F6F3`、说明 `13.5sp / #D9E6E2`、箭头 `23dp / #78DCC7` 固定距左右 20dp、底部 20dp。Flutter InkWell 继续覆盖全部图片及文字。
- **视频保护封面：** 用户确认的原有 JPEG 完全不变，主角真实清晰，未新增或移动任何 Mask。
- **舞蹈对比封面：** 运行时 `home-dance-comparison.jpg` 现在为 600×672 / ~65 KiB 的竖版双路裁切，直接取自前一提交中 1536×668 的双视频照片：两半各取以舞者为中心的纵向视区，按 0.75 倍原比例置入等宽的左/右 300px 画面，背景用相同照片柔化延伸并进行纵向羽化，在中间保留细分界线。没有新生成人物、增添特效 Mask 或调换两路角色；原照片可从 Git 历史版本 `d71cd0b` 恢复。
- **视觉对齐：** 两张卡片共享 `PageView` 的固定宽高、同一 `BoxFit.cover`、圆角与阴影、相同底部文字坐标。Flutter widget 测试比较卡片与图片矩形尺寸、两张标题及说明的底部 Y 坐标（偏差 ≤0.5dp）。
- **全尺寸截图：** 390×844 / DPR 1 深色和浅色、视频保护与舞蹈对比共 4 张，分别保存到 Mac 的 `/tmp/_woah_bleed_v2_{dark,light}_{privacy,comparison}.png`；同时验证 320×568 / 1.6× 字号两页不溢出。
- **限度：** Flutter 字体测试环境会将部分汉字显示成方框，无法直接证明 Android/iOS 本地字体表现及深浅主题触感；图片只是功能视觉示意，不代表隐私分割算法真实输出。

### 本轮全幅改造最终验证回执

- 最终 `flutter analyze --no-pub`：PASS（0 问题）；`flutter test --no-pub`：206/206 PASS。
- 390×844：两张卡片图片均与卡片尺寸完全一致，且标题、说明在同一底部 Y 坐标（Widget 测试允许误差 0.5dp）。双路封面不再通过中间横幅与其余背景分区。
- 320×568 / 1.6 倍字体：能滑动并显示两张卡片，未出现溢出；原图片在大字体下可能被文字覆盖更多，属于系统文字放大的预期布局。
- 四个光照/滑动状态截图：`/tmp/_woah_bleed_v2_dark_privacy.png`、`/tmp/_woah_bleed_v2_dark_comparison.png`、`/tmp/_woah_bleed_v2_light_privacy.png`、`/tmp/_woah_bleed_v2_light_comparison.png`。另有 320×568、1.6× 字体截图 `/tmp/_woah_bleed_compact_{privacy,comparison}.png`。源/实施对比确认原比较卡只有横向图片条且两侧背景虚化，现在双舞者贯穿卡片主体。
- iOS Simulator Debug `flutter build ios --simulator --debug --no-pub`：PASS，`Runner.app` 内两张 JPEG 与源码逐字节一致，复用本轮最终源码。
- CoreSimulator `simctl list devices booted`：8 秒超时，未证明实际 iOS 模拟器安装、点击与中文字体渲染；Android 真机也没有新增验证。不宣称完成设备级 UI 验收。
- 实际生产图片的 Mask/隐私算法不在本轮改动范围，卡片截图只是功能表达。


## 2026-10-30 Android 滑动中间态：标题基线修复

- **真实证据：** 用户主动保持手指未松开并截取 Android 首页两卡同时出现的照片（Screenshot_2026-10-30-14-39-55-03_07b3adb6d9524e5cdc0bf7b5cdbfde65.jpg）。两卡外框顶部/底部本来对齐；此前把中间态误判为回弹或居中异常，现更正。
- **原因：**「视频保护」说明为一行，「舞蹈对比」说明在真实中文字体下为两行。原 `Positioned(bottom:20)` 固定整个动态高度的标题和说明 `Column`，因此后者标题上移约一行。
- **修复：** 保留图片、文字、卡片尺寸与滑动手势，仅对说明固定预留两行高度（`MediaQuery.textScalerOf(context).scale(13.5 * 1.42 * 2)`），以 `Align.topLeft` 排列首行，保证两个标题、两个说明首行共用顶部坐标。忙碌时仍保留同一标题位置。
- **回归：** 390×844 两卡标题与说明首行几何误差 ≤0.5dp；360×800 字体 1.0×/1.6×；360×800 保持手指未松开的 -140dp 中间帧直接比较双卡；导入忙碌文本变更仍不移动标题。
- **组件截图：** Mac `/tmp/woah-midgesture-aligned.png（实际为 /tmp/woah-midgesture-aligned.png），Flutter 截图缺少真实 Android 中文字体，只证明组件布局。
- **验证：** Flutter Analyze PASS，Flutter 209/209 测试 PASS，iOS Simulator Debug 构建 PASS。Android 修订版尚未重新安装实拍，不能宣称设备视觉已验收。

## 2026-10-30 竖向留白与卡片占比收敛

### 用户问题与定位

用户 Android 首页截图在上一轮已证实卡片边界对齐；此次继续指出顶部品牌与卡片之间、分页点与页面底部的留白仍过多。旧卡片被限制在最多约 300×336dp 的轮播舞台，垂直间距公式在底部额外预留 76dp，使底部留白明显大于顶部。问题属于首页布局与卡片比例，并非滑动中间态或字幕基线。

### 实施

- 轮播卡片宽度从 min(300, usableWidth × 0.88) 改为 min(360, usableWidth × 0.98)，PageController.viewportFraction 同步调整为 cardWidth / viewportWidth。保留下一张边缘预告与整卡点击。
- 轮播舞台高度改为 (cardWidth × 1.52 + 17).clamp(365, 580)，其中 17dp 对应分页点及间距。卡片继续使用单一连续摄影背景，不增加面板或按钮。
- 品牌底边至卡片顶边，与分页点底边至屏幕底边按可用高度配平：gapAboveCard = ((remainingHeight + verticalPadding) / 2).clamp(32,160)。去除旧方案不对称的 76dp 冗余。紧凑屏使用 SingleChildScrollView 兜底。
- 原有摄影封面、统一说明双行槽位、颜色主题、滑动索引及点击入口不变。无隐私、追踪或视频同步算法修改。

### 度量与验收边界

- 360×800 / Flutter DPR1：旧代码卡片约 239×291dp，新卡片 **298×465dp**；品牌底边到卡片顶部约 **128dp**，分页点底边到屏幕底部约 **129dp**，旧方案底部约 268dp。旧尺寸依据前一提交布局公式计算，非用户屏幕直接像素测量。
- 390×844：卡片约 **327×509dp**，左右滑动中保留相邻卡片露出，分页点可用。
- 五档布局：320×568、360×640、360×800、390×844、440×956。检查卡片占比、上下留白差≤18dp、邻卡露出与渲染异常。
- 360×800 组件截图保存在 Mac：/tmp/_woah_home_final_spacing_Brightness.light-protection.png、对应 light-comparison / light-midgesture / dark 等状态。Widget 截图没有正常中文系统字体，不代表 Android 真机视觉验收。
- 全量分析/测试、iOS 模拟器构建以本轮实际结果为准。真机截图仍待复核。
- **真实滑动中间态组件截图：** /tmp/woah-home-spacing-final-midgesture.png；Flutter 手势先移动 24dp 以让横向识别器接管，再保持按住移动 116dp，确认主卡横向实际位移超过 40dp 后截图。该照片同时可见两卡，既不是静止页也不是截图误标。底部悬浮文字共用标题与说明基线。
