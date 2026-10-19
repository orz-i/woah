# Woah · 主角交叠原视频本机标注

**阶段状态：仅收集独立人工证据，不修改导出像素，不能判定隐私通过。**

## 为什么需要人工标注

在 2cb113e 的 Android 真机诊断中，39.733 秒主角强前景与受保护目标仍有 23 个未解决的原型像素重叠，其中 21 个位于目标检测框上半部；39.767 秒则是 31 个中有 30 个位于上半部。39.833 秒以后，该检测框内强主角前景变为 0，但最终导出画面仍存在灰色误遮挡。

已观测的主角 Track Mask 与当前 YOLO 检测 Mask 来自同一次推理，不能作为彼此独立的像素归属证明。受保护 ID 1 当帧也确实存在，不允许直接删除低置信度保守未知保护区域。

这一阶段使用用户本机保存的**原始未打码视频**和匿名数值报告建立独立视觉证据；只保留 4×4 粗网格人工分类。

## 第一步：本机打开标注页面

直接用 Edge 或 Chrome 打开仓库中的文件：

**tools/diagnostics/privacy_overlap_local_annotator.html**

网页离线运行；无需部署服务、不请求外部 API，Content Security Policy 禁用网络连接。

依次选择两个本机文件：

1. 本次测试所用的**原始未打码视频**，不是 Woah 导出视频，也不是微信转码文件。应为 1280×720，时长至少 40 秒。视频只在浏览器本地解码。
2. 此前提供的 **woah_privacy_overlap_20261019_numeric_report.json**，或者由 analyze_privacy_overlap_evidence.py 生成的完整匿名数值报告。两种格式都可以。若报告没有某项逐格数值，页面明确显示“未提供”，不能把它当成 0。

原视频不会进入工具的导出 JSON，也不会被上传或存入 Git。原文件名也不会写入结果。

## 第二步：检查六帧重叠区域

页面按报告 PTS 定位 39.733–39.900 秒六帧，并将受保护目标检测框放大成 4×4 网格。39.733 秒的检测框在原始 1280×720 视频中约为 x=505–589、y=280–501。

选择一类后点击相应网格：

| 类别 | 含义 |
|---|---|
| 可见主角 | 该格中确实可以看见主角 |
| 可见受保护人物 | 该格中确实可以看见需要打码的非主角 |
| 混合/不确定 | 有多人重叠、遮挡、无法确认或不确定谁在前景 |
| 仅可见背景 | 可见前景是背景 |
| 未审 | 默认，尚未核查 |

**重要：** 如果无法判断受保护人物是否位于主角后方，应选“混合/不确定”。主角出现在某个网格，不代表该格全部像素都属于主角，也不授权关闭这个网格的保护。

浏览器寻帧未必与编码器逐帧 PTS 完全一致；标注工具记录可取得的媒体解码时间，无法确认时保留“未验证”。出现时间偏移时不得将结果视为像素精确标注。

## 第三步：只导出粗粒度 JSON

点击 **导出匿名网格标签 JSON**，得到文件：

**woah-local-manual-ownership-labels.json**

只包含六帧 PTS、检测框原型坐标、4×4 格子类别、源视频宽高及视频时长、可选的浏览器媒体时间。无视频、无 RGB、无截帧图片、无二值 Mask。

文件明确声明视觉隐私 **NOT_PROVEN**，像素剔除授权 **NEVER**。如果只完成部分网格，下一阶段会标记为人工验收不完整。

## 第四步：与模型归属统计对照

在 Woah 仓库根目录，通过 Python 3.9+ 执行以下命令。输出目录必须放在 Git 仓库外面。

Windows PowerShell（一整行）：

    py -3 tools\diagnostics\evaluate_privacy_manual_labels.py --numeric-report "D:\Diag\woah_privacy_overlap_20261019_numeric_report.json" --annotations "D:\Downloads\woah-local-manual-ownership-labels.json" --output "D:\Diag\woah-local-coarse-label-triage.json"

macOS/Linux：

    python3 tools/diagnostics/evaluate_privacy_manual_labels.py --numeric-report "/path/to/woah_privacy_overlap_20261019_numeric_report.json" --annotations "/path/to/woah-local-manual-ownership-labels.json" --output "/private/path/woah-local-coarse-label-triage.json"

输出始终保持 **TRIAGE_ONLY_NOT_ACCEPTED**，仅展示人工标记区域内的模型 Mask 统计。报告会区分主角可见但 YOLO 主角 Mask 缺失、强前景归属冲突、以及其他受保护实例仍贡献 Mask 的情况。原始报告未提供的逐格数据会显示 null 而不是虚构为零。

可以在后续对话中提供这两个仅含数字和标签的 JSON，以便针对最值得复查的格子制定**有真实证据约束**的修复方案。不要上传未打码的原始视频或原始图像，也不要将它们提交到 Git。

## 安全和验收界限

人工分类是独立的局部视觉线索，但 4×4 网格仍然太粗，同一格可能有主角和被遮挡人物。**网格标签不能直接作为任何像素剔除指令。**

仅当进一步获得足够明确的实例级前景证据后，才能修改保守未知目标 Mask 的归属规则；修改后仍需要两种输出模式的新真机诊断和 39.700–39.900 秒最终视频画面 A/B 验收。

此前通过实际画面确认的 46.233 秒天花板幽灵 Mask 修复保持不变。
