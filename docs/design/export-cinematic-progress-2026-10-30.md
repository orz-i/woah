# Woah 导出页 · Cinematic Progress / Quiet Frame

日期：2026-10-30
分支：feat/export-cinematic-progress
实现：mobile/app/lib/features/export/presentation/export_screen.dart

## 设计目标与参考

用户提供的 Screenshot_2026-10-21-15-21-44-01_99c04817c0de5652397fc8b56c3b3817.jpg，原图 939×2048，作为页面视觉方向：灰黑至暖黑渐变、大圆角金紫蓝发光影像舞台、点状笑脸进度符号、居中的阶段与百分比、底部圆形停止按钮。使用真实导出处理数据，而不是把参考中的道路照片或“智能移除路人、杂物”等当前没有的功能描述嵌入产品。

## 实现内容

- 活动中的 queued/preparing/processing/muxing 采用参考所示沉浸式暗色媒体舞台，明暗系统主题下使用一致暗色背景及适配暗背景的系统状态栏。失败状态维持已有随主题变化的返回、重试、复制错误及诊断操作。
- 上部摄影框宽约屏幕减 44dp（紧凑宽度减 32dp），上限 460dp，约 1.40:1，保留 30dp 圆角；按照最新评审取消金色至紫蓝色渐变细边与光晕，改为没有装饰性边框及阴影的纯黑媒体框。初始预览来自编辑器，后续预览遵循用户开关；中间真实媒体通过 BoxFit.contain 保留完整宽高比，同一帧模糊暗化的 UI-only 背景填充周边空白而非裁掉主角。
- 真实预览叠加少量轻质点阵，仅为 UI 装饰，不进入导出视频。默认实时预览仍关闭以避免性能负担；整个媒体框可点击切换，右下角使用小型查看图标和 Tooltip，而不是覆盖大片描述。
- 中间为 48×48dp 点状笑脸标志与真实百分比，阶段文案根据 ExportJobState 简写为“正在准备视频 / 正在保护画面 / 正在保存视频”等。百分比直接读取 ExportState.progress，预计剩余时间来自真实帧/fps，不发明百分比。
- 真实的裁切清晰度恢复倍率与设备编码能力降级继续按需呈现；移除底部“视频仅在本设备离线处理”说明。已完成时仍由原控制器跳转结果页。
- 底部停止为 72dp 半透明深圆圈和 20dp 白色圆角方形；安全确认对话框、native cancel 之后返回、任务 ID 不存在时不可取消等契约不变。
- 无预览图片时保留事实占位与开启实时预览的操作；不把参考图的照片、人物或道路固定打入 App。

## 适配与设计差异

- 390×844：媒体框、进度、停止控件分层布局；320×568 可压缩纵向空白并支持滚动。
- 1.6×/2× 字体：使用自然换行/滚动来保障取消控件可达。
- 浅色系统主题：进行中的导出使用暗色参考风格；失败与确认弹窗保留已有主题设定。
- 当参考图内照片的比例不同于真实输出媒体宽高比时，内层素材完整显示并用同帧模糊作为周围衬底，这是保护舞蹈主体的功能性差异。

## 验证证据

- 原始参考截图为 939×2048，Flutter 组件截图为 390×844 / DPR1；以统一 390×844 归一化大小拼成对照图：Mac /tmp/woah_export_design_qa_comparison.png（两者媒体内容同源，仅为版式比较）。
- 改造后组件截图：Mac /tmp/_woah_export_release_dark.png、/tmp/_woah_export_release_light.png。内图为从用户提供参考中裁取的临时 QA 照片，存在 /tmp/woah_export_reference_inner_photo_qa.jpg，**未加入 APK 或 iOS 安装包**。
- 重点检查框体比例、圆角光晕、进度标记、状态文字行数、停止按钮位置、浅/深色舞台一致性。先前过长的处理阶段文案已收敛，媒体光晕已减弱；可读的中文字符仍需 Android/iOS 真机截图验证。
- Flutter Golden 的中文字形显示为方框，不能据此判断字体渲染与实际中文字宽。临时截图基于模拟导出 Repository，也不能证明真机隐私处理或真实预览输出。
- iOS Simulator Debug 构建通过一次本地 CocoaPods 兼容模式。Mac Flutter 默认启用的 Swift Package Manager 当前生成缺少 file_picker_darwin 的插件包，直接构建曾失败；本次临时关闭该配置完成本地构建，随后已恢复全局 Flutter 设置并还原生成的 Xcode 工程变更。这是构建环境兼容事项，不在本轮 UI 改动范围。
- 真机 Android/iOS 实际处理、预览开关、字体及取消/失败/完成验收仍待进行。

## 最终本地验证回执

- 最终源码执行 flutter analyze --no-pub：PASS，0 问题。
- 最终源码执行 flutter test --no-pub：231 / 231 PASS，其中导出专项 29 / 29 PASS。
- 最终源码 iOS Simulator Debug 构建：本地 CocoaPods 兼容模式 PASS，Runner.app 成功生成；Flutter 全局 Swift Package Manager 已恢复为原设定，Runner.xcodeproj 未保留任何跟踪差异。默认 SPM 路径仍需另行治理，不把此次临时构建 workaround 作为产品源代码修改。
- 实际 iOS/Android 设备视觉与真实视频导出验收仍为 NOT PROVEN，不宣称 release QA 全部通过。


## 2026-10-30 视觉精简更新（覆盖此前发光版）

- 删除彩色渐变预览边框及三层外部光晕，保留 30dp 媒体圆角、完整预览宽高比和原有 UI-only 模糊填充；媒体框不再配置边线、渐变或 boxShadow。
- 删除进度标题下方“正在本机处理已选的视频编辑设置”，删除底部“视频仅在本设备离线处理”；同时删除只为 Android 说明文字服务的 showBackgroundHint 参数。标题与预计剩余时间的间距为 12dp，真实进度、ETA、实际编码回退说明继续保留。
- 390×844 / DPR1 深浅主题组件截图：Mac /tmp/_woah_export_borderless_dark.png 与 /tmp/_woah_export_borderless_light.png。复用参考图中的临时 QA 图片，图片不打包进 App。相较历史截图 /tmp/_woah_export_release_dark.png，预览外侧去掉彩色光环、正文减少两处说明，其余重要交互布局不变。
- 已在 export_test.dart 增加无边框/无渐变/无外阴影断言，深浅与 320×568/390×844 下确认两处文案不存在。Flutter Golden 不含真实中文系统字形，Android 真机视觉复核仍待完成。

### 本轮最终本地验证

- Flutter Analyze：0 问题；Flutter 全量测试：231/231 PASS；导出专项：29/29 PASS。
- iOS Simulator Debug：本地 CocoaPods 兼容模式构建成功，未改变任何已跟踪的 Xcode 工程文件，Flutter 全局 SPM 配置已恢复。
- 390×844 深浅组件截图已生成，两个主题的导出中页面均没有彩色光晕、两段说明文字；正式中文字体及真机视频预览仍待 Android/iOS 验收。
