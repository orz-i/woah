# Woah / DanceAnon 移动端侧视频处理架构文档

本文档全面梳理 Woah (DanceAnon) 系统的端侧架构设计、分层交互、核心处理管线、生命周期管理与隐私安全保障原则。

---

## 1. 系统分层架构 (Layered Architecture)

系统严格划分为跨平台 UI / 领域层、类型安全跨进程通信桥梁、原生视频与 AI 处理引擎三层：

```text
Flutter App
│
├── Presentation Layer (Screens, Widgets, Controllers)
├── State Management (Riverpod StateNotifier)
└── Domain Layer (DanceProject, EffectConfig, VideoInfo)
        │
        ▼
Pigeon Type-Safe Bridge (dance_api.dart)
        │
        ├── Android: DanceNativeApiImpl.kt (Kotlin)
        └── iOS: DanceNativePlugin.swift (Swift Stub / Capabilities)
        │
        ▼
Android Native Engine
├── Video Analysis (AnalyzePipeline.kt)
├── Real-time Preview (PreviewPipeline.kt + PreviewAnalysisCache)
├── Background Export (ExportForegroundService + ExportPipeline)
├── Segmentation Inference (YoloOnnxSegmenter + YOLOv11-seg)
├── Multi-Object Tracking (TrackManager: 8D Kalman + Hungarian + warpMask)
├── Privacy Guard (PrivacySegmentationProcessor: Dilation Safety)
├── Hardware Rendering (GlRenderer + GlShaders + InferenceRenderer)
└── Media Codec & Muxing (MediaCodec + MediaExtractor + MediaMuxer)
```

---

## 2. 核心处理管线 (Pipelines)

### 2.1 AnalyzePipeline (首帧检测与人物提取)
1. **触发时机**：用户导入视频后自动调用。
2. **处理流程**：
   - 探测视频元数据（`VideoProbe.probe`），获取正向宽高、时长与旋转角。
   - 使用 `MediaMetadataRetriever` 提取首帧 Bitmap（或通过 OpenGL 解码）。
   - 将画面送入 `YoloOnnxSegmenter.segmentBitmapSync`。
   - 经 `YoloPostprocessor` 计算所有人物的 Bounding Box、Mask 及脚底参考点。
   - 经 `PrivacySegmentationProcessor.DEFAULT.applyPrivacySafety` 应用安全膨胀。
   - 提取各人物的头像缩略图保存在本地缓存目录。
   - 结果写入 `AnalysisResultDto` 返回 Flutter，供用户选择需要保护的人物。

---

### 2.2 PreviewPipeline (实时特效调参预览)
1. **交互特性**：与 Flutter `EffectEditorController` 联动，支持 200ms 防抖与请求序列号（Sequence ID）校验，防止旧响应覆盖新响应。
2. **V1 稳定性约束**：
   - EffectEditor V1 严格限制 `timestampMs = 0` 进行首帧预览，彻底规避任意时间戳单帧匈牙利匹配导致的身份漂移。
   - 架构预留 `TrackingSnapshotCache` 规范接口，为未来任意时间戳追踪预览奠定演进基础。
3. **LRU 缓存机制 (`PreviewAnalysisCache`)**：
   - 采用最大容量限制（3~5 个条目）的线程安全 LRU 缓存。
   - 淘汰条目时自动触发底层 `Bitmap.recycle()`，杜绝 Native 内存泄漏。
   - 提供 `clearForAnalysis(cacheId)` 与 `clear()`，在 `releaseProject()` 及插件退出时与磁盘缓存联动释放。

---

### 2.3 ExportPipeline (高吞吐端侧完整视频导出)
1. **硬件零拷贝编解码管线**：
   ```text
   MediaExtractor (MP4 File)
           │
           ▼
   MediaCodec Decoder (Hardware Surface Rendering)
           │
           ▼
   SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
           │
           ▼
   GlRenderer (OpenGL ES 2.0/3.0 Shaders: Masking, Blur, Whiten, Leg Stretch)
           │
           ▼
   EGL Input Surface (Zero-Copy)
           │
           ▼
   MediaCodec Encoder (Hardware H.264/HEVC)
           │
           ▼
   MediaMuxer (Audio Mux + Atomically Finalized Output MP4)
   ```
2. **高频写盘与渲染开销优化**：
   - 移除了高开销的 1080p full-res 截屏写盘逻辑，Export 进度仅传输轻量数值指标。
   - 导出文件使用临时文件 `output.mp4.tmp`，全部渲染、编码与音频混流完成后原子重命名为目标文件。
3. **取消安全性与异常防御**：
   - 在解码循环、帧读取、编码回收、音频混流等关键检查点均注入 `isCancelled.get()` 检查。
   - 触发取消或发生异常时，`finally` 块确保全面释放 Decoder、Encoder、Muxer、GL 资源并删除未完成的临时文件。

---

## 3. 服务与前台生命周期 (Service & Lifecycle)

1. **`ExportForegroundService` 机制**：
   - 导出任务独立运行在前台服务中，具备独立的通知栏进度常驻展示。
   - 接入系统的 `WAKE_LOCK`，防止设备在导出高负载长视频时息屏进入休眠。
2. **启动与失败通知保障 (P1-04)**：
   - 若系统限制前台服务启动（如 Android 12+ Background Launch Restrictions），`ExportServiceController` 立即向外抛出异常。
   - `DanceNativeApiImpl.startExport` 捕获后同步更新 `ExportJobStore` 状态为 `failed`，并向 Flutter 抛出 `DanceNativeException`，绝不给 Flutter 留下挂起的死任务。
3. **统一取消链路 (P1-03)**：
   - 统一收拢入口：`ExportCoordinator.cancelJob(jobId)` 与 `ExportCoordinator.cancelAllJobs()`。
   - 触发时置位对应任务的 `AtomicBoolean`，通知管线优雅停止，等待协程安全清理退出后移除前台通知。
4. **任务持久化节流 (`ExportJobStore`)**：
   - 内存状态高频即时更新。
   - 磁盘文件（`export_jobs.json`）写入进行节流（默认 3 秒写一次），大幅降低闪存寿命损耗与 I/O 阻塞。
   - 任务进入终态（`completed`, `failed`, `cancelled`, `interrupted`）时**立即同步刷盘**，保证状态永不丢失。

---

## 4. 目标跟踪与 Mask 空间连续性 (Tracking & Continuity)

1. **8D 状态向量卡尔曼滤波**：
   - 跟踪状态：`[cx, cy, a, h, v_cx, v_cy, v_a, v_h]`。
   - 学习人物平移与尺度缩放速度，在短时遮挡下保持预测稳定性。
2. **REMOVED 轨迹彻底清除**：
   - 当人物连续丢失帧数超过阈值（`missedFrames > maxMissedFrames`），`predict()` 内部真正执行 `tracks.removeAll { it.state == TrackState.REMOVED }`，防止已离开画面的人物错误复活或争抢匹配。
   - 跟踪器具备 `hasInitialized` 标记，首帧后即便画面中所有人离开，后序新进入的人物依然单调递增分配全新 ID。
3. **warpMask 几何一致性**：
   - 通过 `ModelCoordinateMapper` 建立 `sourceToProto` 映射，彻底解决 16:9、9:16、1:1 等画面在 Letterbox 下的偏移与黑边变形问题。

---

## 5. 隐私保障最高原则 (Privacy-First Principles)

1. **Strict Person-Only 遮挡与特效**：
   - 严禁对背景执行无区别变形或处理。
   - 在 `GlShaders.kt` 中，Leg Stretch（拉腿）严格限制在人体 Mask 内部，背景像素坐标保持绝对不变，杜绝背景门框、地砖线条弯曲畸变。
2. **统一隐私安全管线 (`PrivacySegmentationProcessor`)**：
   - 全系统（分析、预览、导出）统一接入隐私膨胀策略（默认对 Proto Mask 进行 1 像素安全外扩，对应物理原图数像素外延）。
   - 任何情况下绝不为了性能降低隐私遮盖完整性——允许轻微多遮，严禁露体。
3. **能力诚实原则**：
   - 真实汇报设备参数，文案采用“本地端侧处理中”，明确区分图形 GPU、AI 推理后端与硬件编解码器。
   - 尚未验证的贴纸功能在 V1 中明确隐藏，不向用户承诺虚假特性。
