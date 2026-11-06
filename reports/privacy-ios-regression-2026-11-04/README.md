> 历史记录。最新完整22项与4K结果见 [最终回归报告](../privacy-ios-22-and-4k-regression-2026-11-05/README.md)。

# Woah（舞哇）iOS 模拟器完整媒体回归 — 2026-11-04

**存档与提交状态：** 此报告记录的是回归执行时的证据与结论；文中“未提交”仅指采证时状态。随后已在本地按职责分段提交修复，详见 `../privacy-ios-identity-recovery-2026-11-05/COMMIT_RECORD.md`。安全失败及未验收项并未因此改判。

**生产原生 API/成片矩阵：21/22 通过，失败 1，未执行 0。**

**iOS 模拟器媒体流程：NOT_PASSED；完整发布/人身隐私覆盖验收：NOT_PROVEN。**

## 基线与过程边界

- 测试分支 `codex/privacy-ui-sync-20261104`，本地 HEAD `96e1865`，包含 Android 修复 `60ab1ce`、UI/品牌 `8d48a47` 和后续 CI 契约修正。`origin/master` 已经合入这些提交；本轮 iOS 实现修正未提交、未推送。
- 开始前原始工作区干净；全部新产物在 `reports/privacy-ios-regression-2026-11-04/`，Android `reports/privacy-regression-2026-11-04/` 等未覆盖。
- 环境：Mac mini、Xcode 27.2、Flutter 3.44.2、iPhone 15 Pro 模拟器 iOS 17.0.1；没有真实 iPhone。
- 7 项来源文件逐一匹配 Android `sources.json` SHA-256；另生成对应的 1024 字节损坏视频。iOS YOLO 模型与 canonical 版本相同。
- **原始未修正的 `8d48a47f`：22/22 项在分析缓存路径校验失败**，并非本次通过证据。此前中间版 11/22 文件检查通过，但漏检人物、遮罩纵向错位，不能作为隐私验收。原失败与不安全成片已单独保留。

## 本轮发现与修正（工作树未提交）

1. 修正分析缓存目录 URL 因尾部斜线规范化产生的合法路径误判，保留根目录/叶子目录符号链接安全检查；同时升级 iOS Phase2 静态契约与变异测试，10/10 通过。
2. 将 Metal 17 个常量缓冲区绑定合并至模拟器可用的 14 个，不削减隐私遮挡参数。
3. 移除 YOLO 预处理中错误的上下翻转；真实多人起始帧检出 7 人，单人 HD/4K 检出 1 人，无人花朵 0 人。
4. 修正 Metal 输入帧与 CPU 遮罩的纵向坐标差异；历史错误成片头部候选区域 0/7 正确覆盖，当前首帧 7/7；前后对照与根身份框见 `mask-head-alignment-regression.json`、`artifacts/screenshots/privacy-mask-alignment-before-after.png`。
5. 将 iOS 音频写入放入独立队列避免 AVAssetWriter 视频卡在 43/48 帧；真实 4K 取消重导完成 48/48 帧 H.264+AAC、同一 1.920 秒区间。
6. 新增 iOS 隐私导出持久化作业记录和受约束的临时文件清理；强杀进程后返回 `interrupted/EXPORT_INTERRUPTED`，仅清理本任务沙盒内已登记的 MP4 与 sidecar，保留源素材、已完成成片和无关任务。

## 22 项真实媒体矩阵（最终修正版）

| case | 状态 | 实际/预期帧数 | 错误或成片 |
|---|---|---:|---|
| `group_full_solid` | **通过** | 40/40 | 960×540 H.264, 733146 bytes |
| `group_full_mosaic` | **通过** | 40/40 | 960×540 H.264, 721522 bytes |
| `group_full_blur` | **通过** | 40/40 | 960×540 H.264, 745875 bytes |
| `group_full_gradient` | **通过** | 40/40 | 960×540 H.264, 725997 bytes |
| `group_first_solid` | **通过** | 40/40 | 960×540 H.264, 746548 bytes |
| `group_4k_full` | **通过** | 40/40 | 960×540 H.264, 735111 bytes |
| `duo_full_solid` | **通过** | 48/48 | 960×540 H.264, 582077 bytes |
| `group_all_faces` | **通过** | 76/76 | 960×540 H.264, 1518834 bytes |
| `group_face_sticker_follow` | **通过** | 76/76 | 720×1280 H.264, 1568302 bytes |
| `group_face_solid_follow` | **通过** | 76/76 | 720×1280 H.264, 1561393 bytes |
| `solo_face_sticker` | **通过** | 40/40 | 540×1024 H.264, 357382 bytes |
| `solo_face_mosaic` | **通过** | 40/40 | 540×1024 H.264, 294688 bytes |
| `solo_face_blur` | **通过** | 40/40 | 540×1024 H.264, 299529 bytes |
| `solo_face_solid` | **通过** | 40/40 | 540×1024 H.264, 311771 bytes |
| `solo_face_gradient` | **通过** | 40/40 | 540×1024 H.264, 311852 bytes |
| `solo_face_balanced` | **通过** | 40/40 | 540×1024 H.264, 357382 bytes |
| `solo_face_speed` | **通过** | 40/40 | 540×1024 H.264, 357382 bytes |
| `solo_nonzero_trim` | **通过** | 40/40 | 540×1024 H.264, 323566 bytes |
| `solo_4k_sticker` | **通过** | 40/40 | 540×1024 H.264, 365940 bytes |
| `group_full_balanced` | **通过** | 40/40 | 960×540 H.264, 733146 bytes |
| `group_full_speed` | **通过** | 40/40 | 960×540 H.264, 733146 bytes |
| `group_full_length_follow` | **失败** | —/— | EXPORT_PRIVACY_UNRESOLVED |

媒体通过仅代表：源视频同一区间视频帧数完全一致、PTS 严格递增、H.264 正确分辨率、fast start、完整可解码、非全黑/全绿损坏及有音轨时音视频时长差不超过一帧。**不能仅凭文件有效证明每个人每一帧已被遮挡。**

## 隐私遮罩专项

- 多人输入起始帧 YOLO 根身份：7 人；首帧七人头部候选区域遮挡位置：通过 7/7。测试采用与原始素材同一帧的 bounding boxes，原反向错误成片作为负控制。
- 人脸红色色块（仅内孔拓扑）扫描：192 帧，内部孔洞事件 0；沿用原 Android AVC/抗锯齿边界 3px 和内部 5px 阈值。全身人物肢体缝隙不适用此人脸孔洞判据。
- **未证明：** 7 人整段完整身份覆盖与动态遮挡真值，尤其重叠、转身、出画时，需更完善的逐帧身份标注验证；首帧 7/7 和遮罩内部无孔并不自动等价于全员无泄漏。
- **已执行但失败的 23.24 秒多人全长跟随：** 第 103/581 帧（源时间约 4.12 秒）安全终止为 `EXPORT_PRIVACY_UNRESOLVED`。对原素材前 5 秒再次复现；选中待保护身份 `root 5` 连续失联 91 帧，但当时仍检测到 7 个人物。属于跨人遮挡/身份关联未解的安全阻塞，**未关闭 fail-closed、未强制将临近人重认作该身份**。具体见 `long-follow-identity-root-cause.json` 与 `artifacts/logs/long-follow-trace-stderr.log`。

## 导出生命周期 / 异常输入

- 一进程顺序运行 22 项媒体生产 API：finished，验证进程 PID `79113`。
- 自定义 PNG 资源持久化：True；长导出取消无正式输出：True；随后相同进程重导 48 帧：True，成片 547672 bytes。
- `custom-sticker-pixel-evidence.json`：从有效成片抽 3 帧比较原视频同时间点，黄色 Twemoji 贴纸可见；仅判定颜色图形实际合成，不声称完成逐像素贴图真值验证。
- 无人物视频检出为 0：True；1024B 损坏输入拒绝：True；随后正常输入可重新分析：True。
- 显式强停后旧任务恢复：True，状态 `interrupted`，错误码 `EXPORT_INTERRUPTED`；本次登记的临时文件残留 0。重新创建任务成功：True。

## 逐项覆盖状态与模拟器限制

| 检查 | 结果 | 范围/证据 |
|---|---|---|
| 原始工作区保护、指定提交及来源 | **通过** | HEAD 96e1865（包含 8d48a47、60ab1ce） |
| 素材与模型 SHA-256 | **通过** | 7 项 Android 同源媒体，iOS YOLO canonical 模型一致 |
| 原版 8d48a47f 的 22 项导出 | **失败** | 22 项分析缓存错误，非通过 |
| 最终修正后生产 API 22 项真实媒体导出 | **失败** | 21 通过、1 失败、0 未执行；文件层完整性 |
| 多人 HD 起始帧人物数量（Android 7） | **通过** | iOS 根身份 7 人 |
| 七人首帧头像候选区域遮挡位置 | **通过** | 修复前对照 0/7；修复后 7/7；仅首帧几何判定 |
| 人脸红色遮罩 3px 边界规则内孔逐帧审计 | **通过** | 审计 192 帧，孔洞 0 |
| 多人全员整段身份遮挡真值覆盖 | **未执行** | 只有首帧头部代理区域与已着色区域内孔，并非逐帧身份覆盖金标 |
| 23.24 秒多人全长跟随中的选中身份连续安全关联 | **失败** | 选中 root=5 连续失联 91 帧，安全终止，未发布半成片 |
| B 帧非零起点裁剪尾帧与 PTS | **通过** | 源 [1000,2600) 帧与导出帧数一致 |
| 同进程连续真实媒体导出 | **通过** | 同进程 21 项产物通过，另 1 项按安全策略失败；不等同矩阵全通过 |
| 4K 自定义贴纸 PNG 持久化 | **通过** | 自定义贴纸文件存在 |
| 4K 自定义贴纸色彩实际写入视频 | **通过** | 3 个抽样帧出现 7000+ 黄色贴纸像素，来源帧黄色为 0；不是贴图逐像素匹配 |
| 4K 主动取消并阻止半成品发布 | **通过** | 取消后 outputUri=null |
| 同进程取消后 48 帧重导 | **通过** | 成片字节数 547672 |
| 无人物花朵素材与坏视频拒绝 | **通过** | artifacts/logs/qa_negative_results.json |
| 中断重启 interrupted/EXPORT_INTERRUPTED | **通过** | artifacts/logs/qa_interrupt_check_after_journal.json |
| 中断任务确切临时产物清理 | **通过** | 本次登记的临时 MP4 和 sidecar 均已删除 |
| 强停重启后重新创建导出成功 | **通过** | 40 帧 H.264 / AAC |
| 正式 Flutter 主应用 Xcode 模拟器构建 / 安装 / 启动 | **通过** | 最终候选 iOS 17.0.1 iPhone 15 Pro 主应用 |
| Flutter 272 项全套 | **通过** | 272/272 已完成 |
| iOS Phase 0–6 静态契约 + 31 项安全变异/归档测试 | **通过** | 7/7 阶段静态门禁；31/31 项变异/归档测试 |
| 完整产品 UI 手势、相册保存、系统分享 | **未执行** | 只执行生产原生 API 与应用启动，未重放所有 UI 手势 |
| 真实 iPhone Core ML/GPU/编码及后台调度 | **模拟器限制** | 没有真实 iPhone；模拟器无 Neural Engine |

- 没有真实 iPhone，因此不能宣称 Neural Engine/Core ML、真机 GPU/硬编码性能、温度与后台调度等通过。模拟器能力接口报告硬件 H.264/HEVC 编码不可用，即使模拟器可产生 H.264。
- 已运行真实媒体生产 API、真实模型/遮挡/导出、截帧和文件核验；**未执行完整产品 UI 手势链路、系统相册保存及分享面板的人工/自动交互**，不把这类项标为通过。
- Flutter 272/272、iOS Phase 0–6 静态契约 7/7、31 项变异/归档测试及 Swift 持久化记录器独立测试均通过；这些检查只做辅助，不能代替 22 项真实成片回归。

## 证据与交付

- `test-summary.json`：最终机器可读 22 项结果与各阶段证据；`coverage-status.json`：通过/失败/未执行/模拟器限制逐项状态。
- `final-red-face-mask-hole-audit.json`、`mask-head-alignment-regression.json`：面具内孔及相同首帧人物头部位置对照。
- `artifacts/outputs-final/`：**仅 21 个最终 22 项媒体矩阵的通过成片**。`artifacts/outputs-lifecycle/`：取消重导及中断后重新导出 2 个成片；`artifacts/outputs-diagnostics-preserved/`：5 个非矩阵诊断成片。`artifacts/outputs-unsafe-renderer/`、`artifacts/outputs-before-upright/` 独立保存不安全及中间版本。
- 最终正式主应用（非 QA 入口）构建成功，已在 iPhone 15 Pro 模拟器安装启动；日志与截图见 `artifacts/logs/production-final-rebuild-after-diagnostics.log`、`artifacts/screenshots/08-final-production-after-tests.png`。
- `artifact-manifest.json`：本地素材、成片、选定截图的 SHA-256。媒体二进制保留在当前 Mac，不加入 Git；所有 Swift/CI 修正仍在当前分支工作树的未提交状态。
- `open-issue-long-follow-identity-association.md`：唯一安全阻塞的面向协作者复现说明、验收标准和证据索引，未在 GitHub 创建 issue。
- 未覆盖 Android 报告、未合并或推送本轮 iOS 修正。
