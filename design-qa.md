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
