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


## 视频内选片与历史入口 — 2026-10-25

- Source visual truth：用户附件“微信图片_20261025231922_7_328.jpg”，以及同状态的原设备完整截图 docs/session/comparison-ux-20261025/16-source-before.png。
- Implementation：1e1807668d24a837d14a78c3bda39560e7760be6；docs/session/comparison-ux-20261025/16-main-final.png、16-history-opened.png。
- Viewport/state：同一 KB2000，完整截图均 1080×2400 / DPR 3，逻辑 360×800；同一原片对、手动 +1.450s、暂停于 0。用户附件用于意图，不对其压缩尺寸作精确像素比较；全尺寸真机图无外框缩放。
- Full-view evidence：调整前、最终对比页与历史列表置于同一比较输入，最终页另单独打开复核；视频舞台 1352→1544px，顶部独立选片栏移除，原控制台位置保留。
- Focused checks：老师/学生视频角落按钮、历史图标与标题、更多菜单、历史记录恢复。全尺寸图中这些区域可读，无需额外裁切。
- Typography：沿用 AppTheme 和现有原生字体，角色 12sp，历史延续导航文字层级；小屏和 2 倍字体回归无溢出。
- Spacing/layout：按钮各 72×48dp，保留触控面积；两路按钮随竖屏上下或横屏左右的视频区域定位。移除重复角色/选片栏后，释放的 64dp 归还视频。
- Colors/tokens：沿用石墨灰边框、深色半透明按钮与主题文字；未选加号和金色提示使用既有强调色。
- Image quality：复用原视频，保持完整人物和源比例；源内自带播放器与黑边保留，未替换素材或生成图片。
- Copy/content：顶部与列表使用“历史 / 对比历史”；角色标签兼任选片，交换动作迁至更多菜单。保存模型和保存重命名功能保持原有语义。
- Findings/iterations：一次实现后无待修复的本次 P0/P1/P2；选片、历史、菜单与画布增高均符合明确调整目标，无扩展其他页面。沿用此前记录的源素材自带 UI 限制。
- Validation：14 项相关测试、改动文件静态检查、Debug 构建/安装与哈希比对通过。真机两个角色入口打开系统选片器并取消返回，历史恢复成功，原校准/锚点/位置保持，日志未见应用异常。不扩展为全产品可访问性或多机型验收。
- Implementation checklist：选片整合、顶部栏移除、历史重命名、交换迁移、专项回归、真机证据均完成。

final result: passed


## 选片无边框与历史仅图标 — 2026-10-25

- Source：本轮明确样式指令及上一版 docs/session/comparison-ux-20261025/16-main-final.png；实现：17-main-final.png，代码 0fbf334e95feff59543dfb16e784220019699033。
- 同一设备 1080×2400 / DPR 3（360×800dp）；两图在同一视觉输入中对照。用户已交换视频顺序，偏移由 +1.450s 变为 -1.450s，只比较界面控件、导航和布局，不据此评价视频内容一致性。
- Full-view/focused：完整页面布局保留，重点检查两处角色控件无描边、历史图标没有常驻文字、控制台及点击区域。全尺寸图可读，无需额外裁切。
- Fonts/copy：沿用字体和字号，移除历史可见文字并保留 tooltip/无障碍名称；角色标签文字保留。
- Spacing/colors：维持 48dp 触控，历史改标准图标按钮；移除选片边框，沿用原有半透明背景与颜色。
- Image quality：视频仍按原比例显示；顺序变化来自用户交换。本次不改变素材或图像处理。
- Validation：现有相关交互测试 1 项通过，改动文件静态检查无问题，构建安装及哈希一致；真机点击历史正常打开返回，交换后的原校准、锚点和位置保留。
- Findings/checklist：两项明确调整完成，无本次待处理 P0/P1/P2；此前手动校准等未变更部分沿用有效验证。

final result: passed


## 上下/左右与横竖屏 — 2026-10-26

- Source visual truth：用户明确扩展当前页面的要求，以及 docs/session/comparison-ux-20261025/18-view-baseline.png。实施沿用已有原生页面、主题和用户素材，新增视图菜单，不创建新原型或资源。
- Implementation：最终代码 2801d1f78ca285f7617369e38d824f19f83bc617；20-portrait-ready.png、20-landscape-final-zero.png、20-view-menu.png；手动页补充 18-manual-portrait-side.png 和 18-manual-landscape-side.png。
- Viewport/state：KB2000，竖屏 1080×2400 / DPR 3（360×800dp）；横屏 2400×1080（800×360dp）。源与最终竖屏同尺寸、同素材对、共同 0、1×、+1.450s；横屏是有意新增状态，不与竖屏做像素相等判断。
- Full-view evidence：原上下页、最终竖屏左右、横屏左右与菜单一起置于同一视觉输入中对照；另逐张查看手动窄栏及横屏手动页。
- Focused checks：顶部视图/历史/更多导航，菜单选中态，角色入口随排列定位，公共控制台，手动两路进度条与完成/取消。完整图可读，无需额外裁切。
- Fonts/typography：延续 AppTheme 原生字体、字号和字重；窄栏时间用两行显示；320×640 与 2 倍字体测试通过，未缩小触控目标。
- Spacing/layout：上下与左右独立于屏幕方向；横屏控制台在右侧，竖屏在底部。手动宽栏保持单排 48dp，窄栏把滑条与微调分两排，保留滑条宽度和至少 48dp 点击区域。
- Colors/tokens：沿用石墨灰、黑色舞台、银白文字及金色播放强调；保留无边框角色入口与仅图标历史。
- Image quality：原视频完整适配各 pane，无新图片或占位图；原素材自带 UI/黑边及处理效果保留；原生 44 项中的几何测试覆盖两种排列、横竖素材与视口边界。
- Copy/content：新增“视图 / 上下视图 / 左右视图 / 横屏显示 / 竖屏显示”，不引入实现细节到产品页面；选中状态与当前布局一致。
- Comparison history：初版视觉布局可用，但 [P1] 从横屏退出后首页仍横屏（18-exit-restored-home.png）。固定横屏请求本身未解决。最终记录进入角度，等待恢复稳定后释放；20-exit-first-home.png 与 20-exit-second-home.png 两轮均回到竖屏且旋转设置恢复 0。先前 P1 已关闭。
- Validation：最终 16 项 Flutter 通过、静态检查无问题，44 项原生有效结果复用；最终包安装哈希一致。真机完成四种组合、播放中切换、seek、手动独立拖动、后台、项目恢复和退出方向恢复。最终保留原锚点，测试进度归零。没有未完成的本次 P0/P1/P2。
- Scope/limits：单台 Android 14 真机结论；保留此前源素材自带 UI 的 P3 限制，不声称完成多机型或完整可访问性审计。
- Checklist：布局切换、保存兼容、页面横竖屏、手动窄栏、播放恢复、退出还原、真机证据均完成。详见 docs/comparison-views-2026-10-26.md。

final result: passed


## 中央视图与参考/目标画面选片 — 2026-10-26

- Source visual truth：用户四项明确调整，以及 docs/session/comparison-ux-20261025/21-reference-target-before.png；隐私打码页的 `_buildTopActions` 文字与箭头排列作为入口样式参照。实施沿用现有 Flutter 页面、主题、原生图标和素材。
- Implementation：1831a65、8319f20；最终 22-empty-final.png、22-loaded-final.png、22-centered-menu.png 和 22-landscape-menu.png。
- Viewport/state：KB2000，竖屏 1080×2400 / DPR 3，横屏 2400×1080；原空槽图与最终空槽图同尺寸。用户本轮已改自动 +1.490s、17.899s、1×，不与上一轮 +1.450s 素材帧作像素相等比较。
- Full-view/focused evidence：原空槽、最终空槽、最终竖屏菜单和横屏菜单在同一视觉输入中对照；检查导航、角色、选片入口、公共控制台与弹出面板。完整图可读，无需额外裁切。
- Fonts/typography：中央文字使用 13.5sp / w500，箭头 18dp；角色标签 12sp，沿用 AppTheme 字体。320×640、800×360 与 2 倍字号回归无溢出。
- Spacing/layout：移除标题与角色下拉，整个 pane 作为选片命中区域；标题保留 104×48dp 隐形触控区，菜单宽 200dp。竖屏菜单与入口中心同为 540px，横屏安全区同为 1252px；顶部历史仍只有图标。
- Colors/tokens：沿用黑色舞台、石墨控制台、银白文字、金色播放与滑条；角色无描边，顶部入口不增加胶囊装饰。
- Image quality：保持原视频适配比例；使用原生加号和角色文字表达空槽，无新图片。参考源内自带播放器和结尾暗帧继续保留。
- Copy/content：页面与手动页统一“参考 / 目标”，包含空槽提示、选片/更换无障碍名称、交换菜单和历史缺片回退文案；内部保存字段兼容。
- Findings/iterations：初版仅触发文字居中，弹出面板仍偏右（21-centered-view-menu.png），记录为 P2。8319f20 通过显式宽度与偏移修正，并补充实际弹出条目中心断言；最终真机横竖屏均关闭该 P2。无待处理的本次 P0/P1/P2。
- Validation：17 项专项回归、静态检查、Debug 构建及覆盖安装通过，本地/安装 APK SHA-256 一致。两路已加载画面和空槽选片均可取消返回；手动取消保留校准；两条历史的 ID、标题、偏移、校准状态、锚点、位置、速度和布局均保留。最终运行日志无应用异常。
- Checklist/limits：四项明确调整与相关检查完成；单台 Android 14 结论，原素材自带 UI 的限制保留；不扩展全产品可访问性、多机型或全量 Release 验收。详细数据见 docs/comparison-reference-target-2026-10-26.md。

final result: passed


## A/B 循环、独立镜像与真实 PTS 逐帧 — 2026-10-26

- Source：用户明确要求的三项练习功能，沿用既有对比页和主题；原参考为 22-loaded-final.png，新增控件不改变视频比例、整区域选片和中央视图入口。
- Implementation：当前 feat/dance-comparison-m0 工作区，HEAD e286cf6，未提交/推送/合并；最终 APK SHA-256 为 07d40f7df2969716493fb9d4f463ab5700a4cdad4c5211cfe4910300b702df3c。
- Full-view evidence：已查看完整竖屏练习页、横屏镜像与控件、双独立手动预览、真实素材暂停和最终原项目恢复图；证据在本地忽略目录 docs/session/comparison-practice-20261026/。
- Layout/type/tokens：保留现有石墨色控制台、银白文字及金色选中状态；新增一排 A/B、循环和帧按钮。640×320 窄横屏分两排以保留 48dp 触控，320×640、800×360 与 2 倍字体无溢出且可滚动访问；24 项 Flutter 专项通过。
- Image/copy：视图菜单使用“参考镜像 / 目标镜像”；镜像只作用于视频，文字标签与控件不翻转。共同时间、音乐偏移和动作差异保留；逐帧使用参考实际解码 PTS，包含 VFR 的不同帧间隔。
- Pixel evidence：同一暂停帧检查参考单独、目标单独、两路和均不镜像四种状态；排除 UI 覆盖后的反射误差 <0.75，另一侧原像素不变。真实素材参考/目标镜像误差分别约 0.39/1.22；镜像前后暂停位置与校准保持。
- Findings/repair：真机发现循环回跳准备期暂停按钮禁用。原生改为 buffering 并保留暂停，逐帧等待画面就绪；新增控件回归及真机单次暂停通过，后续无自动循环。原片尾与重播状态通过定向回归。
- Persistence/lifecycle：循环点和镜像随项目保存、交换及冷启动恢复；横竖屏、手动取消、循环中后台与清除后越过 B 均通过。最终恢复原两条历史的内容字段；一条更新时间变化。临时项目及媒体已清理，原亮屏设置和旋转值均为 0。
- Validation/scope：原生专项 49 项、静态检查、Release Kotlin 定向编译、Debug 构建和覆盖安装哈希通过。按未改动代码范围复用早期逐帧/镜像证据，未重跑完整 M0 矩阵或全量 Release QA。循环使用共同 seek，保留短暂定位开销，不宣称无缝音频；未新增长原片、多机型或物理显示测量。
- Report：[comparison-practice-2026-10-26.md](docs/comparison-practice-2026-10-26.md)。本次要求及必要验证完成，无未关闭的本次 P0/P1/P2。

final result: passed


## 独立缩放平移与问题片段 — 2026-10-26

- Source/implementation：用户明确要求的两项练习功能，沿用现有 Flutter 对比页、GL 合成器和 AppTheme。当前 feat/dance-comparison-m0 工作区，HEAD e286cf6；本次未提交、推送或合并。
- Full-view evidence：已查看竖屏实际拖动、横屏左右布局、手动双预览、真实素材调整页及原项目恢复完整图。截图、XML 与原始日志位于本地忽略目录 docs/session/comparison-picture-markers-20261026/。
- Layout/type/tokens：中央视图菜单新增「调整画面」，专用模式提供参考/目标选择、1–4× 缩放、平移、单路重置、完成/取消；金色边框标记选中画面。延续现有字体与图标，保留 48dp 触控；窄栏和大字体可滚动访问。书签角标使用现有 Badge，无新图片资源。
- Image/gesture：每路在自身 pane 缩放和裁剪，保留原比例，兼容镜像与横竖布局。手机完成按钮放大及实际单指拖动，参考改变时目标像素误差为 0；两路相对预期变换的像素平均绝对差为 2.21895 / 1.39230。Flutter 多指针测试覆盖双指缩放；手机 shell 注入 helper 被系统以 137 结束，真机双指未据此记为通过，helper 已移除。
- Copy/flow：使用「问题片段 / 开始时间 / 结束时间 / 问题备注」；可创建、编辑、删除、暂停定位及设为 A/B。源锚点在交换后保留，换源或无可用范围时提示并禁用定位/循环；备注仍可保留。
- Findings/repair：大字体窄栏的缩放标签溢出已通过弹性布局修复；真机发现数量角标覆盖读屏名称，已明确图标语义并排除角标文字。最终组件断言与手机 XML 均确认「问题片段」名称保留。
- Persistence/lifecycle：两路画面与标记保存、取消、后台未确认草稿、冷启动、横竖屏及手动取消通过；微秒起止点保留。手机实际循环两次，交换后负偏移映射通过，删除可跨冷启动保留。临时项目、合成素材与触控 helper 清理完成，原两条历史的内容及更新时间保留，系统设置保持原值。
- Validation/scope：33 项 Flutter、55 项原生、静态检查、Release Kotlin 定向编译、Debug 构建和覆盖安装哈希通过。最终 APK SHA-256 为 3c6135b9ce87f7847c6d1749d78d16789f997be21b4761600dee4b174513ff35。仅语义修复后的包复用未改动的原生/像素检查结果；没有重跑全量 M0 或全量 Release QA。
- Report：[comparison-picture-markers-2026-10-26.md](docs/comparison-picture-markers-2026-10-26.md)。本机素材与覆盖范围内功能完成；长时原片、多机型、导出与 iOS 未新增验收。

final result: passed within recorded scope


## A/B 同轨剪辑式选段与单一时间读数 — 2026-10-26

- Source visual truth：原对比页 `docs/session/comparison-ab-timeline-20261026/00-source-before.png`、四时间版本 `00-centered-before-interaction.png`，以及隐私打码页既有 `_buildIntegratedTrimTrack` / `_IntegratedTrimHandle`。按用户最终要求使用轨道中线剪辑式选区，时间整合为一个情境读数。
- Implementation：最终截图 `00-one-clock-before-interaction.png`、`03-B-dragged-live.png`、`08-landscape.png`。当前分支 feat/dance-comparison-m0，HEAD e286cf6；未提交、推送或合并。
- Viewport/state：竖屏 1080×2400 / Flutter 360×800 / DPR 3；横屏 2400×1080 / 800×360。前后使用同一暂停位置 8.344 秒、+1.490 秒偏移、上下排列与镜像设置。原生媒体等比适配可用区域，不将邻近视频帧当作像素完全相等。
- Combined comparison：已将前后图组合到 `qa-one-clock-full.png` 与 `qa-one-clock-focused.png` 并查看；前者检查整体层级，后者检查时间与轨道细节。横屏及拖动中的实际界面也已查看。
- Fonts/typography：沿用系统字体，常态仅一个 12sp 等宽数字时间；拖动时同处临时显示 A/B 端点时间。总时长和两端常驻数字移除，完整微秒数据仍保留于项目及读屏。窄栏、大字体专项无溢出。
- Spacing/layout：选区框 14dp 高、把手 10×14dp，均与播放轨道中线对齐；白色播放针 2×18dp。触控/语义目标保留 48dp，近邻端点可分别操作；移除下挂浮标和多余时间行，预览获得更多空间，播放及练习入口仍可见。
- Colors/tokens：沿用 AppTheme 的石墨背景、灰轨、金色选区和银白播放针；沿用现有剪辑页圆角与细边框，没有新增卡片或装饰。
- Image quality/assets：沿用真实视频 Texture 和现有图标，无新图片或生产字体资产；原素材自带播放器及暗帧保留。
- Copy/interaction：默认显示当前播放位置，拖动 A/B 时显示当前调整值；点击轨道/远离把手处拖动定位，拖把手选段。循环、逐帧与问题片段保持可用，选区可带入片段表单。
- Comparison history：初版进度填充与选段混淆，修复为明确区间框与播放针。金色方框、下挂竖柄被用户指出不协调，改用已有剪辑页的同轨居中把手。四处常驻时间被指出冗余，改为单一情境读数。最终同视口对照及真机已确认上述 P2 修复；无待处理的本次 P0/P1/P2。
- Functional checks：41 项 Flutter、静态检查、Debug 构建及安装哈希通过。最终手机完成中线把手拖动、独立定位、3 次共同循环、单时间临时切换、冷启动、横竖屏、片段默认选区和清除。原两条历史内容保持，一条更新时间变化，临时项目已清理；设备设置保持原值。
- Scope/checklist：核对字体、布局、颜色、图像、文案及核心操作。原生 11 个相关输入未变化，复用此前 55 项结果；未重跑全量 M0 或全量 Release QA。仅本机与所用素材范围，无新增导出、iOS 或多机型覆盖。
- Report：[comparison-ab-timeline-2026-10-26.md](docs/comparison-ab-timeline-2026-10-26.md)。最终 APK SHA-256：8ac68213d742f3d116fb10462ce816d4a48bf43cd9575afd31c2125a8a4e7aa0。

final result: passed


## 2026-10-26：恒定音乐速率与双锚点校准

本轮沿用现有对比页、单一共同时间读数和剪辑式 A/B 轨道。手动页增加「只调位置 / 校准速度差」及前后音乐点步骤；正常观看仅在存在速率差时增加相对音乐速度读数。真机发现底部步骤区压缩横屏预览，已改为横屏侧栏并保留原上下/左右排列；最终两路预览各约 243px，高于 48dp 最小可见区检查。模式选择使用既有金色状态，保持 48dp 操作目标。

最终截图已查看：`layout-landscape-manual.png`、`layout-portrait.png`、`layout-manual-applied.png`、`real-music-matched.png`、`final-original-restored.png`，位于忽略目录 `docs/session/comparison-music-rate-20261026/`。实机完成双锚点记录/应用/取消与冷启动；标记、逆映射、真实 PTS 和循环定向检查通过。Flutter 53 项、原生 50 项唯一用例及静态检查通过。

16kHz 无需重采样的输入现在直接消费 PCM，避免调用未启用的 Sonic；真实音乐副本的时间戳问题通过修正验收素材解决，未削弱产品时间契约。原两条项目内容与时间戳保持，临时项目和媒体已清理。最终包 SHA-256 `418acfc6fea58704126fcb358095fc4bcf1bfbe5ef70e6e345f127876a67d5a1`。本轮范围内无待处理阻断问题；未重复全量 M0 或 Release QA。完整边界与证据见 `docs/comparison-music-rate-2026-10-26.md` / `.json`。


## 2026-10-26：Android 对比视频导出

正式对比页「更多操作」增加导出入口，面板沿用现有 Material/AppTheme，提供全段或 A/B 选段、尺寸/1× 编码说明、进度、取消、重试和相册动作。无新装饰资产或时间轴改版。活动后台任务先恢复进度，结束后再恢复预览，避免占用四路视频解码。

Flutter 62 项唯一用例、相关原生 30 项及静态检查通过。真机通过专项 instrumentation 验证正式管线与前台服务；独立检查全段/精确 A/B、正逆音乐速率映射、布局及独立画面设置、VFR 真实 PTS、HEVC/44.1kHz 真实音乐派生副本、静音 AAC、1080×1800 输出，以及取消/重试/相册哈希和幂等保存。修正 AAC 固有延迟与尾部封装后，音频比对偏差 0–1 个采样，时长偏差最多 74µs。

用户解锁后已补齐正式页全段/A/B、后台完成、取消选项、运行中取消、冻结设置重试、返回键取消及预览恢复/播放暂停。已查看选项、进度、完成、取消恢复和系统相册播放截图；默认 OPPO 视频的首次协议未同意，改用已就绪的本地相册实际播放，没有更改默认应用。文件核对发现初版 A/B 少一帧，已修复异步 Surface 队列，并加入逐帧提交/编码 PTS 一致性保护；修复后手机画布 1080×1402 的 A/B 143 帧、全段 591 帧均完整。

本轮验收已完成，清理后手机自动锁屏不影响已完成结果。只运行本轮导出相关检查，没有重复全量 M0 或 Release QA。原两条项目逐字节恢复，测试文件和相册条目已清理，导出服务/唤醒锁释放，设备设置保持。完整范围、最终包哈希与待办见 `docs/comparison-export-2026-10-26.md` / `.json`。

最终 APK SHA-256：`e72f19222394696364872224c86ce672df61d2fc317799f5a7c2a0595db91b42`。代码未提交、未推送、未合并。

## 2026-10-26：iOS 舞蹈对比接入

沿用现有参考/目标、单一时间读数和剪辑式 A/B 轨道，iOS 首页开放正式对比入口。复用 Flutter 页面，通过 Swift/Pigeon 接入共同音频时钟、双画面合成、恒定音乐速率校准、真实 PTS 逐帧、独立画面设置、标记及项目保存恢复；导出复用同一时间/画面设置，提供全段与 A/B 的 1× H.264/AAC、有限后台任务、Photos 保存和本地播放。

iOS 17 独立模拟器 16 阶段全部通过。已查看跨安装容器变更后的正式对比页截图：双纹理、1.250s 位置、音乐映射、镜像/缩放平移、A/B 和一个标记恢复正常；项目 JSON 和媒体内容保留稳定身份。最终成片 26 帧均通过时序与像素检查，音频 lag=0，视频/音频/container 时长误差均为 0。Flutter 47 个有效用例及定向静态检查通过，相关 Mac 原生门禁通过；正式 `lib/main.dart` 的 iPhoneOS 无签名 Debug 和模拟器构建通过。

本轮修复视频输出重建导致 seek 时钟提前推进、压缩媒体原 PTS 未应用编辑列表偏移，以及 CMTime 过早取整的边界问题；591 个可见帧索引已与独立解码逐项核对。Windows/Mac 沿用同分支同步，未提交、推送或合并，未重跑全量 M0 或 Release QA。物理 iPhone/iPad、后台额度实际到期、PHPicker 手动选入与权限弹窗/拒绝仍未验证。完整证据与接续命令见 `docs/comparison-ios-2026-10-26.md` / `.json`。
