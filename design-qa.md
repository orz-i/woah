# Woah Home Screen Design QA

- source visual truth: /tmp/woah_home_option3.png
- selected direction: option 3, immersive star-function hub
- implementation: mobile/app/lib/features/import_video/presentation/import_video_screen.dart
- implementation screenshot: /tmp/woah_home_option3_impl_qa.png
- runtime: iPhone 15 Pro simulator, iOS 17.0
- logical viewport: 393 x 852 pt
- source pixels: 853 x 1844
- implementation pixels: 1179 x 2556 (@3x)
- density normalization: source and implementation compared at the same approximately 393 x 852 content aspect

## Full-view comparison evidence

The implemented first screen now preserves the selected concept's primary composition:
- Woah brand at upper left with a restrained supporting line
- one central luminous star as the current protection/import entry
- orbit lines and smaller feature stars implying a multi-function system
- the buddy character living in the same star-night environment instead of inside a card
- a single dominant action, 选择视频, wired to the existing import flow
- no app bar, no persistent close control, no bottom navigation, and no stacked feature cards

The resulting screen remains an immersive product surface rather than a dashboard or card carousel.

## Focused region comparison

### Typography
The reference uses a soft rounded display face. The implementation uses the app/platform type stack for native Chinese readability and keeps similar relative hierarchy:
- Woah remains the strongest brand text
- 导入舞段 is the strongest product action label
- secondary feature labels stay below the primary action
No clipping or truncation is present at 393 x 852 or the compact 360 x 640 regression viewport.

### Spacing and layout rhythm
The initial implementation placed the buddy scene as an obvious rectangular image. This was replaced by a full lower-background scene extending beyond the screen edges with vertical feathering. The final layout keeps the orbit hub above the buddy and removes text/image collisions.

### Colors and visual tokens
The implementation matches the accepted dark-navy / warm-gold direction:
- near-black navy background
- warm-gold primary and satellite stars
- deep indigo primary CTA
- white / silver supporting copy
The implementation deliberately shifts the previous graphite home screen into the selected cosmic palette while leaving the editor/export flow theme unchanged.

### Image quality and asset fidelity
The buddy/stage region is extracted from the accepted concept as a standalone scene asset, not as a screenshot of the whole UI. UI labels, CTA, icons, orbit paths, busy states, and error states remain native Flutter components.
The buddy scene is rendered as a background environment so it does not appear as a card.

### Copy and content
Visible first-screen copy is limited to:
- Woah
- 记录每一个闪闪发光的你
- 导入舞段
- 选择视频 / import busy states
- 智能识别，自动保护隐私，让舞蹈自由被记录
- 人脸贴纸
- 智能裁切
- 更多工具

No unrequested dashboard, navigation, profile, or notification surface was introduced.

## Fidelity ledger

1. **Primary star hierarchy**
   - Concept: one giant glowing star is the only dominant actionable object.
   - Render: one giant gold star with the only active CTA.
   - Result: matched.

2. **Multi-function discoverability**
   - Concept: smaller stars orbit the current function.
   - Render: 人脸贴纸 / 智能裁切 / 更多工具 appear as satellite stars on orbit paths.
   - Result: matched structurally.

3. **Buddy integration**
   - Concept: buddy belongs to the environment below the feature hub.
   - Initial render: buddy looked like a rectangular image layer.
   - Fix: moved buddy/stage into the full page background and extended it past horizontal screen edges.
   - Result: matched at the composition level.

4. **Immersion / container model**
   - Concept: no central card frame; scene and controls float in one visual space.
   - Render: no feature card, app bar, bottom navigation, or panel wrapper.
   - Result: matched.

5. **Core action**
   - Concept: central star initiates video selection.
   - Render: central star remains wired to the existing _pickVideoAndContinue flow and preserves picking/probing states.
   - Result: matched.

6. **Responsive behavior**
   - Concept: mobile-first full-screen composition.
   - Render: verified at iPhone 15 Pro and 360 x 640 test viewport without overflow.
   - Result: matched.

## Intentional deviations

- The generated concept showed notification/account icons. They were omitted because the current product has no corresponding first-screen functionality and adding them would invent scope.
- Satellite feature stars are currently future-facing visual affordances, not independent routes. The current business flow remains only 导入舞段 → 保护编辑器.
- The concept's “上滑发现更多可能” hint was not implemented because there is no real upward discovery action yet; showing an inert gesture prompt would be misleading.
- The generated star texture is richer than the native star control; the implementation uses native Flutter iconography plus glow/sparkles for maintainability. This is a P3 visual difference only.

## Comparison history

### Pass 1
Findings:
- P1 buddy appeared as a square App Icon image and broke immersion.
- P2 satellite colors were too cool/lavender versus the warm-gold concept.
Fixes:
- extracted the approved buddy/stage scene from the concept
- changed satellite stars to warm gold
- strengthened primary star glow

### Pass 2
Findings:
- P2 scene image still read as a separate rectangular layer
- P2 lower feature copy collided visually with the scene
Fixes:
- moved the scene into the full page background
- extended the scene beyond screen edges
- added vertical feathering
- adjusted orbit/satellite spacing

### Pass 3
Findings:
- P2 buddy was too high and reduced contrast for subtitle/hint
Fixes:
- shifted buddy scene downward
- removed the inert bottom discovery hint
Post-fix evidence:
- /tmp/woah_home_option3_impl_qa.png

## Verification

- flutter analyze on changed home/test files: passed
- import/home widget tests: passed
- compact 360 x 640 home viewport: passed
- App smoke test: passed
- easter egg entry/regression tests: passed
- iPhone 15 Pro / iOS 17 render: visually verified
- runtime launch errors: none observed after full rebuild
- source and final implementation visually inspected at matching mobile aspect ratio

No actionable P0/P1/P2 findings remain.

## Home buddy motion pass

The first-screen buddy is now implemented as a dedicated five-frame IP animation rather than a static extracted scene image.

Production frame assets:
- assets/home/buddy_motion/buddy_idle.png
- assets/home/buddy_motion/buddy_step.png
- assets/home/buddy_motion/buddy_sway.png
- assets/home/buddy_motion/buddy_wave.png
- assets/home/buddy_motion/buddy_jump.png

Runtime sequence:
- idle dwell
- step
- sway
- wave
- jump
- return to idle

Implementation details:
- frames are pre-cached before display
- 120 ms cross-fade softens pose swaps
- small translation / rotation / scale motion bridges the discrete poses
- Reduce Motion locks the buddy to the idle frame
- the previous static `woah_buddy_stage.png` asset was removed
- all five assets were resized to 768 x 768 with alpha, totaling about 2.6 MB

Verification:
- widget test explicitly asserts frame 0 -> 1 -> 2 -> 3 -> 4 -> 0
- flutter analyze: passed
- relevant widget suite: 8/8 passed
- iPhone 15 Pro / iOS 17 actual runtime captured at three different moments:
  - /tmp/woah_home_motion_a.png
  - /tmp/woah_home_motion_b.png
  - /tmp/woah_home_motion_c.png
- all captures show the buddy changing pose while remaining inside the immersive home composition

No actionable P0/P1/P2 findings remain.

final result: passed


# Android 舞蹈对比 UI QA — 2026-10-25

此节仅记录对比页面，保留上方既有首页 QA 内容。

- Source visual truth：用户附件截图，以及 docs/session/comparison-ux-20261025/02-current-phone.png。
- Intended changes：更大比较画布、选片后自动采用最佳位置、不显示候选、手动模式双独立播放条并隐藏共同条。
- Implementation：50140291bad331f4895e5de694c79bdf4c4d278c；14-main-final.png、14-manual-final.png。
- Viewport：1080×2400，逻辑 360×800，DPR 3；前后截图同像素尺寸、同设备与主题，无设备外框缩放。后期学生源片已由用户更换，因此不对不同视频内容做像素相等比较。
- Full-view evidence：调整前、最终共同页和最终手动页在同一视觉输入中一起检查；共同画布由 898px 增至 1352px。
- Focused checks：控制区、双源进度条、完成/取消按钮、校准文案和切换后时间更新；截图已逐张打开审查。
- Fidelity surfaces：沿用 AppTheme 字体/颜色，间距与触控目标检查通过，原视频比例保留，文案对应最终自动/手动流程。
- Iterations：修复候选常驻、粗跳转、纯图标播放、大字体拥挤，以及真机纹理切换后旧帧问题。后者通过先移除 widget、等待帧结束再释放播放器修复，取消和完成路径均已复测。
- Validation：Flutter 94 项全套通过；最终切换修复后 14 项专项通过，最新静态检查无问题，APK 本机/安装哈希一致。
- P0/P1/P2：无待解决项。P3：源视频内自带黑边/播放器按原样保留。尚未做完整 TalkBack 或多机型审计。
- Full report：[docs/comparison-ux-2026-10-25.md](docs/comparison-ux-2026-10-25.md)。

final result: passed


## 手动校准控制区压缩 — 2026-10-25

- Source：用户附件 14-manual-final.png；最终运行图 15-manual-compact-final.png，均为同一 KB2000 的 1080×2400（360×800dp）。两张完整截图在同一视觉输入中对照，随后检查时间、滑条和底部操作区域。
- Intent：减少滑条整体占比，移除顶部说明。实现为每路单排 48dp 控制区（原 96dp），2dp 轨道和 12dp 圆点；时间置于视频角落，错误提示按需显示。
- Fidelity：主题颜色与字体沿用现有 AppTheme；视频比例与素材不变；释放的高度归还视频；角色与时间层级清晰；完成/取消保持原位置与操作。
- Validation：相关 14 项通过，含小屏、横屏与 2 倍字体；48dp 触控目标保留，静态检查无问题。真机老师拖到 8.949s 后学生未动，学生拖到 11.918s 后老师未动；取消恢复原 +1.450s。构建/安装 APK 哈希一致。
- Scope：仅本次手动页布局；不重复全量 Release QA，不扩展为全产品可访问性或多设备验收。无待处理的本次 P0/P1/P2 问题。
- Code：db936a50fa5e23fcf0b4b72177e80bfa60c3a6d1。详细数据见 docs/comparison-ux-2026-10-25.md 的紧凑布局补充。

final result: passed
