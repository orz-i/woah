# Mobile 主角自动运镜裁切

## 范围

仅实现 `mobile`；不修改历史 `desktop`。入口位于统一保护编辑器的“输出画幅”。

选择“竖屏 9:16”，在完整原画上轻触主角后，预览切换为竖屏构图。
主角与保护范围仍是独立配置，但主角必须保持可见：如果所选主角当前正在 FULL_BODY 或 FACE_ONLY 保护集合中，选择主角时会自动将其从保护集合移除；未被保护的主角不会被自动加入保护。“原画选保护对象”
可暂时返回完整画面调整保护成员，“裁切预览”返回竖屏。清空保护对象后可以仅裁切导出。
“更换主角”重新选择相机目标，“原画”关闭裁切并恢复原输出尺寸。

## 当前功能边界

- 编辑器显示裁剪时间段的首帧构图，不是完整运镜视频预览；时间运镜发生在最终导出。
- 固定单主角，默认 9:16，最大保留源画面高度；没有额外特写缩放、自动切人、场景切换识别或手工关键帧。
- 主角必须由用户明确选择；不会再从保护对象或人物列表中静默选择第一个人作为镜头目标。
- 主角在边缘时裁切框停在源画面边界，不能保证人物始终严格居中；姿态横向展开超过裁切宽度时仍可能截断四肢。
- 丢失可信观察后，相机先使用最多 6 帧的当前主角身份预测；若该身份处于遮挡/重获阶段且存在与预测框高度重叠、中心和尺寸都接近的当前可观测 duplicate/occluder track，则只把该 track 作为临时相机代理，不修改真实人物 ID、相机身份锚点或隐私归属。OCCLUSION_PROXY 的 bbox 中心会经过独立滞回稳定器：横向 1.5% 源宽可立即突破，0.7%–1.5% 的同向漂移需持续约 150 ms，进入跟随后只保留 0.4% 的小释放区；纵向使用相同语义的 1.2% / 0.6% / 0.35% 门槛。短周期左右摆动会被吸收，而真实持续移动仍可通过。若当前相机身份随后进入 LOST/被 tracker 移除，则允许 camera-only ID 接力：650 ms 内使用常规唯一几何门槛；650 ms–1.1 s 只接受 IoU/中心/尺度更强的唯一连续候选。接力后的新 ID 成为新的相机身份锚点，因此可以安全链式处理 `旧 ID → 新 ID → 再新 ID` 的普通 tracker 断链；任一阶段存在多个近似候选或超过 1.1 s 都保持最后可信构图，不凭空切人。
- 复用已有分割/身份跟踪，不增加第二套检测模型，不恢复 SAM2 或 ONNX。

## 几何与状态约定

`FollowConfig.outputAspectRatio` 及 Pigeon DTO 是新增可空字段，空值保留旧项目的源比例缩放语义。
9:16 导出使用宽高分别为 18、32 的整数倍，保证编码偶数尺寸、精确比例及源像素不放大。理想输出不再固定限制最长边 1920，而由共享 `ExportPlan` 根据设备编码能力统一决定是否降级。
例如 1920×1080 源理想输出 594×1056，3840×2160 源理想输出 1206×2144；设备若只支持 1080p，则由 `ExportPlan` 显式按比例降级，而不是在 Android/iOS native pipeline 中静默改尺寸。

相机内部使用完整、已旋转显示方向的源空间归一化坐标，左上角为原点。
平滑以视频 PTS 为准，将 30 Hz 参考 alpha 0.1 转换为 `1 - (1-alpha)^(dt*30)`。运动品质层不再把该 alpha 直接变成位置跳步，而是先生成目标速度，再经过加速度/刹车限制：
小于裁切跨度 2% 的变化保持稳定；大位移的允许速度会从每秒 0.65 个裁切跨度渐进提高到 1.50 个裁切跨度，加速度上限为 5.5 span/s²、刹车/反向上限为 7.5 span/s²，单次时间步仍最大 100 ms。这样主角重新出现或发生 camera ID handoff 时镜头不会瞬间满速启动，急转向时也先减速；同时横向大位移比旧固定 0.8 span/s 更快追上。时间倒退、关闭跟随或更换目标会清除位置和速度状态。

Android 跟随开启时，推理/跟踪始终保持完整源显示空间，原有隐私保护链路不再感知竖屏输出尺寸。
主角不加入隐私级 strict identity-protected 集合，也不加入隐私集合；运镜使用独立、短时且带唯一性约束的 camera continuity bridge，避免严格隐私身份规则在交叉遮挡时把相机永久冻结。自动运镜改为渲染后处理：第一 GPU pass 先按完整源画幅
完成 full-body mask、遮挡关系和 face sticker 合成；第二 GPU pass 只对已经保护完成的 RGBA 纹理应用 cropRect。
因此裁切不再参与任何隐私 mask/贴纸的采样坐标。mask 没有显式 samplingRect 时，也必须使用其原始源画幅尺寸，
禁止回退到 9:16 输出尺寸。主角相机代理只消费现有 tracker 的 bbox，不参与 TrackManager 身份变更。top-left crop 到 screen-GL 的转换与 post-crop texture matrix 均由纯几何单测锁定。
9:16 首帧预览和最终导出都使用同一后处理结构与 18×32 整数单位画幅；预览失败时不会随意换主角。

iOS 使用相同参数的 `IOSSubjectReframer`，跟踪使用完整源画面，并与 Android 采用相同的临时遮挡代理、链式 camera identity handoff、650 ms 常规门槛及 1.1 s 强几何延伸门槛。
源画面直接按裁切窗口采样到输出尺寸，隐私 mask、人脸区域和贴纸位置同步转换。
`IOSMetalPreviewRenderer` 的 tightMask 参数显式传入隐私构造函数，并在渲染入口拒绝非法/越界裁窗。
iOS 首帧预览也复用与 Android 一致的精确 9:16 尺寸规则。

源画面选人时不在裁切图上保留旧坐标点击区域；异步预览请求在排队时立即失效旧结果，
切换画幅会清空不兼容的旧图。结果页和导出页使用输出比例。

## 验证命令

Windows，在对应独立 worktree 内运行：

```powershell
cd mobile/app
flutter analyze --no-pub
flutter test --no-pub
flutter build apk --debug --no-pub
cd android
.\gradlew.bat :dance_native:testDebugUnitTest --offline --console=plain
```

领域模型与桥接测试分别在 `mobile/packages/dance_domain` 和 `mobile/packages/dance_native` 运行：

```text
dart test
flutter test --no-pub
```

独立 Swift 主机，在 `mobile/packages/dance_native/ios` 下运行（只验证相机算法，不验证 iOS SDK）：

```sh
swiftc dance_native/Sources/dance_native/IOSSubjectReframer.swift tests/subject_reframe/main.swift -o /tmp/subject-reframe-test
/tmp/subject-reframe-test
```

## 本轮已取得的证据与待验收项

当前实现的宿主自动化证据：

- Android 原生 348 项单测通过，0 failure / 0 error / 0 skipped。其中 `SmoothFollowerTest` 11 项，`ReframeGeometryTest` 4 项。
- Flutter app 全量 64 项测试通过；领域模型 10 项、native Dart/Pigeon 桥接 5 项测试通过。
- `flutter analyze --no-pub` 通过；Pigeon Dart/Kotlin/Swift 代码已由当前 schema 重新生成。
- 最新 Android debug APK 构建通过，产物 `mobile/app/build/app/outputs/flutter-apk/app-debug.apk`，SHA-256 为
  `0d21aa43fbd5d3cc30939c0bb8aedfc88093eec1128c8d6b12013865ba551998`。

2026-09-26 的运镜连续性治理在 macOS Woah 工作区进行。当前 Mac 没有 Android JDK/SDK，因此本轮新增的 Android handoff 单测尚不能在该宿主重新执行；上面的 Android/Flutter 数字仅是此前基线证据，不能替代本轮 Android 回归。

本轮 iOS 已使用 `/Applications/Xcode.app` 临时 `DEVELOPER_DIR` 构建 `Runner.xcworkspace` 的 `dance_native` Debug / iPhoneSimulator scheme，包含链式 handoff、acceleration-limited `IOSSubjectReframer` 与 proxy-only `IOSOcclusionProxyStabilizer`，构建成功；独立 Swift 主机测试覆盖 dead-zone、加速启动、大位移追赶、反向刹车、缺失保持、proxy 抖动保持、持续漂移突破和 reset。Android 的 `AUTO_REFRAME_SAMPLE` 额外输出 camera velocity / target-error，以及 `proxy_raw_center_x`、`proxy_stabilized_center_x`、`proxy_stabilization_delta_x`，供下一份诊断直接量化 OCCLUSION_PROXY 的微运镜抑制。基于 2026-09-26 两份真实诊断逐帧 track signature 的离线回放，当前横向门槛预计可将两段代理轨迹的中心总变动分别压低约 60% 与 77%，同时保留长期段的明显方向变化。仍需用真实视频完成 Android/iOS 导出视觉验收。

下一次设备验证应覆盖横屏左右移动、交叉遮挡、快速舞蹈动作、
短暂及长期离场、带旋转元数据的视频、音频/时间裁剪，以及纯裁切、全身保护、人脸贴纸三种输出。
