# 隐私打码剩余失败修复与完整回归

日期：2026-11-04。状态：原有 1 项 JVM 和 8 项真机失败已全部消除；同批素材完整回归通过，未发现尚未解决的新增问题。[机器可读汇总](test-summary.json)。

## 修复与测试纠正

| 问题 | 原因与处理 |
| --- | --- |
| JVM 诊断包符号链接排除失败 | Windows `File.canonicalFile` 不解析符号链接。快照复制前和打包前均增加不跟随链接的普通文件检查；Android 24/25 保留 canonical 路径校验。原有目录外文件拒绝、敏感文本脱敏和质量二进制不变断言全部保留并通过。 |
| 3 项人脸失联/休眠测试失败 | 无身体观测超过 30 帧后，旧脸仍会开启局部检测；增加身体观测窗口限制，保留当前可信身体运动证据的探测能力。身体框对称扩缩曾被误算成单边平移，导致休眠重激活被拒绝；现在使用其中心位移，单边形变仍使用较安静的边。原来的大位移拒绝、探测失败继续隐藏、检测成功恢复且不放大贴纸等断言通过。 |
| 4 项导出/预览样式测试失败 | 测试请求关闭了人脸贴纸，却检查黄色贴纸。改为明确请求“全身红色色块 + 人脸内置贴纸”，保留颜色、位置、尺寸、独立目标、残留色块及下半身不变断言。另增加关闭贴纸时的纯色人脸正向检查。 |
| 300 帧压力测试超过 40ms | 同一个 BlazeFace full-range 模型在当前设备的 CPU 路径 p95 为 65.01ms。持续导出采用 GPU，实例在各自固定线程上创建、调用和关闭，保留 CPU 回退；新增单次与批量调用结果一致性检查。编译模型缓存在应用私有 code cache；单帧预览继续用 CPU，避免反复 GPU 冷启动拖慢预览。最终 p95 为 **20.78ms**，原 **40ms** 断言不变；300 次检测全命中，拒绝、预测、回退、未解析帧均为 0。 |

新增 4 项 JVM 回归：身体框对称扩张/收缩、孤立遮罩接缝闭合、外边界和较大间隙保持。新增 1 项真机 GPU 工作线程单次/批量一致性检查。原 39 项真机用例全部保留。

GPU 缓存选项按项目实际使用的 MediaPipe 1.0.0 AAR API 核对，并参考 [MediaPipe BaseOptions 官方实现](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/core/BaseOptions.java)。没有替换模型或降低检测频率、置信度及性能断言。

## 回归发现并修复的接缝缺口

新 GPU 几何在多人全员人脸保护第 6 帧形成了一处单像素栅格接缝，成片放大后可见原图。保留失败成片与原检查结果，没有放宽孔洞检查阈值。

仅在人脸遮罩合并后补齐上下左右均为完全不透明遮罩的孤立空像素。检查原输入，不连锁扩张；没有缺口时不复制缓冲区。单元测试验证输入、边缘开口和较大间隙保持。最终同一片段孔洞数 **1 → 0**。

- 修复前：`group_all_faces-before-seam-fix.mp4`（本地留存）、[检查结果](privacy-mask-hole-audit-before-seam-fix.json)、[局部截图](gpu-hole-detail.png)。
- 修复后：`group_all_faces.mp4`（本地留存）、[局部截图](seam-fixed-detail.png)、[最终逐帧检查](privacy-mask-hole-audit.json)。

最终多人跟随 76 帧、全员人脸 76 帧、单人 40 帧、全长跟随 581 帧，共 **773 帧**，内部缺口为 0。检查保留原 AVC 色度/抗锯齿边缘排除规则：只忽略外边界 3 像素内毛刺，内部暴露超过 5 像素仍判失败。

## 检查结果

| 检查 | 结果与证据 |
| --- | --- |
| Flutter 全套 | **272/272**，[日志](flutter-tests.txt)；本轮没有修改 Dart 实现，复用该轮有效结果 |
| Android JVM 全套 | **555/555**，0 失败、0 错误，[构建日志](build-seam-fix.txt)、[逐项 JUnit 结果](jvm-test-results.json)；原始 XML 本地留存 |
| 真机隐私相关用例 | **40/40**，包含原 39 项及新增 GPU 工作线程检查，[逐项结果](final-device-results.json) |
| 原 8 项真机失败 | 最终 APK 上全部通过；原失败与日志见 [前一轮结果](../privacy-fixes-2026-11-04/final-device-results.json) |
| 同批素材生产管线 | **22/22**：19 项样式/规格/裁剪、2 项多人均衡/速度模式、1 项全长多人跟随，[矩阵](network-case-results.json)、[执行日志](media-regression-log.txt) |
| 真实 API/前台服务生命周期 | 预览→导出完成→预览→取消→预览→再导出→最终预览，通过；等待实际资源归还后才进入下一阶段，[结果](lifecycle-final.json) |
| 主应用成片 | 2 次完成并保存相册；4K 长任务主动取消后可在原进程再次导出，[UI 生命周期](ui-lifecycle.json)、[任务](export-jobs.json) |
| 媒体完整性 | **24 个视频 / 1661 帧**全部解码；PTS 严格递增，无全黑/全绿损坏帧，全部 MP4 fast start；含音轨的成片音画时长差小于一帧，[检查结果](media-inspection.json) |
| B 帧裁剪尾帧 | `[0,1600ms)` 对应源帧 **0–39**，`[1000,2600ms)` 对应 **25–64**，各 40 帧；逐帧匹配未遮挡身体区域，末帧实际存在，[结果](trim-frame-correspondence.json) |

全长多人输出为 **581 帧 / 23.24 秒**，末帧 PTS **23.20 秒**，与源区间内帧数一致；最终执行耗时 226.25 秒，属于 Debug 真机观测，不把人脸检测耗时当作整条导出耗时。

最后的接缝修正后，重跑整套 JVM、40 项真机用例、全部 12 项受影响人脸素材、全长片段、真实服务生命周期及主应用流程。该修正只进入人脸合并遮罩路径；7 项全身素材与 2 项全身性能模式的实现、输入和产物未变，复用本轮刚完成的有效结果。矩阵逐项记录验证 APK 哈希。

## 主应用流程

- 同一 HD 多人素材识别全部 7 人；选择蓝衣人物为主角，其余 6 人保护，9:16 跟随，约 3 秒裁剪，强度 100%。成片 **720×1280、76 帧 / 3.04 秒**，保存到系统相册。
- 查看已发布视频及系统分享面板均通过；没有选择接收方或发送视频，[检查记录](ui-auxiliary-results.json)。
- 不重启应用，导入同一 **2160×4096** 单人素材，预览正常，导入同一透明 PNG 自定义贴纸。长任务切后台继续处理，验证“继续处理”和“取消处理”两个分支，在 **184/662 帧**主动取消，`outputUri=null`，没有发布半成品；前台服务证据见 [记录](final-ui-background-service.txt)。
- 仍在原进程修改裁剪起止点，重新识别并确认保护，导出 **674×1280、48 帧 / 1.92 秒**，AAC 1.941333 秒，音画时长差 21.33ms。完成→导入→取消→重导全过程均为 **PID 16733**。
- 同一无人花朵素材首次识别和重新识别均提示“没有找到可保护的人物”，点击导出没有创建任务。
- 同一 1024 字节损坏视频明确提示“解析视频规格失败，请重试（VIDEO_PROBE_FAILED）”，返回首页，没有创建任务；随后重新导入正常素材成功，[异常素材结果](ui-negative-results.json)。
- 另起同一 4K 素材长任务，处理到 **97/662 帧**时主动强停；重启后为 `interrupted / EXPORT_INTERRUPTED / outputUri=null`，返回首页，已完成成片保留。仅清理本测试任务新建的一个临时文件。强停前 PID 16733，强停后 PID 22696；这一显式中断独立于此前同进程的完成/取消/重导链路。见 [恢复状态](interruption-after.json)、[清理记录](interruption-temp-cleanup.json)。

## 环境、素材与产物

- OnePlus 8T / **KB2000**，Android **14**，ADB **334da6a3**。
- 主应用和测试 APK 均已安装并与最终本地文件核对 SHA-256；主 APK：`f79fc4263945e5add73bebc733649051ef52788c6ec2c90d311f73d14d2fba96`。见 [环境记录](environment.json)。
- 沿用上一轮下载的双人、单人 HD/4K、多人 HD/4K、无人花朵、损坏视频与 PNG 贴纸。8 个文件的真机 SHA-256 均与原始下载一致；来源见 [素材记录](sources.json)。
- APK：`D:/dance-anonymizer/mobile/app/build/app/outputs/apk/debug/app-debug.apk`。
- 本次归档仅纳入隐私修复、相关测试和回归报告；既有 UI/品牌改动留在工作区，未对外发送媒体。

## 提交隔离验证

真机 APK 基于回归发生时的工作区构建，外观包含当时已有的 UI/品牌修改，本次提交不收录这些修改。为确认独立性，另建只包含 `9ef3d23` 基线和本次 32 个实现/测试文件的工作树，重新运行 Flutter 与 Android JVM 全套：**272/272、555/555** 通过。见 [隔离验证记录](commit-validation.json)、[Flutter 日志](commit-validation-flutter.txt)、[JVM 日志](commit-validation-jvm.txt)。此验证补充提交边界证据，原真机结果及 APK 哈希保持不变。

## 复现入口

在仓库根目录执行脚本；需要 Python 的 OpenCV、NumPy，PATH 中的 ADB、ffprobe，以及项目已有的 Flutter/Android 构建环境。脚本默认记录本次设备 `334da6a3`，使用其他设备前替换脚本中的序列号和 UI 布局坐标。

1. 按 `sources.json` 下载素材到 `reports/privacy-e2e-2026-11-04/` 并逐项核对 SHA-256；损坏视频由双人素材前 1024 字节生成。构建并安装 app/test APK，再将五个人物视频推送到测试应用的 `files/network_privacy_qa/` 外部目录，无人素材推送到 `/sdcard/Movies/WoahPrivacyQA/`，贴纸和损坏输入推送到 `/sdcard/Download/WoahQA-sticker.png`、`WoahQA-corrupt-video.mp4`。
2. 执行 `record_environment.py` 校验素材与安装包，然后 `run_existing_device_cases.py --all` 和 `run_regression_media.py`。原生测试入口为 `NetworkPrivacyWorkflowInstrumentedTest`，默认 opt-in，不会在普通仪器测试中自动下载或导出。
3. `ui_workflow.py` 包含导入、跟随、导出、取消、重试、无人素材和中断的具名操作；`device_qa.py` 辅助采集当前界面的截图/XML。主应用每次旅程的参数与结果见 `ui-lifecycle.json`。
4. 执行 `audit_outputs.py`、`audit_face_holes.py`、`verify_trim_correspondence.py` 检查原始成片。执行 `aggregate_checks.py` 可直接校验已归档的结构化结果，原始 JUnit XML 不在检出目录时使用已归档的逐项 JUnit 结果。

本记录的结论限定为以上设备、素材和回归范围。


## 归档范围

Git 保存本报告、结构化结果、关键测试日志和选定帧证据。下载原片、完整成片、UI 抓屏、调试媒体及中间产物保留在本地，不随提交写入仓库；未删除这些文件。完整文件大小与 SHA-256 见 [产物索引](artifact-index.json)。历史记录中的工作区、APK 和测试计数描述测试发生时的状态；最终验收见 [最终回归报告](../privacy-regression-2026-11-04/README.md)。
