# Lime Beat 验证记录

日期：2026-11-04。参考为用户选定的青柠节拍首页；现有应用保持左右滑动卡片与原工作流。

## UI 与品牌提交前核验

本次单独归档 UI、品牌资源、相关测试及设计文档，基于隐私修复提交 `60ab1ce7`。品牌资源、系统明暗外观和 iOS / Android 启动器图标三项静态检查通过，`flutter analyze --no-pub lib test` 无问题。

62 个既有 UI / 品牌修改文件逐一与此前完整回归的输入哈希核对一致，首页两张摄影封面与 W 标识也与已安装 APK 内资产逐字节一致，因此复用该轮 **Flutter 272 / 272** 有效结果，见 [测试日志](../../../reports/privacy-regression-2026-11-04/flutter-tests.txt)。该 APK 的主流程和媒体回归见 [隐私完整回归报告](../../../reports/privacy-regression-2026-11-04/README.md)，不将其扩大为 iOS 或其他 Android 启动器的视觉认证。

VI 2.2 正式 PDF 与既有 15 页文档复核记录的 SHA-256 一致；资产包 ZIP CRC 和 58 项内部文件清单全部通过。正式 v2.2 PDF / ZIP、可编辑源和视觉证据随本次提交保留。v2.0 / v2.1 的重复 PDF / ZIP 仅本地留存，文件名、大小和 SHA-256 见 [历史交付索引](deliverables/local-history.json)。本次不重新生成影像或标识，不对外推送或发布。

## 隐私配置：透明悬浮图标工具条

预览工具条移至实际视频画面右上角，边距 8dp；移除独占横条以及“原片预览”、保护人数、操作说明等常驻文字。取消背景与圆角包裹，仅保留轻微阴影；长按说明、读屏提示与 48dp 图标触摸区域保留。用户指出亮青柠与底部颜色不一致后，悬浮图标改为与底部工具、时间轴、原声开关共用 `editorSelection`：亮色统一 `#50670E`，暗色统一 `#D8F54C`；禁用状态保持同色相并降低不透明度。

`flutter analyze --no-pub lib test` 通过；悬浮工具条接入时 `protection_editor_test.dart` **36 / 36** 通过，覆盖横竖画面、工具面板切换、视频边界锚定、仅图标展示、原片 / 裁切切换、恢复默认、清空对象和裁剪后的主角重选 / 确认。本次只修改悬浮图标的颜色角色，新增“悬浮图标颜色等于底部选中图标”的相对断言，明暗两套现有测试 **2 / 2** 通过；其余有效行为检查复用。

最终 APK 构建通过，保留数据覆盖安装至 OnePlus 8T，并核对设备 APK 的 SHA-256 与最终构建一致。恢复同一视频与剪辑面板后，已实际检查[最终同色图标栏截图](qa/floating-preview-toolbar/04-after-matched-color.png)：悬浮恢复图标与底部剪辑控件均含有相同的 `#50670E` 实心像素，分别为 460 / 2122 个；见 `color-match-review.json`。图标保持透明悬浮，无背景包裹或常驻说明文字。`02-after.png`、`03-after-transparent.png` 是用户纠正前的中间版本，不作为最终视觉依据。本次不改媒体像素与导出参数，不据此声称真实保护效果或导出回归通过。

## 当前交付：VI 2.2 首页视觉柔化

按用户“视觉上有点冲击”的反馈，首页将纯青柠底部色块替换为浅青柠渐变：76% 处淡入，90% 处为 `0xE6F5F8EB`，底部为 `#F0F6DA`。W 宽度 32 → 28dp，Woah 字号 28 → 24sp，间隔 10 → 8dp。品牌仍为浅色底青柠主版本；摄影源图、卡片尺寸、底部文字行高、左右滑动与入口行为保留。

相关主题、系统切换、首页与品牌长按测试 **32 / 32** 通过。品牌行使用最小高度约束，放大字号可自然增高。配色和原生图标资源沿用 v2.1 已通过结果，不重新生成未变化的封面或图标。

最终 `flutter analyze --no-pub lib test` 与 APK 构建通过，新包已保留数据覆盖安装至 OnePlus 8T。冷启动、两张卡片静止状态、双向滑动与文案可读性已复核；[调整前后](qa/soft-home/04-before-after.png)及最新首页截图留档。15 页手册全部重新渲染，实际检查整本预览及第 10 / 12 页，文字无越界；字体、SVG、ZIP CRC 和清单校验通过。媒体播放、真实保护效果与导出不在本次首页调整的验证范围。

当前正式交付：[VI 手册 v2.2](deliverables/Woah-Lime-Beat-VI-v2.2.pdf)、[资产包 v2.2](deliverables/Woah-Lime-Beat-Assets-v2.2.zip)。真机与文档复核证据见 `qa/soft-home/`、`qa/vi-document-review.json`、`qa/vi-package-review.json`；v2.1 与更早版本仅保留历史。

## 历史：VI 2.1 亮色主版本

按用户最新确认，亮色统一为 Chalk `#F7F9F1` 浅白底与 Lime `#D8F54C` 青柠主标识：首页 W 和 Woah 字标、应用图标、启动页、双语正式标识、海报与 Token 同步。Ink / Chalk 单色版保留为辅助。正文和必要操作提示继续使用 Ink / Olive，暗色配套保持原样。

- 当时的 15 页手册 `deliverables/Woah-Lime-Beat-VI-v2.1.pdf` 与资产包 `deliverables/Woah-Lime-Beat-Assets-v2.1.zip` 曾替代 v2.0；现仅作为本地版本历史，当前交付为 v2.2。
- `flutter analyze --no-pub lib test` 通过；主题、系统切换、首页共 **28 / 28** 测试通过，涵盖切换后的字标颜色、卡片页码与原文字基线检查。
- 品牌资源、明暗资源、Any / Dark / Tinted 与 Android 图标静态检查均通过。iOS 本轮未编译或运行，Android 其他启动器实际呈现不由静态检查保证。
- 新 APK 已保留数据覆盖安装至 OnePlus 8T，冷启动成功；亮色两张首页静止截图均已复核：[视频保护](qa/alignment-fix/04-after-protection.png)、[舞蹈对比](qa/alignment-fix/05-after-comparison.png)。滑动、页码、文案位置保持。
- 全部 15 页重新渲染，整本预览及标识、图标、真机应用、宣传应用页已检查；文字无页面越界。PDF 字体、ZIP CRC、SVG 解析与文件清单校验通过，见 `qa/vi-document-review.json`、`qa/vi-package-review.json`。

以下为 v2.0 与首轮接入历史；配色及正式交付以 v2.1 为准，最新首页截图已更新为亮色青柠主版本。

## VI 2.0 与文字基线补齐

用户反馈上一轮正式 VI 文件没有同步。已找到原 `Woah-Quiet-Frame-VI-v1.1.pdf` 与资产 ZIP，以原 W / 字标源为基础完成当时的 15 页正式手册 `deliverables/Woah-Lime-Beat-VI-v2.0.pdf` 和可编辑资产包 `deliverables/Woah-Lime-Beat-Assets-v2.0.zip`。旧下载文件保留，v2.0 现为本地历史产物。

- Logo：移除上一轮保留的深薄荷主版本，统一 Ink / Lime / Chalk；浅色应用图标为青柠底 + Ink W，浅色启动页与首页为 Chalk + Ink W。W 几何保持不变。
- 正式资产：符号、字标、横向与双语组合 SVG，iOS/Android 图标源，摄影、宣传应用、JSON/CSS Token 和授权字体均已换版，不仅是仓库里的 Markdown。
- 文字：标题与说明采用强制 Strut 行高，保留两行说明槽位；正常 / 1.6 倍字号及滑动中间状态比较实际文字基线。受影响 4 个测试文件 **32 / 32** 通过；补充中间状态基线断言后首页 **20 / 20** 再次通过。
- 静态分析与品牌 / 明暗 / 图标检查通过，最终 Debug APK 已保留数据覆盖安装到 OnePlus 8T。
- v2.0 真机复核确认两卡文字基线一致、Logo 当时为 Ink；系统顶部及底部行为不变。上述首页截图现已更新为 v2.1，文字区域与[同高度文字对照](qa/alignment-fix/06-aligned-label-proof.png)保持一致。
- 手册 15 页均已渲染，整本预览及 Logo / 字体 / 真机应用页已实际检查；文本均在页面边界内，无旧薄荷品牌色值混入新规范。

以下保留首轮接入记录。当前交付以 VI 2.1 和上述最新截图为准。

## 已完成

- `flutter analyze --no-pub lib test`：通过，应用源码与测试无静态分析问题。
- `flutter test --no-pub`：**270 / 270 通过**。包括主题切换、首页滑卡 / 导入状态、比较工作流、编辑器、导出及结果页面现有回归。
- 最后补充表单焦点、光标和默认进度指示语义颜色后，`flutter analyze --no-pub lib test/app/theme_test.dart` 及主题 **6 / 6** 测试再次通过；随后重新构建并安装最终 APK。
- `verify_quiet_frame_assets.py`：通过，检验新 VI 色值、两张新封面引用、品牌命名、资源尺寸及用户特效 ARGB 隔离。
- `verify_system_appearance.py`：通过，检验 Flutter 系统明暗模式及 Android / iOS 启动资源。
- `verify_launcher_icon_appearances.py`：通过，检验 Any / Dark / Tinted、Android 自适应 / 单色 / 夜间图标与 RGB 格式。
- `verify_wavi_assets.py`：通过，既有彩蛋资源与互动约束保留。
- `tools/release/verify_ios_phase0.py`：静态检查通过。

纯色 sRGB 计算：Ink / Lime **13.56:1**，Olive / 白色 **6.39:1**，必要控件边界 / 白色 **4.02:1**。Lime / Chalk 仅 **1.16:1**，所以青柠不用作浅色页面上的小字，分页青柠胶囊增加深色细边界。半透明照片区域需结合真机截图判断，不能用纯色值代替。

## 当前构建与设备验证

`flutter build apk --debug --no-pub --target-platform android-arm64` 成功。最终 APK 通过 `adb install -r` 保留数据覆盖安装到已连接 OnePlus 8T（Android 14，1080 × 2400 px，density 480），冷启动成功，复核结束时应用进程仍存活。

已检查并保存：

1. [视频保护首页](qa/01-home-protection.png)：新摄影、浅色渐变、墨色文案、箭头与相邻卡片露边清晰，无旧暗场图回退。
2. [舞蹈对比首页](qa/02-home-comparison.png)：左右滑动正常，第二张新封面和分页同步，标题 / 说明位置一致。
3. [比较入口](qa/03-comparison-entry.png)：整卡进入原有比较页；已保存项目提示源视频当前不可用，未重选用户视频或声称播放 / 导出通过。
4. [比较返回](qa/04-home-return.png)：系统返回保留原先的第二张卡片页码。
5. [选择器取消返回](qa/05-home-picker-cancelled.png)：视频保护整卡打开系统 `PhotoPickerGetContentActivity`；未选择视频、未截取相册内容，取消后正常回到首页，无残留忙碌状态。

[参考与真机同图比较](qa/reference-device-comparison.png)和[文字区域比较](qa/label-comparison.png)已实际打开检查。原生系统顶部留黑及底部手势条是原设备行为，不是新增的品牌装饰；为保持现有布局与系统行为，本轮不改系统栏策略。

视觉结果：本轮首页范围无待修复 P0 / P1 / P2 项；保留原生字体、卡片几何与分页深色边界是遵循既有产品约束 / 可读性的有意差异。完整比较见项目根 `design-qa.md` 当前 Lime Beat 记录。

iOS 资源已更新并通过静态检查，本轮尚未在 macOS 编译或 iOS 设备运行。Android 深色主题切换由现有 Widget 测试覆盖，未改变用户设备全局外观设置来重复验证。

## 检查范围与已知限制

直接运行仓库根级 `flutter analyze --no-pub` 会扫描已有 `mobile/app/build/audit3`、`build/premerge_audit`、`build/reaudit` 临时审计代码并报告 16 项存量提示 / 警告。本轮没有修改这些临时文件；正式 `lib`、`test` 的定向分析通过。

本次变更不修改分割 / 追踪模型、用户视频像素、特效 ARGB、校准与导出算法。单元测试通过不等于新增隐私效果认证或全平台真机视觉验收。以上为测试发生时的历史记录；本次整理仅进行本地提交，不推送或发布。
